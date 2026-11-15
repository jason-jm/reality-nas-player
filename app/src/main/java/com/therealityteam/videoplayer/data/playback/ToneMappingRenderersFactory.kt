package com.therealityteam.videoplayer.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * HDR sources played flat and washed-out: the spatial panel samples the video texture as
 * SDR, so a PQ/HLG signal (HDR10 anime remuxes, e.g. SEED Freedom 2160p) loses its
 * contrast and saturation — Movist next to it looks vivid, we look grey.
 *
 * The standard Android answer is to ask the DECODER to tone-map down to SDR
 * (MediaFormat.KEY_COLOR_TRANSFER_REQUEST, API 31+): the codec converts BT.2020/PQ to
 * BT.709/SDR before the frame ever reaches the Surface, and the panel's SDR sampling is
 * then correct. Codecs that cannot tone-map ignore the key. It is asked only of an HDR picture:
 * asked of an SDR one, a Qualcomm HEVC decoder gave no picture at all (see getMediaFormat).
 *
 * The audio side adds three stages at the head of the sink's processor chain: [ambisonic]
 * (全景声, a sound field to the ears), [delay] (音画同步) and then [gain] (音量增强).
 */
@OptIn(UnstableApi::class)
class ToneMappingRenderersFactory(
    context: Context,
    private val delay: DelayAudioProcessor,
    private val gain: GainAudioProcessor,
    private val ambisonic: AmbisonicAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            object : MediaCodecVideoRenderer(
                context,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ) {
                override fun getMediaFormat(
                    format: Format,
                    codecMimeType: String,
                    codecMaxValues: CodecMaxValues,
                    codecOperatingRate: Float,
                    deviceNeedsNoPostProcessWorkaround: Boolean,
                    tunnelingAudioSessionId: Int,
                ): MediaFormat {
                    val mediaFormat = super.getMediaFormat(
                        format, codecMimeType, codecMaxValues, codecOperatingRate,
                        deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId,
                    )
                    // SDR asked for only of an HDR picture (PQ, HLG — Media3 reads it from the container's colour
                    // box or the HEVC/AVC stream's own VUI). Asked of every picture, it broke HEVC on a PICO
                    // B3110 (Qualcomm, OS 6.2): the decoder routes even SDR frames through its tone-mapping
                    // engine, which does not start for an 8-bit picture ("Failed to start the transformer
                    // engine") — no picture from any HEVC film, 1080p included, until the no-picture watchdog
                    // gave it to mpv's software decoder seconds later. HDR10 tone-maps there as it should.
                    val transfer = format.colorInfo?.colorTransfer
                    if (transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG) {
                        mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        android.util.Log.i("AuraToneMap", "$codecMimeType ${format.width}x${format.height}: HDR (${if (transfer == C.COLOR_TRANSFER_HLG) "HLG" else "PQ"}), SDR asked of the decoder")
                    }
                    return mediaFormat
                }
            },
        )
        // Keep the parent's extension video renderers (none today) out of the picture.
    }

    /**
     * The parent's sink with our 全景声 + 音画同步 + 音量增强 stages first in the chain. DefaultAudioProcessorChain
     * still appends its own silence-skipping and Sonic (speed) processors after ours, and every
     * audio renderer — MediaCodec and the FFmpeg extension alike — is handed this one sink, so
     * the boost reaches AC3/DTS/TrueHD tracks too.
     */
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(ambisonic, delay, gain))
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
