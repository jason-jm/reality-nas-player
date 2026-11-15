package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.ByteSource
import com.therealityteam.videoplayer.data.playback.Chapter
import com.therealityteam.videoplayer.data.playback.ChapterReader
import com.therealityteam.videoplayer.data.playback.HttpByteSource
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.playback.indexAt
import com.therealityteam.videoplayer.data.playback.MatroskaAttachments
import com.therealityteam.videoplayer.data.playback.MatroskaPgs
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress

/** Builds tiny Matroska and MP4 containers in memory and reads their chapters back. */
class ChapterReaderTest {

    private class Bytes(private val b: ByteArray) : ByteSource {
        override val length: Long get() = b.size.toLong()
        override fun read(offset: Long, size: Int): ByteArray {
            if (offset >= b.size) return ByteArray(0)
            return b.copyOfRange(offset.toInt(), minOf(b.size, offset.toInt() + size))
        }
    }

    // ---- EBML helpers -----------------------------------------------------------------------

    private fun id(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    private fun vint(size: Long): ByteArray {
        // 8-byte size marker keeps the encoder trivial: 0x01 then 7 bytes of big-endian size.
        val out = ByteArray(8)
        out[0] = 0x01
        var s = size
        for (i in 7 downTo 1) { out[i] = (s and 0xFF).toByte(); s = s shr 8 }
        return out
    }

    private fun el(id: ByteArray, payload: ByteArray): ByteArray = id + vint(payload.size.toLong()) + payload

    private fun uint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var started = false
        for (i in 7 downTo 0) {
            val b = ((v shr (i * 8)) and 0xFF).toInt()
            if (b != 0 || started || i == 0) { out.write(b); started = true }
        }
        return out.toByteArray()
    }

    private fun atom(startNs: Long, title: String, lang: String = "und", hidden: Boolean = false): ByteArray {
        val display = el(id(0x80), el(id(0x85), title.toByteArray(Charsets.UTF_8)) + el(id(0x43, 0x7C), lang.toByteArray()))
        var body = el(id(0x91), uint(startNs)) + display
        if (hidden) body += el(id(0x98), uint(1))
        return el(id(0xB6), body)
    }

    private fun mkv(chapters: ByteArray, chaptersFirst: Boolean, clusterBytes: Int = 16): ByteArray {
        val ebml = el(id(0x1A, 0x45, 0xDF, 0xA3), el(id(0x42, 0x82), "matroska".toByteArray()))
        val cluster = el(id(0x1F, 0x43, 0xB5, 0x75), ByteArray(clusterBytes))
        val segmentBody = if (chaptersFirst) chapters + cluster else {
            // Chapters after the cluster, reachable only through the SeekHead. The position is
            // written as a fixed 8-byte integer so the SeekHead's own size does not depend on it.
            val seekHead = { pos: Long ->
                el(id(0x11, 0x4D, 0x9B, 0x74), el(id(0x4D, 0xBB), el(id(0x53, 0xAB), id(0x10, 0x43, 0xA7, 0x70)) + el(id(0x53, 0xAC), uint(pos).let { u -> ByteArray(8 - u.size) + u })))
            }
            val pos = seekHead(0).size.toLong() + cluster.size
            seekHead(pos) + cluster + chapters
        }
        return ebml + el(id(0x18, 0x53, 0x80, 0x67), segmentBody)
    }

    private fun chaptersElement(vararg atoms: ByteArray): ByteArray =
        el(id(0x10, 0x43, 0xA7, 0x70), el(id(0x45, 0xB9), atoms.fold(ByteArray(0)) { a, b -> a + b }))

    private fun pgsFile(): Bytes {
        fun entry(number: Int) = el(id(0xAE), el(id(0xD7), uint(number.toLong())) +
            el(id(0x86), "S_HDMV/PGS".toByteArray()))
        val tracks = el(id(0x16, 0x54, 0xAE, 0x6B), entry(3) + entry(4))
        val info = el(id(0x15, 0x49, 0xA9, 0x66), el(id(0x2A, 0xD7, 0xB1), uint(2_000_000)))
        val clock = el(id(0xE7), uint(10))
        // Two tracks in the same cluster. The first block starts two ticks before its cluster.
        val one = el(id(0xA3), id(0x83, 0xff, 0xfe, 0x80, 11))
        val other = el(id(0xA3), id(0x84, 0, 0, 0x80, 22))
        val timed = el(id(0xA0), el(id(0xA1), id(0x83, 0, 10, 0, 33)) + el(id(0x9B), uint(3)))
        val cluster = el(id(0x1F, 0x43, 0xB6, 0x75), clock + one + other + timed)
        fun cues(clusterOffset: Int): ByteArray {
            fun point(ticks: Int, track: Int, relative: Int?): ByteArray {
                val position = el(id(0xF1), java.nio.ByteBuffer.allocate(8).putLong(clusterOffset.toLong()).array())
                val fields = el(id(0xF7), uint(track.toLong())) + position +
                    (relative?.let { el(id(0xF0), uint(it.toLong())) } ?: byteArrayOf())
                return el(id(0xBB), el(id(0xB3), uint(ticks.toLong())) + el(id(0xB7), fields))
            }
            return el(id(0x1C, 0x53, 0xBB, 0x6B), point(8, 3, clock.size) + point(10, 4, null) + point(20, 3, null))
        }
        val body = info + tracks + cues(info.size + tracks.size + cues(0).size) + cluster
        return Bytes(el(id(0x1A, 0x45, 0xDF, 0xA3), byteArrayOf()) + el(id(0x18, 0x53, 0x80, 0x67), body))
    }

