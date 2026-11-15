package com.therealityteam.videoplayer.data.playback

/**
 * Which engine a file plays in. Media3 plays what it can read — nearly everything, with the hardware
 * decoders, the app's own subtitles and all its features — and mpv the rest (docs/legacy-formats.md):
 * a container Media3 has no reader for opens in mpv from the start; a file Media3 turns out unable to
 * play is handed over to it. Pure, so the choices are tested.
 */
object EngineRoute {

    /**
     * Files to open in mpv from the start, by the extension the path ends with (a share's stream URL
     * ends with the file's name): RealMedia, Windows Media, FLV (whose old codecs crash Media3's
     * reader), DivX's own container, Ogg Media — and MPEG program streams: Media3 took an MPEG-1
     * .mpg for an MP3 (sound over black, no error), and reads a DVD's VOB without its LPCM, DTS or
     * subtitles — and a Blu-ray's own streams (.m2ts), whose 192-byte packets Media3's TS reader
     * does not take (nor TrueHD or DTS-HD in them).
     */
    fun mpvFirst(path: String): Boolean = extension(path) in MPV_FIRST

    /**
     * Whether a file Media3 failed on may yet play in mpv: a format Media3 lacks, or a file its
     * readers balk at (FFmpeg's are more forgiving of a broken index). Not a share out of reach, nor
     * a missing file — no engine reads those.
     */
    fun handsOver(failure: PlaybackFailure?): Boolean =
        failure == PlaybackFailure.UNSUPPORTED || failure == PlaybackFailure.DAMAGED

    /**
     * Whether a picture Media3 cannot draw, while the sound plays, is an old codec mpv decodes: an
     * MKV's MPEG-2, a VC-1, an MPEG-4 Part 2 — or none at all (null) where a video file should have
     * one ([expectsPicture]) — or an H.264 profile only software decodes ([softwareOnly], from the
     * track's [codecs]). A modern one it cannot draw (a 4K HEVC the decoder refuses) stays where it
     * is — software decoding would not keep up.
     */
    fun handsOverPicture(videoMime: String?, codecs: String? = null): Boolean =
        videoMime == null || videoMime == UNKNOWN_VIDEO || PlaybackFailure.legacyCodec(videoMime) != null ||
            softwareOnly(codecs)

    /**
     * Whether [codecs] — a track's RFC 6381 codecs, as Media3 reads them from the file (`avc1.6E0032`)
     * — is an H.264 profile no hardware decoder takes: High 10 (anime's "Hi10P", 8 % of a real home
     * library), High 4:2:2, High 4:4:4 and CAVLC 4:4:4 Intra. A headset's decoder says it cannot and,
     * made to try, gives nothing or green; FFmpeg decodes it in software — a 1080p one with ease.
     */
    fun softwareOnly(codecs: String?): Boolean {
        val c = codecs?.trim()?.lowercase() ?: return false
        if (!c.startsWith("avc1.") && !c.startsWith("avc3.")) return false
        val profile = c.substring(5).take(2).takeIf { it.length == 2 }?.toIntOrNull(16) ?: return false
        return profile in SOFTWARE_ONLY_AVC
    }

    /**
     * Whether a file's name says it is H.264 in 10 bits — "[Hi10p_1080p]", "x264(10bit)", "AVC_10bit",
     * "x264 Hi10P" — so it can open in mpv at once rather than after Media3 has read its tracks and
     * found no decoder ([softwareOnly]); a second of the start, every Hi10P episode. HEVC's "Ma10p",
     * "x265 10bit" stay Media3's: the hardware decodes them.
     */
    fun namedSoftwareOnly(path: String): Boolean {
        val name = path.substringBefore('?').substringAfterLast('/').lowercase()
        if (HEVC_NAMED.containsMatchIn(name)) return false
        return HI10P_NAMED.containsMatchIn(name)
    }

    /**
     * Whether a file's name says its picture is XviD or DivX (MPEG-4 Part 2): no decoder on the headset
     * takes those, so Media3 opens the file, reads it and fails, and only then does mpv start it — a
     * second more before every episode of an old release. "DivX Plus" is H.264, and stays Media3's.
     */
    fun namedLegacy(path: String): Boolean {
        val name = path.substringBefore('?').substringAfterLast('/').lowercase()
        return LEGACY_NAMED.containsMatchIn(name)
    }

    private val LEGACY_NAMED = Regex("""(?<![a-z])(?:xvid|divx(?![\s._\-]*plus))(?![a-z])""")

    private val HI10P_NAMED = Regex("""hi10p?(?![a-z0-9])|(?:x264|avc|h\.?264)[\s_.\-\[\]()]*(?:hi)?10[\s_\-]?bit|10[\s_\-]?bit[\s_.\-\[\]()]*(?:x264|avc|h\.?264)""")
    private val HEVC_NAMED = Regex("""x265|hevc|h\.?265|ma10p""")

    /** H.264's profile_idc for High 10 (110), High 4:2:2 (122), High 4:4:4 Predictive (244), CAVLC 4:4:4 Intra (44). */
    private val SOFTWARE_ONLY_AVC = setOf(110, 122, 244, 44)

    /**
     * Whether FFmpeg in software (mpv's, on the headset's CPU) keeps up with a picture [width]×[height]
     * at [fps] frames a second (30 when unknown): up to 4K's pixels at 30 frames. Past it — a 5.7K or
     * 8K panorama, 4K at 60 — software is no stand-in for a hardware decoder that failed: a frame every
     * few seconds, the sound running ahead. Then the viewer is told (PlaybackFailure.TOO_LARGE). An
     * unknown size keeps up, as before. The line is an estimate from FFmpeg's HEVC on phone-class CPUs,
     * to be measured on a headset.
     */
    fun softwareKeepsUp(width: Int, height: Int, fps: Float): Boolean {
        if (width <= 0 || height <= 0) return true
        val rate = if (fps > 0f) fps else 30f
        return width.toDouble() * height * rate <= SOFTWARE_PIXELS_PER_SECOND
    }

    /** 4K's pixels (DCI, 4096×2160) at 30 frames a second. */
    private const val SOFTWARE_PIXELS_PER_SECOND = 4096.0 * 2160 * 30

    /**
     * Whether a file is a video by its name — so that Media3 reading only sound from it means its
     * reader passed over a picture it does not know (a QuickTime's Sorenson or Cinepak, an MKV's
     * RealVideo) rather than that there is none.
     */
    fun expectsPicture(path: String?): Boolean = path != null && extension(path) in VIDEO_CONTAINERS

    /**
     * What Media3's readers call a video codec they do not know — RealVideo, or an XviD by its
     * FourCC, in an MKV: no Media3 decoder takes it, so it goes to mpv as soon as the tracks are read.
     */
    const val UNKNOWN_VIDEO = "video/x-unknown"

    private fun extension(path: String): String =
        path.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()

    private val MPV_FIRST = setOf("rmvb", "rm", "wmv", "asf", "flv", "divx", "ogm", "mpg", "mpeg", "vob", "m2ts", "mts")

    private val VIDEO_CONTAINERS = setOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "webm", "3gp")
}
