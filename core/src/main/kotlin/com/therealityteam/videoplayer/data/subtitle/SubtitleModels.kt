package com.therealityteam.videoplayer.data.subtitle

/** A single timed subtitle line. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** A parsed subtitle track (cues sorted by start time). */
class SubtitleTrack(val cues: List<SubtitleCue>, val label: String = "") {
    /** The cue active at [positionMs], or null. */
    fun cueAt(positionMs: Long): SubtitleCue? {
        // Binary search on sorted cues.
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                positionMs < c.startMs -> hi = mid - 1
                positionMs > c.endMs -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    val isEmpty: Boolean get() = cues.isEmpty()
}
