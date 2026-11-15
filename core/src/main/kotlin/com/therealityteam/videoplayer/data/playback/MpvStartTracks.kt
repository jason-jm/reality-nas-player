package com.therealityteam.videoplayer.data.playback

/**
 * mpv opens a file on the sound and subtitles it was last left on (loadfile's `aid=`/`sid=`), so the
 * player's own choice, made once the tracks are read, finds them already chosen. Switched after the
 * film has started, a track makes mpv go back and read again for it — a resume in a Blu-ray rip
 * whose indexed keyframes are a minute apart read 130 MB a second time (24 s on the emulator).
 * Pure, so it is tested.
 */
object MpvStartTracks {

    /** What a file is known by from one run to the next: a share's loopback URL names a port that changes. */
    fun key(path: String): String = LOOPBACK.replace(path, "loopback/")

    /** The loadfile options for the tracks remembered, "no" (subtitles off) included. */
    fun options(aid: String?, sid: String?): List<String> =
        listOfNotNull(aid?.takeIf { it.isNotBlank() }?.let { "aid=$it" }, sid?.takeIf { it.isNotBlank() }?.let { "sid=$it" })

    /** "aid|sid|when", either id empty when not set. */
    fun encode(aid: String?, sid: String?, at: Long): String = "${aid.orEmpty()}|${sid.orEmpty()}|$at"

    data class Picked(val aid: String?, val sid: String?, val at: Long)

    fun decode(value: String?): Picked? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 3) return null
        return Picked(parts[0].ifEmpty { null }, parts[1].ifEmpty { null }, parts[2].toLongOrNull() ?: 0L)
    }

    private val LOOPBACK = Regex("""^http://127\.0\.0\.1:\d+/""")
}
