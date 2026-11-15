package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.model.MediaItem
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
 * 跳过片头片尾: where a show's opening ends and how long its closing runs, set once while watching
 * and applied to every episode after.
 *
 * Keyed by the show ([keyOf]) — every episode of one series shares one opening. The closing is kept
 * as a length counted back from the end rather than a position, because episodes differ by a few
 * seconds and the credits do not: "片尾从这里开始" pressed at 22:05 of a 23:50 episode is the last
 * 1:45 of every one. Stored beside the library like [BookmarkStore], written at once.
 */
class SkipStore(filesDir: File) {

    data class Marks(val introEndMs: Long? = null, val outroLengthMs: Long? = null) {
        val isEmpty: Boolean get() = introEndMs == null && outroLengthMs == null

        /** Where an episode about to start at [startMs] should start: past the opening if inside it. */
        fun startAt(startMs: Long): Long = introEndMs?.takeIf { startMs < it } ?: startMs

        /**
         * Where the closing begins in a file [durationMs] long — null when there is none, or when it
         * would begin before the opening ends (a far shorter file: an extra, a recap).
         */
        fun outroAt(durationMs: Long): Long? {
            val length = outroLengthMs ?: return null
            if (durationMs <= 0L) return null
            val at = durationMs - length
            return at.takeIf { it > (introEndMs ?: 0L) }
        }
    }

    private val file = File(filesDir, "aura/skips.json")
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _all = MutableStateFlow(runCatching { if (file.exists()) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()))
    val all: StateFlow<Map<String, Marks>> = _all

    fun of(key: String): Marks = _all.value[key] ?: Marks()

    fun setIntroEnd(key: String, ms: Long?) = update(key) { it.copy(introEndMs = ms?.coerceAtLeast(0L)) }

    fun setOutroLength(key: String, ms: Long?) = update(key) { it.copy(outroLengthMs = ms?.takeIf { l -> l > 0L }) }

    fun clear(key: String) = update(key) { Marks() }

    private fun update(key: String, change: (Marks) -> Marks) {
        val next = change(of(key))
        _all.value = if (next.isEmpty) _all.value - key else _all.value + (key to next)
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
        /**
         * The show a file belongs to, for its marks: the identity its card is grouped by when a
         * database or a server named it, else the folder it sits in — a season of fansub files
         * shares a folder, if nothing else.
         */
        fun keyOf(item: MediaItem): String =
            item.collectionKey?.takeIf { it.isNotBlank() }
                ?: "dir:" + item.path.replace('\\', '/').substringBeforeLast('/', item.path)

        fun encode(all: Map<String, Marks>): String {
            val arr = JSONArray()
            all.forEach { (key, m) ->
                val o = JSONObject().put("key", key)
                m.introEndMs?.let { o.put("intro", it) }
                m.outroLengthMs?.let { o.put("outro", it) }
                arr.put(o)
            }
            return arr.toString()
        }

        fun decode(json: String): Map<String, Marks> = runCatching {
            val arr = JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val marks = Marks(
                        introEndMs = if (o.has("intro")) o.optLong("intro") else null,
                        outroLengthMs = if (o.has("outro")) o.optLong("outro") else null,
                    )
                    if (!marks.isEmpty) put(key, marks)
                }
            }
        }.getOrDefault(emptyMap())
    }
}
