package com.therealityteam.videoplayer.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 音量增强 and 夜间模式 as a Media3 [AudioProcessor]: 16-bit PCM through [Loudness] on the audio
 * thread — the boost, the night mode's compressor, and the soft limiter that keeps either from
 * clipping. Both off is a bit-exact copy. Other encodings (float / 24-bit, should a decoder ever
 * emit them) are bypassed rather than refused, so this stage can never make the sink fail to
 * configure. It only scales samples — no frames are inserted or dropped — so the audio clock that
 * keeps video in sync is untouched.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {

    /** Written on the main thread, read once per buffer on the audio thread. */
    @Volatile
    var gain: Float = 1.0f

    /** 夜间模式, likewise. */
    @Volatile
    var night: Boolean = false

    private var loudness: Loudness? = null
    private var channels = 1
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            loudness = Loudness(inputAudioFormat.sampleRate, channels)
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET // inactive → the sink passes audio straight through
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - position
        if (size <= 0) return
        val out = replaceOutputBuffer(size)
        val g = gain
        val n = night
        val l = loudness
        if ((g == 1.0f && !n) || l == null) {
            out.put(inputBuffer)
        } else {
            val count = size / 2
            if (scratch.size < count) scratch = ShortArray(count)
            val buf = scratch
            for (k in 0 until count) buf[k] = inputBuffer.getShort(position + 2 * k)
            l.gain = g
            l.night = n
            l.process(buf, count / channels)
            for (k in 0 until count) out.putShort(buf[k])
            inputBuffer.position(limit)
        }
        out.flip()
    }

    /** A seek: the compressor starts from silence rather than from the scene left behind. */
    override fun onFlush() {
        loudness?.reset()
    }
}
