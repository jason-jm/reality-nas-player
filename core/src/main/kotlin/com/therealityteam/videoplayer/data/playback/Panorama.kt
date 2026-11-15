package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.model.VideoFlags
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * How a 360°/180° picture lies in its frame: the projection, the eyes, and for an equirectangular
 * one the part of the sphere it covers (a VR180's front half; the `equi` box's bounds), for a fisheye
 * its lens's field of view. One eye's picture is described — VideoMaterial gives each eye its half of
 * a side-by-side or top-and-bottom frame.
 *
 * Directions are the viewer's: x to the right, y up, z behind (forward is −z). The projections are
 * FFmpeg's (libavfilter/vf_v360.c) — the layouts YouTube, Google's spherical metadata and the cameras'
 * own tools write — ported so a picture FFmpeg converts lands where FFmpeg put it (PanoramaTest checks
 * against its output).
 */
data class PanoLayout(
    val projection: ProjectionMode,
    val stereo: StereoMode = StereoMode.MONO,
    /** Equirectangular coverage, degrees: yaw left→right, pitch bottom→top. */
    val yawFrom: Float = -180f,
    val yawTo: Float = 180f,
    val pitchFrom: Float = -90f,
    val pitchTo: Float = 90f,
    /** A fisheye lens's field of view, degrees — each lens's, for a dual fisheye. */
    val lensFov: Float = 180f,
    /** One eye's picture in pixels, when known ([sized]): EAC keeps a 2-pixel border round its faces, a cubemap's grid follows its shape. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** The frame's width over its height when its pixels are not known (0 unknown): how a cubemap's faces are laid out. */
    val frameAspect: Float = 0f,
    /**
     * A cubemap's padding (Spherical V2 `cbmp`, Matroska's ProjectionPrivate): the pixels round each face's
     * edge that are not the face — the face itself the rest, inside them. Needs [frameWidth] to count in.
     */
    val cubePadding: Int = 0,
    /** The picture's own mesh (a mesh projection: one eye's, or the only one), shown as written. */
    val ownMesh: PanoMesh? = null,
    /**
     * A stereo frame with the right eye's picture first (left half, or top) — Matroska StereoMode 2/11,
     * Apple's reversed views. Shown flat, its left eye's picture to both eyes ([Panorama.leftEyeOnly]).
     */
    val eyesSwapped: Boolean = false,
) {
    /** Whether this is a picture to be inside of, not a flat one. */
    val surrounds: Boolean get() = projection in SPHERICAL

    /** How the material splits the frame between the eyes: as the frame is, or one picture for both. */
    val shownStereo: StereoMode get() = if (eyesSwapped) StereoMode.MONO else stereo

    /**
     * This layout drawn without a mesh — a shader's view, a still's — from a [frameWidth] × [frameHeight]
     * frame (0 when not known): a file's own mesh as YouTube's EAC, the mesh projections met; one eye's
     * pixels for a cubemap's faces and EAC's border.
     */
    fun meshless(frameWidth: Int, frameHeight: Int): PanoLayout =
        (if (ownMesh != null) of(ProjectionMode.EAC, stereo).copy(eyesSwapped = eyesSwapped) else this).sized(frameWidth, frameHeight)

    /**
     * This layout in a [frameWidth] × [frameHeight] frame: one eye's pixels, which [frameWidth] and [frameHeight]
     * hold. A size not known (0) leaves the one it has — the header's, say, while the player has not said.
     */
    fun sized(frameWidth: Int, frameHeight: Int): PanoLayout {
        if (frameWidth <= 0 || frameHeight <= 0) return this
        val eyeW = if (stereo == StereoMode.SBS) frameWidth / 2 else frameWidth
        val eyeH = if (stereo == StereoMode.OU) frameHeight / 2 else frameHeight
        // A cubemap's padding is counted in the pixels it was given in: the same part of a face at another size.
        val padding = if (cubePadding > 0 && this.frameWidth > 0 && eyeW != this.frameWidth) {
            Math.round(cubePadding.toDouble() * eyeW / this.frameWidth).toInt()
        } else cubePadding
        return copy(frameWidth = eyeW, frameHeight = eyeH, cubePadding = padding)
    }

    companion object {
        val SPHERICAL = setOf(
            ProjectionMode.EQ360, ProjectionMode.EQ180, ProjectionMode.FISHEYE,
            ProjectionMode.DUAL_FISHEYE, ProjectionMode.CUBEMAP, ProjectionMode.EAC,
        )

        /** The layout a projection has when nothing more is known of it. */
        fun of(projection: ProjectionMode, stereo: StereoMode = StereoMode.MONO, lensFov: Float = 0f): PanoLayout = when (projection) {
            ProjectionMode.EQ180 -> PanoLayout(projection, stereo, yawFrom = -90f, yawTo = 90f)
            ProjectionMode.FISHEYE -> PanoLayout(projection, stereo, lensFov = if (lensFov > 0f) lensFov else 180f)
            ProjectionMode.DUAL_FISHEYE -> PanoLayout(projection, stereo, lensFov = if (lensFov > 0f) lensFov else 190f)
            else -> PanoLayout(projection, stereo)
        }

        /**
         * How a film's picture surrounds the viewer, or null for a flat one — on the headset and the desktop
         * alike: the viewer's choice of format ([flags] userOverridden); else what the file says of itself
         * ([info], its header read as it opens; a fisheye's lens as its flags name it); else what it said
         * the last time it played ([VideoFlags.fromFile]), while its header is not read yet; else its name,
         * read by today's rules (the scan's word may be older).
         */
        fun forFilm(flags: VideoFlags, fileName: String, info: SphericalInfo?): PanoLayout? {
            val flagStereo = flags.stereo.takeIf { it == StereoMode.SBS || it == StereoMode.OU } ?: StereoMode.MONO
            // Flags made a layout: their stereo, and which eye comes first.
            fun of(f: VideoFlags, eyes: StereoMode) =
                of(f.projection, eyes, f.lensFov.toFloat()).copy(eyesSwapped = f.rightFirst && eyes != StereoMode.MONO)
            // The frame's size, where the header said it: how a cubemap's faces lie, EAC's border — and a
            // cubemap's padding, which the file's picture has whatever format was chosen for it.
            fun PanoLayout.framed(): PanoLayout {
                val padded = if (projection == ProjectionMode.CUBEMAP && cubePadding == 0 && info != null) copy(cubePadding = info.cubePadding) else this
                return if (padded.frameWidth == 0 && info != null && info.frameWidth > 0) padded.sized(info.frameWidth, info.frameHeight) else padded
            }
            if (flags.userOverridden) return if (flags.projection in SPHERICAL) of(flags, flagStereo).framed() else null
            if (info != null && info.isSpherical) {
                val l = info.layout(fallbackStereo = flagStereo) ?: return null
                val lens = l.projection == ProjectionMode.FISHEYE || l.projection == ProjectionMode.DUAL_FISHEYE
                return if (lens && flags.lensFov > 0 && flags.projection == l.projection) l.copy(lensFov = flags.lensFov.toFloat()) else l
            }
            if (info == null && flags.fromFile && flags.projection in SPHERICAL) return of(flags, flagStereo)
            val named = ProjectionDetector.detect(fileName)
            if (named.projection !in SPHERICAL) return null
            // The name's eyes; where it says none, the file's own marking (st3d, StereoMode, packing SEI — and
            // which eye it puts first), else the flags'.
            val namedStereo = named.stereo.takeIf { it == StereoMode.SBS || it == StereoMode.OU }
            val fileStereo = info?.stereo?.takeIf { (it == StereoMode.SBS || it == StereoMode.OU) && !info.multiview }
            return when {
                namedStereo != null -> of(named, namedStereo)
                fileStereo != null -> of(named, fileStereo).copy(eyesSwapped = info?.eyesSwapped == true)
                else -> of(named.copy(rightFirst = flags.rightFirst), flagStereo)
            }.framed()
        }
    }
}

