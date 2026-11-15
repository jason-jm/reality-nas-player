package com.therealityteam.videoplayer.player

import kotlin.math.pow

/**
 * 动态氛围光: the light a film throws into a place, from a small copy of its frame. A place baked with its screen's light
 * in zones (tools/envbake: the left, centre and right thirds of the screen, each in white at the film's mean the bake
 * assumed) draws each zone's light scaled by what the film shows there now — a red scene on the left reddens the left
 * wall; a dark one leaves the room dark.
 */
object Ambience {

    /** The screen's zones, left to right, as the bake makes them. */
    const val ZONES = 3

    /**
     * The screen's light a zone is baked with, linear: white (tools/envbake/scenes/private_theater.py) — a film's frame
     * turns it down, and never up past what the 8-bit atlas holds.
     */
    const val BAKED_MEAN = 1f

    /** A zone's light for a film's mean frame (the bake's SCREEN_MEAN) — where it rests with no film to look at. */
    const val STILL = 0.35f

    /** A zone's light at most, in multiples of the bake's (a debug gain can push it past white). */
    const val MOST = 3f

    private val LINEAR = FloatArray(256) { srgbToLinear(it / 255f) }

    /**
     * Each zone's light as a factor of the bake's, red, green and blue in turn ([ZONES] × 3 values), from [pixels] (ARGB,
     * [width] × [height]: the whole frame). The film is fitted inside the screen as the app shows it — a narrower one leaves
     * dark sides, a wider one dark bars — so a zone's mean is over the whole of its third of the screen, the dark parts too.
     */
    fun zones(pixels: IntArray, width: Int, height: Int, filmAspect: Float, screenAspect: Float, gain: Float = 1f): FloatArray {
        val sums = FloatArray(ZONES * 3)
        if (width <= 0 || height <= 0 || filmAspect <= 0f || screenAspect <= 0f) return sums
        val fw = if (filmAspect < screenAspect) filmAspect / screenAspect else 1f
        val fh = if (filmAspect > screenAspect) screenAspect / filmAspect else 1f
        val left = (1f - fw) / 2f
        val col = FloatArray(3)
        for (x in 0 until width) {
            col.fill(0f)
            for (y in 0 until height) {
                val p = pixels[y * width + x]
                col[0] += LINEAR[p shr 16 and 0xff]
                col[1] += LINEAR[p shr 8 and 0xff]
                col[2] += LINEAR[p and 0xff]
            }
            // the column's stretch of the screen, shared between the zones it falls in
            val x0 = left + x.toFloat() / width * fw
            val x1 = left + (x + 1f) / width * fw
            for (z in 0 until ZONES) {
                val share = (minOf(x1, (z + 1f) / ZONES) - maxOf(x0, z.toFloat() / ZONES)) / (x1 - x0)
                if (share <= 0f) continue
                sums[z * 3] += col[0] * share; sums[z * 3 + 1] += col[1] * share; sums[z * 3 + 2] += col[2] * share
            }
        }
        // A pixel covers (fw / width) × (fh / height) of the screen; a zone, 1 / ZONES of it.
        val perPixel = fw * fh / (width.toFloat() * height) * ZONES
        return FloatArray(ZONES * 3) { (sums[it] * perPixel / BAKED_MEAN * gain).coerceIn(0f, MOST) }
    }

    private fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}
