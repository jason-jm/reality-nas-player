package com.therealityteam.videoplayer.desktop

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.platform.MacNowPlaying
import com.therealityteam.videoplayer.desktop.platform.RemoteCommands
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * macOS's now playing, without a window: the commands registered, the information set and read
 * back, and — where the system lets a test send one — a play/pause arriving at the handler.
 *
 *   ./gradlew -p desktop test --tests '*NowPlayingProbe*' -Daura.nowplaying=1 -i
 */
class NowPlayingProbe {

    @Test
    fun registerAndUpdate() {
        assumeTrue("set -Daura.nowplaying=1 (macOS)", System.getProperty("aura.nowplaying") != null && Host.isMac)
        java.awt.Toolkit.getDefaultToolkit() // AppKit's run loop, which delivers the commands
        val calls = CopyOnWriteArrayList<String>()
        val np = MacNowPlaying(
            RemoteCommands(
                play = { calls += "play" }, pause = { calls += "pause" }, toggle = { calls += "toggle" },
                next = { calls += "next" }, previous = { calls += "previous" }, seekTo = { calls += "seek:$it" },
            ),
        )
        // -Daura.nowplaying.art=/a/poster.jpg: the poster, as the system asks the artwork for it.
        val art = System.getProperty("aura.nowplaying.art")
        np.update("敦刻尔克", "2017", art, 6_398_272, 600_000, playing = true, rate = 1.0)
        val info = np.describe()
        println("NOWPLAYING info: $info")
        assertTrue(info != null && info.contains("600") && info.contains("6398"), "the centre holds what was set")
        if (art != null) {
            val size = np.artworkImageSize(300.0, 450.0)
            println("NOWPLAYING artwork drawn through our block: ${size?.width}×${size?.height}")
            assertTrue(size != null && size.width > 0, "the artwork's block hands over the poster")
            // Another poster, then the first again: blocks reused or made, none dangling.
            np.update("Other", null, null, 1_000, 0, playing = false, rate = 1.0)
            np.update("敦刻尔克", "2017", art, 6_398_272, 600_000, playing = true, rate = 1.0)
            println("NOWPLAYING artwork again: ${np.artworkImageSize(100.0, 150.0)?.let { "${it.width}×${it.height}" }}")
        }

        // A play/pause as a media key would send it (MediaRemote, private; may be refused).
        val sent = runCatching {
            val mr = NativeLibrary.getInstance("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote")
            mr.getFunction("MRMediaRemoteSendCommand").invokeInt(arrayOf(2, Pointer.NULL))
        }
        println("NOWPLAYING MRMediaRemoteSendCommand(togglePlayPause) -> ${sent.getOrElse { "failed: $it" }}")
        Thread.sleep(2_000)
        println("NOWPLAYING commands received: $calls")
        np.clear()
        println("NOWPLAYING after clear: ${np.describe()}")
    }
}
