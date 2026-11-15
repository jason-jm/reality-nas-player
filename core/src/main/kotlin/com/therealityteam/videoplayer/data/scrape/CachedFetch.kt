package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.util.Http
import com.therealityteam.videoplayer.util.HttpResult
import com.therealityteam.videoplayer.util.TransientHttpException
import java.io.File

/**
 * One GET, remembered by URL — the answer AND the absence of one.
 *
 * A library is mostly repeats: 9,597 files resolve to 867 works, so every episode of a show asked
 * TMDB the same question and got the same answer back over the network. AniList has kept a response
 * cache since it was throttled into needing one; the TMDB path, which serves films, television and
 * documentaries, had none at all — not for search, not for details, not for OMDb's ratings.
 *
 * A MISS is cached too, and that is half the value: a documentary no database carries walks five
 * fallback queries and finds nothing, and without this its neighbours in the same folder walk all
 * five again. Measured before the cache existed, a shelf of unmatched files cost 5.6s a file against
 * 2.5s for a shelf that matched.
 *
 * A TRANSIENT failure (429, 5xx, a dropped connection) is never cached. It is not an answer, and
 * writing it down would turn one bad minute into a week of pretending the work does not exist.
 */
internal class CachedFetch(root: File, ttlMs: Long = DEFAULT_TTL_MS) {

    private val disk = ResponseCache(root, ttlMs)

    /** A few hundred bodies in front of the disk, so the hot repeats never touch the filesystem. */
    private val hot = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MEMORY_ENTRIES
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), throwOnTransient: Boolean = false): String? {
        cached(url)?.let { return it.takeIf { v -> v != MISS } }
        return when (val r = Http.fetch(url, headers)) {
            is HttpResult.Ok -> r.body.also { remember(url, it) }
            HttpResult.Missing -> null.also { remember(url, MISS) }
            HttpResult.Transient -> if (throwOnTransient) throw TransientHttpException(url) else null
        }
    }

    private fun cached(url: String): String? =
        synchronized(hot) { hot[url] } ?: disk.get(url)?.also { synchronized(hot) { hot[url] = it } }

    private fun remember(url: String, body: String) {
        synchronized(hot) { hot[url] = body }
        disk.put(url, body)
    }

    internal companion object {
        /** A body the caller reads as "asked, nothing there" rather than "not asked yet". */
        const val MISS = "<<aura-miss>>"
        const val MEMORY_ENTRIES = 256
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
