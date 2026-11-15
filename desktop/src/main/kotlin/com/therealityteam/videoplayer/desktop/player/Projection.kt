package com.therealityteam.videoplayer.desktop.player

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.model.VideoFlags
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.Panorama
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The picture formats the headset's player offers (PlaybackControls.Format), with its rules for
 * moving between them — a 3D film chosen as 180° keeps its packing, as 360° it becomes one picture.
 */
enum class PictureFormat(val labelKey: String) {
    FLAT_2D("pc_stereo_flat"),
    SBS("pc_stereo_sbs"),
    OU("pc_stereo_tb"),
    EQ180("pc_proj_180"),
    EQ360("pc_proj_360");

    /** The flags this format stands for, from the ones a file has now; marked as the user's choice. */
    fun flags(current: VideoFlags): VideoFlags = when (this) {
        FLAT_2D -> VideoFlags(StereoMode.MONO, ProjectionMode.FLAT)
        SBS -> VideoFlags(StereoMode.SBS, ProjectionMode.FLAT)
        OU -> VideoFlags(StereoMode.OU, ProjectionMode.FLAT)
        EQ180 -> VideoFlags(current.stereo, ProjectionMode.EQ180)
        EQ360 -> VideoFlags(StereoMode.MONO, ProjectionMode.EQ360)
    }.copy(userOverridden = true)

    companion object {
        fun of(f: VideoFlags): PictureFormat = when (f.projection) {
            ProjectionMode.EQ180, ProjectionMode.FISHEYE -> EQ180
            ProjectionMode.EQ360, ProjectionMode.EAC, ProjectionMode.CUBEMAP, ProjectionMode.DUAL_FISHEYE -> EQ360
            else -> when {
                f.stereo == StereoMode.SBS -> SBS
                f.stereo == StereoMode.OU -> OU
                else -> FLAT_2D
            }
        }
    }
}

/**
 * One choice of 全景格式 (the headset's): a projection, and for a fisheye its lens's angle — the ways a
 * picture of the 180° or the 360° kind is laid in its frame.
 */
data class PanoChoice(val projection: ProjectionMode, val lens: Int = 0) {
    /** The flags this choice stands for, from the ones a file has now: its eyes kept; the user's choice. */
    fun flags(current: VideoFlags): VideoFlags =
        current.copy(projection = projection, lensFov = lens, userOverridden = true)

    /** Whether the playing picture is laid out so ([layout]): a fisheye by its lens too, a file's own mesh as none of them. */
    fun matches(layout: PanoLayout): Boolean =
        layout.ownMesh == null && layout.projection == projection && (lens == 0 || layout.lensFov.toInt() == lens)

    companion object {
        /**
         * The ways a panorama of [p]'s kind lies in its frame, as the headset offers them: a 180° is a
         * half sphere or a fisheye's circle; a 360° a whole sphere, YouTube's EAC, a cubemap, or a 360
         * camera's two circles.
         */
        fun kinds(p: ProjectionMode): List<ProjectionMode> = when (p) {
            ProjectionMode.EQ180, ProjectionMode.FISHEYE -> listOf(ProjectionMode.EQ180, ProjectionMode.FISHEYE)
            else -> listOf(ProjectionMode.EQ360, ProjectionMode.EAC, ProjectionMode.CUBEMAP, ProjectionMode.DUAL_FISHEYE)
        }

        /** A fisheye's lenses, degrees across, as the headset offers them (Canon's RF5.2 190°, mkx200, mkx220). */
        val LENSES = listOf(180, 190, 200, 220)

        /** Every choice for [p]'s kind, a fisheye once per lens — the headset's tiles, the menu bar's items. */
        fun of(p: ProjectionMode): List<PanoChoice> = kinds(p).flatMap { k ->
            if (k == ProjectionMode.FISHEYE) LENSES.map { PanoChoice(k, it) } else listOf(PanoChoice(k))
        }

        /** The headset's word for a kind: 等距柱状, 鱼眼, EAC, 立方体, 双鱼眼. */
        fun labelKey(p: ProjectionMode): String = when (p) {
            ProjectionMode.FISHEYE -> "pc_pano_fisheye"
            ProjectionMode.EAC -> "pc_pano_eac"
            ProjectionMode.CUBEMAP -> "pc_pano_cube"
            ProjectionMode.DUAL_FISHEYE -> "pc_pano_dual"
            else -> "pc_pano_equirect"
        }
    }
}

