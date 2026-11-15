package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.RatingScore
import com.therealityteam.videoplayer.data.model.RatingSource
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** A score is stored with a point in every language: "%.1f" alone wrote "7,5" in German, which the 评分 order read as 7. */
class RatingFormatTest {

    private val before = Locale.getDefault()

    @After fun restore() = Locale.setDefault(before)

    @Test fun `scores are written with a point whatever the device's language`() {
        for (tag in listOf("de-DE", "fr-FR", "ru-RU", "pl-PL", "cs-CZ", "tr-TR", "en-US", "zh-CN")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, "7.5", RatingScore.tenths(7.46))
            assertEquals(tag, "10.0", RatingScore.tenths(10.0))
        }
    }

    @Test fun `a comma written by an older build reads back as a point`() {
        assertEquals("7.5", RatingScore.stored("7,5"))
        assertEquals("91%", RatingScore.stored("91%"))
        assertEquals("74", RatingScore.stored("74"))
    }

    @Test fun `the rating order ranks a stored 7,5 above a 7,0`() {
        fun card(key: String, value: String) = MediaGroup(
            key = key, title = key, year = null, posterPath = null, backdropPath = null, dominantColor = null,
            runtime = null, certification = null, ratings = listOf(RatingScore(RatingSource.IMDB, value)), isSeries = false,
            seasonal = false, category = LibCategory.MOVIE, count = 1,
            items = listOf(MediaItem(key, "s", "/v/$key.mkv", "$key.mkv", title = key)),
        )
        val order = LibrarySort.apply(listOf(card("seven", "7,0"), card("seven-and-a-half", "7,5"), card("eight", "8.0")), LibSort.RATING)
        assertEquals(listOf("eight", "seven-and-a-half", "seven"), order.map { it.key })
    }
}
