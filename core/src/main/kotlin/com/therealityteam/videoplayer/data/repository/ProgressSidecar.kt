package com.therealityteam.videoplayer.data.repository

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playback positions, kept apart from the library file.
 *
 * The library is one JSON document — 32 MB for 9.6k files — and every change rewrites all of it,
 * which is fine for a scrape's coalesced bursts and wrong for a position that should be saved
 * every few seconds while a film plays: on a kill (headset off, the process reclaimed) whatever
 * had not been written was gone, and 续看 had nothing to offer. Positions therefore go into a
 * small sidecar of their own, written within a second of a change, and are laid back over the
 * library on load wherever they are newer. Capped: only the most recent entries are kept.
 */
object ProgressSidecar {
    const val MAX_ENTRIES = 1_000

    data class Entry(val id: String, val positionMs: Long, val durationMs: Long, val watched: Boolean, val updatedAt: Long)

    fun encode(entries: Collection<Entry>): String {
        val arr = JSONArray()
        entries.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES).forEach { e ->
            arr.put(
                JSONObject().put("id", e.id).put("pos", e.positionMs).put("dur", e.durationMs)
                    .put("watched", e.watched).put("at", e.updatedAt),
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<Entry> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(id, o.optLong("pos"), o.optLong("dur"), o.optBoolean("watched"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    /** The items with every sidecar entry that is newer than what they carry laid over them. */
    fun merge(items: List<MediaItem>, entries: Collection<Entry>): List<MediaItem> {
        if (entries.isEmpty()) return items
        val byId = entries.associateBy { it.id }
        return items.map { item ->
            val e = byId[item.id] ?: return@map item
            if (e.updatedAt <= item.progress.updatedAt) return@map item
            item.copy(progress = PlaybackProgress(e.positionMs, e.durationMs, e.watched, e.updatedAt))
        }
    }
}
