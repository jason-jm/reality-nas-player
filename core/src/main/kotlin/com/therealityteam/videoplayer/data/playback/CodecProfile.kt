package com.therealityteam.videoplayer.data.playback

/**
 * A video track's profile by the name people know it, from its RFC 6381 codecs (`avc1.6E0032`,
 * `hvc1.2.4.L150.B0`) — "High 10", "Main 10" — and the bit depth it implies, for the info panel:
 * what makes a picture what it is beyond its codec (Hi10P is decoded in software for it). Pure.
 */
object CodecProfile {

    /** The profile's name, or null for a codec or profile not known here. */
    fun name(codecs: String?): String? {
        val c = codecs?.trim()?.lowercase() ?: return null
        return when {
            c.startsWith("avc1.") || c.startsWith("avc3.") ->
                c.substring(5).take(2).takeIf { it.length == 2 }?.toIntOrNull(16)?.let { AVC[it] }
            c.startsWith("hvc1.") || c.startsWith("hev1.") ->
                c.substring(5).substringBefore('.').trimStart('a', 'b', 'c').toIntOrNull()?.let { HEVC[it] }
            else -> null
        }
    }

    /** "10-bit" where the profile is a 10-bit one, else null (8-bit says nothing). */
    fun depth(codecs: String?): String? = when (name(codecs)) {
        "High 10", "Main 10" -> "10-bit"
        else -> null
    }

    private val AVC = mapOf(
        66 to "Baseline", 77 to "Main", 88 to "Extended", 100 to "High", 110 to "High 10",
        122 to "High 4:2:2", 244 to "High 4:4:4",
    )
    private val HEVC = mapOf(1 to "Main", 2 to "Main 10", 3 to "Main Still", 4 to "RExt")
}