/** Where a viewer of a spherical film looks: yaw and pitch, and the view's width, in radians. */
data class SphereView(val yaw: Double = 0.0, val pitch: Double = 0.0, val fov: Double = Projection.DEFAULT_FOV)

/**
 * A 3D or spherical file on a flat screen, as mpv is told to show it.
 *
 * - A flat 3D film shows one eye: mpv crops the frame to it (video-crop) and, for the half-width
 *   and half-height packings, gives the frame its true shape so that eye fills 16:9 again.
 * - A spherical film is seen through a window: a GPU shader (resources/shaders/aura-360.glsl)
 *   draws the part of the sphere in front of the viewer in perspective. mpv's libmpv renderer
 *   takes no shader parameters, so the view is written into a copy of the shader when it moves.
 */
object Projection {
    val DEFAULT_FOV = Math.toRadians(100.0)
    val MIN_FOV = Math.toRadians(35.0)
    val MAX_FOV = Math.toRadians(120.0)

    private val template: String by lazy {
        Projection::class.java.getResource("/shaders/aura-360.glsl")!!.readText()
    }

    /**
     * The spherical shader for [layout] — fixed while a file plays — in a [frameW] × [frameH] frame (0
     * while not known: a cubemap is then 3×2, EAC's border left out), its picture turned by the file's
     * own [pose] (yaw, pitch, roll, degrees) as the headset turns its sphere. A file's own mesh is drawn
     * as YouTube's EAC, the mesh projections met — a shader carries no mesh (the headset's poster does
     * the same).
     */
    fun sphereShader(layout: PanoLayout, frameW: Int = 0, frameH: Int = 0, pose: FloatArray = NO_POSE): String {
        val l = layout.meshless(frameW, frameH)
        // One eye's picture, in pixels.
        val eyeW = l.frameWidth
        val eyeH = l.frameHeight
        val mode = when (l.projection) {
            ProjectionMode.FISHEYE -> 1
            ProjectionMode.DUAL_FISHEYE -> 2
            ProjectionMode.CUBEMAP -> 3
            ProjectionMode.EAC -> 4
            else -> 0
        }
        val (cols, rows) = Panorama.cubeGrid(l)
        fun f(d: Double) = String.format(Locale.ROOT, "%.6f", d)
        fun rad(deg: Float) = Math.toRadians(deg.toDouble())
        return template
            .replace("{{MODE}}", "$mode.0")
            .replace("{{STEREO}}", when (l.stereo) { StereoMode.SBS -> "1.0"; StereoMode.OU -> "2.0"; else -> "0.0" })
            .replace("{{SWAP}}", if (l.eyesSwapped && (l.stereo == StereoMode.SBS || l.stereo == StereoMode.OU)) "1.0" else "0.0")
            .replace("{{BOUNDS}}", listOf(rad(l.yawFrom), rad(l.yawTo), rad(l.pitchFrom), rad(l.pitchTo)).joinToString(", ") { f(it) })
            .replace("{{LENS}}", f(rad(l.lensFov / 2)))
            .replace("{{GRID}}", "$cols.0, $rows.0")
            // EAC's border, a part of one eye's picture; a cubemap's padding, a part of one face.
            .replace("{{PAD}}", when {
                mode == 4 -> f(if (eyeW > 0) EAC_BORDER / eyeW else 0.0) + ", " + f(if (eyeH > 0) EAC_BORDER / eyeH else 0.0)
                mode == 3 && l.cubePadding > 0 && eyeW > 0 && eyeH > 0 -> f(l.cubePadding * cols.toDouble() / eyeW) + ", " + f(l.cubePadding * rows.toDouble() / eyeH)
                else -> "0.0, 0.0"
            })
            .replace("{{POSE}}", poseMatrix(pose).joinToString(", ") { f(it) })
    }

