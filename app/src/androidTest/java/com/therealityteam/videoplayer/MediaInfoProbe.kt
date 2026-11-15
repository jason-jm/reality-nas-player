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

/** mediaInfo() must yield display-ready video + audio lines from the real decoder formats. */
@RunWith(AndroidJUnit4::class)
class MediaInfoProbe {
    @Test
    fun infoReportsResolutionCodecAndAudio() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val fixture = File(ctx.filesDir, "engine-test.mkv") // 1280x720 H264 + AAC
        assumeTrue(fixture.exists())
        lateinit var engine: ExoPlaybackManager
        inst.runOnMainSync { engine = ExoPlaybackManager(ctx); engine.setup("file://${fixture.absolutePath}") }
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && engine.state.value != PlaybackState.PLAYING) Thread.sleep(100)
        Thread.sleep(800)
        val info = inst.runOnMainSync2 { engine.mediaInfo() }
        println("INFOPROBE video='${info?.video}' audio='${info?.audio}'")
        inst.runOnMainSync { engine.release() }
        assertTrue("video line has resolution+codec, got '${info?.video}'",
            info?.video?.contains("1280×720") == true && info.video!!.contains("AVC", true))
        assertTrue("audio line has codec, got '${info?.audio}'",
            info?.audio?.contains("AAC", true) == true)
    }
}

// runOnMainSync returns Unit; small helper to get a value back off the main thread.
private fun <T> android.app.Instrumentation.runOnMainSync2(block: () -> T): T {
    var r: T? = null
    runOnMainSync { r = block() }
    @Suppress("UNCHECKED_CAST")
    return r as T
}
