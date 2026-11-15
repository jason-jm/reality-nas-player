package com.therealityteam.videoplayer.data.scrape

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A small disk-backed string cache (key → value + timestamp) with a TTL, used to avoid re-hitting the
 * free rating APIs (Jikan / Bangumi) on re-scans and for titles shared across episodes. An empty
 * value is a *negative* cache ("looked up, nothing there") so misses aren't re-queried every time.
 * Writes are coalesced on a background scope so a burst of puts during a scrape is one file write.
 */
class ScrapeCache(baseDir: File, name: String, private val ttlMs: Long) {

    private val file = File(baseDir, "aura/cache/$name.json").apply { parentFile?.mkdirs() }
    private val map = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    @Volatile private var flushPending = false

    init {
        runCatching { load() }
    }

    /** Cached value (possibly "" for a known-negative), or null when absent/expired — caller fetches. */
    fun get(key: String): String? {
        val e = map[key] ?: return null
        return if (System.currentTimeMillis() - e.ts < ttlMs) e.value else null
    }

    fun put(key: String, value: String) {
        map[key] = Entry(value, System.currentTimeMillis())
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushPending) return
        flushPending = true
        scope.launch {
            delay(2_000) // debounce: fold a burst of puts into a single write
            flushPending = false
            writeMutex.withLock { runCatching { persist() } }
        }
    }

    private fun persist() {
        val o = JSONObject()
        map.forEach { (k, e) -> o.put(k, JSONObject().put("v", e.value).put("t", e.ts)) }
        file.writeText(o.toString())
    }

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        o.keys().forEach { k ->
            o.optJSONObject(k)?.let { map[k] = Entry(it.optString("v"), it.optLong("t")) }
        }
    }

    private data class Entry(val value: String, val ts: Long)
}
