package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.SurprisePick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 随便看看: an unbegun, named title — the better-rated more often — and not the last ones offered. */
class SurprisePickTest {

    private fun card(key: String, rating: String? = null, progress: PlaybackProgress = PlaybackProgress(), named: Boolean = true) = MediaGroup(
        key = key, title = key, year = 2000, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null,
        ratings = listOfNotNull(rating?.let { RatingScore(RatingSource.IMDB, it, votes = 1000) }),
        isSeries = false, seasonal = false, category = LibCategory.MOVIE, count = 1,
        items = listOf(MediaItem("$key-1", "s", "/v/$key.mkv", "$key.mkv", title = key, progress = progress,
            scrapeStatus = if (named) ScrapeStatus.MATCHED else ScrapeStatus.UNMATCHED)),
    )

    @Test fun `only named titles nobody has begun are offered`() {
        val seen = card("seen", progress = PlaybackProgress(positionMs = 1, durationMs = 100))
        val raw = card("raw", named = false)
        val fresh = card("fresh")
        repeat(20) { assertEquals("fresh", SurprisePick.of(listOf(seen, raw, fresh), random = Random(it))!!.key) }
        assertNull(SurprisePick.of(listOf(seen, raw)))
    }

    @Test fun `a better-rated title comes up more often`() {
        val pool = listOf(card("great", "9.0"), card("poor", "5.0"))
        val great = (0 until 2000).count { SurprisePick.of(pool, random = Random(it))!!.key == "great" }
        // 2^3 against 2^-1: about 16 in 17.
        assertTrue("great came up $great times", great in 1750..1950)
    }

    @Test fun `the last ones offered wait while there are others`() {
        val pool = listOf(card("a"), card("b"), card("c"))
        repeat(20) { assertEquals("c", SurprisePick.of(pool, recent = listOf("a", "b"), random = Random(it))!!.key) }
        // All offered already: start over rather than offer nothing.
        assertTrue(SurprisePick.of(pool, recent = listOf("a", "b", "c"), random = Random(1)) != null)
    }
}
