package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Whether a film with no word of its own (no metadata, nothing in its name) is a 360°/180° picture,
 * read off its frames — what a downloaded panorama, stripped of its metadata on the way, still shows:
 * - an equirectangular sphere joins up across its left and right edges (they are both straight
 *   behind), and its top and bottom rows are each one point (the poles) drawn out across the width;
 * - a lens's picture is a circle with black round it, the boundary sharp all the way round;
 * - a stereo frame's two halves are the same scene a few pixels apart.
 * Each test asks for texture first (a black bar joins up and is uniform too), and a guess needs
 * the frames to agree ([agree]). Only the shapes such films come in are tried: 2:1 (a sphere, a
 * VR180's two squares, a camera's two lenses), 1:1 (a sphere's two eyes stacked, one lens), 4:1.
 */
object ProjectionGuess {

    data class Guess(val projection: ProjectionMode, val stereo: StereoMode)

    /** A frame's brightness, 0–1, row by row. */
    class Luma(val width: Int, val height: Int, val v: FloatArray) {
        operator fun get(x: Int, y: Int) = v[y * width + x]

        fun crop(x0: Int, y0: Int, w: Int, h: Int) = Luma(w, h, FloatArray(w * h) { this[x0 + it % w, y0 + it / w] })

        companion object {
            fun ofArgb(argb: IntArray, width: Int, height: Int, offset: Int = 0) = Luma(width, height, FloatArray(width * height) {
                val c = argb[offset + it]
                (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f
            })
        }
    }

    /** What one frame looks like it is, or null when it looks like none of them (or a flat picture). */
    fun guess(frame: Luma): Guess? {
        val w = frame.width
        val h = frame.height
        if (w < 64 || h < 32) return null
        val aspect = w.toFloat() / h
        val halfW = w / 2
        val halfH = h / 2
        return when {
            aspect in 1.9f..2.1f -> {
                val left = frame.crop(0, 0, halfW, h)
                val right = frame.crop(halfW, 0, halfW, h)
                val circles = isCircle(left) && isCircle(right)
                when {
                    isStereoPair(left, right) -> when {
                        circles -> Guess(ProjectionMode.FISHEYE, StereoMode.SBS)
                        stretchedAtPoles(left) && stretchedAtPoles(right) -> Guess(ProjectionMode.EQ180, StereoMode.SBS)
                        else -> null
                    }
                    circles -> Guess(ProjectionMode.DUAL_FISHEYE, StereoMode.MONO)
                    isSphere(frame) -> Guess(ProjectionMode.EQ360, StereoMode.MONO)
                    else -> null
                }
            }
            aspect in 0.95f..1.05f -> {
                val top = frame.crop(0, 0, w, halfH)
                val bottom = frame.crop(0, halfH, w, halfH)
                when {
                    isStereoPair(top, bottom) && isSphere(top) && isSphere(bottom) -> Guess(ProjectionMode.EQ360, StereoMode.OU)
                    isCircle(frame) -> Guess(ProjectionMode.FISHEYE, StereoMode.MONO)
                    else -> null
                }
            }
            aspect in 3.8f..4.2f -> {
                val left = frame.crop(0, 0, halfW, h)
                val right = frame.crop(halfW, 0, halfW, h)
                if (isStereoPair(left, right) && isSphere(left) && isSphere(right)) Guess(ProjectionMode.EQ360, StereoMode.SBS) else null
            }
            else -> null
        }
    }

    /** The frames' verdict: one guess most of them make (at least two, and more than any other), else null. */
    fun agree(guesses: List<Guess?>): Guess? {
        val counts = guesses.filterNotNull().groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return null
        if (best.value < 2 || best.value * 2 <= guesses.size) return null
        return best.key
    }

    // ---- an equirectangular sphere ---------------------------------------------------------------

    /** Joins up across its left and right edges, and is drawn out sideways at its poles. */
    internal fun isSphere(img: Luma): Boolean = joinsAcrossEdges(img) && stretchedAtPoles(img)

    /**
     * Its first and last columns differ no more than the columns beside them do (they are neighbours,
     * either side of straight behind), where both edges have something on them.
     */
    internal fun joinsAcrossEdges(img: Luma): Boolean {
        val w = img.width
        val y0 = img.height / 8
        val y1 = img.height - y0
        var seam = 0f
        var near = 0f
        for (y in y0 until y1) {
            seam += abs(img[0, y] - img[w - 1, y])
            near += (abs(img[0, y] - img[1, y]) + abs(img[1, y] - img[2, y]) +
                abs(img[w - 2, y] - img[w - 1, y]) + abs(img[w - 3, y] - img[w - 2, y])) / 4
        }
        val rows = (y1 - y0).coerceAtLeast(1)
        if (near / rows < MIN_DETAIL) return false
        if (columnSpread(img, 0, y0, y1) < MIN_EDGE_SPREAD || columnSpread(img, w - 1, y0, y1) < MIN_EDGE_SPREAD) return false
        return seam < SEAM_RATIO * near
    }

    /**
     * Near a pole each row is a small circle round it drawn the whole width across: the picture changes
     * far less along a row there than down a column — against the middle rows, where it changes alike.
     * A band with nothing on it (a black patch over the tripod) says nothing; one with something on it
     * must be drawn out, and at least one must be.
     */
    internal fun stretchedAtPoles(img: Luma): Boolean {
        val band = (img.height / 10).coerceAtLeast(2)
        val middle = anisotropy(img, img.height / 2 - band, img.height / 2 + band) ?: return false
        var stretched = 0
        for (y0 in listOf(0, img.height - band)) {
            val a = anisotropy(img, y0, y0 + band) ?: continue
            if (a < POLE_STRETCH * middle) stretched++ else return false
        }
        return stretched > 0
    }

    /** How much the rows [y0, y1) change along against down, or null when they hardly change at all. */
    private fun anisotropy(img: Luma, y0: Int, y1: Int): Float? {
        var along = 0.0
        var down = 0.0
        var n = 0
        for (y in y0 until (y1 - 1).coerceAtMost(img.height - 1)) for (x in 0 until img.width - 1) {
            along += abs(img[x + 1, y] - img[x, y])
            down += abs(img[x, y + 1] - img[x, y])
            n++
        }
        if (n == 0 || (along + down) / (2 * n) < MIN_DETAIL) return null
        return (along / down.coerceAtLeast(1e-9)).toFloat()
    }

    // ---- a lens's circle -------------------------------------------------------------------------

    /**
     * A circle touching the frame's sides, black round it: the corners one even dark (a copied frame's
     * black is not 0 — the emulator's came out 31/255), the ring just inside the edge brighter than them in
     * most directions (a dark scene may have a dark side), and a sharp step down to them along the
     * diagonals (a vignette darkens gradually and unevenly; a black-boxed picture's corner is square).
     */
    internal fun isCircle(img: Luma): Boolean {
        val aspect = img.width.toFloat() / img.height
        if (aspect !in 0.9f..1.1f) return false
        val cx = img.width / 2f
        val cy = img.height / 2f
        val r = minOf(img.width, img.height) / 2f
        var cornerSum = 0f
        var cornerMax = 0f
        var cornerMin = 1f
        var cornerCount = 0
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) / r > 1.08f) {
                val v = img[x, y]
                cornerSum += v
                cornerMax = maxOf(cornerMax, v)
                cornerMin = minOf(cornerMin, v)
                cornerCount++
            }
        }
        if (cornerCount == 0) return false
        val black = cornerSum / cornerCount
        if (black > DARK || cornerMax - cornerMin > EVEN) return false
        var lit = 0
        for (k in 0 until RING_STEPS) {
            val a = 2 * Math.PI * k / RING_STEPS
            if (sampleAround(img, cx + 0.9f * r * kotlin.math.cos(a).toFloat(), cy + 0.9f * r * kotlin.math.sin(a).toFloat()) > black + STEP) lit++
        }
        if (lit < RING_STEPS / 2) return false
        var sharp = 0
        for (k in 0 until 4) {
            val a = Math.PI / 4 + Math.PI / 2 * k
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            val inside = sampleAround(img, cx + 0.95f * r * dx, cy + 0.95f * r * dy)
            val outside = sampleAround(img, cx + 1.05f * r * dx, cy + 1.05f * r * dy)
            if (inside - outside > STEP && abs(outside - black) < EVEN) sharp++
        }
        return sharp >= 3
    }

    // ---- a stereo pair ---------------------------------------------------------------------------

    /**
     * Two halves of one scene from two eyes: their edges (the brightness's change across) line up, at
     * some small shift sideways (the eyes' disparity), better than a different scene's would.
     */
    internal fun isStereoPair(a: Luma, b: Luma): Boolean =
        brightnessCorrelation(a, b) >= STEREO_BRIGHTNESS && stereoCorrelation(a, b) >= STEREO_EDGES

    /**
     * How well [a]'s and [b]'s brightness lines up, in 4-pixel blocks (the eyes' small differences and
     * the coding's noise averaged away), at the best small sideways shift. Alone it is fooled by two
     * halves laid out alike (a test card's, two lenses' black corners); with the edges it is not.
     */
    internal fun brightnessCorrelation(a: Luma, b: Luma): Float {
        if (a.width != b.width || a.height != b.height) return 0f
        val ba = blocks(a)
        val bb = blocks(b)
        val maxShift = (ba.width * 0.04f).toInt().coerceAtLeast(1)
        var best = -1f
        for (s in -maxShift..maxShift) best = maxOf(best, correlation(ba.v, bb.v, ba.width, ba.height, s, step = 1))
        return best
    }

    private fun blocks(img: Luma): Luma {
        val w = img.width / BLOCK
        val h = img.height / BLOCK
        return Luma(w, h, FloatArray(w * h) {
            val x = it % w * BLOCK
            val y = it / w * BLOCK
            var sum = 0f
            for (dy in 0 until BLOCK) for (dx in 0 until BLOCK) sum += img[x + dx, y + dy]
            sum / (BLOCK * BLOCK)
        })
    }

    /** How well [a]'s and [b]'s edges line up at the best small sideways shift, −1…1. */
    internal fun stereoCorrelation(a: Luma, b: Luma): Float {
        if (a.width != b.width || a.height != b.height) return 0f
        val ga = edges(a)
        val gb = edges(b)
        if (spread(ga) < MIN_DETAIL) return 0f
        val maxShift = (a.width * 0.03f).toInt().coerceAtLeast(1)
        var best = -1f
        for (s in -maxShift..maxShift) best = maxOf(best, correlation(ga, gb, a.width, a.height, s))
        return best
    }

    /** The brightness's change to the right, everywhere. */
    private fun edges(img: Luma): FloatArray = FloatArray(img.width * img.height) {
        val x = it % img.width
        if (x == img.width - 1) 0f else abs(img.v[it + 1] - img.v[it])
    }

    /** Pearson correlation of [a] and [b] shifted [shift] columns, over what overlaps (every [step]th). */
    private fun correlation(a: FloatArray, b: FloatArray, w: Int, h: Int, shift: Int, step: Int = 2): Float {
        var n = 0
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        val x0 = maxOf(0, -shift)
        val x1 = minOf(w, w - shift)
        for (y in 0 until h step step) for (x in x0 until x1 step step) {
            val p = a[y * w + x].toDouble()
            val q = b[y * w + x + shift].toDouble()
            sa += p; sb += q; saa += p * p; sbb += q * q; sab += p * q; n++
        }
        if (n < 16) return 0f
        val cov = sab / n - sa / n * (sb / n)
        val va = saa / n - (sa / n) * (sa / n)
        val vb = sbb / n - (sb / n) * (sb / n)
        if (va <= 0 || vb <= 0) return 0f
        return (cov / sqrt(va * vb)).toFloat()
    }

    // ---- numbers ---------------------------------------------------------------------------------

    private fun sampleAround(img: Luma, x: Float, y: Float): Float {
        var sum = 0f
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val px = (x + dx).toInt().coerceIn(0, img.width - 1)
            val py = (y + dy).toInt().coerceIn(0, img.height - 1)
            sum += img[px, py]; n++
        }
        return sum / n
    }

    private fun spread(v: FloatArray): Float {
        var s = 0.0; var ss = 0.0
        for (x in v) { s += x; ss += x * x }
        val m = s / v.size
        return sqrt((ss / v.size - m * m).coerceAtLeast(0.0)).toFloat()
    }

    private fun columnSpread(img: Luma, x: Int, y0: Int, y1: Int) = spread(FloatArray(y1 - y0) { img[x, y0 + it] })

    /** Neighbouring pixels' typical difference below which there is nothing to compare (a black bar, a fade). */
    private const val MIN_DETAIL = 0.004f
    /** An edge column must have this much on it to be compared (a black bar has none). */
    private const val MIN_EDGE_SPREAD = 0.03f
    /** The seam may differ this many times what the columns beside it do (measured: a sphere 1.1, flat pictures 3–900). */
    private const val SEAM_RATIO = 2f
    /** A pole band changes along its rows at most this share of what the middle rows do (measured: spheres 0.14–0.2, flat 0.7–1.5). */
    private const val POLE_STRETCH = 0.35f
    /** A lens's black surround is no brighter than this, and even to within [EVEN]; the circle stands [STEP] above it. */
    private const val DARK = 0.2f
    private const val EVEN = 0.06f
    private const val STEP = 0.08f
    private const val RING_STEPS = 32
    /** Two eyes: their brightness in blocks lines up at least this well (measured: 0.97–0.99; two lenses 0.72)… */
    private const val STEREO_BRIGHTNESS = 0.9f
    /** …and their edges this well (measured: 0.58–0.74; a test card's halves 0.44). */
    private const val STEREO_EDGES = 0.5f
    private const val BLOCK = 4
}