/** A mesh round the viewer: positions (x, y, z in metres), texture coordinates (u right, v up), triangles. */
class PanoMesh(val positions: FloatArray, val uvs: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
}

/** Where each direction is in the picture ([uv]), and the meshes that put the picture round the viewer ([mesh]). */
object Panorama {

    /**
     * Where unit direction ([x], [y], [z]) is in one eye's picture: (u, v), u to the right and v up from
     * the bottom row (the video texture's own way round); null when the picture has nothing there.
     */
    fun uv(layout: PanoLayout, x: Double, y: Double, z: Double): DoubleArray? = when (layout.projection) {
        ProjectionMode.FISHEYE -> fisheyeUv(x, y, z, layout.lensFov)
        ProjectionMode.DUAL_FISHEYE -> dualFisheyeUv(x, y, z, layout.lensFov)
        ProjectionMode.CUBEMAP, ProjectionMode.EAC -> cubeUv(layout, x, y, z)
        else -> equirectUv(layout, x, y, z)
    }

    /**
     * The mesh for [layout], a sphere of [radius] metres (or the part of one the picture covers). [coarse]:
     * half as fine each way — for a window's view cut from it, where every triangle costs the SDK's mesh
     * time on each drag (cells of 5.6° still carry the texture true to about a pixel there).
     */
    fun mesh(layout: PanoLayout, radius: Float, coarse: Boolean = false): PanoMesh {
        val d = if (coarse) 2 else 1
        val mesh = if (layout.ownMesh != null) {
            MeshProjection.fitted(layout.ownMesh, radius, layout.stereo)
        } else when (layout.projection) {
            ProjectionMode.FISHEYE -> capMesh(radius, layout.lensFov, d) { x, y, z -> fisheyeUv(x, y, z, layout.lensFov) }
            ProjectionMode.DUAL_FISHEYE -> dualFisheyeMesh(layout, radius, d)
            ProjectionMode.CUBEMAP, ProjectionMode.EAC -> cubeMesh(layout, radius, d)
            else -> equirectMesh(layout, radius, d)
        }
        return if (layout.eyesSwapped) leftEyeOnly(mesh, layout.stereo) else mesh
    }

