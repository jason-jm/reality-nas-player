package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.util.PRODUCT_AGENT
import com.therealityteam.videoplayer.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Chinese metadata for an anime from Bangumi (bgm.tv): localized name, summary, cover, score.
 *  [nameJp] is the original (Japanese) title — Bangumi indexes Chinese names, so it bridges a
 *  Chinese-only filename to the Japanese title that AniList / MyAnimeList actually resolve. */
data class BangumiInfo(
    val nameCn: String?,
    val summary: String?,
    val posterUrl: String?,
    val score: Double?,
    val nameJp: String? = null,
    /** The subject is called by one of the names the work was asked by, exactly — not merely a
     *  name that contains the query or is contained in it. */
    val namesWork: Boolean = false,
    /** bgm.tv's own id for the subject, which its episodes are asked by; null in an answer cached
     *  before ids were kept. */
    val subjectId: Int? = null,
)

/**
 * Bangumi (bgm.tv) — a Chinese ACG database used to enrich an AniList match with a proper Chinese
 * name + summary (AniList's titles are romaji/English). Looked up by the anime's Japanese title.
 * Calls are gently throttled and each search's answer (incl. an empty one) is cached to disk
 * across sessions; which subject of it is the work is decided afresh on every read, so a change
 * to that decision needs no second trip to bgm.tv.
 */
class BangumiClient(cacheDir: java.io.File) {

    private val cache = ScrapeCache(cacheDir, "bangumi-search", TTL_MS)
    private val details = CachedFetch(cacheDir)
    private val episodeCache = ScrapeCache(cacheDir, "bangumi-episodes", EPISODES_TTL_MS)

    init {
        // The cache this one replaced held one chosen subject per query, not the answer it was
        // chosen from, so none of it can be read under the rule that chooses now.
        runCatching { java.io.File(cacheDir, "aura/cache/bangumi.json").delete() }
    }

    /**
     * Find the Chinese name/summary/cover for a Japanese anime title; null if nothing usable.
     * [names] are the work's other names of its own — its romaji and English titles, not its
     * synonyms — which bgm.tv may file the subject under instead of the Japanese one. [official]
     * says the title is a database's name for the work rather than a folder's; see [pick].
     */
    suspend fun chinese(
        japaneseTitle: String,
        names: Collection<String> = emptyList(),
        official: Boolean = true,
    ): BangumiInfo? =
        withContext(Dispatchers.IO) {
            // NFKC, because a database's own title is not always written the way its search engine
            // indexes it: AniList spells Nanoha A's natively as 魔法少女リリカルなのはＡ’ｓ, in fullwidth,
            // and bgm.tv answers that with the FIRST season — the right subject only comes back once
            // Ａ’ｓ is A’s. (The guard below refused that wrong subject's name, so the cost was a card
            // showing its English title rather than a card showing season one's.)
            val q = java.text.Normalizer.normalize(japaneseTitle, java.text.Normalizer.Form.NFKC).trim()
            if (q.isEmpty()) return@withContext null
            choose(q, names, official)
        }

