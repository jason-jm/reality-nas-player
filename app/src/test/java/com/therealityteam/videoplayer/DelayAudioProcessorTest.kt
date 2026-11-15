package com.therealityteam.videoplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.therealityteam.videoplayer.data.playback.DelayAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 48 kHz mono 16-bit: 1 frame = 2 bytes, 1 ms = 48 frames. */
class DelayAudioProcessorTest {

    private fun processor(): DelayAudioProcessor = DelayAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun pcm(frames: Int, value: Short = 1000): ByteBuffer =
        ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(frames) { putShort(value) }
            flip()
        }

    private fun outFrames(p: DelayAudioProcessor): Int = p.output.remaining() / 2

    @Test
    fun zeroDelayIsABitExactPassthrough() {
        val p = processor()
        p.queueInput(pcm(100, 1234))
        val out = p.output
        assertEquals(100, out.remaining() / 2)
        repeat(100) { assertEquals(1234.toShort(), out.short) }
    }

    @Test
    fun positiveDelayPrependsThatMuchSilenceOnce() {
        val p = processor()
        p.delayUs = 10_000            // +10 ms = 480 frames of silence, audio later
        p.queueInput(pcm(100))
        val out = p.output
        assertEquals(580, out.remaining() / 2)
        repeat(480) { assertEquals("silence first", 0.toShort(), out.short) }
        repeat(100) { assertEquals(1000.toShort(), out.short) }
        p.queueInput(pcm(100))
        assertEquals("applied once, not per buffer", 100, outFrames(p))
    }

    @Test
    fun negativeDelayDropsThatMuchAudioAcrossBuffers() {
        val p = processor()
        p.delayUs = -5_000            // −5 ms = drop 240 frames, audio earlier
        p.queueInput(pcm(100))        // all 100 dropped, 140 still owed
        assertEquals(0, outFrames(p))
        p.queueInput(pcm(1000))       // 140 more dropped
        assertEquals(860, outFrames(p))
        p.queueInput(pcm(1000))
        assertEquals("debt paid", 1000, outFrames(p))
    }

    @Test
    fun aChangeAppliesOnlyTheDelta() {
        val p = processor()
        p.delayUs = 10_000
        p.queueInput(pcm(100))
        assertEquals(580, outFrames(p))
        p.delayUs = 15_000            // +5 ms more → 240 extra frames of silence, not 720
        p.queueInput(pcm(100))
        assertEquals(340, outFrames(p))
        p.delayUs = 5_000             // back down 10 ms → drop 480
        p.queueInput(pcm(1000))
        assertEquals(520, outFrames(p))
    }

    @Test
    fun aFlushReAppliesTheFullDelay() {
        val p = processor()
        p.delayUs = 10_000
        p.queueInput(pcm(100))
        assertEquals(580, outFrames(p))
        p.flush()                     // a seek: the sink re-anchors, so the offset goes back in
        p.queueInput(pcm(100))
        assertEquals(580, outFrames(p))
    }
}