    /**
     * A frame with the right eye's picture first, shown as its left eye's picture to both eyes. VideoMaterial
     * gives the left eye the left (or top) half and has no way round, and its texture does not repeat —
     * coordinates moved past the edge only smear the edge's pixels (tried: a shifted mesh's left eye was
     * the frame's first column, drawn out) — so the eyes cannot be exchanged. Shown as they are, each eye
     * would see the other's picture and the depth turn inside out; flat, the film is at least right.
     * The material is then [PanoLayout.shownStereo] (MONO), the mesh reaching into the left eye's half.
     */
    fun leftEyeOnly(mesh: PanoMesh, stereo: StereoMode): PanoMesh = when (stereo) {
        // Side by side, right first: the left eye's picture is the right half.
        StereoMode.SBS -> PanoMesh(mesh.positions, FloatArray(mesh.uvs.size) { if (it % 2 == 0) 0.5f + mesh.uvs[it] * 0.5f else mesh.uvs[it] }, mesh.indices)
        // Over-under, right on top: the left eye's is the bottom half (v counts up from the bottom row).
        StereoMode.OU -> PanoMesh(mesh.positions, FloatArray(mesh.uvs.size) { if (it % 2 == 1) mesh.uvs[it] * 0.5f else mesh.uvs[it] }, mesh.indices)
        else -> mesh
    }

    // ---- equirectangular ------------------------------------------------------------------------

    private fun equirectUv(l: PanoLayout, x: Double, y: Double, z: Double): DoubleArray? {
        val yaw = Math.toDegrees(atan2(x, -z))
        val pitch = Math.toDegrees(atan2(y, hypot(x, z)))
        val u = (yaw - l.yawFrom) / (l.yawTo - l.yawFrom)
        val v = (pitch - l.pitchFrom) / (l.pitchTo - l.pitchFrom)
        return if (u in 0.0..1.0 && v in 0.0..1.0) doubleArrayOf(u, v) else null
    }

    /** A grid over the covered yaw × pitch: the picture's own grid, so its lines stay straight in texture space. */
    private fun equirectMesh(l: PanoLayout, radius: Float, d: Int = 1): PanoMesh {
        val nx = max(8, ceil((l.yawTo - l.yawFrom) / 360f * YAW_STEPS / d).toInt())
        val ny = max(8, ceil((l.pitchTo - l.pitchFrom) / 180f * PITCH_STEPS / d).toInt())
        val b = Builder()
        for (j in 0..ny) for (i in 0..nx) {
            val u = i.toDouble() / nx
            val v = j.toDouble() / ny
            val yaw = Math.toRadians(l.yawFrom + (l.yawTo - l.yawFrom) * u)
            val pitch = Math.toRadians(l.pitchFrom + (l.pitchTo - l.pitchFrom) * v)
            b.vertex(radius, cos(pitch) * sin(yaw), sin(pitch), -cos(pitch) * cos(yaw), u, v)
        }
        b.grid(0, nx, ny)
        return b.build()
    }

