package com.therealityteam.videoplayer.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Where a scrape's network time goes, per host.
 *
 * A full pass is hours and the reasons are not guessable: a rate limiter, a handshake paid per
 * request, a lookup repeated once per FILE that belongs once per WORK, an image fetched again for
 * every episode. Each of those was proposed and some were wrong. Counting them costs two atomics
 * per request and settles the argument.
 */
object HttpStats {

    class Stat {
        val calls = AtomicLong()
        val millis = AtomicLong()
        val bytes = AtomicLong()
        val failures = AtomicLong()

        /**
         * Time spent asleep between attempts, not talking to anybody.
         *
         * The backoff is a Thread.sleep, and whether that should be a suspending delay is a question
         * about how much of a pass it actually accounts for — so it is counted rather than argued
         * about. A worker holds its place in the scrape's twelve either way, so the only thing a
         * delay would free is the IO thread underneath it, which is worth doing only if this number
         * is large.
         */
        val backoffMillis = AtomicLong()
    }

    private val byHost = ConcurrentHashMap<String, Stat>()

    fun record(url: String, ms: Long, bytes: Long, ok: Boolean) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
        val s = byHost.getOrPut(host) { Stat() }
        s.calls.incrementAndGet()
        s.millis.addAndGet(ms)
        s.bytes.addAndGet(bytes)
        if (!ok) s.failures.incrementAndGet()
    }

    /** Count time a request spent waiting between attempts rather than in flight. */
    fun recordBackoff(url: String, ms: Long) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"
        byHost.getOrPut(host) { Stat() }.backoffMillis.addAndGet(ms)
    }

    fun reset() = byHost.clear()

    /** One line per host, busiest first — for the scrape's own log. */
    fun report(): String = byHost.entries
        .sortedByDescending { it.value.millis.get() }
        .joinToString("\n") { (host, s) ->
            val c = s.calls.get()
            val ms = s.millis.get()
            "  %-26s calls=%-6d total=%-8s avg=%-7s bytes=%-9s fail=%-4d backoff=%s"
                .format(
                    host, c, "${ms / 1000}s", "${if (c > 0) ms / c else 0}ms", "${s.bytes.get() / 1024}KB",
                    s.failures.get(), "${s.backoffMillis.get() / 1000}s",
                )
        }
        .ifEmpty { "  (no requests)" }
}
