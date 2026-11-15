package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AssDrawing
import com.therealityteam.videoplayer.data.playback.BitmapSubtitlePlacement
import com.therealityteam.videoplayer.data.playback.CuePlacement
import com.therealityteam.videoplayer.data.playback.FrameRect
import com.therealityteam.videoplayer.data.subtitle.SubtitleTranscoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class SubtitleLookTest {

    @Test
    fun `bitmap follows the 4 by 3 film inside a wide screen`() {
        val cue = BitmapSubtitlePlacement(80f / 1024, 80f / 768, 224f / 1024, 56f / 768, 0f, 0f)
        val screen = FrameRect(0.125f, 0f, 0.75f, 1f)
        assertEquals(FrameRect(188f, 60f, 168f, 42f), cue.bounds(screen, 1024f, 576f, 4f))
    }

    @Test
    fun `cropped film keeps authored anchor when resizing a bitmap with no authored height`() {
        val cue = BitmapSubtitlePlacement(0.5f, 0.75f, 0.25f, null, 0.5f, 1f)
        val frame = FrameRect(0f, -0.25f, 1f, 1.5f)
        assertEquals(FrameRect(250f, 150f, 500f, 200f), cue.bounds(frame, 1000f, 400f, 2.5f, scale = 2f))
    }

    @Test
    fun `authored height follows the stretched film and allows a partially visible cue`() {
        val cue = BitmapSubtitlePlacement(0.0625f, 0.5f, 0.25f, 0.25f, 1f, 0.5f)
        assertEquals(FrameRect(-150f, 150f, 200f, 100f), cue.bounds(FrameRect.FULL, 800f, 400f, 4f))
    }

    @Test
    fun `ass alignments land in the lanes they name`() {
        // Media3's SSA parser: \an7-9 → line 0.05 anchored at its start; \an1-3 → 0.95 anchored at its end.
        assertEquals(CuePlacement.TOP, CuePlacement.of(0.05f, true, 0))
        assertEquals(CuePlacement.MIDDLE, CuePlacement.of(0.5f, true, 1))
        assertEquals(CuePlacement.BOTTOM, CuePlacement.of(0.95f, true, 2))
        // WebVTT "line:10%" and "line:80%".
        assertEquals(CuePlacement.TOP, CuePlacement.of(0.10f, true, 0))
        assertEquals(CuePlacement.BOTTOM, CuePlacement.of(0.80f, true, 0))
    }

    @Test
    fun `an unset or numbered line is the default lane`() {
        assertEquals(CuePlacement.BOTTOM, CuePlacement.of(-Float.MAX_VALUE, true, 0))   // DIMEN_UNSET
        assertEquals(CuePlacement.BOTTOM, CuePlacement.of(2f, false, 0))                // a line number
        assertEquals(CuePlacement.BOTTOM, CuePlacement.of(Float.NaN, true, 1))
    }

    @Test
    fun `utf-8 needs no conversion, gbk does`() {
        val utf8 = "这是字幕".toByteArray(StandardCharsets.UTF_8)
        assertNull(SubtitleTranscoder.charsetToConvert(utf8))
        assertNull(SubtitleTranscoder.charsetToConvert(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8))
        val gbk = "这是一条 GBK 编码的字幕".toByteArray(Charset.forName("GBK"))
        val from = SubtitleTranscoder.charsetToConvert(gbk)
        assertEquals("GB18030", from?.name()) // GBK's superset
        assertArrayEquals("这是一条 GBK 编码的字幕".toByteArray(StandardCharsets.UTF_8), SubtitleTranscoder.toUtf8(gbk, from!!))
    }

    @Test
    fun `a gbk file gets a utf-8 copy in the cache, a utf-8 file is handed over as is`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aura-transcode-${System.nanoTime()}").apply { mkdirs() }
        try {
            val gbkFile = File(dir, "movie.chs.srt").apply { writeBytes("1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n".toByteArray(Charset.forName("GBK"))) }
            val copy = SubtitleTranscoder.utf8Path(gbkFile, File(dir, "cache"))
            assertEquals("srt", copy.extension)
            assertEquals(true, copy.absolutePath.contains("cache"))
            assertEquals("1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n", copy.readText(StandardCharsets.UTF_8))
            // Same file again reuses the copy.
            assertEquals(copy, SubtitleTranscoder.utf8Path(gbkFile, File(dir, "cache")))
            val utf8File = File(dir, "movie.eng.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nhello\n") }
            assertEquals(utf8File, SubtitleTranscoder.utf8Path(utf8File, File(dir, "cache")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an ASS drawing Media3 left as words is not a line to show`() {
        // A fansub's mask behind a sign, and a curved one, as Media3 hands them over.
        assertEquals(true, AssDrawing.isVectorDrawing("m 0 0 l 1920 0 1920 1080 0 1080"))
        assertEquals(true, AssDrawing.isVectorDrawing("m 12.5 -3 b 10 10 20 20 30 30 l 4 4 c"))
        assertEquals(true, AssDrawing.isVectorDrawing("  m 0 0 l 10 0  "))
        // Lines that only start like one.
        assertEquals(false, AssDrawing.isVectorDrawing("m 5 people"))
        assertEquals(false, AssDrawing.isVectorDrawing("m"))
        assertEquals(false, AssDrawing.isVectorDrawing("moon 0 0"))
        assertEquals(false, AssDrawing.isVectorDrawing("那是 3 号门"))
    }
}
