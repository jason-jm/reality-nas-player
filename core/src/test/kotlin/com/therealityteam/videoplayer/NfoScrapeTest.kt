package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
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
import com.therealityteam.videoplayer.data.source.LocalSource
import com.therealityteam.videoplayer.data.source.SidecarReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A file with an nfo beside it is identified by the nfo's database id, not by its name; offline the
 * nfo's own words fill the page; a series' episodes take the series' id; and the pictures beside the
 * file are the ones shown. Run over a real folder layout through the real local walk.
 */
class NfoScrapeTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeTmdb(private val answers: Map<Pair<Int, MediaKind>, MetaResult>, private val online: Boolean = true) : MetadataClient {
        var searches = 0
        val detailsAsked = mutableListOf<Pair<Int, MediaKind>>()
        override val name = "fake"
        override val isConfigured: Boolean get() = online
        override suspend fun search(parsed: ParsedName): List<MetaResult> { searches++; return emptyList() }
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? {
            detailsAsked += providerId to kind
            return if (online) answers[providerId to kind] else null
        }
        override suspend fun findByImdb(imdbId: String): MetaResult? =
            if (online && imdbId == "tt0133093") MetaResult(603, MediaKind.MOVIE, "The Matrix") else null
    }

    private val files by lazy { tmp.newFolder("files") }
    private val library by lazy { tmp.newFolder("library") }

    private fun engine(client: MetadataClient) = ScrapeEngine(
        client = client,
        artwork = ArtworkCache(files) { _, _, _ -> null },
        repo = JsonMediaRepository(files),
        anilist = AniListClient(files),
        bangumi = BangumiClient(files),
        omdb = OmdbClient(files),
        jikan = JikanClient(files),
        imdb = ImdbDatasetClient(files),
        sidecars = SidecarReader { _, path, max -> File(path).takeIf { it.isFile && it.length() <= max }?.readBytes() },
    )

    private fun write(rel: String, text: String = "") = File(library, rel).apply { parentFile.mkdirs(); writeText(text) }

    /** The items a scan of the library folder makes, as the scanner builds them. */
    private fun scan(): List<MediaItem> = runBlocking {
        val config = MediaSourceConfig("local", SourceType.LOCAL, "Library", library.absolutePath)
        LocalSource().list(config).map { v ->
            MediaItem(
                id = MediaItem.idFor(config.id, v.path), sourceId = config.id, path = v.path, fileName = v.fileName,
                title = v.fileName.substringBeforeLast('.'), local = v.local,
            )
        }
    }

    private val dunkirkNfo = """
        <?xml version="1.0" encoding="UTF-8"?><movie>
        <title>敦刻尔克</title><originaltitle>Dunkirk</originaltitle>
        <ratings><rating name="tmdb" max="10" default="true"><value>7.455</value><votes>15676</votes></rating></ratings>
        <plot>二战初期……</plot><runtime>107</runtime><mpaa>PG-13</mpaa>
        <thumb aspect="poster">http://image.tmdb.org/t/p/original/b4Oe.jpg</thumb>
        <uniqueid type="tmdb" default="true">374720</uniqueid><uniqueid type="imdb">tt5013056</uniqueid>
        <genre>战争</genre><year>2017</year>
        </movie>
    """.trimIndent()

    private fun dunkirk() {
        val d = "Movies/Dunkirk.2017.1080p.BluRay.x264-SPARKS[rarbg]"
        write("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS.mkv")
        write("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS.nfo", dunkirkNfo)
        write("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS-poster.jpg", "POSTER")
        write("$d/Dunkirk.2017.1080p.BluRay.x264-SPARKS-fanart.jpg", "FANART")
    }

    @Test fun `the nfo's id decides, the name is never searched, and its pictures are shown`() = runBlocking {
        dunkirk()
        val item = scan().single()
        val tmdb = FakeTmdb(mapOf((374720 to MediaKind.MOVIE) to MetaResult(374720, MediaKind.MOVIE, "敦刻尔克", originalTitle = "Dunkirk", year = 2017)))
        val out = engine(tmdb).scrape(item)

        assertEquals(0, tmdb.searches)
        assertEquals(listOf(374720 to MediaKind.MOVIE), tmdb.detailsAsked)
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(374720, out.tmdbId)
        assertEquals(MediaKind.MOVIE, out.kind)
        assertEquals("敦刻尔克", out.title)
        assertTrue(out.fromNfo)
        assertTrue(out.identityPinned)
        assertArrayEquals("POSTER".toByteArray(), File(out.posterPath!!).readBytes())
        assertArrayEquals("FANART".toByteArray(), File(out.backdropPath!!).readBytes())
        assertEquals(item.local!!.stamp, out.localApplied)
    }

    @Test fun `offline, the nfo's own words fill the page`() = runBlocking {
        dunkirk()
        val item = scan().single()
        val was = TmdbConfig.offline
        TmdbConfig.offline = true
        try {
            val out = engine(FakeTmdb(emptyMap(), online = false)).scrape(item)
            assertEquals(374720, out.tmdbId)
            assertEquals("敦刻尔克", out.title)
            assertEquals("Dunkirk", out.originalTitle)
            assertEquals(2017, out.year)
            assertEquals(107, out.runtime)
            assertEquals("PG-13", out.certification)
            assertEquals(listOf("战争"), out.genres)
            assertEquals("7.5", out.ratings.single { it.source == RatingSource.TMDB }.value)
            assertArrayEquals("POSTER".toByteArray(), File(out.posterPath!!).readBytes())
        } finally {
            TmdbConfig.offline = was
        }
    }

    @Test fun `an episode is filed under its series' id, at the episode its own nfo names`() = runBlocking {
        write("TV/Shogun/tvshow.nfo", """<tvshow><title>Shōgun</title><uniqueid type="tmdb">126308</uniqueid></tvshow>""")
        write("TV/Shogun/poster.jpg", "SHOW POSTER")
        write("TV/Shogun/Season 1/ep.mkv")
        write("TV/Shogun/Season 1/ep.nfo", """<episodedetails><title>Anjin</title><season>1</season><episode>2</episode><uniqueid type="tmdb">9999</uniqueid></episodedetails>""")
        val item = scan().single()
        val tmdb = FakeTmdb(mapOf((126308 to MediaKind.SHOW) to MetaResult(126308, MediaKind.SHOW, "幕府将军")))
        val out = engine(tmdb).scrape(item)

        assertEquals(126308, out.tmdbId)
        assertEquals(MediaKind.EPISODE, out.kind)
        assertEquals(1, out.season)
        assertEquals(2, out.episode)
        assertEquals("幕府将军", out.showTitle)
        assertArrayEquals("SHOW POSTER".toByteArray(), File(out.posterPath!!).readBytes())
    }

    @Test fun `a URL nfo with an IMDb id is looked up once, then fetched by id`() = runBlocking {
        write("Films/The Matrix/The.Matrix.1999.mkv")
        write("Films/The Matrix/The.Matrix.1999.nfo", "http://www.imdb.com/title/tt0133093/")
        val item = scan().single()
        val tmdb = FakeTmdb(mapOf((603 to MediaKind.MOVIE) to MetaResult(603, MediaKind.MOVIE, "黑客帝国", year = 1999)))
        val out = engine(tmdb).scrape(item)
        assertEquals(0, tmdb.searches)
        assertEquals(603, out.tmdbId)
        assertEquals("黑客帝国", out.title)
    }

    @Test fun `an nfo without an id leaves the name to be asked, and its pictures still count`() = runBlocking {
        write("Films/Home/Home.2009.mkv")
        write("Films/Home/Home.2009.nfo", "<movie><title>Home</title><year>2009</year></movie>")
        write("Films/Home/poster.jpg", "HOME")
        val item = scan().single()
        val tmdb = FakeTmdb(emptyMap())
        val out = engine(tmdb).scrape(item)
        assertTrue(tmdb.searches > 0)
        assertFalse(out.fromNfo)
        assertArrayEquals("HOME".toByteArray(), File(out.posterPath!!).readBytes())
        assertEquals(item.local!!.stamp, out.localApplied)
    }
}
