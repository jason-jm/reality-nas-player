package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.HanFold
import com.therealityteam.videoplayer.ui.library.Romanizer
import com.therealityteam.videoplayer.ui.library.TextMemo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The titles' romanisations and foldings, kept for the next launch on the same build and system only. */
class TextMemoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `romanisations come back for the same stamp, not another`() {
        val f = tmp.root.resolve("aura/texts.tsv")
        Romanizer.prime(mapOf("刀剑神域" to "dao jian shen yu", "テスト" to "", "bad\ttext" to "x"))
        HanFold.prime(mapOf("進撃の巨人" to "进击の巨人"))
        val stamp = TextMemo.stamp(7L)
        assert(TextMemo.save(f, stamp) >= 3)
        assertEquals(0, TextMemo.load(f, TextMemo.stamp(8L)))
        assert(TextMemo.load(f, stamp) >= 3)
        assertEquals("dao jian shen yu", Romanizer.soFar()["刀剑神域"])
        assertEquals("", Romanizer.soFar()["テスト"])
        assertEquals("进击の巨人", HanFold.soFar()["進撃の巨人"])
        val written = f.readLines()
        assertEquals(0, written.count { it.contains("bad") })
    }
}
