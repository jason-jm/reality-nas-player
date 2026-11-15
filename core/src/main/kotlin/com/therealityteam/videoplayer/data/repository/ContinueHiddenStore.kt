package com.therealityteam.videoplayer.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 从「继续观看」中移除: the cards taken out of the row, each with the moment its entry was from — the
 * card comes back by itself once it is watched again after that (see ContinueWatching.of). The film
 * keeps its place and its progress; only the row forgets it. Kept on this headset only, beside the
 * library, written at once.
 */
class ContinueHiddenStore(filesDir: File) {

    private val file = File(filesDir, "aura/continue_hidden.json")
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _all = MutableStateFlow(runCatching { if (file.exists()) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()))
    val all: StateFlow<Map<String, Long>> = _all

    /** Take card [key] out of the row as of [at], the time of the entry it showed. */
    fun hide(key: String, at: Long) {
        _all.value = _all.value + (key to maxOf(at, _all.value[key] ?: 0L))
        save()
    }

    /** 撤销: card [key] as it was before a [hide] — [previous] its mark then, null for none. */
    fun restore(key: String, previous: Long?) {
        _all.value = if (previous == null) _all.value - key else _all.value + (key to previous)
        save()
    }

    private fun save() {
        writes.launch {
            writeLock.withLock {
                // The state as it is when the lock is held — see BookmarkStore for why.
                val latest = _all.value
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(encode(latest))
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }
            }
        }
    }

    companion object {
        fun encode(all: Map<String, Long>): String =
            JSONArray().apply { all.forEach { (k, at) -> put(JSONObject().put("key", k).put("at", at)) } }.toString()

        fun decode(json: String): Map<String, Long> = runCatching {
            val arr = JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                    put(key, o.optLong("at"))
                }
            }
        }.getOrDefault(emptyMap())
    }
}
