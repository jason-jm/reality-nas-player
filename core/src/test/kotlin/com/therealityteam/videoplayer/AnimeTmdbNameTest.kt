package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TMDB's Chinese name for an anime bgm.tv could not name — only from the work's own entry. Titles as TMDB and AniList give them. */
class AnimeTmdbNameTest {

    @Test fun `TMDB's entry is the work when its original title holds the work's whole`() {
        assertTrue(AnimeMatch.sameWorkByName("機動警察パトレイバー 劇場版", "機動警察パトレイバー 劇場版"))
        assertTrue(AnimeMatch.sameWorkByName("ソードアート・オンライン -オーディナル・スケール-", "劇場版 ソードアート・オンライン -オーディナル・スケール-"))
        assertTrue(AnimeMatch.sameWorkByName("るろうに剣心 -明治剣客浪漫譚-", "るろうに剣心 明治剣客浪漫譚"))
        assertTrue(AnimeMatch.sameWorkByName("機動戦士Ζガンダム A New Translation -星を継ぐ者-", "機動戦士Ζガンダム A New Translation 星を継ぐ者"))
        assertTrue(AnimeMatch.sameWorkByName("Fate/Zero", "Fate/Zero"))
        assertTrue(AnimeMatch.sameWorkByName("機動戦士ΖガンダムII A New Translation -恋人たち-", "機動戦士ZガンダムII A New Translation 恋人たち"))
    }

    @Test fun `a sibling or a shorter name is not the work`() {
        // TMDB's first answer for Zeta Gundam's first film is the second.
        assertFalse(AnimeMatch.sameWorkByName("機動戦士Ζガンダム A New Translation -星を継ぐ者-", "機動戦士ZガンダムII A New Translation 恋人たち"))
        // The whole show for its first season; the series for a film.
        assertFalse(AnimeMatch.sameWorkByName("銀河英雄伝説 -Die Neue These- 邂逅", "銀河英雄伝説 Die Neue These"))
        assertFalse(AnimeMatch.sameWorkByName("機動警察パトレイバー 劇場版", "機動警察パトレイバー"))
        assertFalse(AnimeMatch.sameWorkByName(null, "Fate/Zero"))
        assertFalse(AnimeMatch.sameWorkByName("Fate/Zero", null))
    }

    @Test fun `only a name in Han characters is a Chinese name`() {
        assertEquals("刀剑神域：序列之争", AnimeMatch.chineseName("刀剑神域：序列之争"))
        assertEquals("命运之夜 前传", AnimeMatch.chineseName(" 命运之夜 前传 "))
        assertEquals("机动战士Z高达Ⅰ：星之继承者", AnimeMatch.chineseName("机动战士Z高达Ⅰ：星之继承者"))
        assertEquals("新・福音战士剧场版：终", AnimeMatch.chineseName("新・福音战士剧场版：终"))
        // TMDB's fallback to the original where it has no translation.
        assertNull(AnimeMatch.chineseName("CLANNAD"))
        assertNull(AnimeMatch.chineseName("キノの旅"))
        assertNull(AnimeMatch.chineseName("劇場版 ソードアート・オンライン"))
        assertNull(AnimeMatch.chineseName(null))
    }
}
