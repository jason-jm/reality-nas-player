package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.disc.DiscTitle
import com.therealityteam.videoplayer.data.disc.Udf
import com.therealityteam.videoplayer.data.playback.FileByteSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Disc images made by tools/dev/disc/make-disc-tests.sh: UDF formatted by macOS's newfs_udf (2.50 with a
 * metadata partition for the Blu-rays, 1.02 for the DVD) and filled by its own file system driver — so
 * the reader is checked against images it did not write.
 */
class DiscTitleTest {

    private fun image(name: String): FileByteSource {
        val out = File.createTempFile("disc", ".iso").apply { deleteOnExit() }
        GZIPInputStream(javaClass.getResourceAsStream("/disc/$name.iso.gz")!!).use { input -> out.outputStream().use { input.copyTo(it) } }
        return FileByteSource(out)
    }

    @Test fun `a Blu-ray's UDF 2_50 folders are read through its metadata partition`() {
        val udf = Udf.open(image("Test_BD"))!!
        assertEquals(setOf("00000.mpls", "00001.mpls", "00002.mpls"), udf.list("BDMV/PLAYLIST")!!.map { it.name }.toSet())
        assertEquals(setOf("00001.m2ts", "00800.m2ts", "00801.m2ts"), udf.list("bdmv/stream")!!.map { it.name }.toSet())
        assertTrue(udf.list("BDMV")!!.any { it.name == "PLAYLIST" && it.isDirectory })
    }

    @Test fun `a Blu-ray's film is its longest playlist, its clips in order, the bytes as on the disc`() {
        val src = image("Test_BD")
        val found = DiscTitle.find(src)
        val title = (found as DiscTitle.Found.Film).title
        assertEquals(DiscTitle.Kind.BLURAY, title.kind)
        assertTrue(title.source, title.source.startsWith("00000.mpls of 2"))
        assertEquals(listOf("00800.m2ts", "00801.m2ts"), title.parts.map { it.name })
        assertEquals(4_000L, title.durationMs)
        assertFalse(title.encrypted)
        // The film's bytes are the two clips', one after the other: packets of 192 bytes, each with a TS sync byte.
        val reader = DiscTitle.Reader(src, title.extents)
        assertEquals(title.size, reader.size)
        assertEquals(0L, reader.size % 192)
        for (p in listOf(0L, 1L, reader.size / 192 / 2, reader.size / 192 - 1)) {
            assertEquals("packet $p", 0x47, reader.read(p * 192 + 4, 1)[0].toInt() and 0xFF)
        }
    }

    @Test fun `the clips read out match the files the image was made from`() {
        val dir = System.getProperty("aura.disc.source") ?: return // the folder make-disc-tests.sh worked in, when kept
        val src = image("Test_BD")
        val title = (DiscTitle.find(src) as DiscTitle.Found.Film).title
        val reader = DiscTitle.Reader(src, title.extents)
        val original = title.parts.map { File(dir, "BDMV/STREAM/${it.name}").readBytes() }.reduce { a, b -> a + b }
        assertArrayEquals(original, reader.read(0, reader.size.toInt()))
    }