    // ---- fisheye (equidistant, as FFmpeg's) -------------------------------------------------------

    /** One circle filling the frame, its centre straight ahead: the distance from it grows with the angle off the axis. */
    private fun fisheyeUv(x: Double, y: Double, z: Double, fov: Float): DoubleArray? {
        val angle = acos((-z).coerceIn(-1.0, 1.0))
        val half = Math.toRadians(fov / 2.0)
        if (angle > half + 1e-9) return null
        val h = hypot(x, y)
        val r = 0.5 * angle / half
        return if (h < 1e-12) doubleArrayOf(0.5, 0.5) else doubleArrayOf(0.5 + r * x / h, 0.5 + r * y / h)
    }

    /**
     * Two circles side by side, front lens on the right, back lens on the left and mirrored (FFmpeg's
     * dfisheye; a 360 camera's own recording). Each lens draws its own half of the sphere.
     */
    private fun dualFisheyeUv(x: Double, y: Double, z: Double, fov: Float): DoubleArray {
        val front = z <= 0.0
        val angle = acos(abs(z).coerceIn(0.0, 1.0))
        val r = angle / Math.toRadians(fov / 2.0)
        val h = hypot(x, y)
        val ax = if (h < 1e-12) 0.0 else r * x / h
        val ay = if (h < 1e-12) 0.0 else r * y / h
        val u = if (front) 0.5 + (ax + 1) / 4 else (1 - ax) / 4
        return doubleArrayOf(u, 0.5 + 0.5 * ay)
    }

    /** A spherical cap of [fov] degrees about straight ahead, in rings: a fisheye's circle is polar. */
    private fun capMesh(radius: Float, fov: Float, d: Int = 1, uvOf: (Double, Double, Double) -> DoubleArray?): PanoMesh {
        val b = Builder()
        capInto(b, radius, Math.toRadians(fov / 2.0), forward = true, d = d, uvOf = uvOf)
        return b.build()
    }

    private fun dualFisheyeMesh(l: PanoLayout, radius: Float, d: Int = 1): PanoMesh {
        val b = Builder()
        val uvOf = { x: Double, y: Double, z: Double -> dualFisheyeUv(x, y, z, l.lensFov) }
        capInto(b, radius, PI / 2, forward = true, d = d, uvOf = uvOf)
        capInto(b, radius, PI / 2, forward = false, d = d, uvOf = uvOf)
        return b.build()
    }

    private fun capInto(b: Builder, radius: Float, halfAngle: Double, forward: Boolean, d: Int = 1, uvOf: (Double, Double, Double) -> DoubleArray?) {
        val rings = max(8, ceil(halfAngle / PI * PITCH_STEPS / d).toInt())
        val segments = YAW_STEPS / d
        val first = b.count
        val axis = if (forward) -1.0 else 1.0
        for (k in 0..rings) for (s in 0..segments) {
            val a = halfAngle * k / rings
            val t = 2 * PI * s / segments
            val x = sin(a) * cos(t)
            val y = sin(a) * sin(t)
            val z = axis * cos(a)
            // The ring's own direction for its texture (the centre has none): the azimuth is kept.
            val uv = if (k == 0) uvOf(cos(t) * 1e-6, sin(t) * 1e-6, axis) else uvOf(x, y, z)
            b.vertex(radius, x, y, z, uv?.get(0) ?: 0.5, uv?.get(1) ?: 0.5)
        }
        b.grid(first, segments, rings)
    }

    // ---- cubemaps: Google's 3×2 (and the 6×1, 1×6 strips), YouTube's equi-angular -----------------

    /** Faces as FFmpeg names them, its vectors x right, y DOWN, z forward. */
    private enum class Face { RIGHT, LEFT, UP, DOWN, FRONT, BACK }

