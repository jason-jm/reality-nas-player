package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.Log
import com.therealityteam.videoplayer.util.Offline
import com.therealityteam.videoplayer.data.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * AniList (anilist.co) — a proper anime database, used for files that look like anime. Its GraphQL
 * search matches romaji / english / native / synonyms server-side, so a messy fansub romaji title
 * resolves reliably (where TMDB fails). No API key. Results returned as [MetaResult]; the display
 * title prefers a Chinese synonym when present, else English, else romaji.
 */
class AniListClient(
    cacheDir: File? = null,
    private val transport: Transport = HttpTransport,
    minIntervalMs: Long = 2_500,
    /**
     * Send many searches in one request (see [batched]).
     *
     * OFF by default, and deliberately. The shape was verified against the live API — eleven
     * aliased searches come back correctly and cost ONE of the thirty requests a minute — but the
     * body this client GENERATES could not be confirmed before shipping, because a session of
     * probing had the address blocked. The first version of that generator declared `s0` and asked
     * for `search`, and every anime query on the shelf came back empty with no error anywhere.
     * A wrong answer here is silent, so it was off until a live call proved it. It now has one:
     * eight queries, the same eight answers either way, 20.6s single against 4.9s batched.
     * [AniListLiveBatchProbe] is that call, and it is how both generator bugs were caught.
     */
    private val batching: Boolean = true,
) {

    /** One POST to the endpoint — replaceable so the outage handling can be tested without a network. */
    fun interface Transport {
        fun post(body: String): Reply?
    }

    /** What came back: the status, the body (an error body included) and a Retry-After in seconds. */
    data class Reply(val code: Int, val body: String, val retryAfterSec: Long? = null)

    private object HttpTransport : Transport {
        override fun post(body: String): Reply? {
            val started = System.nanoTime()
            val r = postInner(body)
            com.therealityteam.videoplayer.util.HttpStats.record(
                ENDPOINT, (System.nanoTime() - started) / 1_000_000,
                r?.body?.length?.toLong() ?: 0L, r != null && r.code in 200..299,
            )
            return r
        }

        private fun postInner(body: String): Reply? = if (Offline.refuses(ENDPOINT)) null else runCatching {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                IDENTITY.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val retry = conn.getHeaderField("Retry-After")?.toLongOrNull()
            conn.disconnect()
            Reply(code, text, retry)
        }.getOrNull() // exception (network blip) → null → retried by post()
    }

    // ── outage breaker ──────────────────────────────────────────────────────────────────────
    // AniList can switch itself off under load ("The AniList API has been temporarily disabled",
    // 403) and stay off for hours. Without this every anime file in a scan still asked its
    // three-to-eight questions, each paid the 2.5s spacing below for a 403, and a library scan
    // that should have skipped the database spent a day waiting on it. After a few hard failures
    // in a row the lookups are paused; the engine reports it, and the next scan re-probes.
    //
    // The same 403 and the same sentence are also what a request WITHOUT a Referer header gets,
    // whatever its User-Agent — see [IDENTITY]. That reads as an outage and is not one, so a
    // client that sends no Referer looks permanently down while the API serves everyone else.
    @Volatile private var hardFailures = 0
    @Volatile private var outageUntil = 0L

    /** True while lookups are being skipped because the service is down. */
    val isDown: Boolean get() = System.currentTimeMillis() < outageUntil

    /** Searches skipped since the counter was last reset — for the scan's report. */
    val skipped = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * What the last failure actually was, in the service's own words.
     *
     * The breaker used to count failures without keeping one. A 403 reading "temporarily disabled
     * due to severe stability issues" was therefore indistinguishable from a real outage, and the
     * anime shelf sat idle for five days over a missing request header. Whoever looks next gets
     * the code and the sentence rather than a guess.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    private fun noteFailure(why: String? = null) {
        why?.let { lastFailure = it }
        if (++hardFailures >= OUTAGE_TRIP && !isDown) {
            outageUntil = System.currentTimeMillis() + OUTAGE_COOLDOWN_MS
            runCatching {
                Log.w(
                    TAG,
                    "AniList unreachable $hardFailures times in a row — pausing anime lookups for " +
                        "${OUTAGE_COOLDOWN_MS / 60_000} min; last failure: ${lastFailure ?: "no reply"}",
                )
            }
        }
    }

    private fun noteSuccess() {
        hardFailures = 0
        outageUntil = 0L
        lastFailure = null
    }

    private val cache = ConcurrentHashMap<String, List<MetaResult>>()

    /**
     * The same answers, kept across runs.
     *
     * The in-memory map dies with the process, so every re-scan pays AniList's throttle again from
     * zero — a full library pass is hours, and a user who rescans after adding a folder pays all of
     * it a second time. Measured here: a resumed pass was completing about one file a minute.
     *
     * This is a file per query rather than one map like [ScrapeCache], deliberately. An AniList
     * response carries the description, cast and staff of eight entries — tens of KB — and a
     * library asks thousands of distinct questions. Holding all of that in one in-memory map is
     * exactly how writing the library file once ran this process out of memory.
     */
    private val disk = cacheDir?.let { ResponseCache(it, RESPONSE_TTL_MS) }

    // AniList rate-limits per IP and degrades HARD under load (its sustained budget drops well below
    // the documented 90/min). The 4-way scrape gate would fire bursts that trip 429 storms whose
    // backoff stalls the whole scrape, so space calls conservatively (~24/min) to stay under even the
    // degraded ceiling — steady-but-slower beats fast-but-stalling. Repeats hit the in-memory cache.
    private val limiter = RateLimiter(minIntervalMs)

    suspend fun search(query: String): List<MetaResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        // The GraphQL shape is part of the answer: a cached page-of-8 response answers a
        // page-of-12 question wrongly (the Love Live! fix widened the page precisely because
        // row 9 held the right entry — serving the old cached page would un-fix it silently).
        val key = q.lowercase() + "#" + QUERY.hashCode()
        cache[key]?.let { return@withContext it }
        disk?.get(key)?.let { cached ->
            parseResponse(cached)?.let { results ->
                cache[key] = results
                return@withContext results
            }
        }
        if (isDown) {
            skipped.incrementAndGet()
            return@withContext emptyList()
        }
        val resp = (if (batching) batched(q) else single(q)) ?: return@withContext emptyList()
        val results = parseResponse(resp) ?: return@withContext emptyList()
        // Only a body we could actually read gets kept — `post` already returns null for a
        // transient failure, so a rate-limit blip can never be cached as "nothing there".
        disk?.put(key, resp)
        // Cache the MISS too. The API answered; "no such title" is an answer, and every file in a
        // folder asks the same questions — a 100-episode show re-issued each of its failing
        // candidate queries 100 times, at 2.5s a request. (A transient failure returns earlier,
        // above, without touching the cache, so a rate-limit blip still can't poison it.)
        cache[key] = results
        results
    }

    /** The media array of a GraphQL body → results. Null when the body is not one we can read. */
    // ───────────────────────── batching ─────────────────────────

    private class Waiting(val query: String, val reply: kotlinx.coroutines.CompletableDeferred<String?>)

    private val inbox = kotlinx.coroutines.channels.Channel<Waiting>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val batchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    init {
        batchScope.launch { pump() }
    }

    /**
     * Ask for [q], but let the request travel with whatever else is waiting.
     *
     * AniList counts REQUESTS, not queries: a reply carrying eleven aliased searches costs the same
     * one of the thirty a minute it allows (verified against `X-RateLimit-Remaining`, which fell by
     * one for a batch of five just as it did for a single). A library asks thousands of distinct
     * questions at 2.5 seconds each; asked eleven at a time that is eleven times less waiting, for
     * exactly the same answers.
     *
     * The ceiling is the server's query COMPLEXITY budget, not a count: 500, and the field set this
     * client asks for costs 42 apiece — 11 fit, 12 are refused with "Max query complexity should be
     * 500 but got 504".
     */
    private suspend fun single(q: String): String? {
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("search", q))
            .toString()
        return limiter.withPermit { post(body) }
    }

    private suspend fun batched(q: String): String? {
        val reply = kotlinx.coroutines.CompletableDeferred<String?>()
        inbox.send(Waiting(q, reply))
        return reply.await()
    }

    private suspend fun pump() {
        while (true) {
            val first = runCatching { inbox.receive() }.getOrNull() ?: return
            val batch = mutableListOf(first)
            // Hold the door briefly for company. Alone, a query still goes out on time; the window
            // only costs something when there is nothing else to send.
            kotlinx.coroutines.withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (batch.size < BATCH_MAX) batch += inbox.receive()
            }
            runCatching { dispatch(batch) }
                .onFailure { e -> batch.forEach { it.reply.complete(null) } }
        }
    }

    private suspend fun dispatch(batch: List<Waiting>) {
        if (isDown) {
            repeat(batch.size) { skipped.incrementAndGet() }
            batch.forEach { it.reply.complete(null) }
            return
        }
        // Two files can ask the same thing in one window; ask the server once.
        val distinct = batch.map { it.query }.distinct()
        val alias = distinct.withIndex().associate { (i, q) -> q to "a$i" }
        val vars = JSONObject().apply { distinct.forEachIndexed { i, q -> put("s$i", q) } }
        val decl = distinct.indices.joinToString(", ") { "\$s$it: String" }
        val blocks = distinct.indices.joinToString("\n") { i -> BATCH_BLOCK.replace("@I@", i.toString()) }
        val body = JSONObject()
            .put("query", "query ($decl) {\n$blocks\n}")
            .put("variables", vars)
            .toString()
        val resp = limiter.withPermit { post(body) }
        batch.forEach { w ->
            val slice = resp?.let { sliceOf(it, alias.getValue(w.query)) }
            w.reply.complete(slice)
        }
    }

    /**
     * The full entry for one id — what [search] deliberately no longer brings back.
     *
     * Called once a candidate has been chosen, so the description, cast and staff are fetched for
     * the entry that will be shown rather than for eleven that will not. Returns null when AniList
     * cannot be reached, and the caller keeps the light result it already has.
     */
    suspend fun detail(id: Int): MetaResult? = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext null
        val key = "id:" + id + "#" + DETAILS_QUERY.hashCode()
        details[key]?.let { return@withContext it }
        disk?.get(key)?.let { cached ->
            parseOne(cached)?.let { details[key] = it; return@withContext it }
        }
        if (isDown) {
            skipped.incrementAndGet()
            return@withContext null
        }
        val body = JSONObject()
            .put("query", DETAILS_QUERY)
            .put("variables", JSONObject().put("id", id))
            .toString()
        val resp = limiter.withPermit { post(body) } ?: return@withContext null
        val one = parseOne(resp) ?: return@withContext null
        disk?.put(key, resp)
        details[key] = one
        one
    }

    private val details = ConcurrentHashMap<String, MetaResult>()

    /**
     * The banners of many entries, [BANNER_BATCH] to a request — for the works matched before
     * [DETAILS_QUERY] asked for one. One detail call each, at the limiter's spacing, was ten minutes
     * for a 253-work shelf before its scrape could start; aliased, it is eleven requests. Each answer
     * is kept per id, an entry with no banner included, so the question is not asked again.
     */
    suspend fun banners(ids: Collection<Int>): Map<Int, String> = withContext(Dispatchers.IO) {
        val found = HashMap<Int, String>()
        val ask = ArrayList<Int>()
        for (id in ids.distinct().filter { it > 0 }) {
            when (val kept = disk?.get(BANNER_KEY + id)) {
                null -> ask += id
                NO_BANNER -> Unit
                else -> found[id] = kept
            }
        }
        for (chunk in ask.chunked(BANNER_BATCH)) {
            if (isDown) break
            val query = chunk.joinToString("\n", "query {\n", "\n}") { "b$it: Media(id: $it, type: ANIME) { bannerImage }" }
            val resp = limiter.withPermit { post(JSONObject().put("query", query).toString()) } ?: continue
            val data = runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull() ?: continue
            for (id in chunk) {
                if (!data.has("b$id")) continue
                val url = data.optJSONObject("b$id")?.optStringOrNull("bannerImage")
                disk?.put(BANNER_KEY + id, url ?: NO_BANNER)
                url?.let { found[id] = it }
            }
        }
        found
    }

    private fun parseOne(body: String): MetaResult? = runCatching {
        parseMedia(JSONObject(body).optJSONObject("data")?.optJSONObject("Media"))
    }.getOrNull()

    private fun parseResponse(body: String, alias: String = "Page"): List<MetaResult>? {
        val media = runCatching {
            JSONObject(body).optJSONObject("data")?.optJSONObject(alias)?.optJSONArray("media")
        }.getOrNull() ?: return null
        return (0 until media.length()).mapNotNull { parseMedia(media.optJSONObject(it)) }
    }

    /** One alias's slice of a batched reply, re-shaped as the single-query body the cache stores. */
    private fun sliceOf(body: String, alias: String): String? = runCatching {
        val page = JSONObject(body).optJSONObject("data")?.optJSONObject(alias) ?: return null
        JSONObject().put("data", JSONObject().put("Page", page)).toString()
    }.getOrNull()

    private fun parseMedia(o: JSONObject?): MetaResult? {
        o ?: return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val t = o.optJSONObject("title")
        val romaji = t?.optStringOrNull("romaji")
        val english = t?.optStringOrNull("english")
        val native = t?.optStringOrNull("native")
        val synonyms = o.optJSONArray("synonyms")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        } ?: emptyList()
        val chinese = synonyms.firstOrNull { hasHan(it) && !hasKana(it) }
        // The name the reader knows it by: a Chinese reader the Chinese synonym, a Japanese one the work's own,
        // everyone else the English (a German reader was handed the Chinese synonym too).
        val reader = TmdbConfig.language.substringBefore('-')
        val display = when (reader) {
            "zh" -> chinese ?: english ?: romaji ?: native
            "ja" -> native ?: romaji ?: english
            else -> english ?: romaji ?: native
        } ?: return null
        val isMovie = o.optString("format") == "MOVIE"
        return MetaResult(
            providerId = id,
            kind = if (isMovie) MediaKind.MOVIE else MediaKind.SHOW,
            title = display,
            originalTitle = native ?: romaji, // Japanese first — best for the Bangumi Chinese lookup
            romajiTitle = romaji ?: english,  // Latin script — best for the MyAnimeList / Jikan lookup
            englishTitle = english,           // kept separate so an English query can still be validated
            synonyms = synonyms,              // fansub abbreviations (Oinarisama, Otoboku) for the guard
            year = o.optInt("seasonYear").takeIf { it in 1900..2100 },
            overview = o.optStringOrNull("description")?.let(::stripHtml),
            rating = o.optDouble("averageScore").takeIf { it > 0 }?.let { it / 10.0 },
            ratings = o.optInt("averageScore").takeIf { it > 0 }
                ?.let { listOf(com.therealityteam.videoplayer.data.model.RatingScore(com.therealityteam.videoplayer.data.model.RatingSource.ANILIST, "$it")) }
                ?: emptyList(),
            runtime = o.optInt("duration").takeIf { it > 0 },
            certification = if (o.optBoolean("isAdult")) "R18+" else null,
            genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList(),
            cast = parseCharacters(o),
            crew = parseStaff(o),
            // Prefer extraLarge (~500px) over large (~230px) — the latter looks blurry on the big poster.
            posterUrl = o.optJSONObject("coverImage")
                ?.let { it.optStringOrNull("extraLarge") ?: it.optStringOrNull("large") },
            // The entry's banner, a 1900x400 still across the top of its AniList page: the picture behind
            // the detail page and the 继续观看 card, which an anime title had none of (TMDB's backdrops
            // are TMDB matches'). Only the details ask for it; a search has no use for it.
            backdropUrl = o.optStringOrNull("bannerImage"),
            popularity = o.optDouble("popularity", 0.0),
            format = o.optStringOrNull("format"),
        )
    }

    /** Cast = each character's Japanese voice actor (actor photo + name, role = the character). */
    private fun parseCharacters(o: JSONObject): List<Credit> {
        val edges = o.optJSONObject("characters")?.optJSONArray("edges") ?: return emptyList()
        return (0 until edges.length()).mapNotNull { i ->
            val edge = edges.optJSONObject(i) ?: return@mapNotNull null
            val character = edge.optJSONObject("node") ?: return@mapNotNull null
            val charName = character.optJSONObject("name")?.let { it.optStringOrNull("native") ?: it.optStringOrNull("full") }
            val va = edge.optJSONArray("voiceActors")?.optJSONObject(0) ?: return@mapNotNull null
            val vaName = va.optJSONObject("name")?.let { it.optStringOrNull("native") ?: it.optStringOrNull("full") }
                ?: return@mapNotNull null
            Credit(vaName, charName, va.optJSONObject("image")?.optStringOrNull("large"))
        }.take(10)
    }

    /** Staff = director / writers etc., keeping only recognized roles, ordered by importance. */
    private fun parseStaff(o: JSONObject): List<Credit> {
        val edges = o.optJSONObject("staff")?.optJSONArray("edges") ?: return emptyList()
        val credits = (0 until edges.length()).mapNotNull { i ->
            val edge = edges.optJSONObject(i) ?: return@mapNotNull null
            val role = STAFF_ROLES[edge.optStringOrNull("role")] ?: return@mapNotNull null
            val node = edge.optJSONObject("node") ?: return@mapNotNull null
            val name = node.optJSONObject("name")?.let { it.optStringOrNull("native") ?: it.optStringOrNull("full") }
                ?: return@mapNotNull null
            Credit(name, role, node.optJSONObject("image")?.optStringOrNull("large"))
        }
        // A director who also drew storyboards is the director, whichever edge came first.
        return rankCrew(credits, STAFF_ORDER)
    }

    private suspend fun post(body: String): String? {
        var backoff = 1_200L
        var rateLimited = false
        repeat(MAX_ATTEMPTS) { attempt ->
            val reply = transport.post(body)
            when {
                reply == null -> Unit // network blip → retried below
                reply.code in 200..299 -> {
                    noteSuccess()
                    return reply.body.ifEmpty { null }
                }
                // 403, and no retry helps. It may be a real outage, or it may be us: AniList sends
                // the same "temporarily disabled" sentence to a caller whose request carries no
                // Referer (see IDENTITY). Keep the words so the two can be told apart.
                reply.code == 403 -> {
                    noteFailure("403: " + reply.body.take(200).replace(Regex("\\s+"), " ").trim())
                    return null
                }
                // Rate-limited / transient server error → retry. Honour Retry-After if present.
                reply.code == 429 || reply.code in 500..599 -> {
                    if (reply.code == 429) rateLimited = true
                    reply.retryAfterSec?.let { backoff = it * 1_000 }
                }
                else -> return null // 4xx (bad request etc.) → give up; not the service's fault
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(8_000)
            }
        }
        // Every attempt failed. Being rate-limited is our own doing and not an outage — keep
        // going, slowly; anything else means the service is unreachable for us right now.
        if (!rateLimited) noteFailure("no usable reply after $MAX_ATTEMPTS attempts")
        return null
    }

    private fun stripHtml(s: String): String = Synopsis.unescape(
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .trim(),
    )

    private fun hasHan(s: String) = s.any { it.code in 0x4E00..0x9FFF }
    private fun hasKana(s: String) = s.any { it.code in 0x3040..0x30FF }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co"

        /**
         * Who we are, on every request.
         *
         * AniList answers a request that carries NO `Referer` with 403 and "The AniList API has
         * been temporarily disabled due to severe stability issues" — the wording of an outage,
         * sent to a caller the API is perfectly willing to serve. Any non-empty value passes, so
         * this one names the app rather than impersonating the site's own web client; the
         * User-Agent says who to throttle or contact if we ever misbehave. Verified 2026-09-11:
         * no Referer 403s, `aura://library` returns data, five calls at the 2.5 s spacing all pass.
         */
        val IDENTITY = mapOf(
            "User-Agent" to Http.USER_AGENT,
            "Referer" to "aura://library",
        )

        /** AniList staff edge role → localized Staff label. */
        val STAFF_ROLES: Map<String, String> = mapOf(
            "Director" to "导演",
            "Chief Director" to "总导演",
            "Original Creator" to "原作",
            "Original Story" to "原作",
            "Series Composition" to "系列构成",
            "Script" to "脚本",
            "Screenplay" to "脚本",
            "Storyboard" to "分镜",
            "Character Design" to "角色设计",
            "Original Character Design" to "原案",
            "Music" to "音乐",
            "Sound Director" to "音响监督",
            "Art Director" to "美术监督",
            "Animation Director" to "作画监督",
            "Producer" to "制片",
        )
        val STAFF_ORDER = listOf(
            "导演", "总导演", "原作", "系列构成", "脚本", "角色设计", "原案",
            "分镜", "音乐", "美术监督", "音响监督", "作画监督", "制片",
        )
        /** Anime metadata barely moves; a month between refreshes is plenty. */
        const val RESPONSE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_ATTEMPTS = 4
        private const val TAG = "AniList"
        /** Hard failures in a row before lookups are paused, and for how long. */
        /**
         * How many searches ride in one request.
         *
         * It was eleven because each aliased block cost 42 of the server's 500-point budget, and
         * most of that 42 was the characters and staff the search no longer asks for. Asked again
         * after the split, the server priced it itself: forty blocks came back
         * "Max query complexity should be 500 but got 640", which is 16 a block, and thirty were
         * answered in full at 480. Thirty it is.
         *
         * Note what now binds instead: the scrape runs twelve files at once, so at most twelve
         * searches can be outstanding and a batch never actually reaches thirty. The budget simply
         * stopped being the limit.
         */
        const val BATCH_MAX = 30

        /** How long a lone query waits for company before it goes out by itself. */
        const val BATCH_WINDOW_MS = 120L

        /** Entries per [banners] request: a bare `bannerImage` costs the server next to nothing. */
        const val BANNER_BATCH = 25
        private const val BANNER_KEY = "banner:"
        /** What the disk keeps for an entry with no banner (the cache keeps no empty answer). */
        private const val NO_BANNER = "-"


        /**
         * One aliased search inside a batched query — the same field set [QUERY] asks for.
         *
         * Written out rather than spliced from [QUERY]: the first attempt cut the field list out of
         * it and took one closing brace too many, so block zero closed the whole query and the
         * server answered `Syntax Error: Unexpected Name "a1"`. [AniListBatchTest] now counts the
         * braces, because that failure is invisible until every anime query returns nothing.
         */
        val BATCH_BLOCK = """
              a@I@: Page(perPage: 12) {
                media(search: ${'$'}s@I@, type: ANIME, sort: SEARCH_MATCH) {
                  id format seasonYear episodes duration isAdult averageScore popularity genres
                  title { romaji english native }
                  synonyms
                }
              }
        """.trimIndent()

        /**
         * Everything the SEARCH no longer carries, for the one entry that was actually chosen.
         *
         * The search used to bring back the description, the cast with their voice actors and the
         * staff — for all twelve entries on the page, of which the picker keeps one and reads none
         * of it while choosing (that is decided on titles, synonyms, format, year and popularity).
         * Measured against the live endpoint on four real searches, the page was 9.8x to 20.5x
         * bigger than the picker needs and between 1.6x and 3.9x slower to come back:
         *
         *     Attack on Titan     84808 bytes 1645ms  ->  8617 bytes  423ms
         *     Lucky Star          18995 bytes  689ms  ->   928 bytes  435ms
         *     Ghost in the Shell  74359 bytes 1100ms  ->  5370 bytes  773ms
         *     Macross Frontier    33957 bytes  742ms  ->  2853 bytes  435ms
         *
         * This is asked once per WORK rather than once per query, and the answer is kept on disk
         * like any other, so a hundred-episode show asks for it once.
         */
        val DETAILS_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id format seasonYear episodes duration isAdult averageScore popularity genres
                title { romaji english native }
                synonyms
                description(asHtml: false)
                coverImage { extraLarge large }
                bannerImage
                characters(perPage: 12, sort: [ROLE, RELEVANCE]) {
                  edges {
                    role
                    node { name { full native } }
                    voiceActors(language: JAPANESE, sort: [RELEVANCE]) { name { full native } image { large } }
                  }
                }
                staff(perPage: 12, sort: [RELEVANCE]) {
                  edges { role node { name { full native } image { large } } }
                }
              }
            }
        """.trimIndent()

        /** The real transport, for a probe that wants to watch what the server says. */
        fun httpTransport(): Transport = HttpTransport

        private const val OUTAGE_TRIP = 3
        private const val OUTAGE_COOLDOWN_MS = 10L * 60 * 1000

        /**
         * perPage 12, not 8: AniList's SEARCH_MATCH order buries the franchise's own first
         * season under its later spin-offs — for "Love Live!" the S1 entry ranks NINTH, one
         * past a page of 8, so every S1 file matched Sunshine!! instead. The picker's guards
         * do the filtering; the page just has to be big enough to contain the right answer.
         */
        val QUERY = """
            query (${'$'}search: String) {
              Page(perPage: 12) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                  id format seasonYear episodes duration isAdult averageScore popularity genres
                  title { romaji english native }
                  synonyms
                }
              }
            }
        """.trimIndent()
    }
}