    @Test
    fun `indexed PGS survives mid-cue seeks and stops at the block duration`() {
        val source = pgsFile()
        val track = MatroskaPgs.open(source, 3)!!
        assertEquals(null, track.displayAt(source, 15_999))
        assertEquals(11, track.displayAt(source, 16_000)!!.packet.single().toInt())
        assertEquals(33, track.displayAt(source, 45_999)!!.packet.single().toInt())
        assertTrue(track.displayAt(source, 46_000)!!.packet.isEmpty())
        assertEquals(40_000L, track.displayAt(source, 39_999)!!.endUs)
        assertEquals(11, track.displayAt(source, 18_000)!!.packet.single().toInt())
    }

    @Test
    fun `a PGS seek uses only the selected track including when CueRelativePosition is absent`() {
        val source = pgsFile()
        assertEquals(null, MatroskaPgs.open(source, 1))
        val track = MatroskaPgs.open(source, 4)!!
        assertEquals(null, track.displayAt(source, 19_999))
        assertEquals(22, track.displayAt(source, 100_000)!!.packet.single().toInt())
    }

    private fun attachment(name: String, mime: String, data: ByteArray): ByteArray =
        el(id(0x61, 0xA7), el(id(0x46, 0x6E), name.toByteArray(Charsets.UTF_8)) + el(id(0x46, 0x60), mime.toByteArray()) + el(id(0x46, 0x5C), data))

    @Test
    fun `an mkv's attached fonts are found where they lie, the cover told apart`() {
        val font = ByteArray(300) { it.toByte() }
        val attachments = el(
            id(0x19, 0x41, 0xA4, 0x69),
            attachment("方正准圆_GBK.ttf", "application/x-truetype-font", font) +
                attachment("FOT-SkipProN-D.otf", "application/vnd.ms-opentype", ByteArray(40)) +
                attachment("cover.jpg", "image/jpeg", ByteArray(20)),
        )
        val bytes = mkv(attachments, chaptersFirst = true)
        val found = MatroskaAttachments.list(Bytes(bytes))
        assertEquals(listOf("方正准圆_GBK.ttf", "FOT-SkipProN-D.otf", "cover.jpg"), found.map { it.name })
        assertEquals(listOf(true, true, false), found.map { it.isFont })
        val first = found[0]
        assertEquals(300L, first.size)
        assertTrue(bytes.copyOfRange(first.dataOffset.toInt(), (first.dataOffset + first.size).toInt()).contentEquals(font))
        // No Attachments element: none.
        assertEquals(0, MatroskaAttachments.list(Bytes(mkv(chaptersElement(atom(0, "x")), chaptersFirst = true))).size)
    }

    @Test
    fun `matroska chapters before the first cluster`() {
        val file = mkv(chaptersElement(atom(0, "片头"), atom(9_000_000_000, "第一幕"), atom(19_000_000_000, "第二幕")), chaptersFirst = true)
        assertEquals(
            listOf(Chapter(0, "片头"), Chapter(9_000, "第一幕"), Chapter(19_000, "第二幕")),
            ChapterReader.read(Bytes(file)),
        )
    }

    @Test
    fun `matroska chapters found through the seek head when stored after the clusters`() {
        val file = mkv(chaptersElement(atom(0, "Intro"), atom(60_000_000_000, "Act One")), chaptersFirst = false)
        assertEquals(listOf(Chapter(0, "Intro"), Chapter(60_000, "Act One")), ChapterReader.read(Bytes(file)))
    }

    @Test
    fun `hidden chapters are skipped and a chinese display wins`() {
        val twoNames = el(
            id(0xB6),
            el(id(0x91), uint(5_000_000_000)) +
                el(id(0x80), el(id(0x85), "Chapter 2".toByteArray()) + el(id(0x43, 0x7C), "eng".toByteArray())) +
                el(id(0x80), el(id(0x85), "第二章".toByteArray()) + el(id(0x43, 0x7C), "chi".toByteArray())),
        )
        val file = mkv(chaptersElement(atom(0, "One"), atom(2_000_000_000, "secret", hidden = true), twoNames), chaptersFirst = true)
        assertEquals(listOf(Chapter(0, "One"), Chapter(5_000, "第二章")), ChapterReader.read(Bytes(file)))
    }

