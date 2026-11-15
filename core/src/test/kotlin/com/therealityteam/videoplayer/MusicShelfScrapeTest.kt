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
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.MetadataClient
import com.therealityteam.videoplayer.data.scrape.OmdbClient
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * What a pass leaves on a music shelf's clip: the answer its own name names, or nothing — never the
 * poster of an answer an earlier pass gave and this one did not — and, when the database could not
 * be asked at all, what the clip already had.
 */
class MusicShelfScrapeTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeTmdb(private val hits: Map<String, MetaResult>, private val down: Boolean = false) : MetadataClient {
        override val name = "fake"
        override val isConfigured = true
        override suspend fun search(parsed: ParsedName): List<MetaResult> {
            if (down) throw IOException("unreachable")
            return listOfNotNull(hits[parsed.title])
        }
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? =
            hits.values.firstOrNull { it.providerId == providerId }
    }

    private fun engine(client: MetadataClient) = tmp.newFolder().let { files ->
        ScrapeEngine(
            client = client,
            artwork = ArtworkCache(files) { _, _, _ -> null },
            repo = JsonMediaRepository(files),
            anilist = AniListClient(files),
            bangumi = BangumiClient(files),
            omdb = OmdbClient(files),
            jikan = JikanClient(files),
            imdb = ImdbDatasetClient(files),
        )
    }

    /** A clip an earlier pass matched to a film, wearing that film's poster and genres. */
    private fun wearing(path: String, id: Int) = MediaItem(
        id = "m", sourceId = "s", path = path, fileName = path.substringAfterLast('/'), title = "Guitar",
        kind = MediaKind.MOVIE, tmdbId = id, posterPath = "/files/aura/artwork/tmdb_${id}__5e1f.jpg",
        genres = listOf("音乐"), overview = "A film.", scrapeStatus = ScrapeStatus.MATCHED, ratingsVersion = 19,
    )

    private val guitar = "smb://nas/video/Music/Guitar/Larry Carlton Room335.wmv"

    @Test fun `a clip nothing names now wears nothing an earlier pass gave it`() = runBlocking {
        val out = engine(FakeTmdb(emptyMap())).scrape(wearing(guitar, 1052445))
        assertEquals(ScrapeStatus.UNMATCHED, out.scrapeStatus)
        assertNull(out.tmdbId)
        assertNull(out.posterPath)
        assertTrue(out.genres.isEmpty())
        assertNull(out.overview)
    }

    @Test fun `a hit that merely holds the clip's word is refused`() = runBlocking {
        val path = "smb://nas/video/Music/Guitar/Marcel Coenen/jamming.rm"
        val saya = MetaResult(815153, MediaKind.MOVIE, "Jamming on an Old Saya", popularity = 0.6)
        // The matcher alone takes it, as it did on the real shelf.
        assertNotNull(Matcher.best(ParsedName("jamming"), listOf(saya)))
        val out = engine(FakeTmdb(mapOf("jamming" to saya))).scrape(wearing(path, 815153))
        assertEquals(ScrapeStatus.UNMATCHED, out.scrapeStatus)
        assertNull(out.tmdbId)
        assertNull(out.posterPath)
    }

    @Test fun `a concert its file names is matched`() = runBlocking {
        val path = "smb://nas/video/Music/Band/Casino Lights 99 A.wmv"
        val casino = MetaResult(
            177539, MediaKind.MOVIE, "Casino Lights '99 - Live At The Montreux Jazz Festival",
            genres = listOf("音乐"), popularity = 1.2,
        )
        val out = engine(FakeTmdb(mapOf("Casino Lights 99 A" to casino))).scrape(
            MediaItem(id = "c", sourceId = "s", path = path, fileName = "Casino Lights 99 A.wmv", title = "Casino Lights 99 A"),
        )
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(177539, out.tmdbId)
    }

    @Test fun `a clip whose own name finds another work is asked again by its concert's folder`() = runBlocking {
        val folder = "Macross F Galaxy Tour Final in Budokan"
        val name = "[$folder][Disc2][LIVE DOCUMENT][DVDRIP][848x480][H264_AACx2].mp4"
        val arashi = MetaResult(1214647, MediaKind.MOVIE, "嵐 LIVE & DOCUMENT ～15年目の告白～", originalTitle = "Arashi Live & Document", popularity = 2.0)
        val budokan = MetaResult(44212, MediaKind.MOVIE, folder, genres = listOf("音乐"), popularity = 1.5)
        // The matcher alone takes the other band's film for the disc's own name.
        assertNotNull(Matcher.best(ParsedName("LIVE DOCUMENT"), listOf(arashi)))
        val out = engine(FakeTmdb(mapOf("LIVE DOCUMENT" to arashi, folder to budokan))).scrape(
            MediaItem(id = "d", sourceId = "s", path = "smb://nas/video/Music/ACG/$folder/$name", fileName = name, title = name),
        )
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(44212, out.tmdbId)
    }

    @Test fun `a database that cannot be asked takes nothing away`() = runBlocking {
        val before = wearing("smb://nas/video/Movies/Guitar.2008/Guitar.2008.mkv", 1052445)
        val out = engine(FakeTmdb(emptyMap(), down = true)).scrape(before)
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(1052445, out.tmdbId)
        assertEquals(before.posterPath, out.posterPath)
        assertEquals(19, out.ratingsVersion)
    }
}