    /**
     * The subject for [q], or — when bgm.tv's answer for it holds none — for one of the work's other
     * [names], exactly: bgm.tv answers 「CLANNAD -クラナド-」 with the show's sequels and its film and
     * never the show, which it files as 「CLANNAD」. Another name is taken only for a subject that
     * bears it (BangumiInfo.namesWork), never a near one.
     */
    private suspend fun choose(q: String, names: Collection<String>, official: Boolean = true, fresh: Boolean = false): BangumiInfo? =
        search(q, fresh)?.let { pick(q, names, it, official) }
            ?: names.asSequence()
                .map { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC).trim() }
                .filter { it.isNotEmpty() && !it.equals(q, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .firstNotNullOfOrNull { other -> search(other, fresh)?.let { pick(other, names, it, official) }?.takeIf { it.namesWork } }

    /**
     * The subject [chinese] chooses, with bgm.tv's id for it — asked again of bgm.tv when the cached
     * answer predates the ids, once, and kept.
     */
    suspend fun subjectId(japaneseTitle: String, names: Collection<String> = emptyList()): Int? =
        withContext(Dispatchers.IO) {
            val q = java.text.Normalizer.normalize(japaneseTitle, java.text.Normalizer.Form.NFKC).trim()
            if (q.isEmpty()) return@withContext null
            val chosen = choose(q, names) ?: return@withContext null
            chosen.subjectId ?: choose(q, names, fresh = true)?.subjectId
        }

    /**
     * A subject's episodes as bgm.tv tells them, by their number in it: the Chinese name where it has
     * one, else the Japanese, and what happens in each. Cached for a week (an airing show gains
     * episodes); empty when bgm.tv could not be asked.
     */
    suspend fun episodes(subjectId: Int): Map<Int, EpisodeInfo> = withContext(Dispatchers.IO) {
        val key = subjectId.toString()
        val body = episodeCache.get(key) ?: run {
            val fetched = limiter.withPermit { get("$EPISODES?subject_id=$subjectId&type=0&limit=200") } ?: return@withContext emptyMap()
            episodeCache.put(key, fetched)
            fetched
        }
        episodesOf(body)
    }

    internal fun episodesOf(body: String): Map<Int, EpisodeInfo> = runCatching {
        val arr = JSONObject(body).optJSONArray("data") ?: return@runCatching emptyMap()
        buildMap {
            (0 until arr.length()).forEach { i ->
                val o = arr.optJSONObject(i) ?: return@forEach
                // `ep` is the number within the subject (a second season's 1 is 1); `sort` runs on
                // across a franchise's subjects, so it is only the fallback.
                val number = o.optDouble("ep", 0.0).takeIf { it > 0 } ?: o.optDouble("sort", 0.0)
                if (number <= 0 || number % 1.0 != 0.0) return@forEach
                val name = (o.optStringOrNull("name_cn") ?: o.optStringOrNull("name"))?.trim()
                    ?.takeUnless { EpisodeNames.isPlaceholder(it) }
                val overview = o.optStringOrNull("desc")?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }
                    ?.takeUnless { EpisodeNames.isCredits(it) }
                if (name != null || overview != null) put(number.toInt(), EpisodeInfo(name, overview, null))
            }
        }
    }.getOrDefault(emptyMap())

    /** One subject of a search's answer: what choosing needs and what a card shows. */
    internal data class Subject(
        val name: String?,
        val nameCn: String?,
        val summary: String?,
        val posterUrl: String?,
        val score: Double?,
        val id: Int? = null,
    ) {
        val usable get() = !nameCn.isNullOrBlank() || !name.isNullOrBlank() || posterUrl != null
    }

    /** bgm.tv's top answers for [q], cached; null when it could not be asked (not cached then). */
    internal suspend fun search(q: String, fresh: Boolean = false): List<Subject>? {
        val key = q.lowercase()
        if (!fresh) cache.get(key)?.let { return subjectsFromJson(it) }
        val body = JSONObject().put("keyword", q).put("filter", JSONObject().put("type", JSONArray().put(2))).toString()
        val resp = limiter.withPermit { post(body) } ?: return null
        val subjects = runCatching { JSONObject(resp).optJSONArray("data") }.getOrNull()
            ?.let(::subjectsOf).orEmpty()
        cache.put(key, subjectsToJson(subjects))
        return subjects
    }

    /** A subject can identify a file itself when AniList has no corresponding entry. */
    internal suspend fun direct(names: List<String>, year: Int?, season: Int?, movie: Boolean, numbered: Boolean): MetaResult? =
        withContext(Dispatchers.IO) {
            if (TmdbConfig.offline) return@withContext null
            val own = names.firstOrNull() ?: return@withContext null
            for (q in names.distinct().take(2)) {
                val name = StringSimilarity.normalizeTitle(q)
                if (name.length < 2 || name.none(Char::isLetter)) continue
                val ids = search(q).orEmpty().filter { result ->
                    listOfNotNull(result.name, result.nameCn).any { StringSimilarity.normalizeTitle(it) == name }
                }.mapNotNull { it.id }.distinct()
                val candidates = ids.mapNotNull { detail(it) }.filter { record ->
                    val titles = listOfNotNull(record.title, record.originalTitle) + record.synonyms
                    val hasName = titles.any { StringSimilarity.normalizeTitle(it) == name }
                    val laterSeason = season != null && season > 1
                    val confirmsSeason = !laterSeason || year != null && year == record.year ||
                        AnimeMatch.seasonFit(season, record) > 0
                    hasName && confirmsSeason && (q == own || !AnimeMatch.namesAnotherWork(own, record)) &&
                        (year == null || record.year != null && kotlin.math.abs(year - record.year) <= 1) &&
                        (!movie || record.kind == MediaKind.MOVIE) && (!numbered || movie || record.kind != MediaKind.MOVIE)
                }
                if (candidates.size > 1) return@withContext null
                candidates.singleOrNull()?.let { return@withContext it }
            }
            null
        }

    internal suspend fun detail(id: Int): MetaResult? = withContext(Dispatchers.IO) {
        if (id <= 0 || TmdbConfig.offline) return@withContext null
        val url = "https://api.bgm.tv/v0/subjects/$id"
        val text = details.cached(url) ?: limiter.withPermit {
            details.get(url, mapOf("User-Agent" to USER_AGENT), throwOnTransient = true)
        }
        if (text == null || text == CachedFetch.MISS) return@withContext null
        val row = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        if (row.optInt("id") != id || row.optInt("type") != 2) return@withContext null
        val native = row.str("name") ?: return@withContext null
        val cn = row.str("name_cn")
        val form = when (row.str("platform")?.uppercase(java.util.Locale.ROOT)) {
            "剧场版", "劇場版", "MOVIE" -> MediaKind.MOVIE
            "TV", "WEB", "OVA", "OAD" -> MediaKind.SHOW
            else -> return@withContext null
        }
        MetaResult(0, form, cn.takeIf { TmdbConfig.isChinese } ?: native, originalTitle = native,
            synonyms = listOfNotNull(cn, native), year = row.str("date")?.take(4)?.toIntOrNull(),
            overview = Synopsis.forReader(row.str("summary"), TmdbConfig.language),
            posterUrl = row.optJSONObject("images")?.let { it.str("large") ?: it.str("common") }?.takeIf { it.startsWith("https://lain.bgm.tv/") },
            genres = listOf("Animation"), rating = row.optJSONObject("rating")?.optDouble("score")?.takeIf { it > 0 },
            publicMovieKey = "bangumi:$id")
    }

    /**
     * Which of bgm.tv's answers for [q] is the work, if any.
     *
     * Its keyword search returns popular partial hits too — "UFO之夏" surfaces "UFO机器人古连泰沙对
     * 大魔神", "マーダープリンセス" surfaces "谋杀之谜" — so a subject must [correspond][corresponds] to the query to be
     * taken at all, and the first that does used to be the one. That lets a subject whose name merely
     * CONTAINS the query beat the work itself whenever bgm.tv files the work under a name in another
     * script: asked for ファイナルファンタジーVII アドベントチルドレン, it answers FINAL FANTASY VII ADVENT
     * CHILDREN first, then its Complete edition, then 「ファイナルファンタジーⅦ アドベントチルドレン 〜On the
     * Way to a Smile〜」 — a short film, and the only Japanese spelling of the three, so the film's card
     * was named 最终幻想7：通向微笑之路 丹泽尔篇. So a subject called by one of the work's own names,
     * letter for letter once normalized, comes first.
     *
     * Next, one that has every word of the query as a word of its own, in order: bgm.tv names the
     * Garden of Sinners films 「劇場版 空の境界 第二章 殺人考察(前)」 where AniList says 「空の境界 殺人考察(前)」,
     * words the query does not have standing between words it does. Neither a substring nor the
     * character overlap accepts that, so six of the seven films kept their English titles, and the
     * first was named after the 3D edition 「劇場版 空の境界 俯瞰風景3D」, which contains the query's
     * letters but not its last word. Only for an [official] title, whose words are the work's own: a
     * folder's are not, and its spacing is its own too — the folder 「魔法少女奈叶 A's」 has every
     * word of the second film 「魔法少女奈叶 The MOVIE 2nd A's」 and not those of the series it
     * holds, 「魔法少女奈叶A's」. The first merely corresponding subject is the last resort.
     */
    internal fun pick(
        q: String,
        names: Collection<String>,
        subjects: List<Subject>,
        official: Boolean = true,
    ): BangumiInfo? {
        val own = (names + q).map(StringSimilarity::normalizeTitle).filter { it.length >= 2 }.toSet()
        val exact = subjects.firstOrNull { s ->
            s.usable && listOfNotNull(s.name, s.nameCn).any { StringSimilarity.normalizeTitle(it) in own }
        }
        val chosen = exact
            ?: subjects.takeIf { official }
                ?.firstOrNull { it.usable && (hasEveryWord(q, it.name) || hasEveryWord(q, it.nameCn)) }
            ?: firstCorresponding(q, subjects)
            ?: return null
        return BangumiInfo(
            nameCn = chosen.nameCn,
            summary = chosen.summary,
            posterUrl = chosen.posterUrl,
            score = chosen.score,
            nameJp = chosen.name, // its own (usually Japanese) title → bridges to AniList
            namesWork = exact != null,
            subjectId = chosen.id,
        )
    }

    /**
     * Every word of [q] is a whole word of [title], in the same order, with any others between.
     * A query of one word is left to [corresponds]: it would match every subject that says it.
     */
    private fun hasEveryWord(q: String, title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val want = StringSimilarity.normalizeTitle(q).split(' ').filter { it.isNotEmpty() }
        if (want.size < 2) return false
        var i = 0
        for (w in StringSimilarity.normalizeTitle(title).split(' ')) if (i < want.size && w == want[i]) i++
        return i == want.size
    }

    /** The first usable subject whose name [corresponds] to [q]: the whole rule, until the exact one. */
    internal fun firstCorresponding(q: String, subjects: List<Subject>): Subject? =
        subjects.firstOrNull { it.usable && (corresponds(q, it.name) || corresponds(q, it.nameCn)) }

    /**
     * The Chinese name to show for the work asked for by [query], or null when [info] has none or
     * is a subject broader than the work ([isBroaderWork]) — unless it answers to one of the work's
     * own names exactly, which no broader subject does.
     */
    internal fun nameFor(query: String, info: BangumiInfo?): String? =
        info?.nameCn?.takeIf { it.isNotBlank() && (info.namesWork || !isBroaderWork(query, info.nameJp)) }

    private fun subjectsOf(arr: JSONArray): List<Subject> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Subject(
            name = o.optStringOrNull("name"),
            nameCn = o.optStringOrNull("name_cn"),
            summary = o.optStringOrNull("summary")?.replace(Regex("\\s+"), " ")?.trim(),
            // Bangumi covers are the original high-res scans; prefer the biggest variant available.
            posterUrl = o.optJSONObject("images")?.let { img ->
                img.optStringOrNull("large") ?: img.optStringOrNull("common") ?: img.optStringOrNull("medium")
            },
            score = o.optJSONObject("rating")?.optDouble("score")?.takeIf { s -> s > 0 },
            id = o.optInt("id", 0).takeIf { it > 0 },
        )
    }

    /**
     * Does a Bangumi title correspond to the query? The reliable signal is a CONTIGUOUS substring (the
     * user's folder name is a prefix of the official title, 银河英雄传说 ⊂ 银河英雄传说 我的征途…). For CJK,
     * fuzzy similarity is NOT used — short titles sharing a common head (最后的大魔王 vs 最后的魁拔 / …最后的
     * 大舞台) collide under Jaro-Winkler's prefix boost; only a near-identical character set is allowed.
     * Latin keeps a fuzzy fallback for romanization variants.
     */
    private fun corresponds(q: String, title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val nq = StringSimilarity.normalizeTitle(q)
        val nt = StringSimilarity.normalizeTitle(title)
        if (nq.length < 2 || nt.isEmpty()) return false
        if (nq.length >= 3 && nt.contains(nq)) return true
        if (nt.length >= 3 && nq.contains(nt)) return true
        return if (CJK.containsMatchIn(nq) || CJK.containsMatchIn(nt)) {
            StringSimilarity.charOverlap(nq, nt) >= 0.8
        } else {
            StringSimilarity.titleSimilarity(nq, nt) >= 0.6
        }
    }

    /**
     * Did Bangumi answer with a SHORTER version of the name it was asked for?
     *
     * Its keyword search is fuzzy in one direction that matters: ask for 「ラブライブ! School idol
     * project 2期」 and the top hit is 「ラブライブ!」, ask for either 「R.O.D -THE TV-」 or
     * 「R.O.D -READ OR DIE-」 and both come back as 「R.O.D」. Every one of those is a real subject,
     * so [corresponds] passes it — and its Chinese name then replaced the right one, which is how
     * two cards on a real wall ended up sharing a single name between them and neither said which
     * season it was. Asking 「無職転生 Ⅱ ～異世界行ったら本気だす～」 returns that exact title, so the
     * shape of the failure is precisely this: strictly less name than was asked for.
     *
     * Skipping such a hit at SEARCH time is the wrong cure — it promotes the next corresponding
     * subject, and for R.O.D that is the other sibling, so the TV series and the OVA swapped Chinese
     * names. What the hit is trusted for instead is narrowed: its poster and its rating are the
     * franchise's and were always used as such, while its NAME is only adopted when it names this
     * work. See the call in ScrapeEngine.animeWork.
     *
     * Only meaningful when the query is an official title. A folder name carries release noise the
     * official title does not, and being shorter than it is then perfectly correct.
     */
    internal fun isBroaderWork(query: String, title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val nq = StringSimilarity.normalizeTitle(query)
        val nt = StringSimilarity.normalizeTitle(title)
        if (nt.isEmpty() || nq.isEmpty()) return true
        if (nt.length >= nq.length || !nq.contains(nt)) return false
        // …unless the only thing it is short of says TELEVISION. bgm.tv's subject for a franchise
        // name IS its TV series, so 「機動警察パトレイバー ON TELEVISION」 and 機動警察パトレイバー
        // are one work, and refusing it cost that card its Chinese name across 62 files. Any other
        // form beside the series is a different work under the same name — this very franchise
        // answers 「機動警察パトレイバー 劇場版」 with the TV series too, and there refusing is the
        // whole point.
        return !SAYS_TV.matches(nq.replace(nt, " ").trim())
    }

    /** A form word that means the series itself rather than something made beside it. */
    private val SAYS_TV = Regex(
        """(?i)(?:on\s+)?(?:television|tv(?:\s*series)?|テレビ(?:シリーズ)?|电视(?:剧|版|系列)?)""",
    )

    private val CJK = Regex("""[㐀-鿿぀-ヿ가-힯]""")

    // Through Http, as every other provider is asked: on a network where api.bgm.tv resolved to an address
    // that never answers, its own connections timed out search after search with nothing to stop them.
    private fun post(body: String): String? = Http.postJson("$ENDPOINT?limit=5", body, mapOf("User-Agent" to USER_AGENT)) // bgm.tv requires a descriptive UA

    private fun get(url: String): String? = Http.get(url, mapOf("User-Agent" to USER_AGENT))

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

    private fun subjectsToJson(subjects: List<Subject>): String = JSONArray().apply {
        subjects.forEach { s ->
            put(
                JSONObject()
                    .put("name", s.name ?: JSONObject.NULL)
                    .put("nameCn", s.nameCn ?: JSONObject.NULL)
                    .put("summary", s.summary ?: JSONObject.NULL)
                    .put("posterUrl", s.posterUrl ?: JSONObject.NULL)
                    .put("score", s.score ?: JSONObject.NULL)
                    .put("id", s.id ?: JSONObject.NULL),
            )
        }
    }.toString()

    private fun subjectsFromJson(s: String): List<Subject> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                Subject(
                    name = o.optStringOrNull("name"),
                    nameCn = o.optStringOrNull("nameCn"),
                    summary = o.optStringOrNull("summary"),
                    posterUrl = o.optStringOrNull("posterUrl"),
                    score = o.optDouble("score").takeIf { it > 0 },
                    id = o.optInt("id", 0).takeIf { it > 0 },
                )
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val ENDPOINT = "https://api.bgm.tv/v0/search/subjects"
        const val EPISODES = "https://api.bgm.tv/v0/episodes"
        /**
         * Public contact bgm.tv's UA convention asks for (a repo URL or an email) so they can
         * reach the developer instead of banning the whole UA outright.
         */
        const val CONTACT = "madeforyou2025@gmail.com"
        val USER_AGENT = "$PRODUCT_AGENT (PICO Spatial video player" +
            (if (CONTACT.isBlank()) "" else "; $CONTACT") + ")"
        const val TTL_MS = 30L * 24 * 3600 * 1000 // 30 days
        const val EPISODES_TTL_MS = 7L * 24 * 3600 * 1000
        // 1/s. bgm.tv is volunteer-run and bans by UA, and a ban lands on every install at
        // once — at 2.5/s a single big library scan sustained for hours was asking for exactly
        // that. Bangumi answers are disk-cached, so only the first pass pays this rate at all.
        val limiter = RateLimiter(1_000)
    }
}