    /** Where each face sits in the grid (row by row, left to right) and its quarter turns. */
    private class CubeLayout(val slot: Map<Face, Int>, val turns: IntArray)

    /**
     * How a plain cubemap's six faces are laid out: 3×2 (Google's layout 0), or in one strip — 6×1 and 1×6,
     * FFmpeg's c6x1 / c1x6 and the strips cube tools write. The same faces in the same order ("rludfb",
     * unrotated) either way; the frame's shape says which. Faces across, and down.
     */
    fun cubeGrid(l: PanoLayout): Pair<Int, Int> {
        val aspect = if (l.frameWidth > 0 && l.frameHeight > 0) l.frameWidth.toFloat() / l.frameHeight else l.frameAspect
        return when {
            aspect > 4f -> 6 to 1
            aspect > 0f && aspect < 0.3f -> 1 to 6
            else -> 3 to 2
        }
    }

    /** FFmpeg's c3x2 default ("rludfb", unrotated): Google's spherical metadata cubemap layout 0. */
    private val C3X2 = CubeLayout(
        mapOf(Face.RIGHT to 0, Face.LEFT to 1, Face.UP to 2, Face.DOWN to 3, Face.FRONT to 4, Face.BACK to 5),
        intArrayOf(0, 0, 0, 0, 0, 0),
    )

    /** YouTube's EAC: left front right / down back up, the bottom row turned (FFmpeg's prepare_eac_in). */
    private val EAC = CubeLayout(
        mapOf(Face.LEFT to 0, Face.FRONT to 1, Face.RIGHT to 2, Face.DOWN to 3, Face.BACK to 4, Face.UP to 5),
        intArrayOf(0, 0, 0, 3, 1, 3),
    )

    private fun cubeUv(l: PanoLayout, x: Double, y: Double, z: Double): DoubleArray {
        // To FFmpeg's axes, then its xyz_to_cube.
        val fx = x
        val fy = -y
        val fz = -z
        val phi = atan2(fx, fz)
        val theta = Math.asin(fy.coerceIn(-1.0, 1.0))
        var face: Face
        val phiNorm: Double
        when {
            phi >= -PI / 4 && phi < PI / 4 -> { face = Face.FRONT; phiNorm = phi }
            phi >= -(PI / 2 + PI / 4) && phi < -PI / 4 -> { face = Face.LEFT; phiNorm = phi + PI / 2 }
            phi >= PI / 4 && phi < PI / 2 + PI / 4 -> { face = Face.RIGHT; phiNorm = phi - PI / 2 }
            else -> { face = Face.BACK; phiNorm = phi + if (phi > 0) -PI else PI }
        }
        val threshold = atan(cos(phiNorm))
        if (theta > threshold) face = Face.DOWN else if (theta < -threshold) face = Face.UP
        val (uf, vf) = when (face) {
            Face.RIGHT -> -fz / fx to fy / fx
            Face.LEFT -> -fz / fx to -fy / fx
            Face.UP -> -fx / fy to -fz / fy
            Face.DOWN -> fx / fy to -fz / fy
            Face.FRONT -> fx / fz to fy / fz
            Face.BACK -> fx / fz to -fy / fz
        }
        return place(l, face, uf, vf)
    }

    /** Face [face]'s point (uf, vf) ∈ [−1, 1]² — before the face's turn — in the frame. */
    private fun place(l: PanoLayout, face: Face, uf: Double, vf: Double): DoubleArray {
        val eac = l.projection == ProjectionMode.EAC
        val layout = if (eac) EAC else C3X2
        val slot = layout.slot.getValue(face)
        val (ru, rv) = turn(uf, vf, layout.turns[slot])
        val (cols, rows) = if (eac) 3 to 2 else cubeGrid(l)
        val col = slot % cols
        val row = slot / cols
        val vRow: Double
        val u: Double
        if (eac) {
            val padU = if (l.frameWidth > 0) EAC_BORDER / l.frameWidth else 0.0
            val padV = if (l.frameHeight > 0) EAC_BORDER / l.frameHeight else 0.0
            val eu = 2 / PI * atan(ru) + 0.5
            val ev = 2 / PI * atan(rv) + 0.5
            u = (eu + col) * (1 - 2 * padU) / 3 + padU
            vRow = ev * (0.5 - 2 * padV) + padV + 0.5 * row
        } else {
            // A padded face holds its picture inside a border of cubePadding pixels (FFmpeg's fin_pad, halved per side).
            val padU = if (l.cubePadding > 0 && l.frameWidth > 0) l.cubePadding * cols.toDouble() / l.frameWidth else 0.0
            val padV = if (l.cubePadding > 0 && l.frameHeight > 0) l.cubePadding * rows.toDouble() / l.frameHeight else 0.0
            u = (col + padU + (ru + 1) / 2 * (1 - 2 * padU)) / cols
            vRow = (row + padV + (rv + 1) / 2 * (1 - 2 * padV)) / rows
        }
        return doubleArrayOf(u, 1 - vRow)
    }

