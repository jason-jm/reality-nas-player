package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.TtmlToSrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TTML and DFXP as SubRip, for a player that reads no TTML. */
class TtmlToSrtTest {

    @Test
    fun `clock times, line breaks and spans`() {
        val ttml = """<?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling" xml:lang="zh">
              <body><div>
                <p begin="00:00:01.000" end="00:00:04.500">葬送的芙莉莲<br/><span tts:fontStyle="italic">勇者辛美尔死后</span></p>
                <p begin="00:01:02.25" end="00:01:05.000">Second   line</p>
              </div></body>
            </tt>""".trimIndent().toByteArray()
        assertEquals(
            "1\n00:00:01,000 --> 00:00:04,500\n葬送的芙莉莲\n勇者辛美尔死后\n\n" +
                "2\n00:01:02,250 --> 00:01:05,000\nSecond line\n",
            TtmlToSrt.convert(ttml),
        )
    }

    @Test
    fun `DFXP in ticks, a duration, a div's offset`() {
        val dfxp = """<tt xmlns="http://www.w3.org/2006/10/ttaf1" xmlns:ttp="http://www.w3.org/2006/10/ttaf1#parameter" ttp:tickRate="10000000">
              <body><div begin="10s">
                <p begin="10000000t" end="40000000t">Ticks</p>
                <p begin="5s" dur="1500ms">Lasting</p>
              </div></body></tt>""".toByteArray()
        assertEquals(
            "1\n00:00:11,000 --> 00:00:14,000\nTicks\n\n2\n00:00:15,000 --> 00:00:16,500\nLasting\n",
            TtmlToSrt.convert(dfxp),
        )
    }

    @Test
    fun `frames at the file's rate`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25">
              <body><div><p begin="00:00:02:12" end="00:00:03:00">Framed</p></div></body></tt>""".toByteArray()
        assertEquals("1\n00:00:02,480 --> 00:00:03,000\nFramed\n", TtmlToSrt.convert(ttml))
    }

    @Test
    fun `not TTML, or nothing timed`() {
        assertNull(TtmlToSrt.convert("1\n00:00:01,000 --> 00:00:02,000\nSRT\n".toByteArray()))
        assertNull(TtmlToSrt.convert("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>".toByteArray()))
        assertNull(TtmlToSrt.convert("<!DOCTYPE tt [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><tt>&x;</tt>".toByteArray()))
    }
}
