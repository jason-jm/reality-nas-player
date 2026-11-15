package com.therealityteam.videoplayer.data.playback

/**
 * What a file's name says about its picture and sound — 4K, Dolby Vision, Atmos — for the badges
 * beside it. Release names carry this exactly (`Dune.2021.2160p.UHD.BluRay.REMUX.DV.HDR10.TrueHD.
 * Atmos.7.1`), and it is what tells two versions of one film apart at a glance. One tag of each kind,
 * the best the name claims: a Dolby Vision file that also carries HDR10 is shown as Dolby Vision, a
 * TrueHD track with Atmos as Atmos. Pure, so the reading is unit-tested.
 */
object QualityTags {

    enum class Tag(val kind: Kind) {
        UHD_8K(Kind.RESOLUTION), UHD_4K(Kind.RESOLUTION), FHD(Kind.RESOLUTION), HD(Kind.RESOLUTION),
        DOLBY_VISION(Kind.HDR), HDR10_PLUS(Kind.HDR), HDR10(Kind.HDR), HLG(Kind.HDR),
        ATMOS(Kind.AUDIO), DTS_X(Kind.AUDIO), TRUEHD(Kind.AUDIO), DTS_HD_MA(Kind.AUDIO),
        REMUX(Kind.SOURCE),
        THREE_D(Kind.STEREO),
        /** A whole sphere round the viewer (equirectangular, a cubemap, a 360 camera's pair). */
        VR360(Kind.PANORAMA),
        /** The front half (VR180, a fisheye). */
        VR180(Kind.PANORAMA),
    }

    enum class Kind { RESOLUTION, HDR, AUDIO, SOURCE, STEREO, PANORAMA }

    /**
     * The tags [fileName] claims, one per kind, best first within each kind, in reading order. Its
     * panorama tag follows [video] where that is more than the name's word (the viewer's 全景格式, or the
     * file's own metadata as it played).
     */
    fun of(fileName: String, video: com.therealityteam.videoplayer.data.model.VideoFlags? = null): List<Tag> {
        val name = fileName.substringBeforeLast('.').lowercase()
        val tokens = name.split(SEPARATORS).filter { it.isNotEmpty() }.toSet()
        val out = mutableListOf<Tag>()
        when {
            tokens.any { it == "4320p" || it == "8k" } -> Tag.UHD_8K
            tokens.any { it == "2160p" || it == "4k" || it == "uhd" || it.startsWith("uhdbd") || it == "3840x2160" } -> Tag.UHD_4K
            tokens.any { it == "1080p" || it == "1080i" || it == "fhd" || it == "1920x1080" } -> Tag.FHD
            tokens.any { it == "720p" || it == "1280x720" } -> Tag.HD
            else -> null
        }?.let(out::add)
        when {
            DOLBY_VISION.containsMatchIn(name) || "dovi" in tokens || "dv" in tokens -> Tag.DOLBY_VISION
            "hdr10+" in tokens || "hdr10plus" in tokens || HDR10_PLUS.containsMatchIn(name) -> Tag.HDR10_PLUS
            "hdr" in tokens || "hdr10" in tokens -> Tag.HDR10
            "hlg" in tokens -> Tag.HLG
            else -> null
        }?.let(out::add)
        when {
            "atmos" in tokens -> Tag.ATMOS
            DTS_X.containsMatchIn(name) -> Tag.DTS_X
            "truehd" in tokens -> Tag.TRUEHD
            DTS_HD_MA.containsMatchIn(name) -> Tag.DTS_HD_MA
            else -> null
        }?.let(out::add)
        if (tokens.any { it == "remux" || it == "bdremux" }) out += Tag.REMUX
        // 3D by the name — or by the file's own marking, or the viewer's word, for a flat film.
        val sure = video?.takeIf { it.userOverridden || it.fromFile }
        val flatStereo = sure != null && sure.projection == com.therealityteam.videoplayer.data.model.ProjectionMode.FLAT &&
            (sure.stereo == com.therealityteam.videoplayer.data.model.StereoMode.SBS || sure.stereo == com.therealityteam.videoplayer.data.model.StereoMode.OU)
        if (tokens.any { it in STEREO_3D } || flatStereo) out += Tag.THREE_D
        // A panorama by the name's own words (ProjectionDetector's: a bare 360 is no panorama).
        val known = sure?.projection
        when (known ?: ProjectionDetector.detect(fileName).projection) {
            com.therealityteam.videoplayer.data.model.ProjectionMode.EQ360, com.therealityteam.videoplayer.data.model.ProjectionMode.EAC,
            com.therealityteam.videoplayer.data.model.ProjectionMode.CUBEMAP, com.therealityteam.videoplayer.data.model.ProjectionMode.DUAL_FISHEYE -> out += Tag.VR360
            com.therealityteam.videoplayer.data.model.ProjectionMode.EQ180, com.therealityteam.videoplayer.data.model.ProjectionMode.FISHEYE -> out += Tag.VR180
            else -> {}
        }
        return out
    }

    private val SEPARATORS = Regex("[\\s._\\-\\[\\]()（）【】,]+")
    private val DOLBY_VISION = Regex("""dolby[\s._\-]?vision""")
    private val HDR10_PLUS = Regex("""hdr10[\s._\-]?(?:\+|plus)""")
    private val DTS_X = Regex("""(?<![a-z])dts[\s._\-:]?x(?![a-z0-9])""")
    private val DTS_HD_MA = Regex("""(?<![a-z])dts[\s._\-]?hd[\s._\-]?ma(?![a-z])""")
    // Only the marks no title uses: not "3D" ("3D BG Concept Demo" is a CG demo), nor "tab" or "hou"
    // (a word; a surname — Hou Hsiao-hsien).
    private val STEREO_3D = setOf("sbs", "hsbs", "htab")
}
