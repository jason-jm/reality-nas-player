package com.therealityteam.videoplayer.util

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a request came back with. The distinction that matters is the last two: a provider saying
 * "no such title" and a provider refusing to answer look identical once both are `null`, and the
 * scrape engine draws opposite conclusions from them — one clears an item's identity, the other
 * must leave it alone until we can ask again.
 */
sealed interface HttpResult {
    data class Ok(val body: String) : HttpResult

    /** The server answered definitively: there is nothing here (a 4xx that is not 429). */
    data object Missing : HttpResult

    /** Rate-limited, server error, or the network failed — and retrying did not help. Ask later. */
    data object Transient : HttpResult
}

/**
 * Thrown by a caller that must not let a rate limit be mistaken for an answer. The scrape engine
 * turns this into ERROR for the item, which keeps whatever identity it already had and re-queues
 * it for the next pass.
 */
class TransientHttpException(url: String) : IOException("provider unavailable (rate limit / outage): $url")

/**
 * Tiny blocking HTTP helper (no third-party deps). Callers wrap in Dispatchers.IO.
 *
 * Every request retries on the failures that are worth retrying — 429 and 5xx and network blips —
 * with exponential backoff, honouring `Retry-After` when the server sends one. This lives here
 * rather than in each client because there is only one correct answer to "we got a 429" and four
 * providers that need it; the AniList client had to grow its own copy first, and the four that go
 * through this helper were meanwhile treating a rate limit as "no such film".
 */
object Http {

    fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        (fetch(url, headers) as? HttpResult.Ok)?.body

    /** [get], but a rate limit throws instead of quietly looking like a miss. */
    fun getOrThrow(url: String, headers: Map<String, String> = emptyMap()): String? =
        when (val r = fetch(url, headers)) {
            is HttpResult.Ok -> r.body
            HttpResult.Missing -> null
            HttpResult.Transient -> throw TransientHttpException(url)
        }

    /**
     * A host that has just said 429 is not asked again until it said we may.
     *
     * Each request retried a rate limit four times on its own, with a doubling backoff — so one
     * throttled service cost 15.8 seconds PER FILE while every one of those attempts made the
     * throttling worse. Measured on a documentary pass: four Jikan calls, four failures, 63 seconds.
     * The limit is a property of the host, not of the request, so it is remembered on the host.
     */
    private val coolUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val consecutive429 = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val consecutiveFail = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    fun cooling(url: String): Boolean =
        (coolUntil[hostOf(url)] ?: 0L) > System.currentTimeMillis()

    /**
     * A host is held off only once it has said 429 [LIMIT_TRIP] times without an answer in between.
     *
     * One 429 followed by a 200 is ordinary and must still succeed — that is what the retry is for.
     * A WALL of them is a different thing: every request was independently retrying four times with
     * a doubling backoff, so a throttled service cost 15.8 seconds per file while each of those
     * attempts made the throttling worse. Measured on a documentary pass: four Jikan calls, four
     * failures, 63 seconds between them.
     */
    private fun noteRateLimit(url: String, retryAfter: Long?) {
        val host = hostOf(url)
        val n = consecutive429.merge(host, 1, Int::plus) ?: 1
        if (n >= LIMIT_TRIP) {
            coolUntil[host] = System.currentTimeMillis() +
                (retryAfter ?: DEFAULT_COOLDOWN_MS).coerceIn(1_000L, COOLDOWN_CAP_MS)
        }
    }

    private fun noteAnswer(url: String) {
        val host = hostOf(url)
        consecutive429.remove(host)
        consecutiveFail.remove(host)
        coolUntil.remove(host)
        UnreachableHosts.forget(host)
    }

    /**
     * A whole call gave up without an answer: leave the host alone for a moment.
     *
     * Unlike [noteRateLimit] this trips on the FIRST failure, because a failure already means every
     * attempt was spent and some seconds of waiting with it — the host has had its chances. Measured
     * against an unroutable address, five calls cost 282 seconds and the last was no cheaper than the
     * first. A cooldown starting at ten seconds and doubling to a minute costs a host that merely
     * blipped almost nothing, since any answer clears it, and costs a host that is genuinely down
     * one call per window instead of one per file.
     */
    private fun noteFailure(url: String) {
        val host = hostOf(url)
        val n = consecutiveFail.merge(host, 1, Int::plus) ?: 1
        // …and a host that has not answered [DEAD_AFTER] times running is out of reach from this network
        // (a headset at home in China: Jikan never answered, 16 s a call, again each time its cooldown ran
        // out): left alone for a long while — any answer from it clears that, as before. One remembered so
        // from an earlier launch is out of reach at its first failure, and remembered for the next.
        val dead = n >= DEAD_AFTER || UnreachableHosts.contains(host)
        coolUntil[host] = System.currentTimeMillis() +
            if (dead) DEAD_COOLDOWN_MS
            else (FAIL_COOLDOWN_MS shl (n - 1).coerceAtMost(3)).coerceAtMost(COOLDOWN_CAP_MS)
        if (dead) UnreachableHosts.remember(host)
    }

