package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.playback.TrackPicks
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
 * The sound and subtitles last picked in a show (see [TrackPicks]), for its next episodes — keyed
 * like 跳过片头片尾 ([SkipStore.keyOf]): one season of files shares one choice. A file's own pick
 * ([com.therealityteam.videoplayer.data.playback.TrackMemory]) still comes first; this answers for
 * a file that has none. Stored beside the library, written at once.
 */
class TrackPickStore(filesDir: File) {

    data class ShowTracks(
        val audio: TrackPicks.Pick? = null,
        val subtitle: TrackPicks.Pick? = null,
        /** The show's subtitles were turned off. */
        val subtitlesOff: Boolean = false,
    ) {
        val isEmpty: Boolean get() = audio == null && subtitle == null && !subtitlesOff
    }

    private val file = File(filesDir, "aura/tracks.json")
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _all = MutableStateFlow(runCatching { if (file.exists()) decode(file.readText()) else emptyMap() }.getOrDefault(emptyMap()))
    val all: StateFlow<Map<String, ShowTracks>> = _all

    fun of(key: String): ShowTracks = _all.value[key] ?: ShowTracks()

    fun setAudio(key: String, pick: TrackPicks.Pick) = update(key) { it.copy(audio = pick) }

    /** The show's subtitles: [pick], or off when null. */
    fun setSubtitle(key: String, pick: TrackPicks.Pick?) =
        update(key) { it.copy(subtitle = pick, subtitlesOff = pick == null) }

    /** The show's picks gone, as never made (a test's, undone). */
    fun forget(key: String) = update(key) { ShowTracks() }

    private fun update(key: String, change: (ShowTracks) -> ShowTracks) {
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
        fun encode(all: Map<String, ShowTracks>): String {
            val arr = JSONArray()
            all.forEach { (key, t) ->
                val o = JSONObject().put("key", key)
                t.audio?.let { o.put("audio", encodePick(it)) }
                t.subtitle?.let { o.put("subtitle", encodePick(it)) }
                if (t.subtitlesOff) o.put("subtitlesOff", true)
                arr.put(o)
            }
            return arr.toString()
        }

        fun decode(json: String): Map<String, ShowTracks> = runCatching {
            val arr = JSONArray(json)
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val tracks = ShowTracks(
                        audio = o.optJSONObject("audio")?.let(::decodePick),
                        subtitle = o.optJSONObject("subtitle")?.let(::decodePick),
                        subtitlesOff = o.optBoolean("subtitlesOff", false),
                    )
                    if (!tracks.isEmpty) put(key, tracks)
                }
            }
        }.getOrDefault(emptyMap())

        private fun encodePick(p: TrackPicks.Pick): JSONObject = JSONObject().apply {
            p.language?.let { put("language", it) }
            put("name", p.name)
            if (p.external) put("external", true)
            p.tail?.let { put("tail", it) }
            if (p.channels > 0) put("channels", p.channels)
        }

        private fun decodePick(o: JSONObject): TrackPicks.Pick = TrackPicks.Pick(
            language = o.optString("language").takeIf { it.isNotBlank() },
            name = o.optString("name"),
            external = o.optBoolean("external", false),
            tail = o.optString("tail").takeIf { it.isNotBlank() },
            channels = o.optInt("channels", 0),
        )
    }
}
