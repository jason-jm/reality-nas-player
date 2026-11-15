package com.therealityteam.videoplayer.data.playback

/**
 * Remembers the audio / subtitle track a file was last watched with, so the next open re-selects
 * it (Movist does this; not re-picking 中文字幕 every episode is the whole point). Ids are the
 * engine's "group:track" keys, stable for the same file. Pure, so the decision is unit-tested.
 */
object TrackMemory {
    /** Saved subtitle value meaning "the user turned subtitles off". */
    const val SUBTITLES_OFF = "off"

    /** The saved audio id to re-select, or null when nothing is saved or it no longer exists. */
    fun audioToApply(saved: String?, available: List<MediaTrack>): String? =
        saved?.takeIf { s -> available.any { it.id == s } }

    sealed class Subtitle {
        object Off : Subtitle()
        data class Select(val id: String) : Subtitle()
    }

    /**
     * What to do about subtitles once [available] is known: turn them off, select a track, or
     * null to leave the engine's default. "Off" waits until there are tracks to turn off, so an
     * early empty list doesn't consume the memory before the file's tracks arrive.
     */
    fun subtitleToApply(saved: String?, available: List<MediaTrack>): Subtitle? = when {
        saved == null -> null
        saved == SUBTITLES_OFF -> if (available.isNotEmpty()) Subtitle.Off else null
        available.any { it.id == saved } -> Subtitle.Select(saved)
        else -> null
    }
}
