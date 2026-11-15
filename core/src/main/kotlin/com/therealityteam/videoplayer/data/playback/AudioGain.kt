package com.therealityteam.videoplayer.data.playback

/**
 * Pure sample math for 音量增强 (Movist's volume boost above 100%). ExoPlayer's own volume
 * only attenuates (0–1); going louder means scaling the PCM samples ourselves and clipping
 * to the 16-bit range — a boost, so clipping on already-loud passages is expected.
 */
object AudioGain {
    const val MIN_16 = -32768
    const val MAX_16 = 32767

    /** The boost ladder offered in the UI; 1.0 is "off" (bit-exact passthrough). */
    val LEVELS: List<Float> = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    /** One 16-bit sample scaled by [gain] and hard-clipped to the 16-bit range. */
    fun apply(sample: Short, gain: Float): Short {
        val v = (sample * gain).toInt()
        return when {
            v > MAX_16 -> MAX_16.toShort()
            v < MIN_16 -> MIN_16.toShort()
            else -> v.toShort()
        }
    }
}
