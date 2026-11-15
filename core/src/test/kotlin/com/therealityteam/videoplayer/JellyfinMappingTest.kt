package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.JellyfinClient
import com.therealityteam.videoplayer.data.source.JellyfinSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server item → library entry, against the demo server's real responses. The properties that
 * matter are the ones the rest of the app draws conclusions from: born MATCHED so the scrape
 * pipeline finds nothing to do, a collectionKey so grouping (and needsScrape's anime-filename
 * fallback) never guesses, and a token-free path so item ids survive re-scans.
 */
class JellyfinMappingTest {

    private fun items(): List<JellyfinClient.Item> {
        val body = javaClass.getResourceAsStream("/jellyfin/items.json")!!.bufferedReader().use { it.readText() }
        return JellyfinClient.parseItemsPage(body).first
    }

    private val config = MediaSourceConfig(id = "jf_1", type = SourceType.JELLYFIN, name = "客厅", uri = "http://nas:8096")
    private val session = JellyfinClient.Session("http://nas:8096", "u1", "tok")

    private fun map(item: JellyfinClient.Item) =
        JellyfinSource.toMediaItem(config, session, item, now = 1_000_000L, index = 0).first

    @Test
    fun aMovieIsBornMatchedWithTheServersIdentity() {
        val m = map(items().first { it.name == "The Boy in the Plastic Bubble" })
        assertEquals(ScrapeStatus.MATCHED, m.scrapeStatus)
        assertEquals(MediaKind.MOVIE, m.kind)
        assertEquals(9878, m.tmdbId)
        assertEquals(1976, m.year)
        assertEquals(93, m.runtime)
        assertEquals("PG", m.certification)
        assertEquals("John Travolta", m.cast.first().name)
        // MAX_VALUE：未来任何评分管线升级都不得把服务器条目拖回猜谜机器。
        assertEquals(Int.MAX_VALUE, m.ratingsVersion)
    }

    @Test
    fun anEpisodeCardsUnderItsShowNotItself() {
        val eps = items().filter { it.type == JellyfinClient.ItemType.EPISODE }.map { map(it) }
        // 同一部剧的六集共享一个 collectionKey —— 这就是墙上"一部剧一张卡"的机制。
        assertEquals(1, eps.map { it.collectionKey }.distinct().size)
        assertTrue(eps.first().collectionKey!!.startsWith("jellyfin:"))
        val e4 = eps.first { it.episode == 4 }
        assertEquals(MediaKind.EPISODE, e4.kind)
        assertEquals("Pioneer One", e4.title)
        assertEquals("Pioneer One", e4.showTitle)
        assertEquals(1, e4.season)
    }

    @Test
    fun everyItemCarriesACollectionKeySoNothingFallsToTheAnimeGuess() {
        // needsScrape 的兜底会把 collectionKey==null 且文件名像番剧的条目送去 AniList；
        // 服务器条目一律带 key，就永远进不了那条路。
        assertTrue(items().map { map(it) }.all { it.collectionKey != null })
    }

    @Test
    fun thePathIsPlayableButTokenFree() {
        val m = map(items().first { it.type == JellyfinClient.ItemType.MOVIE })
        assertTrue(m.path, m.path.startsWith("http://nas:8096/Videos/") && m.path.endsWith("/stream?static=true"))
        // token 进了 path，条目 id 就会随每次扫描轮换，观看进度全丢 —— 所以它不允许在这里出现。
        assertFalse("token must not live in the path", m.path.contains("tok"))
        assertFalse(m.path.contains("api_key"))
    }

    @Test
    fun theFileNameIsTheServersRealBasename() {
        val m = map(items().first { it.name == "The Boy in the Plastic Bubble" })
        assertEquals("The Boy in the Plastic Bubble (1976).mp4", m.fileName)
    }

    @Test
    fun theServersThreeDFormatMarksA3dFilm() {
        val sbs = JellyfinSource.flagsOf("Avatar (2009).mkv", "HalfSideBySide")
        assertEquals(com.therealityteam.videoplayer.data.model.StereoMode.SBS, sbs.stereo)
        assertEquals(com.therealityteam.videoplayer.data.model.ProjectionMode.FLAT, sbs.projection)
        assertEquals(com.therealityteam.videoplayer.data.model.StereoMode.OU, JellyfinSource.flagsOf("Hugo.mkv", "FullTopAndBottom").stereo)
        // MVC's views are coded apart: one picture a frame. And the name's own packing stands.
        assertEquals(JellyfinSource.flagsOf("Gravity.mkv", null), JellyfinSource.flagsOf("Gravity.mkv", "MVC"))
        assertEquals(com.therealityteam.videoplayer.data.model.StereoMode.OU, JellyfinSource.flagsOf("Gravity.3D.HOU.mkv", "HalfSideBySide").stereo)
        // A 360 by its name keeps its sphere and takes the server's packing.
        val pano = JellyfinSource.flagsOf("Reef_360_vr.mp4", "FullTopAndBottom")
        assertEquals(com.therealityteam.videoplayer.data.model.ProjectionMode.EQ360, pano.projection)
        assertEquals(com.therealityteam.videoplayer.data.model.StereoMode.OU, pano.stereo)
    }
}
