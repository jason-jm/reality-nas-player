package com.therealityteam.videoplayer.data.scrape

import com.therealityteam.videoplayer.util.Log
import java.io.File
import java.security.MessageDigest
import com.therealityteam.videoplayer.util.elapsedMillis

/**
 * A disk cache holding ONE FILE PER KEY.
 *
 * [ScrapeCache] keeps its whole map in memory and rewrites one JSON file, which fits the small
 * rating lookups it was built for. This one exists for bodies that are individually large and
 * collectively numerous — an AniList search response carries the description, cast and staff of
 * eight entries, and a library asks thousands of distinct questions. Reading one entry here does
 * not touch the rest, so the cache never has to fit in memory at once.
 *
 * A miss and an expiry are the same thing to the caller: null.
 */
class ResponseCache(root: File, private val ttlMs: Long) {

    private val dir = File(root, "aura/cache/responses").apply { mkdirs() }

    fun get(key: String): String? {
        val startedAt = elapsedMillis()
        try {
            return getInner(key)
        } finally {
            reads.incrementAndGet()
            readMillis.addAndGet(elapsedMillis() - startedAt)
        }
    }

    private fun getInner(key: String): String? {
        val f = File(dir, fileName(key))
        if (!f.isFile) return null
        if (System.currentTimeMillis() - f.lastModified() > ttlMs) {
            f.delete()
            return null
        }
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Written through a temp file so a kill mid-write cannot leave a truncated body behind.
     *
     * A failure is logged rather than swallowed. A cache that silently never writes looks exactly
     * like a cache that works — the scrape simply stays slow — and that is precisely how this one
     * spent an hour doing nothing while every lookup went back to the network.
     */
    fun put(key: String, value: String) {
        val startedAt = elapsedMillis()
        try {
            putInner(key, value)
        } finally {
            writes.incrementAndGet()
            writeMillis.addAndGet(elapsedMillis() - startedAt)
        }
    }

    private fun putInner(key: String, value: String) {
        val target = File(dir, fileName(key))
        // A temp file of its own: episodes of one show ask the same question at once, and with one
        // shared name the second writer found the first had already renamed it away.
        val tmp = File(dir, "${target.name}.${Thread.currentThread().id}-${System.nanoTime()}.tmp")
        runCatching {
            tmp.writeText(value)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("rename failed: ${tmp.name} -> ${target.name}")
            }
        }.onFailure { Log.w(TAG, "cache write failed for ${target.name}: ${it.javaClass.simpleName}: ${it.message}") }
    }

    companion object {
        private const val TAG = "AuraResponseCache"

        /** What the on-disk answer store costs a pass — a file per query adds up or it does not. */
        val reads = java.util.concurrent.atomic.AtomicLong()
        val readMillis = java.util.concurrent.atomic.AtomicLong()
        val writes = java.util.concurrent.atomic.AtomicLong()
        val writeMillis = java.util.concurrent.atomic.AtomicLong()

        fun resetDisk() { reads.set(0); readMillis.set(0); writes.set(0); writeMillis.set(0) }

        fun diskReport(): String =
            "response cache: ${reads.get()} reads in ${readMillis.get()}ms, " +
                "${writes.get()} writes in ${writeMillis.get()}ms"
    }

    private fun fileName(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
