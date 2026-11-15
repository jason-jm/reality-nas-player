package com.therealityteam.videoplayer.data.playback

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the loopback proxy serves: a video's bytes as they are, a text subtitle as UTF-8. */
class LocalStreamServerTest {
    private val server = LocalStreamServer()

    @After
    fun stop() = server.shutdown()

    private class Bytes(val data: ByteArray) : LocalStreamServer.Source {
        override fun size() = data.size.toLong()
        override fun copyRange(start: Long, length: Long, out: OutputStream) = out.write(data, start.toInt(), length.toInt())
        override fun close() {}
    }

    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
        val c = URL(url.replace(" ", "%20")).openConnection() as HttpURLConnection
        if (range != null) c.setRequestProperty("Range", range)
        return c.responseCode to c.inputStream.use { it.readBytes() }
    }

    private val cue = "1\n00:00:01,000 --> 00:00:04,000\n葬送的芙莉莲：勇者辛美尔死后\n\n"

    @Test
    fun `a GBK subtitle goes out as UTF-8, whole or by range`() {
        val url = server.register("s1", "Frieren 01.chs.srt", Bytes(cue.toByteArray(Charset.forName("GBK"))))
        val (code, body) = get(url)
        assertEquals(200, code)
        assertEquals(cue, body.toString(Charsets.UTF_8))
        val utf8 = cue.toByteArray(Charsets.UTF_8)
        val (partial, part) = get(url, "bytes=10-")
        assertEquals(206, partial)
        assertArrayEquals("a range is of the UTF-8 text", utf8.copyOfRange(10, utf8.size), part)
    }

    @Test
    fun `UTF-8 subtitles and videos go out as they are`() {
        val utf8 = cue.toByteArray(Charsets.UTF_8)
        assertArrayEquals(utf8, get(server.register("s2", "Frieren 01.srt", Bytes(utf8))).second)
        // A video's bytes are never read as text, whatever they look like.
        val video = cue.toByteArray(Charset.forName("GBK")) + ByteArray(64) { it.toByte() }
        assertArrayEquals(video, get(server.register("v1", "Frieren 01.mkv", Bytes(video))).second)
    }
}
