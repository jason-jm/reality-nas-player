package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.PublicMovieCatalog
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.scrape.ResponseCache
import com.therealityteam.videoplayer.util.TransientHttpException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class PublicMovieCatalogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val old = "00000000-0000-4000-8000-000000000001"
    private val remake = "00000000-0000-4000-8000-000000000002"
    private fun film(id: String, year: Int) = JSONObject().put("type", "movie").put("id", id)
        .put("original_title", "The Crossing").put("original_release_date", "$year-01-01")
        .put("poster_url", "https://image.tmdb.org/upstream.jpg")
        .put("poster_stored_url", "/api/v1/img/media/$id/poster.jpg")
        .put("translations", JSONArray().put(JSONObject().put("name", "过河").put("language_code", "zh-CN").put("summary", "电影简介")))
    private fun search(vararg rows: JSONObject) =
        JSONObject().put("results", JSONObject().put("media", JSONArray(rows.toList())))
    private fun claim(value: Any) = JSONArray().put(JSONObject().put("mainsnak",
        JSONObject().put("datavalue", JSONObject().put("value", value))))
    private fun wikiFilm(title: String, imdb: String, year: Int) =
        JSONObject().put("id", "Q123").put("labels", JSONObject().put("en",
            JSONObject().put("language", "en").put("value", title)))
            .put("claims", JSONObject().put("P31", claim(JSONObject().put("id", "Q11424")))
                .put("P345", claim(imdb)).put("P577", claim(JSONObject().put("time", "+$year-02-02"))))

    @Test fun remakeIsSelectedByYearAndLocalizedFromItsOwnDetail() = runBlocking {
        val first = film(old, 1965)
        val second = film(remake, 2018)
        val catalog = PublicMovieCatalog { url, _ -> when {
            "search" in url -> search(first, second)
            remake in url -> second
            else -> null
        } }
        val lang = TmdbConfig.language
        try {
            TmdbConfig.language = "zh-CN"
            val hit = catalog.match(ParsedName("The Crossing", 2018))!!
            assertEquals("everyfilm:$remake", hit.publicMovieKey)
            assertEquals("过河", hit.title)
            assertEquals("电影简介", hit.overview)
            assertEquals("https://every.film/api/v1/img/media/$remake/poster.jpg", hit.posterUrl)
        } finally { TmdbConfig.language = lang }
    }

    @Test fun undatedRemakesStayUnmatchedWithoutConfirmationByAnotherCatalogue() = runBlocking {
        val first = film(old, 1965)
        val second = film(remake, 2018)
        val catalog = PublicMovieCatalog { url, _ -> when {
            url.contains("every.film/api/v1/search") -> search(first, second)
            old in url -> first
            else -> null
        } }
        assertNull(catalog.match(ParsedName("The Crossing")))
    }

    @Test fun offlinePrivatePathsAndEpisodesNeverReachTheMovieCatalogues() = runBlocking {
        val catalog = PublicMovieCatalog { _, _ -> error("unexpected external request") }
        assertNull(catalog.match(ParsedName("smb://server/private/Film")))
        assertNull(catalog.match(ParsedName("Some Show", season = 1)))
        val offline = TmdbConfig.offline
        try {
            TmdbConfig.offline = true
            assertNull(catalog.match(ParsedName("The Crossing")))
            assertTrue(catalog.manual("The Crossing").isEmpty())
        } finally { TmdbConfig.offline = offline }
    }

    @Test fun numericTitlesStillUseTheReleaseYearAndBilingualNamesCanBeSearchedSeparately() {
        val path = "/Movies/2012/2012.2009.BluRay.mkv"
        assertEquals(listOf(ParsedName("2012", 2009)), PublicMovieCatalog.fileNames(path, FilenameParser.parseFromPath(path)))
        assertEquals(listOf("JFK", "刺杀肯尼迪", "刺杀肯尼迪-JFK"), PublicMovieCatalog.queryNames("刺杀肯尼迪-JFK"))
    }

    @Test fun featureDocumentariesCanBeLookedUpWithoutAskingForBroadcastEpisodes() {
        fun eligible(path: String): Boolean {
            val parsed = FilenameParser.parseFromPath(path)
            return PublicMovieCatalog.eligible(path, path.substringAfterLast('/'), parsed.title, parsed)
        }
        assertTrue(eligible("/Documentaries/The Crossing/The.Crossing.2021.BluRay.mkv"))
        assertFalse(eligible("/Documentaries/PBS/World.01of12.Streets.avi"))
        assertFalse(eligible("/Documentaries/NHK/A.City.2021.mkv"))
        assertFalse(eligible("/Documentaries/The Crossing/The.Crossing.S01E02.mkv"))
    }

    @Test fun mislabeledSynopsisDoesNotHideAnotherChineseTranslation() = runBlocking {
        val record = film(old, 2018)
        val japanese = "これは日本語で書かれた映画の紹介です。"
        record.getJSONArray("translations").getJSONObject(0).put("summary", japanese)
        val translations = record.getJSONArray("translations")
        translations.put(JSONObject().put("language_code", "zh-Hans").put("summary", "两名船员在海上重逢。"))
        val catalog = PublicMovieCatalog { _, _ -> record }
        val previous = TmdbConfig.language
        try {
            TmdbConfig.language = "zh-CN"
            assertEquals("两名船员在海上重逢。", catalog.detail("everyfilm:$old")?.overview)
            translations.getJSONObject(1).remove("summary")
            record.put("overview", japanese)
            assertNull(catalog.detail("everyfilm:$old")?.overview)
        } finally { TmdbConfig.language = previous }
    }

    @Test fun malformedCachedResponseCanBeRetried() = runBlocking {
        val root = tmp.newFolder()
        val url = "https://every.film/api/v1/search?q=Crossing"
        val disk = ResponseCache(File(root, "public-movies/${TmdbConfig.language}"), 60_000)
        disk.put(url, "<html>Sign in to Wi-Fi</html>")
        try {
            PublicMovieCatalog(root).search(PublicMovieCatalog.Source.EVERY, "Crossing")
            fail("invalid JSON must be retryable")
        } catch (_: TransientHttpException) { assertNull(disk.get(url)) }
    }

    @Test fun shortTitleGetsTheOtherCataloguesPosterOnlyWhenTheFilmIdsAgree() = runBlocking {
        val neoKey = "abcdefghijklmnop"
        val card = JSONObject().put("type", "Movie").put("url", "/movie/$neoKey")
            .put("title", "Water Line: The Journey").put("orig_title", "Water Line: The Journey")
        val entity = wikiFilm("Water Line", "tt1234567", 2018)
        var linkedId = "tt1234567"
        val catalog = PublicMovieCatalog { url, _ -> when {
            "/api/catalog/search" in url -> JSONObject().put("data", JSONArray().put(card))
            "/api/movie/" in url -> JSONObject(card.toString()).put("year", 2018).put("imdb", linkedId)
                .put("cover_image_url", "https://neodb.social/m/movie/cover.jpg")
            "wbsearchentities" in url -> JSONObject().put("search", JSONArray().put(JSONObject().put("id", "Q123")))
            "wbgetentities" in url -> JSONObject().put("entities", JSONObject().put("Q123", entity))
            else -> null
        } }
        val input = ParsedName("Water Line", 2018)
        assertEquals("neodb:$neoKey", catalog.match(input)?.publicMovieKey)
        linkedId = "tt9999999"
        assertEquals("wikidata:Q123", catalog.match(input)?.publicMovieKey)
    }

    @Test fun earlierPremiereRequiresTheSameFilmAndAnUndatedFilename() = runBlocking {
        val film = JSONObject().put("type", "Movie").put("url", "/movie/abcdefghijklmnop")
            .put("title", "A Summer Crossing").put("year", 2018).put("imdb", "tt1234567")
        var imdb = "tt1234567"
        val catalog = PublicMovieCatalog { url, _ -> when {
            "/api/catalog/search" in url -> JSONObject().put("data", JSONArray().put(film))
            "/api/movie/" in url -> film
            "wbsearchentities" in url -> JSONObject().put("search", JSONArray().put(JSONObject().put("id", "Q123")))
            "wbgetentities" in url -> JSONObject().put("entities", JSONObject().put("Q123", wikiFilm("A Summer Crossing", imdb, 2017)))
            else -> null
        } }
        assertEquals(2017, catalog.match(ParsedName("A Summer Crossing"))?.year)
        assertEquals(2018, catalog.match(ParsedName("A Summer Crossing", 2018))?.year)
        imdb = "tt7654321"
        assertEquals(2018, catalog.match(ParsedName("A Summer Crossing"))?.year)
    }

    @Test fun failedArtworkRefreshKeepsThePreviousImage() = runBlocking {
        val pictures = ArtworkCache(tmp.newFolder()) { _, _, _ -> null }
        val saved = pictures.store("cover", "first") { it.write(123); true }!!
        assertNull(pictures.store("cover", "next") { it.write(9); false })
        assertNull(pictures.store("cover", "first") { true })
        assertArrayEquals(byteArrayOf(123), File(saved).readBytes())
    }
}
