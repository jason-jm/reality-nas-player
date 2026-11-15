package com.therealityteam.videoplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.playback.ExoPlaybackManager
import com.therealityteam.videoplayer.data.playback.PlaybackState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** PGS (bitmap) subtitles through the whole chain: track listed, selectable, bitmap cues flow. */
@RunWith(AndroidJUnit4::class)
class PgsSubtitleProbe {
    @Test
    fun pgsTrackYieldsBitmapCues() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val fixture = File(ctx.filesDir, "pgs-sample.mkv")
        assumeTrue(fixture.exists())

        lateinit var engine: ExoPlaybackManager
        inst.runOnMainSync {
            engine = ExoPlaybackManager(ctx)
            engine.setup("file://${fixture.absolutePath}")
        }
        waitFor("PLAYING") { engine.state.value == PlaybackState.PLAYING }
        waitFor("pgs track listed") { engine.subtitleTracks.value.isNotEmpty() }
        println("PGSPROBE tracks=${engine.subtitleTracks.value}")
        val pgs = engine.subtitleTracks.value.first()
        inst.runOnMainSync { engine.selectSubtitleTrack(pgs.id) }
        // Bitmap cues appear whenever dialogue does; scrub forward until one lands.
        var found = false
        outer@ for (pos in listOf(10_000L, 40_000L, 80_000L, 120_000L, 180_000L, 240_000L)) {
            inst.runOnMainSync { engine.seekTo(pos) }
            val end = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < end) {
                if (engine.activeCues.value.any { it.bitmap != null }) { found = true; break@outer }
                Thread.sleep(150)
            }
        }
        println("PGSPROBE bitmapCue=$found")
        inst.runOnMainSync { engine.release() }
        assertTrue("a PGS bitmap cue should have flowed", found)
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(100) }
        throw AssertionError("timeout: $what")
    }
}
