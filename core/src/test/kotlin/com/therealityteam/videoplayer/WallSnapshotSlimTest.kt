package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.WallSnapshot
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot is what a cold start paints before the library has been read, so what it costs to
 * parse is first-paint latency. What it must still carry is everything a card is DRAWN from.
 */
class WallSnapshotSlimTest {

    private fun group(ratings: List<RatingScore> = emptyList(), keys: List<String> = emptyList()) = MediaGroup(
        key = "id:1", title = "进击的巨人", year = 2013, posterPath = "/p.jpg", backdropPath = null,
        dominantColor = 0x112233, runtime = 24, certification = "TV-MA", ratings = ratings,
        isSeries = true, seasonal = true, category = LibCategory.ANIME, count = 25,
        items = listOf(MediaItem(id = "a", sourceId = "s", path = "/a.mkv", fileName = "a.mkv", title = "t")),
        searchKeys = keys, titleKey = "jinji",
    )

    @Test fun `everything a card is drawn from survives the round trip`() {
        val back = WallSnapshot.decode(WallSnapshot.encode(listOf(group()))).single()
        assertEquals("进击的巨人", back.title)
        assertEquals(2013, back.year)
        assertEquals("/p.jpg", back.posterPath)
        assertEquals(0x112233, back.dominantColor)
        assertEquals(24, back.runtime)
        assertEquals("TV-MA", back.certification)
        assertEquals(25, back.count)
        assertEquals(LibCategory.ANIME, back.category)
        assertTrue(back.isSeries)
        assertEquals("jinji", back.titleKey)
    }

    @Test fun `ratings survive as source, value and votes`() {
        val r = listOf(
            RatingScore(RatingSource.TMDB, "8.5", votes = 1234),
            RatingScore(RatingSource.IMDB, "9.0"),
        )
        val back = WallSnapshot.decode(WallSnapshot.encode(listOf(group(ratings = r)))).single()
        assertEquals(r, back.ratings)
    }

    @Test fun `the search keys a cold wall is searched by are still written`() {
        // Typing into the box before the library lands must still find things: a card cannot be
        // opened yet, but the user can see whether their library has it.
        val json = WallSnapshot.encode(listOf(group(keys = listOf("shingeki", "attack on titan"))))
        assertTrue("keys are in the file", json.contains("shingeki"))
    }

    @Test fun `a snapshot whose ratings are the old objects still loads its cards`() {
        // The compact form is new; a file left by the previous build must not vanish wholesale.
        val old = JSONArray(WallSnapshot.encode(listOf(group()))).also { arr ->
            arr.getJSONObject(0)
                .put("ratings", JSONArray(listOf(org.json.JSONObject().put("source", "TMDB").put("value", "8.5"))))
        }.toString()
        val back = WallSnapshot.decode(old).single()
        assertEquals("进击的巨人", back.title)
        assertTrue("an unreadable rating is dropped, not fatal", back.ratings.isEmpty())
    }
}
