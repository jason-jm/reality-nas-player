package com.therealityteam.videoplayer.data.playback

/**
 * 整季沿用音轨与字幕: the sound or subtitles picked in one episode, described so the same choice can be
 * found again in the next — whose stream ids, sidecar file names and even track order may differ.
 * [TrackMemory] keeps the exact pick per file; this carries it across a show's files, so 简体 picked
 * over 繁體 in episode 1 (both tagged Chinese) is 简体 in episode 2 as well. Pure, so the matching is
 * unit-tested.
 */
object TrackPicks {

    /** A picked track as a description rather than an id. */
    data class Pick(
        val language: String? = null,
        /** The track's name as the engine gave it: a titled stream's title, else its language's name. */
        val name: String = "",
        /** A same-folder subtitle file rather than a stream inside the video. */
        val external: Boolean = false,
        /** A subtitle file's name past its episode's: ".chs.ass" of "Show - 01.chs.ass". */
        val tail: String? = null,
        /** Channel count for sound (0 when unknown, and for text). */
        val channels: Int = 0,
    )

    fun of(track: MediaTrack, videoFileName: String): Pick = Pick(
        language = track.language?.takeIf { it.isNotBlank() && it != "und" },
        name = track.name,
        external = track.external,
        tail = if (track.external) tailOf(track.name, videoFileName) else null,
        channels = track.channels,
    )

    /**
     * The track in [available] that is [pick] again, or null to leave the engine's own choice: a
     * subtitle file with the same tail; a stream with the same name (and, for sound, the same
     * channels); else the same language — the same kind of source first, then the same channels.
     */
    fun find(pick: Pick, available: List<MediaTrack>, videoFileName: String): String? {
        if (available.isEmpty()) return null
        if (pick.external) {
            pick.tail?.let { tail ->
                available.firstOrNull { it.external && tailOf(it.name, videoFileName).equals(tail, ignoreCase = true) }
                    ?.let { return it.id }
            }
        } else if (pick.name.isNotBlank()) {
            val named = available.filter { !it.external && it.name == pick.name && (pick.language == null || sameLanguage(it, pick)) }
            (named.firstOrNull { it.channels == pick.channels } ?: named.firstOrNull())?.let { return it.id }
        }
        if (pick.language == null) return null
        return available.filter { sameLanguage(it, pick) }
            .sortedWith(
                compareByDescending<MediaTrack> { it.external == pick.external }
                    .thenByDescending { it.channels == pick.channels },
            )
            .firstOrNull()?.id
    }

    /**
     * A subtitle file's name past its episode's own ("Show - 01.mkv" + "Show - 01.chs.ass" → ".chs.ass"),
     * or, named some other way, its last two dot-parts (".chs.ass") — the part every episode shares.
     */
    fun tailOf(subtitleFileName: String, videoFileName: String): String {
        val stem = videoFileName.substringBeforeLast('.', videoFileName)
        if (stem.isNotEmpty() && subtitleFileName.length > stem.length && subtitleFileName.startsWith(stem, ignoreCase = true)) {
            return subtitleFileName.substring(stem.length)
        }
        val parts = subtitleFileName.split('.')
        return if (parts.size >= 3) "." + parts.takeLast(2).joinToString(".") else "." + parts.last()
    }

    private fun sameLanguage(track: MediaTrack, pick: Pick): Boolean =
        pick.language != null && track.language.equals(pick.language, ignoreCase = true)
}
