package com.therealityteam.videoplayer

import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dev tool: what `HttpURLConnection.disconnect()` costs.
 *
 * `Http.fetch` closed every connection in a finally block, which takes the socket out of the
 * keep-alive pool, so each request paid a fresh TCP handshake and a fresh TLS negotiation. Measured
 * against the real hosts a handshake is most of a second, and a scrape makes thousands of requests.
 *
 * Interleaved A/B over many rounds, because a straight "N with, then N without" measures the
 * network's mood as much as the change: the first attempt at this said the two were identical, then
 * said disconnect was FASTER, on six requests each.
 */
class KeepAliveProbeTest {

    private fun once(url: String, disconnect: Boolean): Long {
        val t = System.nanoTime()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "Aura/1.0")
            c.responseCode
            runCatching { c.inputStream.bufferedReader().use { it.readText() } }
        } finally {
            if (disconnect) c.disconnect()
        }
        return (System.nanoTime() - t) / 1_000_000
    }

    @Test fun keepAlive() {
        if (System.getProperty("aura.keepalive") == null) return
        val url = System.getProperty("aura.keepalive.url") ?: "https://api.jikan.moe/v4/anime/1"
        val block = System.getProperty("aura.keepalive.block")?.toIntOrNull() ?: 10
        val rounds = System.getProperty("aura.keepalive.rounds")?.toIntOrNull() ?: 4
        repeat(3) { once(url, false) }
        val on = ArrayList<Long>()
        val off = ArrayList<Long>()
        // Pooling is a property of a SEQUENCE, not of one request: interleaving request-by-request
        // lets the disconnecting arm tear down the pool the other arm was about to reuse, which is
        // how an earlier run of this test concluded that disconnect() was the faster of the two.
        // Blocks, with the order swapped each round so neither arm always runs on a warm network.
        repeat(rounds) { r ->
            if (r % 2 == 0) {
                repeat(block) { on += once(url, true) }
                repeat(block) { off += once(url, false) }
            } else {
                repeat(block) { off += once(url, false) }
                repeat(block) { on += once(url, true) }
            }
        }
        fun median(x: List<Long>) = x.sorted()[x.size / 2]
        println("KA url=$url blocks=$rounds x $block")
        println("KA  disconnect()=开  中位数 ${median(on)}ms   平均 ${on.average().toInt()}ms   n=${on.size}")
        println("KA  disconnect()=关  中位数 ${median(off)}ms   平均 ${off.average().toInt()}ms   n=${off.size}")
    }
}
