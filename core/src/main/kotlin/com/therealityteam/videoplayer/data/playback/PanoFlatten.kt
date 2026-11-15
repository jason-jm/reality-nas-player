package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.StereoMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A panorama's frame as a camera at its centre would see it — a flat picture of one look into it —
 * for where a whole frame would be read as a picture: a poster made from the film (a 360° frame laid
 * flat is a smear, its poles across the top and bottom). The left eye's picture of a stereo frame.
 */
object PanoFlatten {

    /**
     * [outW] × [outH] ARGB pixels of [frame] ([width] × [height], laid out as [layout]) seen looking
     * [look] ([PanoViewport.Look]: yaw right, pitch up, its fov across [outW]). Black where the
     * picture has nothing (behind a 180).
     */
    fun view(
        frame: IntArray, width: Int, height: Int, layout: PanoLayout, outW: Int, outH: Int,
        look: PanoViewport.Look = PanoViewport.Look(fov = POSTER_FOV),
        /** The sphere's turn (row-major 3×3, the viewer's axes), undone before the picture is read: a file's pose, a levelled horizon. */
        turn: DoubleArray? = null,
    ): IntArray {
        val yaw = Math.toRadians(look.yaw.toDouble())
        val pitch = Math.toRadians(look.pitch.toDouble())
        val fx = sin(yaw) * cos(pitch); val fy = sin(pitch); val fz = -cos(yaw) * cos(pitch)
        val rx = cos(yaw); val rz = sin(yaw)
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        val tx = tan(Math.toRadians(look.fov / 2.0))
        val ty = tx * outH / outW
        val out = IntArray(outW * outH)
        for (j in 0 until outH) {
            val sy = 1 - 2 * (j + 0.5) / outH
            for (i in 0 until outW) {
                val sx = 2 * (i + 0.5) / outW - 1
                val dx = fx + rx * tx * sx + ux * ty * sy
                val dy = fy + uy * ty * sy
                val dz = fz + rz * tx * sx + uz * ty * sy
                val len = sqrt(dx * dx + dy * dy + dz * dz)
                var x = dx / len
                var y = dy / len
                var z = dz / len
                if (turn != null) {
                    // Into the picture's own frame: the turn's transpose.
                    val tx = turn[0] * x + turn[3] * y + turn[6] * z
                    val ty = turn[1] * x + turn[4] * y + turn[7] * z
                    val tz = turn[2] * x + turn[5] * y + turn[8] * z
                    x = tx; y = ty; z = tz
                }
                val uv = Panorama.uv(layout, x, y, z)
                out[j * outW + i] = if (uv == null) BLACK else sample(frame, width, height, eyeU(layout, uv[0]), eyeV(layout, uv[1]))
            }
        }
        return out
    }

    /** One eye's u in the whole frame: the left eye's half of a side-by-side frame (the second half when the right comes first). */
    private fun eyeU(l: PanoLayout, u: Double): Double = when (l.stereo) {
        StereoMode.SBS -> if (l.eyesSwapped) 0.5 + u * 0.5 else u * 0.5
        else -> u
    }

    /** …and its v (up from the bottom row): the top half of an over-under frame, the bottom when the right is on top. */
    private fun eyeV(l: PanoLayout, v: Double): Double = when (l.stereo) {
        StereoMode.OU -> if (l.eyesSwapped) v * 0.5 else 0.5 + v * 0.5
        else -> v
    }

    /** Bilinear, at (u, v) with v up from the bottom row. */
    private fun sample(frame: IntArray, w: Int, h: Int, u: Double, v: Double): Int {
        val x = (u * w - 0.5).coerceIn(0.0, w - 1.0)
        val y = ((1 - v) * h - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val ax = x - x0; val ay = y - y0
        var argb = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            fun ch(px: Int, py: Int) = (frame[py * w + px] shr shift) and 0xFF
            val top = ch(x0, y0) * (1 - ax) + ch(x1, y0) * ax
            val bottom = ch(x0, y1) * (1 - ax) + ch(x1, y1) * ax
            argb = argb or ((top * (1 - ay) + bottom * ay).toInt().coerceIn(0, 255) shl shift)
        }
        return argb
    }

    /** A poster's look: straight ahead, a hundred degrees across — a wide window, not a smear. */
    const val POSTER_FOV = 100f
    private const val BLACK = 0xFF000000.toInt()
}
