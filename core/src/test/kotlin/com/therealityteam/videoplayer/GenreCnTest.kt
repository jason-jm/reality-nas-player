package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.GenreCn
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A stored genre read as the genres it stands for: TMDB's series pairs split, romance one word. */
class GenreCnTest {

    private var saved = ""
    @Before fun chinese() { saved = TmdbConfig.language; TmdbConfig.language = "zh-CN" }
    @After fun restore() { TmdbConfig.language = saved }

    @Test fun `a series pair is its parts, in English or in Chinese`() {
        assertEquals(listOf("科幻", "奇幻"), GenreCn.zhAll("Sci-Fi & Fantasy"))
        assertEquals(listOf("动作", "冒险"), GenreCn.zhAll("动作冒险"))
        assertEquals(listOf("动作", "冒险"), GenreCn.zhAll("Action & Adventure"))
        assertEquals(listOf("战争", "政治"), GenreCn.zhAll("War & Politics"))
    }

    @Test fun `one genre is one, named in Chinese, romance as films name it`() {
        assertEquals(listOf("机战"), GenreCn.zhAll("Mecha"))
        assertEquals(listOf("爱情"), GenreCn.zhAll("Romance"))
        assertEquals(listOf("剧情"), GenreCn.zhAll("剧情"))
    }

    @Test fun `an English reader gets the pair's parts and one word for science fiction`() {
        TmdbConfig.language = "en-US"
        assertEquals(listOf("Sci-Fi", "Fantasy"), GenreCn.zhAll("Sci-Fi & Fantasy"))
        assertEquals(listOf("Sci-Fi"), GenreCn.zhAll("Science Fiction"))
        assertEquals(listOf("Slice of Life"), GenreCn.zhAll("Slice of Life"))
    }

    @Test fun `AniList's English genres read in the reader's language, as TMDB names them`() {
        TmdbConfig.language = "de-DE"
        assertEquals(listOf("Science Fiction", "Fantasy"), GenreCn.zhAll("Sci-Fi & Fantasy"))
        assertEquals(listOf("Übernatürlich"), GenreCn.zhAll("Supernatural"))
        assertEquals(listOf("Komödie"), GenreCn.zhAll("Comedy"))
        assertEquals(listOf("Komödie"), GenreCn.zhAll("Komödie"))
        TmdbConfig.language = "ru"
        assertEquals(listOf("боевик", "приключения"), GenreCn.zhAll("Боевик и Приключения"))
        assertEquals(listOf("повседневность"), GenreCn.zhAll("Slice of Life"))
    }

    @Test fun `Traditional Chinese reads Traditional, Norwegian has its own words`() {
        TmdbConfig.language = "zh-TW"
        assertEquals(listOf("動作", "冒險"), GenreCn.zhAll("Action & Adventure"))
        TmdbConfig.language = "zh-Hant-HK"
        assertEquals(listOf("機戰"), GenreCn.zhAll("Mecha"))
        TmdbConfig.language = "nb-NO"
        assertEquals(listOf("Skrekk"), GenreCn.zhAll("Horror"))
        TmdbConfig.language = "no"
        assertEquals(listOf("Krim"), GenreCn.zhAll("Crime"))
    }
}
