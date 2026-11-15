package com.therealityteam.videoplayer.data.playback

/**
 * How the flat video panel is fitted into the fixed window plane — Movist's 画面比例.
 * [AUTO] preserves the source's own aspect (letterboxing inside the window); [FILL] stretches
 * the picture across the whole plane (the old behaviour, correct only for 16:9 sources); the
 * rest force a specific display aspect, letter- or pillar-boxed to fit.
 */
enum class AspectMode(val label: String) {
    AUTO("自动"),
    FILL("拉伸"),
    R_16_9("16:9"),
    R_4_3("4:3"),
    R_2_35("2.35:1"),
}

/** Pure geometry for [AspectMode] — the panel mesh size within a window plane, no SDK deps. */
object AspectFit {
    /**
     * The shape one eye's picture is shown at, for a frame of [frameAspect] packed as [stereo]: a frame
     * wider than 2.5:1 holds two full-width pictures side by side (3840×1080, each 16:9), one near 16:9
     * two squeezed ones (half SBS, each shown 16:9 again); over-under the same, by height (narrower than
     * 1.25:1 is full height). The desktop's crop reads packings by the same rule (Projection.eye).
     */
    fun eyeAspect(stereo: com.therealityteam.videoplayer.data.model.StereoMode?, frameAspect: Float): Float = when {
        frameAspect <= 0f -> frameAspect
        stereo == com.therealityteam.videoplayer.data.model.StereoMode.SBS && frameAspect > FULL_SBS -> frameAspect / 2f
        stereo == com.therealityteam.videoplayer.data.model.StereoMode.OU && frameAspect < FULL_OU -> frameAspect * 2f
        else -> frameAspect
    }

    private const val FULL_SBS = 2.5f
    private const val FULL_OU = 1.25f

    /**
     * Panel width/height in meters for [mode], given the source [videoAspect] (width÷height,
     * ≤0 when a frame size isn't known yet) and the window plane [winW]×[winH] meters.
     *
     * Fit modes centre the panel inside the window so the base panel shows through as
     * letterbox (top/bottom) or pillarbox (sides) bars; [AspectMode.FILL] — and an [AUTO]
     * whose aspect isn't known yet — use the whole plane so nothing peeks out. [AUTO] shows the
     * picture without the black bars stored in its frame ([crop]): a scope film is a 2.39:1 panel.
     */
    fun panelSize(mode: AspectMode, videoAspect: Float, winW: Float, winH: Float, crop: FrameCrop = FrameCrop.NONE): Pair<Float, Float> {
        val target = when (mode) {
            AspectMode.FILL -> return winW to winH
            AspectMode.AUTO -> crop.aspectOf(videoAspect)
            AspectMode.R_16_9 -> 16f / 9f
            AspectMode.R_4_3 -> 4f / 3f
            AspectMode.R_2_35 -> 2.35f
        }
        if (target <= 0f || winW <= 0f || winH <= 0f) return winW to winH
        return if (target >= winW / winH) {
            winW to (winW / target)   // wider than the window → letterbox top & bottom
        } else {
            (winH * target) to winH   // taller than the window → pillarbox left & right
        }
    }
}
