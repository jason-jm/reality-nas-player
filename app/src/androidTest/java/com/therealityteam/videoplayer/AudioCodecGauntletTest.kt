package com.therealityteam.videoplayer

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.playback.ExoPlaybackManager
import com.therealityteam.videoplayer.data.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The release fixture carries the cinema audio formats together so track selection and decoding are tested on-device. */
@RunWith(AndroidJUnit4::class)
class AudioCodecGauntletTest {
    @Test
    fun measureAudioDecodeSurface() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val fixture = File(inst.targetContext.filesDir, "audio-gauntlet.mkv")
        assumeTrue("push audio-gauntlet.mkv first", fixture.exists())
        lateinit var engine: ExoPlaybackManager
        var activeDecoder = "" // Written and read on the player's looper.
        inst.runOnMainSync {
            engine = ExoPlaybackManager(inst.targetContext)
            engine.player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long,
                ) { activeDecoder = decoderName }
            })
        }
        try {
            inst.runOnMainSync { engine.setup(fixture.toURI().toString()) }
            fun awaitPlayer(message: String, predicate: () -> Boolean) {
                val end = SystemClock.elapsedRealtime() + 15_000
                do {
                    var ready = false
                    inst.runOnMainSync { ready = predicate() }
                    if (ready) return
                    Thread.sleep(100)
                } while (SystemClock.elapsedRealtime() < end && engine.state.value != PlaybackState.ERROR)
                var detail = ""
                inst.runOnMainSync {
                    val counters = engine.player.audioDecoderCounters
                    detail = "state=${engine.state.value}, decoder=$activeDecoder, " +
                        "position=${engine.player.currentPosition}, buffered=${engine.player.bufferedPosition}, " +
                        "input=${counters?.queuedInputBufferCount}, rendered=${counters?.renderedOutputBufferCount}, " +
                        "skipped=${counters?.skippedOutputBufferCount}"
                }
                throw AssertionError("$message ($detail)")
            }
            awaitPlayer("The audio fixture must start") { engine.state.value == PlaybackState.PLAYING }
            var groups = emptyList<androidx.media3.common.Tracks.Group>()
            inst.runOnMainSync { groups = engine.player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
            val expected = mapOf(
                MimeTypes.AUDIO_AC3 to "ac3", MimeTypes.AUDIO_E_AC3 to "eac3",
                MimeTypes.AUDIO_DTS to "dca", MimeTypes.AUDIO_TRUEHD to "truehd",
                MimeTypes.AUDIO_FLAC to "flac", MimeTypes.AUDIO_OPUS to "opus",
                MimeTypes.AUDIO_AAC to "aac", MimeTypes.AUDIO_VORBIS to "vorbis",
            )
            val present = groups.map { it.getTrackFormat(0).sampleMimeType }.toSet()
            assertTrue("Missing fixture tracks: ${expected.keys - present}", present.containsAll(expected.keys))
            for (group in groups) {
                val mime = group.getTrackFormat(0).sampleMimeType
                val codec = expected[mime] ?: continue
                assertTrue("$mime is unsupported", group.isTrackSupported(0))
                inst.runOnMainSync {
                    engine.player.trackSelectionParameters = engine.player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)).build()
                    engine.player.seekTo(1_000)
                }
                var renderedBefore = 0
                awaitPlayer("$mime was not selected on the FFmpeg renderer") {
                    val selected = engine.player.currentTracks.groups.any {
                        it.mediaTrackGroup == group.mediaTrackGroup && it.isTrackSelected(0)
                    }
                    val ready = selected && engine.player.audioFormat?.sampleMimeType == mime &&
                        activeDecoder.startsWith("ffmpeg") && activeDecoder.endsWith("-$codec")
                    if (ready) renderedBefore = engine.player.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                    ready
                }
                awaitPlayer("$mime produced no additional decoded audio buffers") {
                    val rendered = engine.player.audioDecoderCounters?.renderedOutputBufferCount ?: 0
                    rendered > renderedBefore && engine.player.currentPosition > 1_100
                }
                assertEquals(PlaybackState.PLAYING, engine.state.value)
                android.util.Log.i("AuraAudioTest", "$mime $activeDecoder rendered additional buffers")
            }
        } finally {
            inst.runOnMainSync { engine.release() }
        }
    }
}
