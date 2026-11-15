package com.therealityteam.videoplayer.data.playback

/**
 * The part of a frame that is black bars, as fractions of its width and height — a scope film
 * (2.39:1) stored in a 16:9 frame has 12.8 % of black above and below the picture. The panel shows
 * the rest ([crops]) at its own shape instead: a cinema's screen, not a television's.
 */
data class FrameCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** Whether there is anything to cut. */
    val crops: Boolean get() = left > 0f || top > 0f || right > 0f || bottom > 0f

    /** The picture's aspect once the bars are off, from the whole frame's [aspect]. */
    fun aspectOf(aspect: Float): Float =
        if (aspect <= 0f) aspect else aspect * (1f - left - right) / (1f - top - bottom)

    companion object {
        val NONE = FrameCrop(0f, 0f, 0f, 0f)
    }
}

/**
 * Finds black bars in frames sampled across a film ([detect]): rows (and columns) black in EVERY
 * sample are bars; a single frame lit there — an IMAX scene opening up, a title over the bar — keeps
 * that edge whole. Bars come in pairs, centred: black at the top alone is a night sky, not a bar.
 * Frames that are black throughout (a fade, a night shot) say nothing and are left out. Pure, so it
 * is tested; the player hands it luma from frames it decoded itself.
 */
object Letterbox {

    /** One sampled frame: [width]×[height] luma values (0–255), row by row. */
    class Frame(val width: Int, val height: Int, val luma: IntArray)

    /**
     * The bars every one of [frames] agrees on, or [FrameCrop.NONE]. Needs [MIN_FRAMES] frames that
     * show something; an edge is cut only when its bar is at least [MIN_BAR] of the frame — a
     * hairline of a 1.85:1 film in a 16:9 frame is not worth a panel of another shape. Given the
     * frame's [aspect], what is left must be a picture's shape — scope, flat, 4:3 — or nothing is
     * cut: dark sky over a night scene in every sample is no bar.
     */
    fun detect(frames: List<Frame>, aspect: Float = 0f): FrameCrop {
        val lit = frames.filter { f -> (0 until f.height).count { !blackRow(f, it) } > f.height / 10 }
        if (lit.size < MIN_FRAMES) return FrameCrop.NONE
        fun edge(measure: (Frame) -> Float): Float = lit.minOf(measure).let { if (it >= MIN_BAR) it else 0f }
        val top = edge { f -> (0 until f.height).takeWhile { blackRow(f, it) }.count() / f.height.toFloat() }
        val bottom = edge { f -> (f.height - 1 downTo 0).takeWhile { blackRow(f, it) }.count() / f.height.toFloat() }
        val left = edge { f -> (0 until f.width).takeWhile { blackColumn(f, it) }.count() / f.width.toFloat() }
        val right = edge { f -> (f.width - 1 downTo 0).takeWhile { blackColumn(f, it) }.count() / f.width.toFloat() }
        // Bars that leave too little picture are a mistake, not a film: keep the frame whole.
        if (top + bottom > MAX_CUT || left + right > MAX_CUT) return FrameCrop.NONE
        // A picture is centred in its frame: one bar alone, or two that differ, is the picture's own dark.
        fun pair(a: Float, b: Float) = if (a > 0f && b > 0f && kotlin.math.abs(a - b) <= SYMMETRY) a to b else 0f to 0f
        val (t, b) = pair(top, bottom)
        val (l, r) = pair(left, right)
        val crop = FrameCrop(l, t, r, b)
        if (aspect > 0f && crop.crops) {
            val picture = crop.aspectOf(aspect)
            if (PICTURE_SHAPES.none { kotlin.math.abs(picture / it - 1f) <= SHAPE_TOLERANCE }) return FrameCrop.NONE
        }
        return crop
    }

    /** The shapes films and shows are made in: 4:3, Academy, 1.66, 16:9, 1.85, Univisium, 2.20, scope… */
    private val PICTURE_SHAPES = listOf(1.33f, 1.37f, 1.43f, 1.5f, 1.66f, 1.78f, 1.85f, 1.9f, 2f, 2.2f, 2.35f, 2.39f, 2.55f, 2.76f)
    private const val SHAPE_TOLERANCE = 0.03f

    private fun blackRow(f: Frame, y: Int): Boolean {
        var sum = 0
        for (x in 0 until f.width) {
            val v = f.luma[y * f.width + x]
            if (v > PEAK) return false
            sum += v
        }
        return sum <= MEAN * f.width
    }

    private fun blackColumn(f: Frame, x: Int): Boolean {
        var sum = 0
        for (y in 0 until f.height) {
            val v = f.luma[y * f.width + x]
            if (v > PEAK) return false
            sum += v
        }
        return sum <= MEAN * f.height
    }

    /** No pixel of a bar brighter than this — a bar's black is never quite 0 after compression. */
    private const val PEAK = 40
    /** …and on average no brighter than this. */
    private const val MEAN = 20
    private const val MIN_FRAMES = 3
    private const val MIN_BAR = 0.03f
    private const val MAX_CUT = 0.5f
    private const val SYMMETRY = 0.02f
}