    /** FFmpeg's rotate_cube_face: quarter turns of a face's coordinates. */
    private fun turn(uf: Double, vf: Double, quarters: Int): Pair<Double, Double> = when (quarters) {
        1 -> -vf to uf
        2 -> -uf to -vf
        3 -> vf to -uf
        else -> uf to vf
    }

    /** FFmpeg's cube_to_xyz before a face's turn: the point of face [face] at (uf, vf), FFmpeg's axes. */
    private fun faceVector(face: Face, uf: Double, vf: Double): DoubleArray = when (face) {
        Face.RIGHT -> doubleArrayOf(1.0, vf, -uf)
        Face.LEFT -> doubleArrayOf(-1.0, vf, uf)
        Face.UP -> doubleArrayOf(uf, -1.0, vf)
        Face.DOWN -> doubleArrayOf(uf, 1.0, -vf)
        Face.FRONT -> doubleArrayOf(uf, vf, 1.0)
        Face.BACK -> doubleArrayOf(-uf, vf, -1.0)
    }

    /**
     * Six faces, each its own grid so a face's texture never runs across a seam. An equi-angular face is
     * gridded evenly in angle, where its texture is even: the picture stays true between vertices.
     */
    private fun cubeMesh(l: PanoLayout, radius: Float, d: Int = 1): PanoMesh {
        val eac = l.projection == ProjectionMode.EAC
        val n = FACE_STEPS / d
        val b = Builder()
        for (face in Face.values()) {
            val first = b.count
            for (j in 0..n) for (i in 0..n) {
                val s = -1.0 + 2.0 * i / n
                val t = -1.0 + 2.0 * j / n
                val uf = if (eac) tan(s * PI / 4) else s
                val vf = if (eac) tan(t * PI / 4) else t
                val f = faceVector(face, uf, vf)
                val len = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
                val uv = place(l, face, uf, vf)
                // Back to the viewer's axes: y up, z behind.
                b.vertex(radius, f[0] / len, -f[1] / len, -f[2] / len, uv[0], uv[1])
            }
            b.grid(first, n, n)
        }
        return b.build()
    }

    // ---- assembly -------------------------------------------------------------------------------

    private class Builder {
        private val pos = ArrayList<Float>()
        private val tex = ArrayList<Float>()
        private val idx = ArrayList<Int>()
        val count: Int get() = pos.size / 3

        fun vertex(r: Float, x: Double, y: Double, z: Double, u: Double, v: Double) {
            pos += (x * r).toFloat(); pos += (y * r).toFloat(); pos += (z * r).toFloat()
            tex += u.toFloat(); tex += v.toFloat()
        }

        /** Two triangles per cell of a (cols+1) × (rows+1) grid of vertices from [first]. */
        fun grid(first: Int, cols: Int, rows: Int) {
            val w = cols + 1
            for (j in 0 until rows) for (i in 0 until cols) {
                val a = first + j * w + i
                val c = a + w
                idx += a; idx += c; idx += a + 1
                idx += a + 1; idx += c; idx += c + 1
            }
        }

        fun build() = PanoMesh(pos.toFloatArray(), tex.toFloatArray(), idx.toIntArray())
    }

    /** Segments round a whole turn, and from pole to pole: fine enough that a straight line in the picture stays straight. */
    private const val YAW_STEPS = 128
    private const val PITCH_STEPS = 64
    private const val FACE_STEPS = 24
    private const val EAC_BORDER = 2.0
}
