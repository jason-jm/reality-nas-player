package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.SimilarTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 相似作品: same makers first, then shared genres; a card sharing nothing is never offered. */
class SimilarTitlesTest {

    private fun card(
        key: String,
        year: Int,
        genres: List<String>,
        crew: List<String> = emptyList(),
        cast: List<String> = emptyList(),
        category: LibCategory = LibCategory.MOVIE,
        rating: String? = null,
        watched: Boolean = false,
    ) = MediaGroup(
        key = key, title = key, year = year, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null,
        ratings = listOfNotNull(rating?.let { RatingScore(RatingSource.IMDB, it) }),
        isSeries = false, seasonal = false, category = category, count = 1,
        items = listOf(
            MediaItem(
                key, "s", "/v/$key.mkv", "$key.mkv", title = key, year = year, genres = genres,
                crew = crew.map { Person(it, "导演") }, cast = cast.map { Person(it, "x") },
                progress = PlaybackProgress(watched = watched),
            ),
        ),
    )

    private val matrix3 = card("matrix3", 2003, listOf("动作", "科幻"), crew = listOf("Wachowski"), cast = listOf("Keanu Reeves", "Carrie-Anne Moss"))
    private val matrix1 = card("matrix1", 1999, listOf("动作", "科幻"), crew = listOf("Wachowski"), cast = listOf("Keanu Reeves", "Carrie-Anne Moss"), watched = true)
    private val johnWick = card("johnwick", 2014, listOf("动作", "惊悚"), cast = listOf("Keanu Reeves"))
    private val arrival = card("arrival", 2016, listOf("科幻", "剧情"), rating = "7.9")
    private val drama = card("drama", 2010, listOf("剧情"), rating = "9.0")
    private val anime = card("anime", 2003, listOf("动作", "科幻"), category = LibCategory.ANIME)

    @Test fun `the same makers come before a shared genre`() {
        val similar = SimilarTitles.of(matrix3, listOf(drama, arrival, johnWick, matrix1, anime))
        assertEquals("matrix1", similar.first().key)
        assertTrue(similar.indexOf(johnWick) < similar.indexOf(arrival).takeIf { it >= 0 } ?: Int.MAX_VALUE)
    }

    @Test fun `a card sharing nothing is never offered, however well rated`() {
        val similar = SimilarTitles.of(matrix3, listOf(drama, arrival, matrix1))
        assertFalse(drama in similar)
    }

    @Test fun `the card itself is not similar to itself, and a card with nothing known has no row`() {
        assertFalse(matrix3 in SimilarTitles.of(matrix3, listOf(matrix3, matrix1)))
        val blank = card("blank", 2000, emptyList())
        assertTrue(SimilarTitles.of(blank, listOf(matrix1, arrival)).isEmpty())
    }
}
