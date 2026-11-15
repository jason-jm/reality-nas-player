package com.therealityteam.videoplayer.data.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 全景声: a sound-field track ([AmbisonicAudio] — the file's SA3D or Opus family 2) heard as the two
 * ears would, turned with the head and the picture ([SoundField]), through [AmbisonicDecoder]. First in
 * the sink's chain, so the delay, the boost and Sonic after it see plain stereo.
 *
 * The file's word comes from its header, read beside the player's own start ([ambisonic]) — for a
 * stream, only after it. A four-channel track is therefore always taken here and made stereo: decoded
 * as a field whenever the word has come (even mid-stream), mixed down as quad until then — so a late
 * word needs no restart. Other counts (a field with head-locked stereo, a higher order) are taken only
 * when the word was there as the track was configured; 5.1 and the rest pass by untouched.
 */
@OptIn(UnstableApi::class)
class AmbisonicAudioProcessor : BaseAudioProcessor() {

    /** The playing file's sound field, or null. Written on the main thread, read per buffer. */
    @Volatile
    var ambisonic: AmbisonicAudio? = null

    private var decoder: AmbisonicDecoder? = null
    private var channels = 0
    private var scratchIn = ShortArray(0)
    private var scratchOut = ShortArray(0)

    // Debugging (adb shell setprop log.tag.AuraAmbi DEBUG): the ears' levels about once a second.
    private var debug = false
    private var sumL = 0.0
    private var sumR = 0.0
    private var counted = 0
    private var rate = 48_000

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        val n = inputAudioFormat.channelCount
        val field = ambisonic
        if (n != 4 && (field == null || field.channels != n)) return AudioProcessor.AudioFormat.NOT_SET
        channels = n
        rate = inputAudioFormat.sampleRate
        decoder = AmbisonicDecoder(rate).also { it.snapTo(SoundField.rotation) }
        debug = android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)
        android.util.Log.i(TAG, "$n channels at $rate Hz: ${field?.let { "a sound field (${it.from}, order ${it.order}${if (it.headLocked) ", head-locked stereo" else ""})" } ?: "quad, until the file says"}")
        return AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channels)
        if (frames <= 0) return
        val samples = frames * channels
        if (scratchIn.size < samples) scratchIn = ShortArray(samples)
        if (scratchOut.size < 2 * frames) scratchOut = ShortArray(2 * frames)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratchIn, 0, samples)
        inputBuffer.position(inputBuffer.position() + samples * 2)
        val field = ambisonic?.takeIf { it.channels == channels }
        val d = decoder
        when {
            field != null && d != null -> d.decode(scratchIn, frames, field, SoundField.rotation, scratchOut)
            channels == 4 -> AmbisonicDecoder.downmixQuad(scratchIn, frames, scratchOut)
            // Taken as a field, the word since taken back: the first two channels.
            else -> for (f in 0 until frames) { scratchOut[2 * f] = scratchIn[f * channels]; scratchOut[2 * f + 1] = scratchIn[f * channels + 1] }
        }
        val out = replaceOutputBuffer(frames * 4)
        out.asShortBuffer().put(scratchOut, 0, 2 * frames)
        out.position(frames * 4)
        out.flip()
        if (debug) measure(frames, field != null)
    }

    private fun measure(frames: Int, decoded: Boolean) {
        for (f in 0 until frames) {
            sumL += scratchOut[2 * f] * scratchOut[2 * f].toDouble()
            sumR += scratchOut[2 * f + 1] * scratchOut[2 * f + 1].toDouble()
        }
        counted += frames
        if (counted < rate) return
        val db = { s: Double -> 20 * log10(sqrt(s / counted).coerceAtLeast(1.0) / 32768) }
        val r = SoundField.rotation
        android.util.Log.d(TAG, "%s L %.1f dB R %.1f dB, ahead is the film's (%.2f, %.2f, %.2f)".format(
            if (decoded) "field" else "quad", db(sumL), db(sumR), -r[6], -r[7], -r[8],
        ))
        sumL = 0.0; sumR = 0.0; counted = 0
    }

    /** A seek: the ears' filters start from silence, the field where it is now. */
    override fun onFlush() {
        decoder?.reset()
        decoder?.snapTo(SoundField.rotation)
    }

    override fun onReset() {
        decoder = null
        channels = 0
    }

    private companion object {
        const val TAG = "AuraAmbi"
    }
}
