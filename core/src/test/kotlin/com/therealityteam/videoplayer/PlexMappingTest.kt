package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.PlexClient
import com.therealityteam.videoplayer.data.source.PlexSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plex item → library entry, against the server's JSON shapes. As with Jellyfin, the
 * properties that matter are the ones the rest of the app draws conclusions from: born
 * MATCHED, a collectionKey so a show cards as one, and a token-free path so ids survive
 * re-scans while the token still signs every fetched URL.
 */
class PlexMappingTest {

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/plex/$name")!!.bufferedReader().use { it.readText() }

    private val config = MediaSourceConfig(id = "px_1", type = SourceType.PLEX, name = "客厅 Plex", uri = "http://nas:32400", password = "tok-123")
    private val session = PlexClient.Session("http://nas:32400", "tok-123")

    private fun map(item: PlexClient.Item) = PlexSource.toMediaItem(config, session, item, now = 1_000_000L, index = 0).first

    @Test
    fun `only movie and show sections are read`() {
        val sections = PlexClient.parseSections(fixture("sections.json"))
        assertEquals(listOf("1" to "movie", "2" to "show", "3" to "artist"), sections.map { it.key to it.type })
    }

    @Test
    fun `a movie is born matched with the server's identity`() {
        val (items, total) = PlexClient.parseItemsPage(fixture("movies.json"))
        assertEquals(2, total)
        val m = map(items.first { it.title == "千与千寻" })
        assertEquals(ScrapeStatus.MATCHED, m.scrapeStatus)
        assertEquals(MediaKind.MOVIE, m.kind)
        assertEquals(129, m.tmdbId)
        assertEquals(2001, m.year)
        assertEquals(125, m.runtime)
        assertEquals("PG", m.certification)
        assertEquals(9.6, m.rating!!, 0.001)                       // the audience score wins over the critic score
        assertEquals("千と千尋の神隠し", m.originalTitle)
        assertEquals(listOf("动画", "奇幻"), m.genres)
        assertEquals("柊瑠美", m.cast.first().name)
        assertEquals("千寻 (voice)", m.cast.first().role)
        assertEquals("宫崎骏", m.crew.first { it.role == "导演" }.name)
        assertEquals("Spirited.Away.2001.1080p.BluRay.x265.mkv", m.fileName)
        assertEquals(Int.MAX_VALUE, m.ratingsVersion)
        // Token-free path; the token only signs the URL at play time.
        assertEquals("http://nas:32400/library/parts/601/1700000001/file.mkv", m.path)
        assertFalse(m.path.contains("tok-123"))
        assertEquals("http://nas:32400/library/parts/601/1700000001/file.mkv?X-Plex-Token=tok-123", PlexClient.signed(m.path, "tok-123"))
        // A movie without an audience score falls back to the critic score.
        assertEquals(8.7, map(items.first { it.title == "Heat" }).rating!!, 0.001)
    }

    @Test
    fun `an episode cards under its show, not itself`() {
        val eps = PlexClient.parseItemsPage(fixture("episodes.json")).first.map { map(it) }
        assertEquals(1, eps.map { it.collectionKey }.distinct().size)
        assertEquals("plex:299", eps.first().collectionKey)
        val e1 = eps.first { it.episode == 1 }
        assertEquals(MediaKind.EPISODE, e1.kind)
        assertEquals("Pioneer One", e1.title)
        assertEquals("Pioneer One", e1.showTitle)
        assertEquals(1, e1.season)
        assertEquals(35, e1.runtime)
        assertEquals("S01E01.mkv", e1.fileName)
    }

    @Test
    fun `images and streams are signed, the base url is normalised`() {
        assertEquals("http://nas:32400", PlexClient.normalizeBaseUrl("nas"))
        assertEquals("http://192.168.1.5:32400", PlexClient.normalizeBaseUrl("192.168.1.5/"))
        assertEquals("http://nas:8080", PlexClient.normalizeBaseUrl("http://nas:8080"))
        assertEquals("https://plex.example.com", PlexClient.normalizeBaseUrl("https://plex.example.com/"))
        assertEquals("http://nas:32400/library/metadata/101/thumb/1?X-Plex-Token=tok-123", PlexClient.imageUrl(session, "/library/metadata/101/thumb/1"))
        assertEquals(null, PlexClient.imageUrl(session, null))
        assertTrue(PlexClient.headers("t")["X-Plex-Token"] == "t")
        assertEquals("http://nas:32400/library/sections/2/all?type=4&includeGuids=1&X-Plex-Container-Start=500&X-Plex-Container-Size=500", PlexClient.itemsUrl(session, "2", 4, 500, 500))
    }
}
