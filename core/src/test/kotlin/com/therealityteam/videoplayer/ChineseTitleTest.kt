package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.ChineseTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Chinese name from the regions' titles when the Chinese translation has none. */
class ChineseTitleTest {

    /** Andor's alternative titles, as TMDB lists them (a sample, in its order). */
    private val andor = listOf(
        "BR" to "Andor: Uma História Star Wars", "CN" to "星球大战：安多", "CN" to "安多", "US" to "Star Wars - Andor",
        "JP" to "キャシアン・アンドー", "KR" to "안도르", "TW" to "星際大戰：安道爾", "US" to "Star Wars: Andor",
    )

    @Test fun `the reader's region names it first`() {
        assertEquals("星球大战：安多", ChineseTitle.pick(andor, "zh-CN"))
        assertEquals("星際大戰：安道爾", ChineseTitle.pick(andor, "zh-TW"))
        assertEquals("星際大戰：安道爾", ChineseTitle.pick(andor, "zh-Hant-HK"))
    }

    @Test fun `the other script before none, and nothing when no region names it in Chinese`() {
        assertEquals("星際大戰：骨幹小隊", ChineseTitle.pick(listOf("TW" to "星際大戰：骨幹小隊", "FR" to "Escouade squelette"), "zh-CN"))
        // A CN entry in Latin letters is not a Chinese name.
        assertNull(ChineseTitle.pick(listOf("CN" to "Top Gun 2", "RU" to "Лучший стрелок 2"), "zh-CN"))
        assertNull(ChineseTitle.pick(emptyList(), "zh-CN"))
    }

    @Test fun `only a title in letters wants a Chinese name — digits read the same everywhere`() {
        assertTrue(ChineseTitle.wantsChineseName("Andor"))
        assertTrue(ChineseTitle.wantsChineseName("JAPANデビュー"))
        assertFalse(ChineseTitle.wantsChineseName("1917"))
        assertFalse(ChineseTitle.wantsChineseName("2012"))
        assertFalse(ChineseTitle.wantsChineseName("1408幻影凶间"))
        assertFalse(ChineseTitle.wantsChineseName(""))
    }

    @Test fun `Han characters, kana-free or not, count as the reader's script`() {
        assertTrue(ChineseTitle.hasHan("欧比旺·克诺比"))
        assertTrue(ChineseTitle.hasHan("将軍家光忍び旅"))
        assertFalse(ChineseTitle.hasHan("Obi-Wan Kenobi"))
        assertFalse(ChineseTitle.hasHan("キャシアン・アンドー"))
    }
}
