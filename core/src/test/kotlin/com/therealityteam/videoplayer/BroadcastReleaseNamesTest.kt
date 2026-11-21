package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.BroadcastReleaseNames
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.PublicMovieCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BroadcastReleaseNamesTest {
    private val title = "海洋里的候鸟"
    private val file = "[NHK][纪录片]$title.avi"
    private val original = "海を渡る鳥たち"
    private val index = JSONObject().put("found", 1).put("posts", JSONArray().put(JSONObject().put("ID", 9).put("title", title)))
    private val post = JSONObject().put("ID", 9).put("title", title)
    private fun body(date: String = "2010年2月6日", release: String = file) =
        "<p><b>$title</b> $original<br />$date 49mins</p><p>$release</p>"

    @Test fun onlyAnExactPublishedEditionCanSupplyAnotherTitle() = runBlocking {
        var listings = 0
        post.put("content", body())
        val labels = BroadcastReleaseNames { url, _ -> when {
            "number=100" in url -> { listings++; index }
            "/posts/9?" in url -> post
            else -> error("Unexpected catalogue URL")
        } }
        assertNull(labels.find("home-video.mp4"))
        assertEquals(0, listings)
        assertEquals(BroadcastReleaseNames.Broadcast(original, 2010), labels.find(file))
        post.put("content", body().replace(original, "&nbsp;“$original”～"))
        assertEquals(BroadcastReleaseNames.Broadcast(original, 2010), labels.find(file))
        assertNull(labels.find(file.replace(".avi", ".mkv")))
        assertEquals(1, listings)
        post.put("content",body("2010年2月30日"))
        assertNull(labels.find(file))
        post.put("content",body()+ "<p>[NHK][纪录片]另一部影片.avi</p>")
        assertNull(labels.find(file))
        post.put("content",body()).put("ID",10)
        assertNull(labels.find(file))
    }

    @Test fun aReleaseNoteAloneCannotAssignAnIdentityOrOverrideTheDocumentaryGenre() = runBlocking {
        post.put("content", body())
        val key = "abcdefghijklmnop"
        val film = JSONObject().put("type","Movie").put("url","/movie/$key").put("title",original)
            .put("year",2010).put("genre",JSONArray().put("Documentary"))
        var available = false
        val catalog = PublicMovieCatalog { url, _ -> when {
            "number=100" in url -> index
            "/posts/9?" in url -> post
            available && "category=movie" in url -> JSONObject().put("data",JSONArray().put(film))
            available && url.endsWith("/movie/$key") -> film
            else -> null
        } }
        val parsed = ParsedName(title)
        assertNull(catalog.matchBroadcastRelease(file, parsed, "NHK"))
        available=true
        assertEquals("neodb:$key",catalog.matchBroadcastRelease(file, parsed, "NHK")?.publicMovieKey)
        assertNull(catalog.matchBroadcastRelease(file, parsed, "BBC"))
        assertNull(catalog.matchBroadcastRelease(file, parsed.copy(year=2012), "NHK"))
        film.put("genre",JSONArray().put("Animation"))
        assertNull(catalog.matchBroadcastRelease(file, parsed, "NHK"))
        film.put("genre",JSONArray().put("Documentary")).put("year",2009)
        assertNull(catalog.matchBroadcastRelease(file, parsed, "NHK"))
    }
}
