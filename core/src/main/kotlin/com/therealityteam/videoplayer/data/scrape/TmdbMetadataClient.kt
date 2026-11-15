package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * TMDB (themoviedb.org) implementation of [MetadataClient] using plain HttpURLConnection
 * + org.json. Movies and TV. Poster/backdrop resolved to full image URLs.
 */
class TmdbMetadataClient(cacheDir: File? = null) : MetadataClient {

    // Every episode of a show asks the same question; without this each one asked the network.
    private val fetch = cacheDir?.let { CachedFetch(it) }

    private fun cachedGet(url: String): String? =
        fetch?.get(url, throwOnTransient = true) ?: Http.getOrThrow(url)

    override val name = "TMDB"
    override val isConfigured: Boolean get() = TmdbConfig.effectiveKey.isNotBlank()

    private val api = "https://api.themoviedb.org/3"
    private val img = "https://image.tmdb.org/t/p"

    override suspend fun search(parsed: ParsedName): List<MetaResult> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val query = searchQuery(parsed.title)
        val langs = searchLanguages(parsed.title)
        fun endpoint(path: String, kind: MediaKind) = bilingual(langs) { lang -> searchEndpoint(path, query, parsed.year, kind, lang) }
        val results = mutableListOf<MetaResult>()
        if (parsed.isEpisode) {
            results += endpoint("tv", MediaKind.SHOW)
        } else {
            results += endpoint("movie", MediaKind.MOVIE)
            if (results.isEmpty()) results += endpoint("tv", MediaKind.SHOW)
        }
        results
    }

    /**
     * One search in each of [langs]: the first language's hits, each carrying the English title the second one gave
     * it, then any hit only the second found.
     */
    private fun bilingual(langs: List<String>, search: (String) -> List<MetaResult>): List<MetaResult> {
        val first = search(langs.first())
        if (langs.size < 2) return first
        val english = search(langs[1])
        val enTitles = english.associate { it.providerId to it.title }
        val seen = first.map { it.providerId }.toSet()
        return first.map { r -> enTitles[r.providerId]?.takeIf { it != r.title }?.let { r.copy(englishTitle = it) } ?: r } +
            english.filter { it.providerId !in seen }
    }

    /**
     * The title as TMDB's search is asked for it: NFKC-folded, then URL-encoded. TMDB folds its index
     * and not the query — it files G-Reco's fifth film as 「…Ｇのレコンギスタ V 死線を越えて」 with a
     * plain V, and the release's Roman numeral Ⅴ (U+2164) found nothing, while a plain IV still finds
     * the fourth, filed with Ⅳ. Fullwidth letters and digits (ＮＨＫ１９４１) fold the same way.
     */
    internal fun searchQuery(title: String): String =
        URLEncoder.encode(java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC), "UTF-8")

    /**
     * The languages a search is asked in, so titles string-match: a CJK file name in the reader's language; a Latin
     * one in en-US (TMDB returns canonical English titles like "Your Name"/"Parasite") for a reader of English or of
     * Chinese, Japanese or Korean, whose Latin file names are English release names; and for every other reader
     * in theirs and in English — a German shelf holds Der.Pate.1972 and Spirited.Away.2001 both. Details are always
     * fetched in [TmdbConfig.language] for display.
     */
    private fun searchLanguages(title: String): List<String> {
        val reader = TmdbConfig.language
        val lang = reader.substringBefore('-')
        return when {
            title.any { it.code in 0x3040..0x9FFF || it.code in 0xAC00..0xD7A3 } -> listOf(reader)
            lang == "en" || lang in setOf("zh", "ja", "ko") -> listOf("en-US")
            else -> listOf(reader, "en-US")
        }
    }

    private fun searchEndpoint(kindPath: String, query: String, year: Int?, kind: MediaKind, lang: String): List<MetaResult> {
        val yearParam = when {
            year == null -> ""
            kindPath == "movie" -> "&year=$year"
            else -> "&first_air_date_year=$year"
        }
        val url = "$api/search/$kindPath?api_key=${TmdbConfig.effectiveKey}" +
            "&language=$lang&include_adult=false&query=$query$yearParam"
        val body = cachedGet(url) ?: return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until minOf(arr.length(), 8)).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MetaResult(
                providerId = o.optInt("id"),
                kind = kind,
                title = if (kind == MediaKind.MOVIE) o.optString("title") else o.optString("name"),
                originalTitle = if (kind == MediaKind.MOVIE) o.optStringOrNull("original_title")
                else o.optStringOrNull("original_name"),
                year = parseYear(if (kind == MediaKind.MOVIE) o.optString("release_date") else o.optString("first_air_date")),
                overview = o.optStringOrNull("overview"),
                rating = o.optDouble("vote_average").takeIf { it > 0 },
                posterUrl = o.optStringOrNull("poster_path")?.let { "$img/w500$it" },
                backdropUrl = o.optStringOrNull("backdrop_path")?.let { "$img/w1280$it" },
                popularity = o.optDouble("popularity", 0.0),
            )
        }
    }

    private val LATIN = Regex("""[A-Za-z]{3,}""")
    private val NOT_LATIN = Regex("""[^A-Za-z]""")

    /**
     * Does this name read in Latin script at all?
     *
     * Letters first, then look for a word. `I.O.U.S.A.` is a Latin name written in full stops and
     * has to count — it is already findable by typing `iousa`, and asking TMDB for its English
     * title returns the same string, which a raw three-letter-run test would then reject. `寒戰 II`
     * is not one: the numeral is Latin, the name is not.
     */
    private fun hasLatinName(s: String?): Boolean =
        s != null && LATIN.containsMatchIn(NOT_LATIN.replace(s, ""))

    /**
     * The work's English name, asked for only when it has none already.
     *
     * A card shown in Chinese whose original title is also Chinese — 寒战, 周恩来外交风云 — carries no
     * Latin-script name at all, so nobody can find it by typing the name it is known by abroad.
     * TMDB has one, but only in the language you ask for.
     *
     * One extra request, and only for the works that need it: on a 725-work library that was about
     * forty of them. `append_to_response=translations` would avoid the round trip and bring every
     * language's overview with it, which is not worth carrying through the response cache for one
     * field.
     */
    private suspend fun englishTitleFor(
        providerId: Int,
        path: String,
        kind: MediaKind,
        title: String,
        originalTitle: String?,
    ): String? {
        if (TmdbConfig.language.startsWith("en")) return null
        if (hasLatinName(title) || hasLatinName(originalTitle)) return null
        val url = "$api/$path/$providerId?api_key=${TmdbConfig.effectiveKey}&language=en-US"
        val body = runCatching { cachedGet(url) }.getOrNull() ?: return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return (if (kind == MediaKind.MOVIE) o.optStringOrNull("title") else o.optStringOrNull("name"))
            ?.takeIf { it.isNotBlank() && it != title && hasLatinName(it) }
    }

    /**
     * The English synopsis, for a work TMDB has none of in the reader's language — most of them in Norwegian or
     * Malay, where a detail page stood empty. The same request [englishTitleFor] makes (and the cache keeps).
     */
    private fun englishOverviewFor(providerId: Int, path: String): String? {
        if (TmdbConfig.language.startsWith("en")) return null
        val body = runCatching { cachedGet("$api/$path/$providerId?api_key=${TmdbConfig.effectiveKey}&language=en-US") }.getOrNull() ?: return null
        return runCatching { JSONObject(body).optStringOrNull("overview") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * TMDB's alternative titles for an entry: `/movie/{id}/alternative_titles` answers `titles`,
     * `/tv/{id}/alternative_titles` answers `results`, each a list of `{title, iso_3166_1, type}`.
     */
    override suspend fun alternativeTitles(providerId: Int, kind: MediaKind): List<String> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val path = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val body = cachedGet("$api/$path/$providerId/alternative_titles?api_key=${TmdbConfig.effectiveKey}")
            ?: return@withContext emptyList()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        val arr = o.optJSONArray(if (kind == MediaKind.MOVIE) "titles" else "results") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optStringOrNull("title") }
            .filter { it.isNotBlank() }.distinct()
    }

    /** The same alternative titles, each with the region that uses it (`iso_3166_1`), in TMDB's order. */
    suspend fun regionalTitles(providerId: Int, kind: MediaKind): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val path = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val body = cachedGet("$api/$path/$providerId/alternative_titles?api_key=${TmdbConfig.effectiveKey}")
            ?: return@withContext emptyList()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        val arr = o.optJSONArray(if (kind == MediaKind.MOVIE) "titles" else "results") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optStringOrNull("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            t.optString("iso_3166_1") to title
        }
    }

    override suspend fun findByImdb(imdbId: String): MetaResult? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val body = cachedGet("$api/find/$imdbId?api_key=${TmdbConfig.effectiveKey}&external_source=imdb_id")
            ?: return@withContext null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        listOf("movie_results" to MediaKind.MOVIE, "tv_results" to MediaKind.SHOW).firstNotNullOfOrNull { (field, kind) ->
            val hit = o.optJSONArray(field)?.optJSONObject(0) ?: return@firstNotNullOfOrNull null
            val id = hit.optInt("id").takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
            MetaResult(id, kind, hit.optString(if (kind == MediaKind.MOVIE) "title" else "name"), imdbId = imdbId)
        }
    }

    override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        val path = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val ratingsField = if (kind == MediaKind.MOVIE) "release_dates" else "content_ratings"
        val url = "$api/$path/$providerId?api_key=${TmdbConfig.effectiveKey}" +
            "&language=${TmdbConfig.language}&append_to_response=credits,external_ids,$ratingsField"
        val body = cachedGet(url) ?: return@withContext null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val tmdbScore = o.optDouble("vote_average").takeIf { it > 0 }
        val imdbId = o.optJSONObject("external_ids")?.optStringOrNull("imdb_id")
            ?: o.optStringOrNull("imdb_id")
        val ratings = tmdbScore?.let {
            listOf(RatingScore(RatingSource.TMDB, RatingScore.tenths(it), votes = o.optInt("vote_count").takeIf { v -> v > 0 }))
        } ?: emptyList()
        val genres = o.optJSONArray("genres")?.let { g ->
            (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optStringOrNull("name") }
        } ?: emptyList()
        val genreIds = o.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optInt("id")?.takeIf { id -> id > 0 } } } ?: emptyList()
        val runtime = if (kind == MediaKind.MOVIE) o.optInt("runtime").takeIf { it > 0 }
        else o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
        val credits = o.optJSONObject("credits")
        val cast = credits?.optJSONArray("cast")?.let { c ->
            (0 until minOf(c.length(), 12)).mapNotNull { i ->
                val p = c.optJSONObject(i) ?: return@mapNotNull null
                val name = p.optStringOrNull("name") ?: return@mapNotNull null
                Credit(name, p.optStringOrNull("character"), profileUrl(p.optStringOrNull("profile_path")))
            }
        } ?: emptyList()
        val localTitle = if (kind == MediaKind.MOVIE) o.optString("title") else o.optString("name")
        val originalTitle = if (kind == MediaKind.MOVIE) o.optStringOrNull("original_title")
        else o.optStringOrNull("original_name")
        // A Chinese translation that has a synopsis but no name answers with the original one —
        // Andor, Obi-Wan Kenobi on a Chinese wall; the regions' own titles name it then (ChineseTitle).
        val title = if (TmdbConfig.isChinese && ChineseTitle.wantsChineseName(localTitle) &&
            o.optString("original_language") != "zh"
        ) {
            ChineseTitle.pick(regionalTitles(providerId, kind), TmdbConfig.language) ?: localTitle
        } else {
            localTitle
        }
        MetaResult(
            providerId = providerId,
            kind = kind,
            title = title,
            originalTitle = originalTitle,
            englishTitle = englishTitleFor(providerId, path, kind, localTitle, originalTitle),
            year = parseYear(if (kind == MediaKind.MOVIE) o.optString("release_date") else o.optString("first_air_date")),
            overview = o.optStringOrNull("overview")?.takeIf { it.isNotBlank() } ?: englishOverviewFor(providerId, path),
            rating = tmdbScore,
            ratings = ratings,
            imdbId = imdbId,
            runtime = runtime,
            certification = certificationOf(o, kind),
            originalLanguage = o.optStringOrNull("original_language"),
            genres = genres,
            genreIds = genreIds,
            cast = cast,
            crew = parseCrew(o, credits),
            posterUrl = o.optStringOrNull("poster_path")?.let { "$img/w500$it" },
            backdropUrl = o.optStringOrNull("backdrop_path")?.let { "$img/w1280$it" },
        )
    }

    /**
     * Age/content rating from TMDB. Movies carry it under release_dates[].release_dates[].certification,
     * TV under content_ratings[].rating. Prefer the local region, then US, then any non-empty value.
     */
    private fun certificationOf(o: JSONObject, kind: MediaKind): String? {
        val region = TmdbConfig.region
        val results = o.optJSONObject(if (kind == MediaKind.MOVIE) "release_dates" else "content_ratings")
            ?.optJSONArray("results") ?: return null
        fun certForCountry(country: String): String? {
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (!r.optString("iso_3166_1").equals(country, ignoreCase = true)) continue
                val cert = if (kind == MediaKind.MOVIE) {
                    val dates = r.optJSONArray("release_dates")
                    (0 until (dates?.length() ?: 0))
                        .mapNotNull { dates?.optJSONObject(it)?.optStringOrNull("certification") }
                        .firstOrNull()
                } else {
                    r.optStringOrNull("rating")
                }
                if (!cert.isNullOrBlank()) return cert
            }
            return null
        }
        return certForCountry(region) ?: certForCountry("US")
            ?: (0 until results.length()).firstNotNullOfOrNull { i ->
                val r = results.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                if (kind == MediaKind.MOVIE) {
                    val dates = r.optJSONArray("release_dates")
                    (0 until (dates?.length() ?: 0))
                        .mapNotNull { dates?.optJSONObject(it)?.optStringOrNull("certification") }
                        .firstOrNull { it.isNotBlank() }
                } else {
                    r.optStringOrNull("rating")
                }
            }
    }

    private fun profileUrl(path: String?): String? = path?.let { "$img/w185$it" }

    /** TMDB crew job → localized Staff role. Order of keys is the display priority. */
    private val crewJobs = linkedMapOf(
        "Director" to "导演",
        "Screenplay" to "编剧", "Writer" to "编剧", "Story" to "编剧",
        "Novel" to "原著", "Original Story" to "原著", "Author" to "原著", "Comic Book" to "原著",
        "Original Music Composer" to "配乐",
        "Producer" to "制片", "Executive Producer" to "监制",
    )

    /**
     * Director / writers / 主创 (TV `created_by`), deduped by person, highest-priority role kept —
     * kept by priority, not by which came first: TMDB lists Christopher Nolan's Dunkirk credits
     * writing before directing, and the first-come rule had filed the director as a writer.
     */
    private fun parseCrew(o: JSONObject, credits: JSONObject?): List<Credit> = rankCrew(buildList {
        o.optJSONArray("created_by")?.let { a ->
            (0 until a.length()).forEach { i ->
                val p = a.optJSONObject(i) ?: return@forEach
                val name = p.optStringOrNull("name") ?: return@forEach
                add(Credit(name, "主创", profileUrl(p.optStringOrNull("profile_path"))))
            }
        }
        credits?.optJSONArray("crew")?.let { c ->
            (0 until c.length()).forEach { i ->
                val p = c.optJSONObject(i) ?: return@forEach
                val role = crewJobs[p.optStringOrNull("job")] ?: return@forEach
                val name = p.optStringOrNull("name") ?: return@forEach
                add(Credit(name, role, profileUrl(p.optStringOrNull("profile_path"))))
            }
        }
    })

    // Per-(show, season) episode still URLs, fetched once and reused across that season's episodes.
    private val seasonEpisodes = java.util.concurrent.ConcurrentHashMap<String, Map<Int, EpisodeInfo>>()

    override suspend fun episodes(tvId: Int, season: Int): Map<Int, EpisodeInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyMap()
        val cacheKey = "$tvId:$season"
        seasonEpisodes[cacheKey]?.let { return@withContext it }
        val url = "$api/tv/$tvId/season/$season?api_key=${TmdbConfig.effectiveKey}&language=${TmdbConfig.language}"
        val body = cachedGet(url) ?: return@withContext emptyMap()
        val arr = runCatching { JSONObject(body).optJSONArray("episodes") }.getOrNull() ?: return@withContext emptyMap()
        val m = HashMap<Int, EpisodeInfo>()
        (0 until arr.length()).forEach { i ->
            val e = arr.optJSONObject(i) ?: return@forEach
            val ep = e.optInt("episode_number", -1)
            if (ep < 0) return@forEach
            m[ep] = EpisodeInfo(
                name = e.optStringOrNull("name")?.trim()?.takeUnless { EpisodeNames.isPlaceholder(it) },
                overview = e.optStringOrNull("overview")?.trim(),
                still = e.optStringOrNull("still_path")?.let { "$img/w300$it" },
            )
        }
        seasonEpisodes[cacheKey] = m
        m
    }

    override suspend fun seasons(tvId: Int): List<SeasonInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext emptyList()
        val url = "$api/tv/$tvId?api_key=${TmdbConfig.effectiveKey}&language=${TmdbConfig.language}"
        val body = cachedGet(url) ?: return@withContext emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("seasons") }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optInt("season_number", -1)
            val c = o.optInt("episode_count", 0)
            if (s >= 1 && c > 0) SeasonInfo(s, c) else null
        }.sortedBy { it.season }
    }

    private fun parseYear(date: String?): Int? =
        date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

/** The crew jobs a title page shows first, most important first. */
private val CREW_ORDER = listOf("导演", "主创", "编剧", "原著", "监制", "制片", "配乐")

/**
 * One credit per person, their highest-priority job in [order]; the eight most important, in that
 * order. By priority, not by which came first: the databases list a person's jobs in no particular
 * order, and first-come filed Christopher Nolan, on Dunkirk, as its writer.
 */
internal fun rankCrew(credits: List<Credit>, order: List<String> = CREW_ORDER): List<Credit> {
    fun rank(c: Credit) = order.indexOf(c.role).let { if (it < 0) order.size else it }
    val byName = LinkedHashMap<String, Credit>()
    credits.forEach { c ->
        val had = byName[c.name]
        if (had == null || rank(c) < rank(had)) {
            byName[c.name] = if (c.imageUrl == null && had?.imageUrl != null) c.copy(imageUrl = had.imageUrl) else c
        }
    }
    return byName.values.sortedBy(::rank).take(8)
}
