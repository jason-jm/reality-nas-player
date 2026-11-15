package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.disc.DiscImages
import com.therealityteam.videoplayer.data.disc.DiscTitle
import com.therealityteam.videoplayer.data.disc.TsClock
import com.therealityteam.videoplayer.data.disc.Udf
import com.therealityteam.videoplayer.data.playback.DiscOpen
import com.therealityteam.videoplayer.data.playback.FileByteSource
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A disc's film plays as one timeline — each clip after the first moved on in time — and a disc ripped
 * as its files (BDMV / VIDEO_TS folders on a NAS) plays its playlist's clips as an image does, not the
 * largest of them alone: the test discs' clips were made one at a time, so each starts its clock over.
 */
class DiscFolderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val images = HashMap<String, File>()

    private fun image(name: String): File = images.getOrPut(name) {
        tmp.newFile("$name.iso").also { out ->
            GZIPInputStream(javaClass.getResourceAsStream("/disc/$name.iso.gz")!!).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }

    /** The image's files copied out under [into], as a rip keeps them. */
    private fun ripped(image: File, into: File): File {
        val src = FileByteSource(image)
        val udf = Udf.open(src)!!
        fun copy(path: String) {
            udf.list(path)!!.forEach { e ->
                val child = if (path.isEmpty()) e.name else "$path/${e.name}"
                if (e.isDirectory) {
                    File(into, child).mkdirs()
                    copy(child)
                } else {
                    val (extents, size) = udf.file(child)!!
                    val reader = DiscTitle.Reader(src, DiscTitle.trimmed(extents, size))
                    File(into, child).writeBytes(reader.read(0, reader.size.toInt()))
                }
            }
        }
        into.mkdirs()
        copy("")
        return into
    }

    private fun get(url: String, range: String? = null): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        range?.let { conn.setRequestProperty("Range", it) }
        return conn.inputStream.use { it.readBytes() }
    }

    @Test fun `a Blu-ray's clips play as one timeline - the second goes on from where the first ended`() {
        val server = LocalStreamServer()
        try {
            val open = server.registerDisc(image("Test_BD").absolutePath, null, null) as DiscOpen.Playing
            val (first, second) = open.title.parts
            assertEquals(0L, first.shift)
            assertEquals("the playlist's first clip plays 2 s", 180_000L, second.shift)
            val film = get(open.url)
            val a = TsClock.presentationTimes(film.copyOfRange(0, first.size.toInt()))
            val b = TsClock.presentationTimes(film.copyOfRange(first.size.toInt(), film.size))
            assertTrue("the clips have timestamps", a.isNotEmpty() && b.isNotEmpty())
            assertTrue("the second clip's times follow the first's (${a.max()} < ${b.min()})", a.max() < b.min())
            // The disc's own second clip starts its clock over, as the clips were made.
            val raw = DiscTitle.Reader(FileByteSource(image("Test_BD")), DiscTitle.trimmed(second.extents, second.size)).let { it.read(0, it.size.toInt()) }
            assertTrue("the disc's second clip starts over", TsClock.presentationTimes(raw).min() < a.max())
            // A range across the join, starting mid-packet, is the same bytes as the whole film's.
            for (from in listOf(first.size - 1001, first.size + 77, film.size - 5000L)) {
                val to = minOf(from + 4999, film.size - 1L)
                assertArrayEquals("bytes $from-$to", film.copyOfRange(from.toInt(), to.toInt() + 1), get(open.url, "bytes=$from-$to"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a Blu-ray ripped as a folder plays its playlist as the image does`() {
        val iso = image("Test_BD")
        val root = ripped(iso, File(tmp.root, "Some Film (2010)"))
        val stream = File(root, "BDMV/STREAM/00801.m2ts") // the largest clip, as the scan keeps it
        assertEquals(root.path, DiscImages.folderOf(stream.path))
        val server = LocalStreamServer()
        try {
            val folder = server.registerDiscFolder(stream.path, null, null) as DiscOpen.Playing
            assertEquals(listOf("00800.m2ts", "00801.m2ts"), folder.title.parts.map { it.name })
            assertEquals(4_000L, folder.title.durationMs)
            assertTrue(folder.url, folder.url.endsWith("/Some_Film_2010.m2ts"))
            val fromImage = server.registerDisc(iso.absolutePath, null, null) as DiscOpen.Playing
            assertArrayEquals("the same film, byte for byte", get(fromImage.url), get(folder.url))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a DVD ripped as a folder plays its title set's VOBs one after another`() {
        val root = ripped(image("Test_DVD"), File(tmp.root, "DVD Film"))
        val server = LocalStreamServer()
        try {
            val open = server.registerDiscFolder(File(root, "VIDEO_TS/VTS_01_2.VOB").path, null, null) as DiscOpen.Playing
            assertEquals(listOf("VTS_01_1.VOB", "VTS_01_2.VOB"), open.title.parts.map { it.name })
            val expected = open.title.parts.map { File(root, "VIDEO_TS/${it.name}").readBytes() }.reduce { a, b -> a + b }
            assertArrayEquals(expected, get(open.url))
        } finally {
            server.shutdown()
        }
    }

    /**
     * A VTS_01_0.IFO for [vobSectors] (each title VOB's length in sectors): one program chain, each VOB a
     * cell of 2 s and a program (a chapter), each VOB a VOB of its own and the second's clock marked as
     * starting over — as a disc of separately made pieces is.
     */
    private fun ifo(vobSectors: List<Long>): ByteArray {
        val b = ByteArray(3 * 2048)
        fun u16(at: Int, v: Int) { b[at] = (v shr 8).toByte(); b[at + 1] = v.toByte() }
        fun u32(at: Int, v: Long) { u16(at, (v shr 16).toInt()); u16(at + 2, v.toInt()) }
        "DVDVIDEO-VTS".toByteArray().copyInto(b, 0)
        u32(0xC8, 1) // the part-of-title table, sector 1
        u32(0xCC, 2) // the program chains, sector 2
        val n = vobSectors.size
        // Title 1's parts: (chain 1, program p).
        u16(2048, 1); u32(2048 + 4, 12L + n * 4 - 1); u32(2048 + 8, 12)
        for (p in 0 until n) { u16(2048 + 12 + p * 4, 1); u16(2048 + 14 + p * 4, p + 1) }
        val pgci = 2 * 2048
        u16(pgci, 1); u32(pgci + 4, 16L + 0x200); u32(pgci + 8, 0); u32(pgci + 12, 16)
        val pgc = pgci + 16
        b[pgc + 2] = n.toByte(); b[pgc + 3] = n.toByte()
        val map = 0xEC
        val playback = map + n
        val position = playback + n * 24
        u16(pgc + 0xE6, map); u16(pgc + 0xE8, playback); u16(pgc + 0xEA, position)
        var first = 0L
        for (i in 0 until n) {
            b[pgc + map + i] = (i + 1).toByte()
            val at = pgc + playback + i * 24
            if (i > 0) b[at] = 0x02 // the clock starts over
            b[at + 6] = 0x02; b[at + 7] = 0xC0.toByte() // 00:00:02.00 at 29.97
            u32(at + 8, first); u32(at + 16, first + vobSectors[i] - 1); u32(at + 20, first + vobSectors[i] - 1)
            u16(pgc + position + i * 4, i + 1); b[pgc + position + i * 4 + 3] = 1
            first += vobSectors[i]
        }
        return b
    }

    @Test fun `a DVD whose pieces start their clocks over plays as one timeline, its chapters and length from its IFO`() {
        val root = ripped(image("Test_DVD"), File(tmp.root, "Pieces"))
        val vobs = listOf("VTS_01_1.VOB", "VTS_01_2.VOB").map { File(root, "VIDEO_TS/$it") }
        File(root, "VIDEO_TS/VTS_01_0.IFO").writeBytes(ifo(vobs.map { it.length() / 2048 }))
        val server = LocalStreamServer()
        try {
            val open = server.registerDiscFolder(vobs[0].path, null, null) as DiscOpen.Playing
            assertEquals(4_000L, open.title.durationMs)
            assertEquals(listOf(0L, 2_000L), open.title.chaptersMs)
            assertEquals(2, open.title.parts.size)
            assertEquals(0L, open.title.parts[0].shift)
            val film = get(open.url)
            assertEquals("every byte served", vobs.sumOf { it.length() }, film.size.toLong())
            val cut = vobs[0].length().toInt()
            val a = com.therealityteam.videoplayer.data.disc.PsClock.presentationTimes(film.copyOfRange(0, cut), video = true)
            val b = com.therealityteam.videoplayer.data.disc.PsClock.presentationTimes(film.copyOfRange(cut, film.size), video = true)
            val raw = com.therealityteam.videoplayer.data.disc.PsClock.presentationTimes(vobs[1].readBytes(), video = true)
            assertTrue("the disc's second piece starts over (${raw.min()} < ${a.max()})", raw.min() < a.max())
            assertTrue("the second piece's times follow the first's (${a.max()} < ${b.min()})", a.max() < b.min())
            assertTrue("about 4 s from first time to last: ${(b.max() - a.min()) / 90} ms", (b.max() - a.min()) / 90 in 3_500L..4_200L)
            // A range across the join, from mid-pack, is the whole film's bytes.
            val from = cut - 777L
            assertArrayEquals(film.copyOfRange(from.toInt(), from.toInt() + 6000), get(open.url, "bytes=$from-${from + 5999}"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `finding the film leaves open only what it plays - not every playlist read`() {
        val root = ripped(image("Test_BD"), File(tmp.root, "Handles"))
        val open = HashSet<String>()
        val folder = com.therealityteam.videoplayer.data.disc.DiscFolder(
            lister = { rel -> File(root, rel).listFiles()?.map { com.therealityteam.videoplayer.data.disc.DiscFolder.Listed(it.name, it.isDirectory, it.length().takeIf { _ -> it.isFile }) } },
            opener = { rel ->
                val file = File(root, rel)
                object : LocalStreamServer.Source {
                    override fun size() = file.length()
                    override fun copyRange(start: Long, length: Long, out: java.io.OutputStream) {
                        synchronized(open) { open += rel }
                        out.write(file.readBytes(), start.toInt(), length.toInt())
                    }
                    override fun close() { synchronized(open) { open -= rel } }
                }
            },
        )
        val title = (DiscTitle.find(folder, com.therealityteam.videoplayer.data.playback.SourceBytes(folder)) as DiscTitle.Found.Film).title
        assertEquals(listOf("00800.m2ts", "00801.m2ts"), title.parts.map { it.name })
        assertTrue("only the film's clips are held: $open", open.all { it.startsWith("BDMV/STREAM/") })
        folder.close()
        assertTrue("and they go with it: $open", open.isEmpty())
    }

    @Test fun `a DVD of featurettes plays them all, one after another, each a chapter`() {
        // Three title sets of 2 s each, none half the disc — each with its own clock, as separately made pieces are.
        val dvd = ripped(image("Test_DVD"), File(tmp.root, "Pieces source"))
        val root = File(tmp.root, "Featurettes/VIDEO_TS").apply { mkdirs() }
        val pieces = listOf("VTS_01_1.VOB", "VTS_01_2.VOB", "VTS_01_1.VOB")
        pieces.forEachIndexed { i, name ->
            val vob = File(root, "VTS_%02d_1.VOB".format(i + 1))
            File(dvd, "VIDEO_TS/$name").copyTo(vob)
            File(root, "VTS_%02d_0.IFO".format(i + 1)).writeBytes(ifo(listOf(vob.length() / 2048)))
        }
        val server = LocalStreamServer()
        try {
            val open = server.registerDiscFolder(File(root, "VTS_01_1.VOB").path, null, null) as DiscOpen.Playing
            assertTrue(open.title.source, open.title.source.startsWith("all 3 title sets"))
            assertEquals(6_000L, open.title.durationMs)
            assertEquals(listOf(0L, 2_000L, 4_000L), open.title.chaptersMs)
            val film = get(open.url)
            val sizes = pieces.indices.map { File(root, "VTS_%02d_1.VOB".format(it + 1)).length().toInt() }
            assertEquals(sizes.sum(), film.size)
            val times = sizes.runningFold(0) { a, n -> a + n }.zipWithNext().map { (from, to) ->
                com.therealityteam.videoplayer.data.disc.PsClock.presentationTimes(film.copyOfRange(from, to), video = true)
            }
            for (i in 1 until times.size) assertTrue("piece ${i + 1} follows piece $i", times[i - 1].max() < times[i].min())
            assertTrue("about 6 s in all: ${(times.last().max() - times.first().min()) / 90} ms", (times.last().max() - times.first().min()) / 90 in 5_500L..6_200L)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a data disc's videos are found folders deep, in the order their names count`() {
        val root = File(tmp.root, "Tutorials").apply { mkdirs() }
        listOf("10 - Envelopes.mov", "2 - Browser.mov", "1 - Overview.mov", "readme.txt", "Extras/Bonus clip.mp4", "Extras/a/b/c/d/too deep.mov")
            .forEach { File(root, it).apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) } }
        val folder = com.therealityteam.videoplayer.data.disc.DiscFolder(
            lister = { rel -> File(root, rel).listFiles()?.map { com.therealityteam.videoplayer.data.disc.DiscFolder.Listed(it.name, it.isDirectory, it.length().takeIf { _ -> it.isFile }) } },
            opener = { null },
        )
        assertEquals(listOf("1 - Overview.mov", "2 - Browser.mov", "10 - Envelopes.mov", "Extras/Bonus clip.mp4"), DiscTitle.videosIn(folder))
    }

    @Test fun `a disc in several languages plays the reader's first, then English`() {
        val root = File(tmp.root, "Mixing").apply { mkdirs() }
        listOf("Assets/Videos/DE/01-Intro-D.mov", "Assets/Videos/EN/01-Intro-E.mov", "Assets/Videos/FR/01-Intro-F.mov", "Trailer.mov")
            .forEach { File(root, it).apply { parentFile.mkdirs(); writeBytes(ByteArray(10)) } }
        val folder = com.therealityteam.videoplayer.data.disc.DiscFolder(
            lister = { rel -> File(root, rel).listFiles()?.map { com.therealityteam.videoplayer.data.disc.DiscFolder.Listed(it.name, it.isDirectory, it.length().takeIf { _ -> it.isFile }) } },
            opener = { null },
        )
        assertEquals(listOf("Assets/Videos/EN/01-Intro-E.mov", "Trailer.mov", "Assets/Videos/DE/01-Intro-D.mov", "Assets/Videos/FR/01-Intro-F.mov"), DiscTitle.videosIn(folder, java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("Assets/Videos/FR/01-Intro-F.mov", DiscTitle.videosIn(folder, java.util.Locale.FRENCH).first())
    }

    @Test fun `an ISO 9660 data disc (no UDF) gives its videos by their Joliet names, each served as it lies`() {
        // Made by macOS's hdiutil makehybrid -iso -joliet: a tutorial disc's .mov files in Chinese-named folders.
        val iso = image("Data_ISO9660")
        val src = FileByteSource(iso)
        assertEquals(null, Udf.open(src))
        val files = com.therealityteam.videoplayer.data.disc.Iso9660.open(src)!!
        assertEquals(DiscTitle.Found.NoFilm, DiscTitle.find(src))
        assertEquals(listOf("视频教程/01 概览.mov", "视频教程/2 浏览器.mov", "视频教程/第二部分/10 包络.mov"), DiscTitle.videosIn(files))
        val server = LocalStreamServer()
        try {
            assertEquals(DiscOpen.Videos(DiscTitle.videosIn(files)), server.registerDisc(iso.absolutePath, null, null))
            val url = server.registerDiscFile(iso.absolutePath, null, null, "视频教程/2 浏览器.mov", key = "k")!!
            val (extents, size) = files.file("视频教程/2 浏览器.mov")!!
            assertArrayEquals(src.read(extents.first().offset, size.toInt()), get(url))
            assertEquals(0x6674797, (get(url, "bytes=4-7").let { b -> (b[0].toInt() shl 24) or (b[1].toInt() shl 16) or (b[2].toInt() shl 8) or b[3].toInt() }) shr 4)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a video inside an image has an address of its own`() {
        val at = DiscImages.videoPath("smb://nas/video/Tutorials.ISO", "Videos/01 Intro.mov")
        assertEquals("smb://nas/video/Tutorials.ISO#/Videos/01 Intro.mov", at)
        assertEquals("smb://nas/video/Tutorials.ISO" to "Videos/01 Intro.mov", DiscImages.innerOf(at))
        assertEquals(null, DiscImages.innerOf("smb://nas/video/Film #1.mkv"))
        assertTrue(!DiscImages.isImage(at))
    }

    @Test fun `a folder of streams with no playlist is left to play its file`() {
        val stream = File(tmp.root, "Rip/BDMV/STREAM/00000.m2ts").apply { parentFile.mkdirs(); writeBytes(ByteArray(192 * 10)) }
        val server = LocalStreamServer()
        try {
            assertEquals(DiscOpen.NoFilm, server.registerDiscFolder(stream.path, null, null))
            assertEquals(DiscOpen.NoFilm, server.registerDiscFolder(File(tmp.root, "Film.mkv").path, null, null))
        } finally {
            server.shutdown()
        }
    }

    /**
     * -Daura.disc.folder=/path/Film/BDMV/STREAM/00800.m2ts: a real disc folder served, and what ffprobe
     * and ffmpeg make of it — its length, and a frame from past the first clip.
     */
    @Test fun `probe a disc folder`() {
        val path = System.getProperty("aura.disc.folder") ?: return
        val server = LocalStreamServer()
        try {
            val open = (if (DiscImages.isImage(path)) server.registerDisc(path, null, null) else server.registerDiscFolder(path, null, null))
            println("DISCFOLDER $open")
            val url = (open as? DiscOpen.Playing)?.url ?: return
            fun run(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }
            println("DISCFOLDER ffprobe duration: " + run("ffprobe", "-v", "error", "-show_entries", "format=duration,start_time", "-of", "csv=p=0", url).trim())
            val at = (System.getProperty("aura.disc.at") ?: "90").toDouble()
            val shot = File(tmp.root, "at.png")
            run("ffmpeg", "-v", "error", "-y", "-ss", "$at", "-i", url, "-frames:v", "1", shot.path)
            println("DISCFOLDER frame at $at s: ${if (shot.length() > 0) "${shot.length()} bytes" else "none"}")
            println("DISCFOLDER time read there: " + run("ffprobe", "-v", "error", "-read_intervals", "$at%+0.2", "-select_streams", "v", "-show_entries", "packet=pts_time", "-of", "csv=p=0", url).lines().firstOrNull { it.isNotBlank() })
        } finally {
            server.shutdown()
        }
    }

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** A playlist playing [items] (clip, from s, to s) with entry or link [marks] (type, item, at s). */
    private fun mpls(items: List<Triple<String, Long, Long>>, marks: List<Triple<Int, Int, Double>> = emptyList()): ByteArray {
        val body = items.map { (clip, from, to) ->
            val b = clip.toByteArray() + "M2TS".toByteArray() + u16(0) + byteArrayOf(0) + u32(from * 45_000) + u32(to * 45_000) + ByteArray(12) + u16(14) + ByteArray(14)
            u16(b.size) + b
        }.reduce { a, b -> a + b }
        val list = u32(6L + body.size) + u16(0) + u16(items.size) + u16(0) + body
        val app = u32(14) + ByteArray(14)
        val markBytes = marks.map { (type, item, at) -> byteArrayOf(0, type.toByte()) + u16(item) + u32((at * 45_000).toLong()) + u16(0x1011) + u32(0) }
        val markTable = u32(2L + marks.size * 14) + u16(marks.size) + markBytes.fold(ByteArray(0)) { a, b -> a + b }
        val start = 40 + app.size
        return "MPLS0200".toByteArray() + u32(start.toLong()) + u32((start + list.size).toLong()) + u32(0) + ByteArray(20) + app + list + markTable
    }

    @Test fun `a playlist's entry marks are the film's chapters, across its clips`() {
        // Two items: 00800 from 10 s to 70 s, 00801 from 5 s to 65 s; marks at the film's start, a link
        // point (no chapter), 40 s into the first, a duplicate 0.5 s on, 20 s into the second, and its end.
        val parsed = com.therealityteam.videoplayer.data.disc.Mpls.parse(
            mpls(
                listOf(Triple("00800", 10L, 70L), Triple("00801", 5L, 65L)),
                listOf(Triple(1, 0, 10.0), Triple(2, 0, 30.0), Triple(1, 0, 50.0), Triple(1, 0, 50.5), Triple(1, 1, 25.0), Triple(1, 1, 64.9)),
            ),
        )!!
        assertEquals(listOf("00800", "00801"), parsed.items.map { it.clip })
        assertEquals(120_000L, parsed.durationMs)
        assertEquals("the film's last moment starts no chapter", listOf(0L, 40_000L, 80_000L), parsed.chaptersMs)
    }

    @Test fun `a playlist that loops one clip is not the film, however long it plays`() {
        // As on a real Tora! Tora! Tora!: a menu's background, 10 s, played 36 times (6 minutes) beside the 60 s film.
        val root = File(tmp.root, "Loop/BDMV").apply { File(this, "PLAYLIST").mkdirs(); File(this, "STREAM").mkdirs() }
        File(root, "STREAM/00800.m2ts").writeBytes(ByteArray(192 * 2000))
        File(root, "STREAM/00300.m2ts").writeBytes(ByteArray(192 * 100))
        File(root, "PLAYLIST/00000.mpls").writeBytes(mpls(listOf(Triple("00800", 0L, 60L))))
        File(root, "PLAYLIST/00001.mpls").writeBytes(mpls(List(36) { Triple("00300", 0L, 10L) }))
        val server = LocalStreamServer()
        try {
            val open = server.registerDiscFolder(File(root, "STREAM/00800.m2ts").path, null, null) as DiscOpen.Playing
            assertEquals(listOf("00800.m2ts"), open.title.parts.map { it.name })
            assertTrue(open.title.source, open.title.source.startsWith("00000.mpls of 2 (then 00001.mpls 10 s/36)"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a clock moved on keeps every byte but its times`() {
        val raw = DiscTitle.Reader(FileByteSource(image("Test_BD")), (DiscTitle.find(FileByteSource(image("Test_BD"))) as DiscTitle.Found.Film).title.extents)
            .let { it.read(0, it.size.toInt()) }
        val moved = raw.copyOf().also { TsClock.shift(it, 90_000L * 3600) }
        assertEquals(TsClock.presentationTimes(raw).map { it + 90_000L * 3600 }, TsClock.presentationTimes(moved))
        assertArrayEquals(raw, moved.also { TsClock.shift(it, -90_000L * 3600) })
    }
}
