package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.repository.JsonSourceRepository
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
import com.therealityteam.videoplayer.data.source.LocalSource
import com.therealityteam.videoplayer.data.source.SourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 移除来源 keeps a source's entries; adding the same folder again (a new source, so new ids) takes
 * them over — one entry per file still, its match, favourite and progress with it.
 */
class ReaddedSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private object NoMetadata : MetadataClient {
        override val name = "none"
        override val isConfigured = false
        override suspend fun search(parsed: ParsedName): List<MetaResult> = emptyList()
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? = null
    }

    @Test fun `a folder added again takes over what its removed source left`() = runBlocking {
        val files = tmp.newFolder("app")
        val movies = tmp.newFolder("Movies")
        File(movies, "Arrival.2016.1080p.mkv").writeBytes(ByteArray(16))
        File(movies, "Dunkirk.2017.1080p.mkv").writeBytes(ByteArray(16))
        val repo = JsonMediaRepository(files)
        val sources = JsonSourceRepository(files)
        val scrape = ScrapeEngine(
            client = NoMetadata, artwork = ArtworkCache(files) { _, _, _ -> null }, repo = repo, anilist = AniListClient(files), bangumi = BangumiClient(files),
            omdb = OmdbClient(files), jikan = JikanClient(files), imdb = ImdbDatasetClient(files),
        )
        val moved = mutableMapOf<String, String>()
        val scanner = SourceScanner(repo, sources, scrape, listOf(LocalSource()), onAdopted = { moved += it })

        val a = MediaSourceConfig(id = "A", type = SourceType.LOCAL, name = "Movies", uri = movies.absolutePath)
        sources.upsert(a)
        assertEquals(2, scanner.discover(a))
        // What the library came to know of them: a match, a favourite, a film half watched.
        repo.replaceAll(repo.all().map {
            it.copy(title = "Known ${it.fileName}", scrapeStatus = ScrapeStatus.MATCHED, favorite = true,
                progress = it.progress.copy(positionMs = 600_000, durationMs = 6_000_000))
        })

        sources.remove("A")
        val b = a.copy(id = "B")
        sources.upsert(b)
        assertEquals(2, scanner.discover(b))

        val all = repo.all()
        assertEquals(2, all.size)
        assertTrue(all.all { it.sourceId == "B" && it.id == MediaItem.idFor("B", it.path) })
        assertTrue(all.all { it.scrapeStatus == ScrapeStatus.MATCHED && it.title.startsWith("Known ") && it.favorite })
        assertTrue(all.all { it.progress.positionMs == 600_000L })
        // …and what is kept by id elsewhere is told where each entry went.
        assertEquals(all.map { it.id }.toSet(), moved.values.toSet())
        assertTrue(moved.keys.all { it == MediaItem.idFor("A", it.let { k -> all.first { e -> moved[k] == e.id }.path }) })

        // A removed source's copy of a file the scanning source already has does not overwrite it — it goes.
        repo.replaceAll(repo.all() + repo.all().map { it.copy(id = "old-" + it.id, sourceId = "GONE", favorite = false, title = "Stale") })
        assertEquals(4, repo.all().size)
        scanner.discover(b)
        assertEquals(2, repo.all().size)
        assertTrue(repo.all().all { it.sourceId == "B" && it.favorite && it.title.startsWith("Known ") })
    }
}
