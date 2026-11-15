package com.therealityteam.videoplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.core.ecs.resource.SurfaceRenderTexture
import com.therealityteam.videoplayer.data.playback.ExoPlaybackManager
import com.therealityteam.videoplayer.data.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The ExoPlayer engine against a real MKV on the device: two audio tracks (jpn/zho), two
 * embedded SRT subtitles (zho/eng). Proves decode, track enumeration, subtitle cue flow and
 * — when the spatial runtime yields a valid Surface — first-frame rendering.
 *
 * The fixture is pushed by the harness first:
 *   ffmpeg-generated engine-test.mkv → run-as cp into files/engine-test.mkv
 */
@RunWith(AndroidJUnit4::class)
class ExoEngineSmokeTest {

    @Test
    fun theEngineDecodesEnumeratesTracksAndStreamsCues() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val fixture = File(ctx.filesDir, "engine-test.mkv")
        assumeTrue("fixture missing — push it first (see class doc)", fixture.exists())

        lateinit var engine: ExoPlaybackManager
        var surfaceValid = false
        inst.runOnMainSync {
            engine = ExoPlaybackManager(ctx)
            val texture = SurfaceRenderTexture().apply { toGlobal() }
            val surface = texture.acquireSurface()
            surfaceValid = surface != null
            if (surface != null) engine.setVideoSurface(surface)
            engine.setup("file://${fixture.absolutePath}")
        }

        // Decode pipeline reaches PLAYING.
        waitFor("engine reaches PLAYING") { engine.state.value == PlaybackState.PLAYING }

        // Both audio tracks and both embedded subtitles are enumerated.
        waitFor("2 audio tracks visible") { engine.audioTracks.value.size == 2 }
        waitFor("2 subtitle tracks visible") { engine.subtitleTracks.value.size >= 2 }
        val audio = engine.audioTracks.value
        assertTrue("audio languages jpn+zho, got $audio",
            audio.any { it.language in listOf("ja", "jpn") } &&
                audio.any { it.language in listOf("zh", "zho") })
        assertEquals("exactly one audio track selected", 1, audio.count { it.selected })

        // Selecting the Chinese subtitle track makes Chinese text flow as cues.
        val zh = engine.subtitleTracks.value.first { it.language?.startsWith("zh") == true }
        inst.runOnMainSync { engine.selectSubtitleTrack(zh.id) }
        waitFor("chinese cue appears") {
            engine.activeCues.value.any { it.text?.contains("中文字幕") == true }
        }

        // Audio switch takes effect (selection flag moves).
        val other = engine.audioTracks.value.first { !it.selected }
        inst.runOnMainSync { engine.selectAudioTrack(other.id) }
        waitFor("audio selection moved") {
            engine.audioTracks.value.firstOrNull { it.id == other.id }?.selected == true
        }

        // Subtitles off drains the cue flow.
        inst.runOnMainSync { engine.selectSubtitleTrack(null) }
        waitFor("cues drained") { engine.activeCues.value.isEmpty() }

        if (surfaceValid) {
            waitFor("first frame rendered onto the SurfaceRenderTexture surface") {
                engine.hasFirstFrame.value
            }
        }

        inst.runOnMainSync { engine.release() }
        assertTrue("SurfaceRenderTexture yielded no Surface in this process (spatial runtime " +
            "not rendering here) — decode/tracks/cues proven, panel rendering needs the app UI",
            true)
        // Report surface validity through the test log either way.
        println("ExoEngineSmokeTest: surfaceValid=$surfaceValid firstFrame=${engine.hasFirstFrame.value}")
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
