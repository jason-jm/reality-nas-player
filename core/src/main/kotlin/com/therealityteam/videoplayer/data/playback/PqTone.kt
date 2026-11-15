package com.therealityteam.videoplayer.data.playback

/**
 * An HDR10 picture's light (PQ, SMPTE ST 2084) as a plain picture's 8-bit value, for small pictures
 * made from an HDR film — read as they are coded, they come out grey and flat. The paper white of
 * 203 nits (BT.2408) becomes a plain picture's, highlights roll off up to 1000 nits (extended
 * Reinhard), then the display gamma. Pure, so it is tested.
 */
object PqTone {

    /** How finely [table] is stepped over the coded range. */
    const val STEPS = 1024

    /** Nits for PQ-coded [e] (0–1). */
    fun nits(e: Double): Double {
        val p = Math.pow(e.coerceIn(0.0, 1.0), 1 / M2)
        return Math.pow(maxOf(p - C1, 0.0) / (C2 - C3 * p), 1 / M1) * 10_000.0
    }

    /** The 8-bit value for PQ-coded [e] (0–1). */
    fun toSdr(e: Double): Int {
        val x = nits(e) / PAPER_WHITE
        val mapped = minOf(x * (1 + x / (PEAK * PEAK)) / (1 + x), 1.0)
        return (Math.pow(mapped, 1 / GAMMA) * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    /** [toSdr] over [STEPS] coded values, for a picture's worth of pixels. */
    val table: IntArray by lazy { IntArray(STEPS) { toSdr(it / (STEPS - 1.0)) } }

    private const val M1 = 0.1593017578125
    private const val M2 = 78.84375
    private const val C1 = 0.8359375
    private const val C2 = 18.8515625
    private const val C3 = 18.6875
    private const val PAPER_WHITE = 203.0
    private const val PEAK = 1000.0 / PAPER_WHITE
    private const val GAMMA = 2.2
}
