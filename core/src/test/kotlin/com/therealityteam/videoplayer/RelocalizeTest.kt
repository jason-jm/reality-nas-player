package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.scrape.AniListClient
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.scrape.BangumiClient
import com.therealityteam.videoplayer.data.scrape.ImdbDatasetClient
import com.therealityteam.videoplayer.data.scrape.JikanClient
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.MetadataClient
import com.therealityteam.videoplayer.data.scrape.OmdbClient
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 元数据语言 changed: each work matched online is fetched once more in the new language, its files' words rewritten,
 * their identity (ids, a match fixed by hand, progress) untouched; an nfo's words and an entry that does not answer
 * stay as they were.
 */
class RelocalizeTest {

    @get:Rule val tmp = TemporaryFolder()
    private val saved = TmdbConfig.language

    @After fun restore() { TmdbConfig.language = saved }

    /** TMDB in German: The Matrix and a series, asked once each, counted. */
    private class Tmdb : MetadataClient {
        val asked = mutableListOf<Pair<Int, MediaKind>>()
        override val name = "fake"
        override val isConfigured = true
        override suspend fun search(parsed: ParsedName): List<MetaResult> = emptyList()
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? {
            synchronized(asked) { asked += providerId to kind }
            return when (providerId to kind) {
                603 to MediaKind.MOVIE -> MetaResult(603, MediaKind.MOVIE, "Matrix", overview = "Ein Hacker erfährt…", genres = listOf("Action", "Science Fiction"))
                1399 to MediaKind.SHOW -> MetaResult(1399, MediaKind.SHOW, "Game of Thrones", overview = "Sieben Königreiche…", genres = listOf("Drama"))
                else -> null
            }
        }
    }

    private fun item(id: String, title: String, tmdbId: Int, kind: MediaKind, showTitle: String? = null, userMatched: Boolean = false, fromNfo: Boolean = false) =
        MediaItem(
            id, "nas", "smb://nas/video/$id.mkv", "$id.mkv", title = title, overview = "中文简介", genres = listOf("动作"),
            tmdbId = tmdbId, kind = kind, showTitle = showTitle, userMatched = userMatched, fromNfo = fromNfo,
            scrapeStatus = ScrapeStatus.MATCHED,
        )

    @Test fun `each work is fetched once and its files' words rewritten, their identity kept`() = runBlocking {
        val files = tmp.newFolder()
        val repo = JsonMediaRepository(files)
        repo.loaded.first { it }
        val tmdb = Tmdb()
        val engine = ScrapeEngine(
            tmdb, ArtworkCache(files) { _, _, _ -> null }, repo,
            AniListClient(files), BangumiClient(files), OmdbClient(files), JikanClient(files), ImdbDatasetClient(files),
        )
        val film = item("matrix", "黑客帝国", 603, MediaKind.MOVIE, userMatched = true)
        val e1 = item("got-1", "权力的游戏", 1399, MediaKind.EPISODE, showTitle = "权力的游戏").copy(season = 1, episode = 1)
        val e2 = item("got-2", "权力的游戏", 1399, MediaKind.EPISODE, showTitle = "权力的游戏").copy(season = 1, episode = 2)
        val nfo = item("own", "我的片子", 603, MediaKind.MOVIE, fromNfo = true)
        val gone = item("gone", "失踪的", 999, MediaKind.MOVIE)
        repo.upsertAll(listOf(film, e1, e2, nfo, gone))
        TmdbConfig.language = "de-DE"

        val rewritten = engine.relocalize(repo.all())

        assertEquals(2, rewritten)
        // Each work that answers asked once (the series once for both episodes); the nfo's not at all.
        assertEquals(1, tmdb.asked.count { it == 603 to MediaKind.MOVIE })
        assertEquals(1, tmdb.asked.count { it == 1399 to MediaKind.SHOW })
        assertEquals("Matrix", repo.get("matrix")!!.title)
        assertEquals("Ein Hacker erfährt…", repo.get("matrix")!!.overview)
        assertTrue(repo.get("matrix")!!.userMatched)
        assertEquals(603, repo.get("matrix")!!.tmdbId)
        assertEquals("Game of Thrones", repo.get("got-2")!!.showTitle)
        assertEquals(2, repo.get("got-2")!!.episode)
        assertEquals("我的片子", repo.get("own")!!.title)
        assertEquals("失踪的", repo.get("gone")!!.title)
    }
}