    /**
     * The headset's turn of a sphere by a file's pose (yaw about up, then pitch about right, then roll
     * about ahead, each counter-clockwise: PlaybackViewModel.orientSphere), row by row — which, as GLSL
     * reads a mat3 column by column, is the turn undone.
     */
    fun poseMatrix(pose: FloatArray): DoubleArray {
        val (y, p, r) = pose.map { Math.toRadians(it.toDouble()) }
        val ry = arrayOf(doubleArrayOf(cos(y), 0.0, sin(y)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(y), 0.0, cos(y)))
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        // About ahead (−z) by r: about z by −r.
        val rz = arrayOf(doubleArrayOf(cos(r), sin(r), 0.0), doubleArrayOf(-sin(r), cos(r), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> a[i][k] * b[k][j] } } }
        val m = mul(mul(ry, rx), rz)
        return DoubleArray(9) { m[it / 3][it % 3] }
    }

    /**
     * What the file mpv opens at [uri] says of itself as a panorama — its header: the file on this computer,
     * or a few blocks over http (a share through the proxy, a server's stream). Null when it cannot be read.
     */
    fun spherical(uri: String): com.therealityteam.videoplayer.data.playback.SphericalInfo? = runCatching {
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            com.therealityteam.videoplayer.data.playback.SphericalMetadata.read(com.therealityteam.videoplayer.data.playback.HttpByteSource(uri))
        } else {
            java.io.File(uri.removePrefix("file://")).takeIf { it.isFile }
                ?.let { com.therealityteam.videoplayer.data.playback.SphericalMetadata.read(com.therealityteam.videoplayer.data.playback.FileByteSource(it)) }
        }
    }.getOrNull()

    /** A file with no pose of its own. */
    val NO_POSE = floatArrayOf(0f, 0f, 0f)

    /** EAC's border round its faces, pixels (core Panorama's). */
    private const val EAC_BORDER = 2.0

    /**
     * A view, as the file the spherical shader reads it from: yaw, pitch, fov and aspect squeezed
     * into 0..1, since the texture that carries them may hold nothing else. The name is the view
     * rounded to a quarter of a degree: mpv keeps every shader file it has read for as long as it
     * lives, so a view looked at twice costs nothing the second time, and a new one ~300 bytes.
     */
    fun viewShader(view: SphereView, aspect: Double): Pair<String, String> {
        val q = Math.toRadians(0.25)
        val yaw = Math.round(wrap(view.yaw) / q) * q
        val pitch = Math.round(view.pitch / q) * q
        val fov = Math.round(view.fov / (2 * q)) * (2 * q)
        val a = Math.round(aspect * 1000) / 1000.0
        fun f(d: Double) = String.format(Locale.ROOT, "%.6f", d)
        val name = "view_${Math.round(yaw / q)}_${Math.round(pitch / q)}_${Math.round(fov / q)}_${Math.round(a * 1000)}.glsl"
        val text = """
            //!HOOK MAIN
            //!SAVE AURAVIEW
            //!WIDTH 2
            //!HEIGHT 1
            //!COMPONENTS 4
            //!DESC Aura view
            vec4 hook() {
                return vec4(${f((yaw / PI + 1) / 2)}, ${f((pitch / (PI / 2) + 1) / 2)}, ${f(fov / PI)}, ${f(a / 8)});
            }
        """.trimIndent() + "\n"
        return name to text
    }

    /** One eye of a flat 3D frame: what to crop, and the frame's true shape if its packing squeezed it. */
    data class Eye(val crop: String, val aspect: Double?)

    /**
     * The left eye of a [w]×[h] frame packed as [stereo] — the left (or top) half, the other when [rightFirst] —
     * or null for a 2D one. A frame
     * wider than 2.5:1 holds two full-width pictures (3840×1080); one near 16:9 holds two squeezed
     * ones (1920×1080 half-SBS), whose true shape is twice as wide. Over-under the same, by height.
     */
    fun eye(stereo: StereoMode, w: Int, h: Int, rightFirst: Boolean = false): Eye? {
        if (w <= 0 || h <= 0) return null
        val ratio = w.toDouble() / h
        // The left eye's picture is the second half when the right eye's comes first.
        return when (stereo) {
            StereoMode.SBS -> Eye("${w / 2}x$h+${if (rightFirst) w / 2 else 0}+0", if (ratio > 2.5) null else ratio * 2)
            StereoMode.OU -> Eye("${w}x${h / 2}+0+${if (rightFirst) h / 2 else 0}", if (ratio < 1.25) null else ratio / 2)
            else -> null
        }
    }

    /** A yaw in (-π, π]. */
    fun wrap(yaw: Double): Double {
        var y = yaw % (2 * PI)
        if (y > PI) y -= 2 * PI
        if (y <= -PI) y += 2 * PI
        return y
    }
}
