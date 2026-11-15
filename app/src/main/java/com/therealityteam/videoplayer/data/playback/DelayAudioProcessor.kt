package com.therealityteam.videoplayer.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 音画同步 (Movist's audio delay) as a Media3 [AudioProcessor]. A positive [delayUs] makes the
 * sound LATER: that much silence is prepended to the stream. A negative one makes it EARLIER:
 * that much audio is dropped. Either shifts the audio content against the sink's reported
 * position — which the video clock follows — so picture and sound move apart by exactly the
 * delay. (Deliberately NOT reported as skipped frames: that hook exists to keep A/V together.)
 *
 * The offset goes in once, on the first buffer after it changes, as a delta from whatever is
 * already baked in. A flush (seek) re-anchors the sink's media time, so afterwards the full
 * delay is applied again. Only 16-bit PCM is handled; anything else is bypassed untouched.
 */
@OptIn(UnstableApi::class)
class DelayAudioProcessor : BaseAudioProcessor() {

    /** Positive = audio later, negative = audio earlier. Main thread writes, audio thread reads. */
    @Volatile
    var delayUs: Long = 0L

    private var appliedUs = 0L
    private var silenceFramesLeft = 0L
    private var dropFramesLeft = 0L
    private var bytesPerFrame = 0
    private var sampleRate = 0
    private val zeros = ByteArray(4096)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val target = delayUs
        if (target != appliedUs) {
            val deltaFrames = (target - appliedUs) * sampleRate / 1_000_000L
            if (deltaFrames > 0) silenceFramesLeft += deltaFrames else dropFramesLeft += -deltaFrames
            appliedUs = target
        }
        var remaining = inputBuffer.remaining()
        if (dropFramesLeft > 0 && remaining > 0) {
            val dropBytes = minOf(remaining.toLong(), dropFramesLeft * bytesPerFrame).toInt()
            inputBuffer.position(inputBuffer.position() + dropBytes)
            dropFramesLeft -= dropBytes / bytesPerFrame
            remaining -= dropBytes
        }
        val silenceBytes = (silenceFramesLeft * bytesPerFrame).toInt()
        if (remaining == 0 && silenceBytes == 0) return
        val out = replaceOutputBuffer(silenceBytes + remaining)
        var left = silenceBytes
        while (left > 0) {
            val n = minOf(left, zeros.size)
            out.put(zeros, 0, n)
            left -= n
        }
        silenceFramesLeft = 0L
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        // The sink re-anchors its media time after a flush: the whole delay must go back in.
        appliedUs = 0L
        silenceFramesLeft = 0L
        dropFramesLeft = 0L
    }

    override fun onReset() {
        onFlush()
    }
}