    // A remembered-unreachable host's one call in flight, which the others to it wait on.
    private val probes = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /**
     * For a host remembered unreachable ([UnreachableHosts]), one call at a time: the first to come goes
     * (its latch is returned, to be let go when it ends) and the others wait for it, then go on as it left
     * the host — set aside again, or answering and forgotten. Many films' calls to a dead host had all
     * gone out together before the first of them failed, each spending its own 16 s.
     */
    private fun probeFor(host: String): java.util.concurrent.CountDownLatch? {
        if (!UnreachableHosts.contains(host)) return null
        val mine = java.util.concurrent.CountDownLatch(1)
        val running = probes.putIfAbsent(host, mine) ?: return mine
        running.await(PROBE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return null
    }

    /** Test seam: forget every host's rate-limit history. */
    fun forgetRateLimits() {
        coolUntil.clear()
        consecutive429.clear()
        consecutiveFail.clear()
        probes.clear()
        UnreachableHosts.reload()
    }

    /**
     * Names a service answers under besides the one it is asked by, in the order they are tried — the last
     * that answered first. TMDB's API is api.themoviedb.org and api.tmdb.org: on a headset on a Chinese
     * home network the first resolved to an address that never answered (every scrape failed, 16 s a
     * call) while the second answered in under a second; behind the Mac's proxy both did. Callers keep
     * asking by the usual name (cache keys stay as they were); the request goes to the name that answers.
     */
    internal val alternates = java.util.concurrent.ConcurrentHashMap(
        mapOf("api.themoviedb.org" to listOf("api.tmdb.org", "api.themoviedb.org")),
    )
    private val answering = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * [get], as a POST of [json]: a search API that takes its query in the body (bgm.tv's). Asked the way
     * every other call is — the host's cooldowns, its being out of reach, the stats — where bgm.tv's own
     * connection had none of it: on a network that could not reach it, each anime's searches spent 16-22 s
     * apiece, a minute a film, and no report said where it went.
     */
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String? =
        (fetchOne(url, headers, json) as? HttpResult.Ok)?.body

    fun fetch(url: String, headers: Map<String, String> = emptyMap()): HttpResult {
        val host = hostOf(url)
        val names = alternates[host] ?: return fetchOne(url, headers)
        val first = answering[host] ?: names.first()
        var last: HttpResult = HttpResult.Transient
        for (name in listOf(first) + names.filter { it != first }) {
            val r = fetchOne(url.replaceFirst("://$host", "://$name"), headers)
            if (r !is HttpResult.Transient) {
                if (answering.put(host, name) != name && name != first) Log.i("AuraHttp", "$host answers as $name")
                return r
            }
            last = r
        }
        return last
    }

    private fun fetchOne(url: String, headers: Map<String, String>, json: String? = null): HttpResult {
        if (cooling(url)) return HttpResult.Transient
        // Offline: not asked, and not "missing" either — asked again once the user is back online.
        if (Offline.refuses(url)) return HttpResult.Transient
        val host = hostOf(url)
        val probe = probeFor(host)
        // Waited on another call to a remembered-unreachable host, which failed: this one is not made.
        if (probe == null && cooling(url)) return HttpResult.Transient
        val started = System.nanoTime()
        val r = try {
            // A host that answers at all connects well inside this; one that did not last time and still
            // does not is learned in a few seconds rather than two eight-second tries.
            fetchInner(url, headers, json, if (probe != null) PROBE_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
        } finally {
            probe?.let { probes.remove(host, it); it.countDown() }
        }
        HttpStats.record(
            url, (System.nanoTime() - started) / 1_000_000,
            (r as? HttpResult.Ok)?.body?.length?.toLong() ?: 0L, r is HttpResult.Ok,
        )
        return r
    }

    private fun fetchInner(url: String, headers: Map<String, String>, json: String? = null, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS): HttpResult {
        var backoff = INITIAL_BACKOFF_MS
        repeat(MAX_ATTEMPTS) { attempt ->
            var retryAfter: Long? = null
            var pooled = false
            val outcome = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = if (json != null) "POST" else "GET"
                    conn.connectTimeout = connectTimeoutMs
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    if (json != null) conn.setRequestProperty("Content-Type", "application/json")
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) } // a caller may override
                    if (json != null) {
                        conn.doOutput = true
                        conn.outputStream.use { it.write(json.toByteArray()) }
                    }
                    when (val code = conn.responseCode) {
                        in 200..299 -> {
                            // Read to the end and leave the socket alone: a fully-consumed 2xx body
                            // returns its connection to the keep-alive pool, and the next request to
                            // the same host skips the TCP and TLS handshake. Measured over blocks of
                            // ten against two hosts, ~7% — smaller than the handshake cost suggests,
                            // but steady in the same direction.
                            pooled = true
                            noteAnswer(url)
                            HttpResult.Ok(conn.inputStream.bufferedReader().use { it.readText() })
                        }
                        else -> {
                            retryAfter = retryAfterMs(conn.getHeaderField("Retry-After"))
                            if (code == 429) noteRateLimit(url, retryAfter) else noteAnswer(url)
                            if (isTransient(code)) null else HttpResult.Missing
                        }
                    }
                } finally {
                    // Anything else — an error whose body was never drained, a throw part-way — must
                    // be closed, or the unread bytes hold a connection open until it times out.
                    if (!pooled) conn.disconnect()
                }
            }
            val thrown = outcome.exceptionOrNull() // a blip is retryable, same as a 5xx
            val answered = outcome.getOrNull()

            if (answered != null) return answered
            // A host that did not answer in time will not answer faster on the next try. Three more
            // eight-second waits is how one dead API cost a documentary pass 67 seconds over four
            // calls; measured against an unroutable address a single call took 56.4s, and the fifth
            // cost exactly as much as the first.
            if (thrown is java.net.SocketTimeoutException || attempt == MAX_ATTEMPTS - 1) {
                noteFailure(url)
                return HttpResult.Transient
            }
            // A rate limit is answered by waiting, not by asking again sooner: the cooldown above
            // holds the host off, and this request gives up rather than spending three more tries.
            if (cooling(url)) return HttpResult.Transient
            // A server asking for longer than we are willing to hold the scrape open is not worth
            // waiting on: give up now and let the next pass re-queue the item, rather than parking
            // a worker for an hour on a Retry-After we did not choose.
            retryAfter?.let { if (it > RETRY_AFTER_CAP_MS) return HttpResult.Transient }
            val nap = retryAfter ?: backoff
            HttpStats.recordBackoff(url, nap)
            Thread.sleep(nap)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return HttpResult.Transient
    }

