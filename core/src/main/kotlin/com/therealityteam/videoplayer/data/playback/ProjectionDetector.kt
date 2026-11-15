package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.model.VideoFlags

/**
 * A video's stereo layout (2D / SBS / OU) and projection from the words in its file name — the
 * conventions 3D rips and VR sites (DeoVR, SLR, YouTube downloads) name files by: `_180_sbs`,
 * `_360_tb`, `_mkx200`, `_fisheye190`, `_eac`, `.3D.HSBS`. A word counts only whole, between the
 * name's separators (`_`, `.`, `-`, spaces, brackets), so `x264` is no 264 and `test_360` is a 360.
 * What the file itself says (SphericalMetadata) comes first when it is read; the viewer can always
 * correct either (userOverridden).
 *
 * A frame's shape decides nothing: a 2:1 film (3840×1920 — many series are framed so) is not a
 * sphere, and it would open in 沉浸空间.
 */
object ProjectionDetector {

    fun detect(fileName: String, width: Int? = null, height: Int? = null): VideoFlags {
        val n = fileName.lowercase().substringBeforeLast('.')
        val words = n.split(SEPARATORS).filter { it.isNotEmpty() }.toSet()
        val joined = " " + words.joinToString(" ") + " "
        fun has(vararg w: String) = w.any { it in words }
        fun phrase(vararg p: String) = p.any { joined.contains(" $it ") }

        val stereo = when {
            has("sbs", "hsbs", "fsbs", "lr", "rl", "rl3d", "3drl", "3dh", "sbs3d", "3dsbs", "halfsbs", "fullsbs", "3dlr", "lr3d") || phrase("side by side", "half sbs", "full sbs") -> StereoMode.SBS
            has("tb", "ou", "tab", "htab", "hou", "fou", "3dv", "overunder", "topbottom", "3dtb", "tb3d", "3dou", "ou3d") || phrase("over under", "top bottom", "top and bottom") -> StereoMode.OU
            else -> StereoMode.MONO
        }

        // A fisheye lens names its angle: DeoVR/SLR's mkx200 and mkx220, Canon's RF5.2 (rf52), vrca220, and
        // HereSphere's / DeoVR's fisheye followed by its degrees (fisheye180, fish200).
        val lens = when {
            has("mkx220", "mkx22", "vrca220") -> 220
            has("mkx200") -> 200
            has("rf52") -> 190
            else -> words.firstNotNullOfOrNull { LENS_ANGLE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it in 120..280 } ?: 0
        }
        // A bare 180 or 360 is a number as often as not — an episode, a film called "360" — and counts
        // only beside a word that says VR: a stereo layout, "vr", "3d", "mono", a VR frame size.
        // (MV-HEVC — "mvhevc", "spatial video" — codes its two views apart: one picture a frame, not a packing,
        // and a VR word all the same.)
        val vrContext = stereo != StereoMode.MONO || has("vr", "3d", "mono", "stereo", "mvhevc", "5k", "6k", "7k", "8k", "12k") ||
            phrase("mv hevc", "spatial video") ||
            words.any { it in VR_SIZES } || (has("bt") && has("180", "360"))
        val projection = when {
            has("dfisheye", "dualfisheye") || phrase("dual fisheye") -> ProjectionMode.DUAL_FISHEYE
            lens > 0 || has("fisheye") || phrase("fish eye") -> ProjectionMode.FISHEYE
            has("eac", "eac360", "yteac") -> ProjectionMode.EAC
            has("c3x2", "cubemap", "cube3x2", "c6x1", "c1x6", "cube6x1", "cube1x6") || phrase("cube map") -> ProjectionMode.CUBEMAP
            has("vr180", "180vr", "180x180", "180°", "3d180", "180sbs", "180tb", "180lr", "hequirect", "halfequirect") ||
                (has("180") && vrContext) -> ProjectionMode.EQ180
            has("vr360", "360vr", "360x180", "360°", "3d360", "360sbs", "360tb", "360lr", "equirect", "equirectangular", "erp", "eqr", "spherical") ||
                (has("360") && vrContext) -> ProjectionMode.EQ360
            else -> ProjectionMode.FLAT
        }

        // The right eye first: `_RL` (beside the layout words above), and `_BT` — a word too common alone
        // (a release group's [BT]) to count unless the name already says it is a panorama.
        var eyes = stereo
        var rightFirst = has("rl", "rl3d", "3drl")
        if (eyes == StereoMode.MONO && has("bt") && projection != ProjectionMode.FLAT) {
            eyes = StereoMode.OU
            rightFirst = true
        }
        return VideoFlags(
            stereo = eyes, projection = projection, userOverridden = false,
            lensFov = if (projection == ProjectionMode.FISHEYE) lens else 0,
            rightFirst = rightFirst && eyes != StereoMode.MONO,
        )
    }

    /** `fisheye190`, `fish200`: a fisheye and its angle in one word. */
    private val LENS_ANGLE = Regex("fish(?:eye)?(\\d{3})")

    /** Frame sizes VR video comes in (a whole sphere 2:1, a VR180 pair 2:1 side by side). */
    private val VR_SIZES = setOf("5760x2880", "3840x1920", "4096x2048", "7680x3840", "8192x4096", "5120x2560", "6144x3072", "2880x1440", "5400x2700")

    /** What divides a name's words. A "°" stays with its number. */
    private val SEPARATORS = Regex("[\\s_.\\-\\[\\]()（）【】{}+,，;:]+")
}
