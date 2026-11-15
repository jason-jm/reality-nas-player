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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A match fixed by hand on another device, taken in here: filed and pinned when its entry can be
 * fetched, left exactly as it was when it cannot — an empty answer must never become a name.
 */
class AdoptMatchTest {

    @get:Rule val tmp = TemporaryFolder()

    private class Tmdb(private val online: Boolean) : MetadataClient {
        override val name = "fake"
        override val isConfigured = online
        override suspend fun search(parsed: ParsedName): List<MetaResult> = emptyList()
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? =
            if (online && providerId == 603 && kind == MediaKind.MOVIE) MetaResult(603, MediaKind.MOVIE, "黑客帝国", year = 1999) else null
    }

    private fun setup(online: Boolean): Pair<ScrapeEngine, JsonMediaRepository> {
        val files = tmp.newFolder()
        val repo = JsonMediaRepository(files)
        runBlocking { repo.loaded.first { it } }
        val engine = ScrapeEngine(
            Tmdb(online), ArtworkCache(files) { _, _, _ -> null }, repo,
            AniListClient(files), BangumiClient(files), OmdbClient(files), JikanClient(files), ImdbDatasetClient(files),
        )
        return engine to repo
    }

    private val wrong = MediaItem(
        "m", "nas", "smb://nas/video/Films/The.Matrix.1999.mkv", "The.Matrix.1999.mkv",
        title = "The Matrix Reloaded", tmdbId = 604, kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.MATCHED,
    )

    @Test fun `a fix whose entry can be fetched is filed and pinned`() = runBlocking {
        val (engine, repo) = setup(online = true)
        repo.upsertAll(listOf(wrong))
        assertTrue(engine.adoptMatch(listOf(wrong), "tmdb", 603, MediaKind.MOVIE))
        val now = repo.get("m")!!
        assertEquals(603, now.tmdbId)
        assertEquals("黑客帝国", now.title)
        assertTrue(now.userMatched)
    }

    @Test fun `a fix whose entry cannot be fetched leaves the file as it was`() = runBlocking {
        val (engine, repo) = setup(online = false)
        repo.upsertAll(listOf(wrong))
        assertFalse(engine.adoptMatch(listOf(wrong), "tmdb", 603, MediaKind.MOVIE))
        assertEquals(wrong, repo.get("m"))
    }
}