    /** 429 and 5xx are worth asking again; every other 4xx is the server's final answer. */
    fun isTransient(status: Int): Boolean = status == 429 || status in 500..599

    /**
     * `Retry-After` as milliseconds. The header is either a delay in seconds or an HTTP date;
     * both are in the wild. Returns null when absent or unparseable, so the caller falls back to
     * its own backoff.
     */
    fun retryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return (it * 1_000).coerceAtLeast(0L) }
        val date = runCatching {
            java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (date - nowMs).coerceAtLeast(0L)
    }

    fun download(url: String, dest: File): Boolean {
        if (Offline.refuses(url)) return false
        val started = System.nanoTime()
        val ok = downloadInner(url, dest)
        HttpStats.record(url, (System.nanoTime() - started) / 1_000_000, if (ok) dest.length() else 0L, ok)
        return ok
    }

    private fun downloadInner(url: String, dest: File): Boolean {
        var backoff = INITIAL_BACKOFF_MS
        repeat(MAX_ATTEMPTS) { attempt ->
            var pooled = false
            val done = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        // Drain the error body, for the same reason fetchInner does: an artwork host
                        // answers 404 often enough that leaving those sockets undrained costs a
                        // connection each time.
                        runCatching { conn.errorStream?.use { it.readBytes() } }
                        pooled = true
                        return@runCatching if (isTransient(code)) null else false
                    }
                    dest.parentFile?.mkdirs()
                    conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
                    // copyTo has read to the end, so this connection can go back to the keep-alive
                    // pool. It matters more here than anywhere else: artwork is the highest-volume
                    // host in a scrape — 1,944 requests for a single 1,080-file shelf — and every
                    // one of them was paying for a fresh TCP and TLS handshake.
                    pooled = true
                    dest.length() > 0
                } finally {
                    if (!pooled) conn.disconnect()
                }
            }.getOrNull()

            if (done != null) return done
            if (attempt == MAX_ATTEMPTS - 1) return false
            HttpStats.recordBackoff(url, backoff)
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return false
    }

    private const val MAX_ATTEMPTS = 4
    private const val LIMIT_TRIP = 3
    private const val DEFAULT_COOLDOWN_MS = 20_000L

    /** How long a host that failed outright is left alone; doubles while it keeps failing. */
    private const val FAIL_COOLDOWN_MS = 10_000L
    private const val DEAD_AFTER = 3
    private const val DEAD_COOLDOWN_MS = 15 * 60_000L
    private const val COOLDOWN_CAP_MS = 60_000L
    private const val INITIAL_BACKOFF_MS = 1_200L
    private const val MAX_BACKOFF_MS = 8_000L

    /** The longest a call waits on another call to a remembered-unreachable host (one call's worst). */
    private const val PROBE_WAIT_MS = 60_000L

    /** Longer than this and we stop waiting and re-queue instead. */
    private const val RETRY_AFTER_CAP_MS = 15_000L

    /**
     * Who is calling, on every request this object makes.
     *
     * An API that cannot tell an app from an anonymous script tends to block the script:
     * AniList answers a caller with no Referer as though it were switched off, and Bangumi
     * and Jikan ask for a User-Agent outright. Saying who we are costs nothing and gives a
     * provider someone to throttle rather than a reason to shut the door. A caller that
     * passes its own User-Agent in [headers] still wins.
     */
    const val USER_AGENT = "$PRODUCT_AGENT (PICO spatial video player; library metadata lookup)"

    private const val CONNECT_TIMEOUT_MS = 8_000
    /** A remembered-unreachable host's one try ([probeFor]): a reachable host's handshake is well inside it. */
    private const val PROBE_CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 20_000
}
