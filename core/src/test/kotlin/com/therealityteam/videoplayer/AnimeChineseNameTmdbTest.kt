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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An anime film filed under its Chinese name, with AniList and Bangumi giving nothing (a headset at home in
 * China, where bgm.tv does not answer): TMDB is asked by that name as well as by the file's own title.
 */
class AnimeChineseNameTmdbTest {

    @get:Rule val tmp = TemporaryFolder()

    // 离线刮削 keeps AniList, Bangumi and Jikan from being asked at all; the fake TMDB below still answers.
    @Before fun noAnimeDatabases() { TmdbConfig.offline = true }
    @After fun online() { TmdbConfig.offline = false }

    private class FakeTmdb(private val hits: Map<String, MetaResult>) : MetadataClient {
        val asked = mutableListOf<String>()
        override val name = "fake"
        override val isConfigured = true
        override suspend fun search(parsed: ParsedName): List<MetaResult> {
            asked += parsed.title
            return listOfNotNull(hits[parsed.title])
        }
        override suspend fun details(providerId: Int, kind: MediaKind): MetaResult? = hits.values.firstOrNull { it.providerId == providerId }
    }

    private fun engine(client: MetadataClient) = tmp.newFolder().let { files ->
        ScrapeEngine(
            client = client, artwork = ArtworkCache(files) { _, _, _ -> null }, repo = JsonMediaRepository(files), anilist = AniListClient(files), bangumi = BangumiClient(files),
            omdb = OmdbClient(files), jikan = JikanClient(files), imdb = ImdbDatasetClient(files),
        )
    }

    private fun file(path: String) = MediaItem(id = "w", sourceId = "s", path = path, fileName = path.substringAfterLast('/'), title = path.substringAfterLast('/'))

    @Test fun `a film TMDB knows by its Chinese name is matched though the file calls it a Movie`() = runBlocking {
        val wolf = MetaResult(110420, MediaKind.MOVIE, "狼的孩子雨和雪", originalTitle = "おおかみこどもの雨と雪", year = 2012, genres = listOf("动画"), popularity = 10.6)
        val tmdb = FakeTmdb(mapOf("狼的孩子雨和雪" to wolf))
        val out = engine(tmdb).scrape(file("smb://nas/video/Anime/狼的孩子雨和雪/[Kamigami] 狼的孩子雨和雪 Movie [BD x264 1920×1080 FLAC].mkv"))
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(110420, out.tmdbId)
    }

    @Test fun `a live-action answer to an anime's Chinese name is still refused`() = runBlocking {
        val drama = MetaResult(5, MediaKind.MOVIE, "穿越时空的少女", year = 1983, genres = listOf("剧情"), popularity = 3.4)
        val out = engine(FakeTmdb(mapOf("穿越时空的少女" to drama))).scrape(file("smb://nas/video/Anime/穿越时空的少女/[Kamigami] 穿越时空的少女 - Movie [BD x264 1920x1080].mkv"))
        assertEquals(ScrapeStatus.UNMATCHED, out.scrapeStatus)
    }

    @Test fun `the anime is taken when the best answer to its name is a live-action film of the same name`() = runBlocking {
        val remake = MetaResult(1359916, MediaKind.MOVIE, "秒速5厘米", year = 2025, genres = listOf("剧情"), popularity = 5.6)
        val anime = MetaResult(38142, MediaKind.MOVIE, "秒速五厘米", originalTitle = "秒速5センチメートル", year = 2007, genres = listOf("动画"), popularity = 6.4)
        val tmdb = object : MetadataClient {
            override val name = "fake"
            override val isConfigured = true
            override suspend fun search(parsed: ParsedName) = if (parsed.title == "秒速5厘米") listOf(remake, anime) else emptyList()
            override suspend fun details(providerId: Int, kind: MediaKind) = listOf(remake, anime).firstOrNull { it.providerId == providerId }
            override suspend fun alternativeTitles(providerId: Int, kind: MediaKind) = if (providerId == 38142) listOf("秒速5厘米") else emptyList()
        }
        val out = engine(tmdb).scrape(file("smb://nas/video/Anime/新海诚全集/秒速5CM 1080P BDRIP/[秒速5厘米]BDRIP.m2ts"))
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(38142, out.tmdbId)
    }

    @Test fun `a show's Chinese name with its season after it is asked as the show`() = runBlocking {
        val zero = MetaResult(35753, MediaKind.SHOW, "零之使魔", originalTitle = "ゼロの使い魔", year = 2006, genres = listOf("动画"), popularity = 26.2)
        val out = engine(FakeTmdb(mapOf("零之使魔" to zero))).scrape(file("smb://nas/video/Anime/零之使魔2/[EMD&HKG][Zero_Tsukaima_Futatukinokishi][01][BIG5][RV10_AAC][CRC32_508F2EBC].rmvb"))
        assertEquals(ScrapeStatus.MATCHED, out.scrapeStatus)
        assertEquals(35753, out.tmdbId)
    }

    @Test fun `the anime a live-action film of its name remade is taken by their shared original title`() = runBlocking {
        val remake = MetaResult(1359916, MediaKind.MOVIE, "秒速5厘米", originalTitle = "秒速5センチメートル", year = 2025, genres = listOf("剧情"), popularity = 5.6)
        val anime = MetaResult(38142, MediaKind.MOVIE, "秒速五厘米", originalTitle = "秒速5センチメートル", year = 2007, genres = listOf("动画"), popularity = 6.4)
        val tmdb = object : MetadataClient {
            override val name = "fake"
            override val isConfigured = true
            override suspend fun search(parsed: ParsedName) = if (parsed.title == "秒速5厘米") listOf(remake, anime) else emptyList()
            override suspend fun details(providerId: Int, kind: MediaKind) = listOf(remake, anime).firstOrNull { it.providerId == providerId }
        }
        val out = engine(tmdb).scrape(file("smb://nas/video/Anime/新海诚全集/秒速5CM 1080P BDRIP/[秒速5厘米]BDRIP.m2ts"))
        assertEquals(38142, out.tmdbId)
    }
}
