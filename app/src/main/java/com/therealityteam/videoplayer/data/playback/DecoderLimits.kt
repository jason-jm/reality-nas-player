package com.therealityteam.videoplayer.data.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * What the headset's hardware decoders say of a picture its decoder failed on — asked only then, as the
 * flags are no forecast (a Dolby Vision track says no and plays as HEVC; some decoders under-report).
 * A picture software keeps up with goes to mpv as before; past that ([EngineRoute.softwareKeepsUp]) the
 * viewer is told what it is and what the hardware takes (PlaybackFailure.TOO_LARGE).
 */
object DecoderLimits {

    /** [format]'s picture when it is past software, with what the hardware said of it; else null. */
    @OptIn(UnstableApi::class)
    fun oversize(format: Format): Oversize? {
        val fps = format.frameRate.takeIf { it > 0f } ?: 0f
        if (EngineRoute.softwareKeepsUp(format.width, format.height, fps)) return null
        val mime = format.sampleMimeType
        // A two-layer MV-HEVC or a Dolby Vision track with no decoder of its own is decoded as the
        // codec under it (HEVC's base layer, Dolby Vision's HEVC or H.264): that decoder is the one to ask.
        val under = runCatching { MediaCodecUtil.getAlternativeCodecMimeType(format) }.getOrNull()
        val decoders = hardwareDecoders(mime).ifEmpty { under?.let(::hardwareDecoders).orEmpty() }
        return Oversize(format.width, format.height, fps, ExoPlaybackManager.codecName(mime), hardware(decoders, format.width, format.height, fps))
    }

    /** The video capabilities of the hardware decoders for [mime]. */
    private fun hardwareDecoders(mime: String?): List<MediaCodecInfo.VideoCapabilities> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.isHardwareAccelerated && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }.mapNotNull { info -> runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
    }.getOrDefault(emptyList())

    /** What hardware [decoders] say of [width]×[height] at [fps] (30 when unknown). */
    private fun hardware(decoders: List<MediaCodecInfo.VideoCapabilities>, width: Int, height: Int, fps: Float): Oversize.Hardware {
        val rate = if (fps > 0f) fps.toDouble() else 30.0
        return when {
            decoders.isEmpty() -> Oversize.Hardware.None
            decoders.any { it.areSizeAndRateSupported(width, height, rate) } -> Oversize.Hardware.Failed
            decoders.any { it.isSizeSupported(width, height) } -> decoders.filter { it.isSizeSupported(width, height) }
                .maxOf { caps -> runCatching { caps.getSupportedFrameRatesFor(width, height).upper }.getOrDefault(0.0) }.toInt()
                .let { most -> if (most > 0) Oversize.Hardware.Rate(most) else Oversize.Hardware.Failed }
            else -> decoders.mapNotNull { largestOfShape(it, width, height) }.maxByOrNull { (w, h) -> w.toLong() * h }
                ?.let { (w, h) -> Oversize.Hardware.Size(w, h) } ?: Oversize.Hardware.Size(0, 0)
        }
    }

    /** The largest picture of [width]:[height]'s shape [caps] takes, in steps of 16 pixels wide; null when none. */
    private fun largestOfShape(caps: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Pair<Int, Int>? {
        var w = minOf(width, caps.supportedWidths.upper) / 16 * 16
        while (w >= 16) {
            val x = w / caps.widthAlignment * caps.widthAlignment
            val y = (w.toLong() * height / width).toInt() / caps.heightAlignment * caps.heightAlignment
            if (x > 0 && y > 0 && caps.isSizeSupported(x, y)) return x to y
            w -= 16
        }
        return null
    }
}
