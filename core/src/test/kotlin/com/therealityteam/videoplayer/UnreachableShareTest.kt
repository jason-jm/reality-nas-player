package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * A film on a share that cannot be reached (the NAS off): the loopback says so as such (504), and asked again a
 * moment later says so at once rather than trying the share again.
 */
class UnreachableShareTest {

    @Test fun `a share that cannot be reached is a 504, and the next ask does not try it again yet`() {
        val streams = LocalStreamServer()
        try {
            // Nothing listens on this machine's SMB port (the test needs that: it is checked here).
            val probe = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 445), 500) } }
            org.junit.Assume.assumeTrue("port 445 is in use here", probe.isFailure)
            val url = streams.register("smb://127.0.0.1/video/Film.2019.mkv", null, null)!!
            fun ask(): Pair<Int, Long> {
                val t = System.nanoTime()
                val code = (URL(url).openConnection() as HttpURLConnection).run { connectTimeout = 10_000; readTimeout = 10_000; responseCode }
                return code to (System.nanoTime() - t) / 1_000_000
            }
            val (first, _) = ask()
            assertEquals(504, first)
            val (second, ms) = ask()
            assertEquals(504, second)
            assertTrue("asked again at once: $ms ms", ms < 300)
        } finally {
            streams.shutdown()
        }
    }
}
