package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.*
import com.therealityteam.videoplayer.util.Offline
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeylessSeriesAndAnimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun array(rows: List<JSONObject>) = JSONObject().put("items", JSONArray(rows))
    private fun show(id: Int, year: Int, type: String = "Scripted") = JSONObject().put("show",
        JSONObject().put("id", id).put("name", "River").put("premiered", "$year-01-01").put("type", type))

    @Test fun showsRequireAnUnambiguousIdentityAndRespectDocumentaryEvidence() = runBlocking {
        val catalog = PublicSeriesCatalog { url, _ -> if ("api.tvmaze.com/search" in url)
            array(listOf(show(1, 1970), show(2, 2016, "Documentary"))) else null }
        assertNull(catalog.match(listOf("River"), null, null, false))
        assertEquals("tvmaze:2", catalog.match(listOf("River"), 2016, 1, false)?.publicMovieKey)
        assertEquals("tvmaze:2", catalog.match(listOf("River"), null, 1, true)?.publicMovieKey)
        assertNull(catalog.match(listOf("River"), 1970, 1, true))
        assertNull(catalog.match(listOf("Riverbed"), 2016, 1, false))
    }

    @Test fun aNeoSeasonIsFiledUnderItsConfirmedParentAndNeverAsAnotherShow() = runBlocking {
        val parent = "abcdEFGH1234"
        val season = JSONObject().put("type", "TVSeason").put("title", "Delta Season 2").put("parent_uuid", parent)
        val record = JSONObject().put("type", "TVShow").put("title", "Delta").put("uuid", parent)
            .put("url", "/tv/$parent").put("year", 2014)
        val catalog = PublicSeriesCatalog { url, _ -> when {
            "category=tv" in url -> JSONObject().put("data", JSONArray().put(season))
            url.endsWith("/tv/$parent") -> record
            else -> null
        } }
        assertEquals("neodb-tv:$parent", catalog.match(listOf("Delta"), 2016, 2, false)?.publicMovieKey)
        record.put("title", "Delta Blues")
        assertNull(catalog.match(listOf("Delta"), 2016, 2, false))
    }

    @Test fun aDocumentarySeasonWithoutAParentKeepsItsOwnRecordAndBroadcastCredit() = runBlocking {
        val id = "xY1234567890"
        val season = JSONObject().put("type", "TVSeason").put("uuid", id).put("url", "/tv/season/$id")
            .put("title", "River Signals").put("orig_title", "Signals on the River").put("year", 2011)
            .put("genre", JSONArray().put("Documentary")).put("director", JSONArray().put("PBS")).put("episode_count", 3)
        val records = JSONArray().put(season)
        val catalog = PublicSeriesCatalog { url, _ -> when {
            "category=tv" in url -> JSONObject().put("data", records)
            url.endsWith("/api/tv/season/$id") -> season
            else -> null
        } }
        suspend fun identify(folder: String, label: Int? = null, ep: Int? = null) =
            catalog.match(listOf("River Signals"), 2011, label, true, episode = ep, broadcastFolder = folder)
        val key = "neodb-tv-season:$id"
        assertEquals(key, identify("PBS", 1, 3)?.publicMovieKey)
        assertEquals("https://neodb.social/tv/season/$id", PublicSeriesCatalog.credit(key)?.second)
        assertEquals(key, catalog.detail(key)?.publicMovieKey)
        assertNull(identify("BBC"))
        assertNull(identify("PBS", 2))
        assertNull(identify("PBS", 1, 4))
        assertNull(catalog.match(listOf("River Signals"), 2011, 1, false, broadcastFolder = "PBS"))
        assertNull(catalog.match(listOf("River Signals"), 2018, 1, true, broadcastFolder = "PBS"))
        season.put("parent_uuid", "zZ1234567890")
        assertNull(identify("PBS"))
        season.remove("parent_uuid")
        season.put("director", JSONArray()).put("orig_title", "NHKスペシャル 夜の川")
        assertEquals(key, identify("NHK")?.publicMovieKey)
        season.put("genre", JSONArray().put("Drama"))
        assertNull(identify("NHK"))
        season.put("genre", JSONArray().put("Documentary")).put("season_number", 2)
        assertNull(identify("NHK"))
        season.remove("season_number")
        records.put(JSONObject(season.toString()).put("uuid", "aB1234567890").put("url", "/tv/season/aB1234567890"))
        assertNull(identify("NHK"))
    }

    @Test fun aPunctuationVariantNeedsTheSameSeriesIdFromAnotherCatalogue() = runBlocking {
        val id = "tt0123456"
        val tagged = show(30, 2008).also { it.getJSONObject("show").put("externals", JSONObject().put("imdb", id)) }
        val similar = show(31, 1998).also { it.getJSONObject("show").put("name", "River!") }
        val detail = JSONObject().put("id",id).put("type","series").put("name","RIVER").put("year","2008")
        var secondary = false
        val catalog = PublicSeriesCatalog { url, _ -> when {
            url.contains("api.tvmaze.com/search") -> array(listOf(tagged, similar))
            secondary && url.contains("strem.io/catalog/series/") -> JSONObject().put("metas", JSONArray().put(detail))
            secondary && url.endsWith("meta/series/$id.json") -> JSONObject().put("meta", detail)
            else -> null
        } }
        assertNull(catalog.match(listOf("River"), null, 1, false))
        secondary = true
        assertEquals("tvmaze:30", catalog.match(listOf("River"), null, 1, false)?.publicMovieKey)
        similar.getJSONObject("show").put("name","River")
        assertNull(catalog.match(listOf("River"), null, 1, false))
    }

    @Test fun episodeNumbersRepeatAcrossSeasonsAndSpecialsCanBeUnnumbered() = runBlocking {
        fun ep(season: Int, number: Int?, name: String) = JSONObject().put("season", season).put("number", number)
            .put("name", name).put("summary", "<p>North &amp; south</p>")
        val catalog = PublicSeriesCatalog { url, _ ->
            assertEquals("https://api.tvmaze.com/shows/10/episodes", url)
            array(listOf(ep(1, 1, "Departure"), ep(2, 1, "Return"), ep(1, null, "Aside")))
        }
        val episodes = catalog.episodes("tvmaze:10")
        assertEquals(setOf(1 to 1, 2 to 1), episodes.keys)
        assertEquals("Return", episodes[2 to 1]?.name)
        assertEquals("North & south", episodes[1 to 1]?.overview)
        assertTrue(catalog.episodes("neodb-tv:abcdEFGH1234").isEmpty())
    }

    @Test fun anEpisodeOutsideAnEndedNamesakeNeedsIndependentConfirmation() = runBlocking {
        val id = "tt0123456"
        val longRun = show(30, 1998).also { it.getJSONObject("show").put("externals", JSONObject().put("imdb", id)) }
        val shortRun = show(31, 2014)
        fun seasons(id: Int, vararg episodes: Int) = JSONObject().put("id", id).put("status", "Ended")
            .put("_embedded", JSONObject().put("seasons", JSONArray(episodes.mapIndexed { index, count ->
                JSONObject().put("number", index + 1).put("episodeOrder", count) })))
        val first = seasons(30, 20, 8)
        val second = seasons(31, 4)
        val backup = JSONObject().put("id", id).put("type", "series").put("name", "River").put("year", "1998")
        var confirm = true
        var available = true
        val catalog = PublicSeriesCatalog { url, _ -> when {
            "api.tvmaze.com/search" in url -> array(listOf(longRun, shortRun))
            url.endsWith("/30?embed=seasons") -> first
            url.endsWith("/31?embed=seasons") -> if (available) second else throw com.therealityteam.videoplayer.util.TransientHttpException(url)
            confirm && "/catalog/series/" in url -> JSONObject().put("metas", JSONArray().put(backup))
            confirm && url.endsWith("/$id.json") -> JSONObject().put("meta", backup)
            else -> null
        } }
        suspend fun identify(season: Int, episode: Int) = catalog.match(listOf("River"), null, season, false, episode = episode)
        assertNull(identify(1, 1))
        assertNull(identify(1, 3))
        assertEquals("tvmaze:30", identify(1, 12)?.publicMovieKey)
        assertEquals("tvmaze:30", identify(2, 1)?.publicMovieKey)
        assertNull(identify(2, 12))
        confirm = false
        assertNull(identify(2, 1))
        confirm = true
        available = false
        assertNull(identify(2, 1))
        available = true
        second.put("status", "Running")
        assertNull(identify(2, 1))
        second.put("status", "Ended").getJSONObject("_embedded").getJSONArray("seasons")
            .getJSONObject(0).remove("episodeOrder")
        assertNull(identify(1, 12))
    }

    @Test fun cinemetaConfirmsTheSeriesIdAndUsesItsActualGenreAndYear() = runBlocking {
        val id = "tt0123456"
        val hit = JSONObject().put("id", id).put("type", "series").put("name", "A + B")
        val detail = JSONObject(hit.toString()).put("imdb_id", id).put("releaseInfo", "1998–2001")
            .put("genres", JSONArray().put("Documentary"))
        val catalog = PublicSeriesCatalog { url, _ -> when (url) {
            "https://v3-cinemeta.strem.io/catalog/series/top/search=A%20%2B%20B.json" ->
                JSONObject().put("metas", JSONArray().put(hit))
            "https://v3-cinemeta.strem.io/meta/series/$id.json" -> JSONObject().put("meta", detail)
            else -> null
        } }
        val record = catalog.match(listOf("A + B"), 1998, 1, documentary = true)
        assertEquals(id, record?.imdbId)
        assertEquals("cinemeta-tv:$id", record?.publicMovieKey)
        assertNotNull(PublicMovieCatalog.credit(record?.publicMovieKey))
        assertNull(catalog.match(listOf("A + B"), 2018, 1, documentary = false))
        detail.put("genres", JSONArray().put("Drama"))
        assertNull(catalog.match(listOf("A + B"), 1998, 1, documentary = true))
        detail.put("imdb_id", "tt0999999")
        assertNull(catalog.detail("cinemeta-tv:$id"))
        assertNull(catalog.detail("cinemeta-tv:../other"))
    }

    @Test fun catalogueQueriesContainNoShareOrServerAndPersonalRecordingsStayLocal() {
        for (path in listOf("smb://office-server.example/Television/01.mkv",
            "smb://office-server.example/video/TV/01.mkv", "smb://office-server.example/video/My/TV/Trip.S01E01.mkv")) {
            val names = PublicSeriesCatalog.queries(path, path.substringAfterLast('/'), FilenameParser.parseFromPath(path))
            assertTrue(names.none { "office" in it || "Television" in it || "example" in it })
            if ("/My/" in path) assertTrue(names.isEmpty())
        }
        val loose = "smb://example.private/Family/Documentary/NHK/[NHK][纪录片]港口的最后一班船.avi"
        assertEquals("港口的最后一班船", FilenameParser.parseFromPath(loose).title)
        assertNull(FilenameParser.showFolderTitle(loose))
        assertEquals("港口的最后一班船", FilenameParser.parseFromPath("/Volumes/home/Documentary/NHK/港口的最后一班船.avi").title)
    }

    @Test fun miniseriesInAMovieFolderKeepTheirInstalmentNumbers() {
        for ((file,number) in mapOf("Last Crossing [2008] 4 of 6.mkv" to 4, "Last Crossing (2008) 14.avi" to 14)) {
            val path = "/Movies/Last Crossing [2008]/$file"
            assertTrue(PublicSeriesCatalog.queries(path,file,FilenameParser.parseFromPath(path)).contains("Last Crossing"))
            assertEquals(number,PublicSeriesCatalog.instalment(file))
        }
        assertNull(PublicSeriesCatalog.instalment("Last Crossing DVD 1 of 3.mkv"))
        assertNull(PublicSeriesCatalog.instalment("Last Crossing 1 of 2.mkv"))
        assertEquals(2, PublicSeriesCatalog.instalment("Last Crossing (2008) BBC MiniSeries XviD part 2.avi"))
        assertNull(PublicSeriesCatalog.instalment("Last Crossing Part 2.avi"))
    }

    @Test fun aMusicReleaseRequiresAnOriginalMovingImageOfTheNamedConcert() = runBlocking {
        val title = "Live at Winter Harbour"
        val id = "winter-harbour"
        val item = JSONObject().put("identifier",id).put("title",title).put("mediatype","movies")
            .put("date","2014-05-02").put("subject",JSONArray().put("concert")).put("creator","The Harbour Players")
        val file = JSONObject().put("source","original").put("format","Flac").put("length","3600")
        val main = JSONObject().put("metadata",item).put("files",JSONArray().put(file))
        val catalog = PublicMusicCatalog { url, _ -> if (url.startsWith("https://archive.org/metadata/")) main else
            JSONObject().put("response",JSONObject().put("numFound",1).put("docs",JSONArray().put(item))) }
        val path = "/Music/Classical/$title/01.avi"
        val parsed = ParsedName("Opening",2014)
        assertNull(catalog.match(path,"01.avi",parsed))
        file.put("format","Matroska")
        assertEquals("archive:$id", catalog.match(path,"01.avi",parsed)?.publicMovieKey)
        file.put("length", "00:00:24")
        assertNull(catalog.match(path,"01.avi",parsed))
        file.put("length", "77:57")
        assertEquals("archive:$id", catalog.match(path,"01.avi",parsed)?.publicMovieKey)
        assertNull(catalog.match(path,"01.avi",parsed.copy(year=2010)))
        assertNull(PublicMusicCatalog.releaseTitle("/Music/Guitar/track.mkv","track.mkv",parsed))
        assertNull(PublicMusicCatalog.releaseTitle("/Music/Private/$title/track.mkv","track.mkv",parsed))
    }

    @Test fun aStandaloneBroadcastCanUseAFilmOnlyWithPositiveDocumentaryEvidence() = runBlocking {
        val id = "00000000-0000-4000-8000-000000000007"
        val record = JSONObject().put("type","movie").put("id",id).put("original_title","Last Crossing")
            .put("original_release_date","2019-05-05")
        val catalog = PublicMovieCatalog { url, _ -> when {
            "/api/v1/search" in url -> JSONObject().put("results", JSONObject().put("media",JSONArray().put(record)))
            url.endsWith("/$id/detail") -> record
            else -> null
        } }
        val query = ParsedName("Last Crossing",2019)
        assertNull(catalog.broadcastFilm(query))
        record.put("genres",JSONArray().put("Drama"))
        assertNull(catalog.broadcastFilm(query))
        record.put("genres",JSONArray().put("Documentary"))
        assertEquals("everyfilm:$id",catalog.broadcastFilm(query)?.publicMovieKey)
        assertNull(catalog.broadcastFilm(query.copy(season = 1, episode = 1)))
    }

    @Test fun anUntypedBroadcastNeedsItsExactNameAndACorroboratedStation() = runBlocking {
        val id = "abcdEFGH1234"
        val film = JSONObject().put("type", "Movie").put("uuid", id).put("url", "/movie/$id")
            .put("title", "Harbour Survey").put("year", 2008).put("director", JSONArray().put("PBS"))
        val catalog = PublicMovieCatalog { url, _ -> when {
            "category=movie" in url -> JSONObject().put("data", JSONArray().put(film))
            url.endsWith("/movie/$id") -> film
            else -> null
        } }
        val query = ParsedName("Harbour Survey", 2008)
        val labelled = "[PBS][Documentary] Harbour Survey.mkv"
        assertEquals("neodb:$id", catalog.broadcastFilm(query, "PBS", labelled)?.publicMovieKey)
        assertNull(catalog.broadcastFilm(query, "BBC", labelled))
        assertNull(catalog.broadcastFilm(query, "PBS", "Harbour Survey.mkv"))
        assertNull(catalog.broadcastFilm(query.copy(year = 1980), "PBS", labelled))
        film.put("director", JSONArray()).put("description", "PBS documentary")
        assertEquals("neodb:$id", catalog.broadcastFilm(query, "PBS")?.publicMovieKey)
        film.put("description", "A drama about a PBS documentary.")
        assertNull(catalog.broadcastFilm(query, "PBS", labelled))
        film.put("description", "PBS documentary").put("genre", JSONArray().put("Drama"))
        assertNull(catalog.broadcastFilm(query, "PBS", labelled))
    }

    @Test fun shippedAliasesWorkOfflineAndCheckYearSeasonAndFormat() {
        val before = TmdbConfig.offline
        try {
            TmdbConfig.offline = true
            val catalog = PublicSeriesCatalog { _, _ -> error("offline network request") }
            runBlocking { assertNull(catalog.match(listOf("River"), 1991, 1, false)) }
            fun pick(name: String, year: Int?, season: Int?, movie: Boolean) =
                AnimeTitleIndex.match(listOf(name), year, season, movie, null, null, null)?.record?.publicMovieKey
            assertEquals("bangumi:302", pick("龍貓", 1988, null, true))
            assertEquals("bangumi:302", pick("My Neighbor Totoro", 1988, null, true))
            val totoro = AnimeTitleIndex.match(listOf("My Neighbor Totoro"),1988,null,true,null,null,null)!!
            assertEquals(523, totoro.aniListId)
            assertTrue(totoro.identifies("My Neighbor Totoro",MetaResult(7,MediaKind.MOVIE,"The Neighbor")))
            assertFalse(totoro.identifies("My Neighbor Totoro",MetaResult(8,MediaKind.MOVIE,"My Neighbor Totoro")))
            val show = AnimeTitleIndex.match(listOf("Neon Genesis Evangelion"),1995,1,false,"TV",1,null)!!
            assertFalse(show.identifies("Neon Genesis Evangelion",MetaResult(9,MediaKind.MOVIE,"An Individual Film")))
            assertFalse(show.identifies("Neon Genesis Evangelion",MetaResult(10,MediaKind.SHOW,"Neon Genesis Evangelion: New Season")))
            assertNull(pick("龍貓", 2010, null, true))
            assertNull(pick("Neon Genesis Evangelion", null, 4, false))
            assertNull(pick("Neon Genesis Evangelion", null, null, true))
            assertTrue(Offline.refuses("https://static.tvmaze.com/poster.jpg"))
            assertTrue(Offline.refuses("https://v3-cinemeta.strem.io/meta/series/tt0123456.json"))
            assertFalse(Offline.refuses("smb://home.local/video"))
        } finally { TmdbConfig.offline = before }
    }

    @Test fun bangumiCanIdentifyAnExactSubjectWhenAniListHasNoAnswer() = runBlocking {
        val root = tmp.newFolder()
        val name = "Crystal Archive"
        val subjects = JSONArray().put(JSONObject().put("id",123).put("name",name))
        val cache = File(root,"aura/cache/bangumi-search.json")
        cache.parentFile.mkdirs()
        cache.writeText(JSONObject().put(name.lowercase(), JSONObject().put("v",subjects.toString())
            .put("t",System.currentTimeMillis())).toString())
        ResponseCache(root, 60_000).put("https://api.bgm.tv/v0/subjects/123", JSONObject()
            .put("id",123).put("type",2).put("name",name).put("date","2016-03-10").put("platform","TV").toString())
        val client = BangumiClient(root)
        val correct = client.direct(listOf(name), 2016, 1, movie = false, numbered = true)
        assertEquals("bangumi:123", correct?.publicMovieKey)
        assertEquals(MediaKind.SHOW, correct?.kind)
        assertNull(client.direct(listOf(name), null, 3, movie = false, numbered = true))
        assertNull(client.direct(listOf(name), 2016, null, movie = true, numbered = false))
    }
}