    @Test fun `a DVD's film is its largest title set, its VOBs in order, not its menu`() {
        val src = image("Test_DVD")
        val title = ((DiscTitle.find(src)) as DiscTitle.Found.Film).title
        assertEquals(DiscTitle.Kind.DVD, title.kind)
        assertEquals(listOf("VTS_01_1.VOB", "VTS_01_2.VOB"), title.parts.map { it.name })
        assertFalse(title.encrypted)
        // An MPEG program stream: a pack header at the start of every 2048-byte sector.
        val reader = DiscTitle.Reader(src, title.extents)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xBA.toByte()), reader.read(0, 4))
    }

    @Test fun `an encrypted Blu-ray is told apart`() {
        val title = ((DiscTitle.find(image("Encrypted_BD"))) as DiscTitle.Found.Film).title
        assertTrue(title.encrypted)
    }

    @Test fun `a decrypted backup that kept AACS's marks plays - its packets are plain`() {
        // As the user's Margin Call (a scene's full disc) is: every unit's copy-permission bits set, nothing scrambled.
        val title = ((DiscTitle.find(image("Marked_BD"))) as DiscTitle.Found.Film).title
        assertFalse(title.encrypted)
    }

    @Test fun `an image of files holds no film, and a file that is no image is no disc`() {
        assertEquals(DiscTitle.Found.NoFilm, DiscTitle.find(image("Software")))
        val notAnImage = File.createTempFile("not", ".iso").apply { deleteOnExit(); writeBytes(ByteArray(1 shl 20) { (it * 31).toByte() }) }
        assertEquals(DiscTitle.Found.NotADisc, DiscTitle.find(FileByteSource(notAnImage)))
    }

    @Test fun `the stream server serves a disc's film as one file, by range`() {
        val file = File.createTempFile("Test BD", ".iso").apply { deleteOnExit() }
        GZIPInputStream(javaClass.getResourceAsStream("/disc/Test_BD.iso.gz")!!).use { input -> file.outputStream().use { input.copyTo(it) } }
        val server = com.therealityteam.videoplayer.data.playback.LocalStreamServer()
        try {
            val open = server.registerDisc(file.absolutePath, null, null) as com.therealityteam.videoplayer.data.playback.DiscOpen.Playing
            assertTrue(open.url.endsWith(".m2ts"))
            // The image's bytes, the second clip's clock moved on to follow the first's.
            val expected = open.title.parts.map { part ->
                DiscTitle.Reader(FileByteSource(file), DiscTitle.trimmed(part.extents, part.size)).let { it.read(0, it.size.toInt()) }
                    .also { com.therealityteam.videoplayer.data.disc.TsClock.shift(it, part.shift) }
            }.reduce { a, b -> a + b }
            fun get(range: String?): ByteArray {
                val conn = java.net.URL(open.url).openConnection() as java.net.HttpURLConnection
                range?.let { conn.setRequestProperty("Range", it) }
                return conn.inputStream.use { it.readBytes() }
            }
            assertArrayEquals(expected, get(null))
            // A range that crosses from the first clip into the second.
            val from = open.title.parts[0].size - 1000
            assertArrayEquals(expected.copyOfRange(from.toInt(), from.toInt() + 5000), get("bytes=$from-${from + 4999}"))
            assertEquals(
                com.therealityteam.videoplayer.data.playback.DiscOpen.Encrypted(DiscTitle.Kind.BLURAY),
                server.registerDisc(File.createTempFile("enc", ".iso").apply {
                    deleteOnExit()
                    GZIPInputStream(DiscTitleTest::class.java.getResourceAsStream("/disc/Encrypted_BD.iso.gz")!!).use { input -> outputStream().use { input.copyTo(it) } }
                }.absolutePath, null, null),
            )
        } finally {
            server.shutdown()
        }
    }

    /** -Daura.disc.probe=/path/film.iso: what the reader finds in a real image, printed. */
    @Test fun `probe an image`() {
        val path = System.getProperty("aura.disc.probe") ?: return
        val src = FileByteSource(File(path))
        println("DISCPROBE udf: ${Udf.open(src)?.list("")?.map { it.name }}")
        when (val found = DiscTitle.find(src)) {
            is DiscTitle.Found.Film -> with(found.title) {
                println("DISCPROBE $kind $source ${durationMs} ms, encrypted=$encrypted, ${parts.joinToString { "${it.name} ${it.size}" }}")
            }
            else -> println("DISCPROBE $found")
        }
        val server = com.therealityteam.videoplayer.data.playback.LocalStreamServer()
        println("DISCPROBE served: ${server.registerDisc(path, null, null)}")
        server.shutdown()
    }

    @Test fun `a share's ISO is a film's when it is big enough and not named as software`() {
        val images = com.therealityteam.videoplayer.data.disc.DiscImages
        val gb = 1L shl 30
        assertTrue(images.mayBeFilm("Inception.2010.1080p.BluRay.AVC.DTS-HD.MA.5.1.iso", 42 * gb))
        assertTrue(images.mayBeFilm("让子弹飞.2010.DVD9.iso", 7 * gb))
        assertTrue(images.mayBeFilm("Some Film.iso", null))
        assertFalse(images.mayBeFilm("Some Film.iso", 300L shl 20))
        assertFalse(images.mayBeFilm("Win11_24H2_Chinese_Simplified_x64.iso", 5 * gb))
        assertFalse(images.mayBeFilm("ubuntu-24.04-desktop-amd64.iso", 6 * gb))
        assertFalse(images.mayBeFilm("SW_DVD9_Office_Professional_Plus_2021.iso", 4 * gb))
        assertTrue(images.isImage("smb://nas/films/Film.ISO"))
        assertFalse(images.isImage("smb://nas/films/Film.mkv"))
    }

    @Test fun `a playlist names its clips and their in and out points`() {
        val udf = Udf.open(image("Test_BD"))!!
        val list = com.therealityteam.videoplayer.data.disc.Mpls.parse(udf.read("BDMV/PLAYLIST/00000.mpls")!!)!!
        assertEquals(listOf("00800", "00801"), list.items.map { it.clip })
        assertEquals(listOf(90_000L, 90_000L), list.items.map { it.outTime - it.inTime })
        assertNotNull(udf.file("BDMV/STREAM/00800.m2ts"))
    }
}
