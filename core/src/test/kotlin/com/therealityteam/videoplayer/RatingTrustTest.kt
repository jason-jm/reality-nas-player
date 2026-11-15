package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 评分 ranks on scores with an audience behind them — not a concert video's 9.3 from a handful. */
class RatingTrustTest {

    private fun card(key: String, vararg ratings: RatingScore) = MediaGroup(
        key = key, title = key, year = null, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = ratings.toList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = 1, items = listOf(MediaItem(key, "s", "/v/$key.mkv", "$key.mkv", title = key)),
    )

    private val concert = card("concert", RatingScore(RatingSource.IMDB, "9.3"), RatingScore(RatingSource.TMDB, "8.2", votes = 19))
    private val knight = card("knight", RatingScore(RatingSource.IMDB, "9.1"), RatingScore(RatingSource.TMDB, "8.5", votes = 36671))
    private val gits = card("gits", RatingScore(RatingSource.ANILIST, "84"), RatingScore(RatingSource.BANGUMI, "9.2"))

    @Test fun `a vote-less score is not trusted when every known count is tiny`() {
        assertNull(LibrarySort.ratingFor(concert.ratings))
        assertEquals("9.1", LibrarySort.ratingFor(knight.ratings)?.value)
    }

    @Test fun `with no counts anywhere the scores stand, and the order follows`() {
        assertEquals("9.2", LibrarySort.ratingFor(gits.ratings)?.value)
        assertEquals(listOf("gits", "knight", "concert"), LibrarySort.apply(listOf(concert, knight, gits), LibSort.RATING).map { it.key })
    }
}
