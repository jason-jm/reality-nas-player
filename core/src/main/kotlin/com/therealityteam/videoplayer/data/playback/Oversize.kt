package com.therealityteam.videoplayer.data.playback

/**
 * A picture the headset cannot decode in time (PlaybackFailure.TOO_LARGE): its size, its rate (0 when
 * the file does not say), its codec as people name it — and what the hardware decoders said of it, so
 * the viewer knows what version of the film would play.
 */
data class Oversize(val width: Int, val height: Int, val fps: Float, val codec: String?, val hardware: Hardware) {

    sealed interface Hardware {
        /** No hardware decoder for the codec at all. */
        data object None : Hardware

        /**
         * Past the largest picture the hardware takes: the largest of the same shape it does
         * ([width]×[height]), or none at all of that shape (0×0).
         */
        data class Size(val width: Int, val height: Int) : Hardware

        /** The size, but not the rate: the most frames a second the hardware decodes at that size. */
        data class Rate(val fps: Int) : Hardware

        /** The hardware said it could, and gave no picture. */
        data object Failed : Hardware
    }
}
