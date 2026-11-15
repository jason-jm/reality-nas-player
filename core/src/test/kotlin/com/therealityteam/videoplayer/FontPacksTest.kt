package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.AssFonts
import com.therealityteam.videoplayer.data.subtitle.FontCache
import com.therealityteam.videoplayer.data.subtitle.FontNames
import com.therealityteam.videoplayer.data.subtitle.FontPacks
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A fansub's fonts beside its episodes, found, taken out once and picked by what its .ass is typeset in —
 * on fonts made here: a `name` table is all of a font this reads.
 */
class FontPacksTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A font of only a `name` table, at [base] in the file it will be part of: (platform, language, nameID, text). */
    private fun font(names: List<Triple<Int, Int, Pair<Int, String>>>, base: Int = 0): ByteArray {
        val strings = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v shr 8); o.write(v) }
        fun u32(o: ByteArrayOutputStream, v: Int) { u16(o, v shr 16); u16(o, v and 0xFFFF) }
        names.forEach { (platform, language, idText) ->
            val bytes = idText.second.toByteArray(if (platform == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
            u16(records, platform); u16(records, if (platform == 1) 0 else 1); u16(records, language); u16(records, idText.first)
            u16(records, bytes.size); u16(records, strings.size())
            strings.write(bytes)
        }
        val table = ByteArrayOutputStream()
        u16(table, 0); u16(table, names.size); u16(table, 6 + 12 * names.size)
        table.write(records.toByteArray()); table.write(strings.toByteArray())
        val out = ByteArrayOutputStream()
        u32(out, 0x00010000); u16(out, 1); u16(out, 16); u16(out, 0); u16(out, 0)
        out.write("name".toByteArray()); u32(out, 0); u32(out, base + 28); u32(out, table.size())
        out.write(table.toByteArray())
        return out.toByteArray()
    }

    private val sans = font(listOf(Triple(3, 0x409, 1 to "Test Sans"), Triple(3, 0x409, 4 to "Test Sans Regular"), Triple(3, 0x804, 1 to "方正测试黑_GBK"), Triple(1, 0, 6 to "TestSans-Regular")))
    private val round = font(listOf(Triple(3, 0x804, 1 to "汉仪正圆-65S"), Triple(3, 0x409, 1 to "HYZhengYuan 65S")))

    @Test fun `a font answers to its families, full names and PostScript name, in each language it gives`() {
        assertEquals(setOf("Test Sans", "Test Sans Regular", "方正测试黑_GBK", "TestSans-Regular"), FontNames.of(sans))
        assertEquals(setOf("汉仪正圆-65S", "HYZhengYuan 65S"), FontNames.of(round))
        assertEquals(emptySet<String>(), FontNames.of(ByteArray(40)))
    }

    @Test fun `a collection answers to every font in it`() {
        // ttcf, version, 2 fonts, their offsets — each font's tables at their place in the whole file.
        val a = font(listOf(Triple(3, 0x409, 1 to "First Face")), base = 20)
        val b = font(listOf(Triple(3, 0x409, 1 to "Second Face")), base = 20 + a.size)
        val head = ByteArrayOutputStream().apply {
            write("ttcf".toByteArray()); write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 2))
            write(byteArrayOf(0, 0, 0, 20)); val o = 20 + a.size; write(byteArrayOf(0, 0, (o shr 8).toByte(), o.toByte()))
        }.toByteArray()
        assertEquals(setOf("First Face", "Second Face"), FontNames.of(head + a + b))
    }

    @Test fun `an ass is typeset in its styles' faces and the ones its lines switch to`() {
        val script = """
            [Script Info]
            Title: test
            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour
            Style: Default,方正兰亭中黑_GBK,60,&H00FFFFFF
            Style: Sign,@汉仪正圆-65S,50,&H00FFFFFF
            [Events]
            Format: Layer, Start, End, Style, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,{\fnDFPHannotate-W7\b1}真的吗
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,没有换字体
        """.trimIndent()
        assertEquals(setOf("方正兰亭中黑_GBK", "汉仪正圆-65S", "DFPHannotate-W7"), AssFonts.wanted(script))
    }

    @Test fun `a pack is a Fonts folder, an archive named for fonts, or fonts loose beside the episode`() {
        val names = listOf("[VCB] Show [01].mkv", "[VCB] Show [01].chs.ass", "Fonts/", "Show [Fonts].7z", "Extras.7z", "fonts.zip")
        assertEquals(
            listOf(FontPacks.Pack("Fonts", FontPacks.Kind.FOLDER), FontPacks.Pack("Show [Fonts].7z", FontPacks.Kind.SEVEN_ZIP), FontPacks.Pack("fonts.zip", FontPacks.Kind.ZIP)),
            FontPacks.packsIn(names),
        )
        assertEquals(listOf(FontPacks.Pack("", FontPacks.Kind.LOOSE)), FontPacks.packsIn(listOf("Film.mkv", "Film.ass", "FZLTZHK.TTF")))
        assertEquals(emptyList<FontPacks.Pack>(), FontPacks.packsIn(listOf("Film.mkv", "Film.ass")))
    }

    @Test fun `a 7z of fonts is taken out once, kept, and picked from by name`() {
        val archive = tmp.newFile("Show [Fonts].7z")
        SevenZOutputFile(archive).use { z ->
            listOf("Fonts/FZLTZHK--GBK1-0.TTF" to sans, "Fonts/HYZhengYuan-65S.ttf" to round, "readme.txt" to "hi".toByteArray()).forEach { (name, bytes) ->
                z.putArchiveEntry(z.createArchiveEntry(File(name), name))
                z.write(bytes)
                z.closeArchiveEntry()
            }
        }
        val cache = FontCache(tmp.newFolder("fonts"))
        val key = "smb://nas/anime/Show/Show [Fonts].7z"
        assertEquals(null, cache.cached(key))
        val fonts = cache.fromArchive(key, archive, FontPacks.Kind.SEVEN_ZIP)
        assertEquals(2, fonts.size)
        assertEquals(fonts.map { it.names }.toSet(), cache.cached(key)!!.map { it.names }.toSet())
        val picked = FontCache.pick(cache.cached(key)!!, setOf("方正测试黑_GBK", "Nobody"), 64L shl 20)
        assertEquals(listOf(setOf("Test Sans", "Test Sans Regular", "方正测试黑_GBK", "TestSans-Regular")), picked.map { it.names })
        assertTrue(picked.single().file.readBytes().contentEquals(sans))
    }

    @Test fun `a zip of fonts too, and nothing outside its pack's folder`() {
        val archive = tmp.newFile("fonts.zip")
        ZipOutputStream(archive.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../../evil/round.ttf")); z.write(round); z.closeEntry()
        }
        val root = tmp.newFolder("cache")
        val fonts = FontCache(root).fromArchive("k", archive, FontPacks.Kind.ZIP)
        assertEquals(setOf("汉仪正圆-65S", "HYZhengYuan 65S"), fonts.single().names)
        assertTrue(fonts.single().file.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test fun `a special in SPs takes its release's pack a folder up, fetched once`() {
        val release = tmp.newFolder("Release")
        java.util.zip.ZipOutputStream(File(release, "Show [Fonts].zip").outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Fonts/round.ttf")); z.write(round); z.closeEntry()
            z.putNextEntry(ZipEntry("Fonts/sans.ttf")); z.write(sans); z.closeEntry()
        }
        val sps = File(release, "SPs").apply { mkdirs() }
        File(sps, "Show [NCOP].mkv").writeBytes(ByteArray(10))
        val server = com.therealityteam.videoplayer.data.playback.LocalStreamServer()
        try {
            val fetcher = com.therealityteam.videoplayer.data.subtitle.FontPackFetcher(server, tmp.newFolder("packs"), tmp.newFolder("tmp"))
            val picked = fetcher.pick(sps.path, listOf("Show [NCOP].mkv", "Show [NCOP].ass"), setOf("汉仪正圆-65S"), null, null, 64L shl 20)
            assertEquals(1, picked.size)
            assertTrue(picked.single().readBytes().contentEquals(round))
            // The episodes beside the pack ask for the same one: kept, not fetched again.
            File(release, "Show [Fonts].zip").delete()
            assertEquals(1, fetcher.pick(release.path, listOf("Show [01].mkv", "Show [Fonts].zip", "SPs/"), setOf("Test Sans"), null, null, 64L shl 20).size)
            // A folder not of specials does not look up: another show's fonts are not this one's.
            val other = File(release, "Other").apply { mkdirs() }
            assertEquals(emptyList<File>(), fetcher.pick(other.path, listOf("x.mkv"), setOf("Test Sans"), null, null, 64L shl 20))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a Fonts folder beside the video is read font by font`() {
        val dir = tmp.newFolder("Film")
        File(dir, "Fonts").mkdirs()
        File(dir, "Fonts/Round.TTF").writeBytes(round)
        File(dir, "Fonts/notes.txt").writeText("x")
        val server = com.therealityteam.videoplayer.data.playback.LocalStreamServer()
        try {
            val fetcher = com.therealityteam.videoplayer.data.subtitle.FontPackFetcher(server, tmp.newFolder("packs2"), tmp.newFolder("tmp2"))
            val picked = fetcher.pick(dir.path, listOf("Film.mkv", "Film.ass", "Fonts/"), setOf("HYZhengYuan 65S"), null, null, 64L shl 20)
            assertEquals(listOf("Round.TTF"), picked.map { it.name })
        } finally {
            server.shutdown()
        }
    }

    @Test fun `the oldest packs go once the cache is past its size`() {
        val root = tmp.newFolder("small")
        val cache = FontCache(root, maxBytes = sans.size.toLong() + 10)
        cache.fromFiles("first", listOf("a.ttf" to sans))
        Thread.sleep(20)
        cache.fromFiles("second", listOf("b.ttf" to sans))
        assertEquals(null, cache.cached("first"))
        assertEquals(1, cache.cached("second")!!.size)
    }
}