    // ---- MP4 helpers ------------------------------------------------------------------------

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = payload.size + 8
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.US_ASCII) + payload
    }

    private fun chpl(vararg chapters: Pair<Long, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, chapters.size.toByte()))
        for ((ms, title) in chapters) {
            val t = ms * 10_000
            for (i in 7 downTo 0) out.write(((t shr (i * 8)) and 0xFF).toInt())
            val bytes = title.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    @Test
    fun `mp4 nero chapters under moov udta`() {
        val file = box("ftyp", "isom".toByteArray() + ByteArray(4)) +
            box("moov", box("mvhd", ByteArray(100)) + box("udta", box("chpl", chpl(0L to "Opening", 90_500L to "第二幕")))) +
            box("mdat", ByteArray(32))
        assertEquals(listOf(Chapter(0, "Opening"), Chapter(90_500, "第二幕")), ChapterReader.read(Bytes(file)))
    }

    @Test
    fun `a container without chapters reads as none, as does garbage`() {
        val mp4 = box("ftyp", "isom".toByteArray() + ByteArray(4)) + box("moov", box("mvhd", ByteArray(100)))
        assertEquals(emptyList<Chapter>(), ChapterReader.read(Bytes(mp4)))
        assertEquals(emptyList<Chapter>(), ChapterReader.read(Bytes("hello".toByteArray())))
        assertEquals(emptyList<Chapter>(), ChapterReader.read(Bytes(ByteArray(0))))
    }

    // ---- Over http -----------------------------------------------------------------------------

    /** Serves [file] through the app's own loopback proxy, as a file on a share is served to the player. */
    private fun throughProxy(file: ByteArray): String = LocalStreamServer().register(
        "t" + file.size, "film.mkv",
        object : LocalStreamServer.Source {
            override fun size() = file.size.toLong()
            override fun copyRange(start: Long, length: Long, out: OutputStream) = out.write(file, start.toInt(), length.toInt())
            override fun close() = Unit
        },
    )

    @Test
    fun `a share's file gives its chapters through the proxy, a block or two at a time`() {
        // Chapters stored after half a megabyte of clusters, found through the seek head.
        val file = mkv(chaptersElement(atom(0, "片头"), atom(95_000_000_000, "第一幕")), chaptersFirst = false, clusterBytes = 512 * 1024)
        val source = HttpByteSource(throughProxy(file))
        assertEquals(listOf(Chapter(0, "片头"), Chapter(95_000, "第一幕")), ChapterReader.readRemote(source))
        // The head, then the block the chapters sit in (two if they straddle a boundary) — not one
        // request per element read.
        assertTrue("${source.requests} requests", source.requests <= 3)
        assertEquals(file.size.toLong(), source.length)
    }

    @Test
    fun `a server that ignores range gives the head and nothing past it`() {
        val head = mkv(chaptersElement(atom(0, "Opening")), chaptersFirst = true)
        val far = mkv(chaptersElement(atom(0, "Opening")), chaptersFirst = false, clusterBytes = 256 * 1024)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = if (ex.requestURI.path.endsWith("head.mkv")) head else far
            ex.sendResponseHeaders(200, body.size.toLong()) // whatever Range asked for
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(listOf(Chapter(0, "Opening")), ChapterReader.readRemote(HttpByteSource("$base/head.mkv")))
            assertEquals(emptyList<Chapter>(), ChapterReader.readRemote(HttpByteSource("$base/far.mkv")))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a dead address reads as no chapters`() {
        assertEquals(emptyList<Chapter>(), ChapterReader.readRemote(HttpByteSource("http://127.0.0.1:9/none.mkv", timeoutMs = 500)))
    }

    @Test
    fun `position maps to the chapter that began last`() {
        val chapters = listOf(Chapter(0, "a"), Chapter(9_000, "b"), Chapter(19_000, "c"))
        assertEquals(0, chapters.indexAt(0))
        assertEquals(0, chapters.indexAt(8_999))
        assertEquals(1, chapters.indexAt(9_000))
        assertEquals(2, chapters.indexAt(40_000))
        assertEquals(-1, emptyList<Chapter>().indexAt(5))
    }

    @Test
    fun `a muxer's stand-in names no chapter`() {
        listOf("00:07:06.926", "01:02:03", "0:00:00,000", "Chapter 01", "chapter 12", "Chapitre 3", "チャプター1", "章节 4", "第3章", "07", " 5 ")
            .forEach { assertEquals(it, "", ChapterReader.named(it)) }
        listOf("Opening", "Avant", "Part A", "第一章 黎明", "Chapter One", "2001: A Space Odyssey", "Act 1: The Fall")
            .forEach { assertEquals(it, it, ChapterReader.named(it)) }
    }
}
