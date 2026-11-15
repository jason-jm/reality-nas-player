package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Headshots are fetched when somebody looks at them, not when the file is scraped.
 *
 * They were two thirds of everything the scraper downloaded — 3,524 of 5,348 artwork files on a
 * real library — for faces shown only in the cast row of a detail page.
 */
class LazyHeadshotTest {

    private fun withCast(cast: List<Person>) = MediaItem(
        id = "x1", sourceId = "src", path = "/v/a.mkv", fileName = "a.mkv", title = "A", cast = cast,
    )

    @Test fun `the headshot's address survives a restart`() {
        val url = "https://image.tmdb.org/t/p/w185/a.jpg"
        val back = MediaJson.item(MediaJson.item(withCast(listOf(Person("Toshiro Mifune", "武士", profileUrl = url)))))
        assertEquals(url, back.cast.single().profileUrl)
        assertNull("nothing was downloaded at scrape time", back.cast.single().profilePath)
    }

    @Test fun `a library scraped before this change keeps its downloaded faces`() {
        val old = MediaJson.item(withCast(listOf(Person("Setsuko Hara", "紀子", profilePath = "/data/art/person_1__1.jpg"))))
        val back = MediaJson.item(old)
        assertEquals("/data/art/person_1__1.jpg", back.cast.single().profilePath)
        assertNull(back.cast.single().profileUrl)
    }

    @Test fun `the filing scheme is the one those libraries already used`() {
        // If this ever changes, every headshot on every existing device is orphaned and re-fetched.
        assertEquals(
            "person_ca3c6ba0",
            ArtworkCache.personKey("https://image.tmdb.org/t/p/w185/yFjTzJHE6AFbwQifOlnNDzmiwlq.jpg"),
        )
        assertEquals(
            "still_ca3c6ba0",
            ArtworkCache.stillKey("https://image.tmdb.org/t/p/w185/yFjTzJHE6AFbwQifOlnNDzmiwlq.jpg"),
        )
    }

    @Test fun `an episode carries its still's address, not a downloaded file`() {
        val url = "https://image.tmdb.org/t/p/w300/s.jpg"
        val back = MediaJson.item(MediaJson.item(withCast(emptyList()).copy(stillUrl = url)))
        assertEquals(url, back.stillUrl)
        assertNull(back.stillPath)
    }

    @Test fun `an episode whose still was already downloaded keeps it`() {
        val old = MediaJson.item(withCast(emptyList()).copy(stillPath = "/data/art/still_1__1.jpg"))
        assertEquals("/data/art/still_1__1.jpg", MediaJson.item(old).stillPath)
        assertNull(MediaJson.item(old).stillUrl)
    }
}
