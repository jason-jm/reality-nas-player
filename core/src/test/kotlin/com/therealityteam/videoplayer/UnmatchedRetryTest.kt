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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * A file that was looked up and matched nothing is asked again when the user asks for a 刮削 — and
 * not by every pass that follows a source being added or listed again: on a real NAS that re-asked
 * 2,604 files for five minutes and re-saved the library each time for the same answers.
 */
class UnmatchedRetryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val asked = AtomicInteger()

    private val client = object : MetadataClient {
        override val name = "counting"
        override val isConfigured = true
        override suspend fun search(parsed: ParsedName): List<MetaResult> {
            asked.incrementAndGet()
            return emptyList()
        }
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? = null
    }

    private fun item(id: String, status: ScrapeStatus) = MediaItem(
        id = id, sourceId = "s", path = "smb://nas/video/Movie/Some Film (2004)/Some Film (2004).mkv",
        fileName = "Some Film (2004).mkv", title = "Some Film", kind = MediaKind.MOVIE, scrapeStatus = status,
    )

    private fun engine(repo: JsonMediaRepository, files: java.io.File) = ScrapeEngine(
        client = client,
        artwork = ArtworkCache(files) { _, _, _ -> null },
        repo = repo,
        anilist = AniListClient(files),
        bangumi = BangumiClient(files),
        omdb = OmdbClient(files),
        jikan = JikanClient(files),
        imdb = ImdbDatasetClient(files),
    )

    @Test fun `a pass after a scan leaves an unmatched file alone, and a requested one asks again`() = runBlocking {
        val files = tmp.newFolder()
        val repo = JsonMediaRepository(files)
        repo.loaded.first { it }
        repo.upsertAll(listOf(item("u", ScrapeStatus.UNMATCHED)))
        val engine = engine(repo, files)

        engine.scrapeAll(repo.all(), retryUnmatched = false)
        assertEquals("not asked again after a scan", 0, asked.get())

        engine.scrapeAll(repo.all())
        assertTrue("asked again on a requested 刮削", asked.get() > 0)
    }

    @Test fun `a pass after a scan still looks up what is new`() = runBlocking {
        val files = tmp.newFolder()
        val repo = JsonMediaRepository(files)
        repo.loaded.first { it }
        repo.upsertAll(listOf(item("p", ScrapeStatus.PENDING)))
        engine(repo, files).scrapeAll(repo.all(), retryUnmatched = false)
        assertTrue("a new file is looked up", asked.get() > 0)
    }
}
