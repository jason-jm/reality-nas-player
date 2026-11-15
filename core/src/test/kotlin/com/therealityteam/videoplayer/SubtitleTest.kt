package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.OpenSubtitlesHash
import com.therealityteam.videoplayer.data.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTest {

    @Test
    fun parsesSrt() {
        val srt = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello <i>world</i>

            2
            00:00:05,500 --> 00:00:07,000
            Second line
        """.trimIndent()
        val track = SubtitleParser.parseSrt(srt)
        assertEquals(2, track.cues.size)
        assertEquals(1000L, track.cues[0].startMs)
        assertEquals(4000L, track.cues[0].endMs)
        assertEquals("Hello world", track.cues[0].text)
        assertEquals("Second line", track.cueAt(6000)?.text)
        assertNull(track.cueAt(4500))
    }

    @Test
    fun parsesAss() {
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.00,0:00:05.00,Default,,0,0,0,,{\b1}Bold{\b0} line
        """.trimIndent()
        val track = SubtitleParser.parseAss(ass)
        assertEquals(1, track.cues.size)
        assertEquals(2000L, track.cues[0].startMs)
        assertEquals(5000L, track.cues[0].endMs)
        assertEquals("Bold line", track.cues[0].text)
    }

    @Test
    fun detectsUtf8() {
        val bytes = "你好 hello".toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, SubtitleParser.detectCharset(bytes))
    }

    @Test
    fun readsEachCodePageAsItsOwn() {
        fun srt(line: String) = "1\r\n00:00:01,000 --> 00:00:02,000\r\n$line\r\n"
        val gbk = java.nio.charset.Charset.forName("GBK")
        val big5 = java.nio.charset.Charset.forName("Big5")
        val sjis = java.nio.charset.Charset.forName("Shift_JIS")
        val latin = java.nio.charset.Charset.forName("windows-1252")
        fun read(text: String, cs: java.nio.charset.Charset): String {
            val bytes = srt(text).toByteArray(cs)
            return String(bytes, SubtitleParser.detectCharset(bytes))
        }
        // A mainland release's GBK, simplified and traditional both (GBK has the traditional forms too).
        assertTrue(read("这是第二句：我们的电影，你知道吗？", gbk).contains("我们的电影"))
        assertTrue(read("這是第二句：我們的電影，你知道嗎？", gbk).contains("我們的電影"))
        // A Taiwan or Hong Kong release's Big5 — it used to come out 「硂琌材」 as GBK.
        assertTrue(read("這是第二句：我們的電影，你知道嗎？", big5).contains("我們的電影"))
        assertTrue(read("繁體字幕測試，對不起，謝謝你。", big5).contains("對不起"))
        // A Japanese one in Shift_JIS.
        assertTrue(read("これは映画です。ありがとう、また明日。", sjis).contains("ありがとう"))
        // A European one in Windows-1252.
        assertTrue(read("Un café crème, déjà vu à l'hôtel.", latin).contains("café crème, déjà vu"))
        // Short GBK with few everyday words still reads as GBK (first among equals).
        assertTrue(read("龍", gbk).contains("龍"))
    }

    @Test
    fun hashIsDeterministic16Hex() {
        val head = ByteArray(64 * 1024) { (it % 7).toByte() }
        val tail = ByteArray(64 * 1024) { (it % 5).toByte() }
        val h1 = OpenSubtitlesHash.hash(123456789L, head, tail)
        val h2 = OpenSubtitlesHash.hash(123456789L, head, tail)
        assertEquals(h1, h2)
        assertEquals(16, h1.length)
        assertTrue(h1.all { it in "0123456789abcdef" })
    }
}
