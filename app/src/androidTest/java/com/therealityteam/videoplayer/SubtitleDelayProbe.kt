package com.therealityteam.videoplayer

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

/**
 * The first embedded line spans 0.5–4.0 s. Retime it on the same frame and track,
 * then verify that it appears and disappears on the new schedule during playback.
 */
@RunWith(AndroidJUnit4::class)
class SubtitleDelayProbe {
    @Test
    fun adjustingDelayPreservesTheFrameAndMovesTheCue() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val fixture = File(ctx.filesDir, "engine-test.mkv")
        assumeTrue(fixture.exists())

        lateinit var engine: ExoPlaybackManager
        inst.runOnMainSync {
            engine = ExoPlaybackManager(ctx)
            engine.setup("file://${fixture.absolutePath}")
        }
        fun hasLine() = engine.activeCues.value.any { it.text?.contains("中文字幕") == true }
        fun position(): Long {
            var positionMs = 0L
            inst.runOnMainSync { positionMs = engine.positionNow() }
            return positionMs
        }
        fun applyDelay(us: Long) = inst.runOnMainSync {
            engine.subtitleDelayUs = us
            engine.refreshSubtitleTiming()
        }
        try {
            waitFor("PLAYING") { engine.state.value == PlaybackState.PLAYING }
            waitFor("zh track") { engine.subtitleTracks.value.any { it.language?.startsWith("zh") == true } }
            val zh = engine.subtitleTracks.value.first { it.language?.startsWith("zh") == true }
            inst.runOnMainSync { engine.selectSubtitleTrack(zh.id); engine.pause(); engine.seekTo(1200) }
            waitFor("original cue at 1.2 s") { hasLine() && engine.state.value == PlaybackState.PAUSED }
            inst.runOnMainSync { engine.play() }
            waitFor("late in the same subtitle") { position() in 3200..3500 && hasLine() }
            inst.runOnMainSync { engine.pause() }
            var heldFrame = -1L
            waitFor("pause clock settles inside the long cue") {
                val current = position()
                val stable = current == heldFrame && hasLine() && engine.state.value == PlaybackState.PAUSED
                heldFrame = current
                stable
            }
            applyDelay(300_000L)
            waitFor("cue still covers the paused frame with a small positive delay") {
                hasLine() && engine.state.value == PlaybackState.PAUSED
            }
            assertEquals(heldFrame, position())
            applyDelay(5_000_000L)
            waitFor("a large delay clears the paused frame") { !hasLine() && engine.state.value == PlaybackState.PAUSED }
            applyDelay(0)
            waitFor("reset restores the long cue on the same paused frame") { hasLine() && engine.state.value == PlaybackState.PAUSED }
            assertEquals(heldFrame, position())
            inst.runOnMainSync { engine.seekTo(1200) }
            applyDelay(3_000_000L)
            waitFor("paused with the delayed track") {
                engine.state.value == PlaybackState.PAUSED && engine.subtitleTracks.value.any { it.id == zh.id && it.selected }
            }
            assertEquals(1200L, position())
            assertTrue("the cue should now start at 3.5 s", !hasLine())
            inst.runOnMainSync { engine.play() }
            waitFor("the original cue is delayed during playback") { position() in 4000..6500 && hasLine() }
            applyDelay(0)
            waitFor("still playing after restoring the delay") {
                engine.state.value == PlaybackState.PLAYING && position() > 4500 && !hasLine()
            }
            inst.runOnMainSync { engine.pause(); engine.seekTo(400) }
            waitFor("before the authored cue") { engine.state.value == PlaybackState.PAUSED && position() == 400L && !hasLine() }
            applyDelay(-300_000L)
            waitFor("negative delay advances the line onto the paused frame") {
                engine.state.value == PlaybackState.PAUSED && hasLine()
            }
            assertEquals(400L, position())
        } finally {
            inst.runOnMainSync { engine.release() }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(100) }
        throw AssertionError("timeout: $what")
    }
}
