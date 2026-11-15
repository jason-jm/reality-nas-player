package com.therealityteam.videoplayer

import com.sun.net.httpserver.HttpServer
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * A WebDAV server's file served on loopback ([LocalStreamServer.registerHttp]): with the login the server
 * asks for (the engines send none of their own), by range, and a text subtitle in UTF-8 whatever it was.
 */
class HttpRegisterTest {

    @Test fun `a WebDAV file comes through loopback with its login, by range, and its subtitle as UTF-8`() {
        val video = ByteArray(200_000) { (it % 251).toByte() }
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n".toByteArray(charset("GBK"))
        val login = "Basic " + java.util.Base64.getEncoder().encodeToString("tester:secret".toByteArray())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = when (ex.requestURI.rawPath) {
                "/Film%20(2020)/Film.mp4" -> video
                "/Film%20(2020)/Film.chs.srt" -> srt
                else -> null
            }
            when {
                ex.requestHeaders.getFirst("Authorization") != login -> ex.sendResponseHeaders(401, -1)
                body == null -> ex.sendResponseHeaders(404, -1)
                else -> {
                    val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
                    val from = range?.getOrNull(0)?.toLongOrNull() ?: 0L
                    val to = range?.getOrNull(1)?.toLongOrNull() ?: (body.size - 1L)
                    val part = body.copyOfRange(from.toInt(), (to + 1).toInt().coerceAtMost(body.size))
                    if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-${from + part.size - 1}/${body.size}")
                    ex.sendResponseHeaders(if (range != null) 206 else 200, part.size.toLong())
                    ex.responseBody.use { it.write(part) }
                }
            }
            ex.close()
        }
        server.start()
        val streams = LocalStreamServer()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val url = streams.registerHttp("$origin/Film%20(2020)/Film.mp4", "tester", "secret")
            assertEquals("Film.mp4", url.substringAfterLast('/'))
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=1000-1999")
            assertArrayEquals(video.copyOfRange(1000, 2000), conn.inputStream.use { it.readBytes() })

            val sub = streams.registerHttp("$origin/Film%20(2020)/Film.chs.srt", "tester", "secret")
            val text = URL(sub).openStream().use { it.readBytes() }.toString(Charsets.UTF_8)
            assertEquals(true, text.contains("中文字幕"))
        } finally {
            streams.shutdown()
            server.stop(0)
        }
    }
}
