package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.BecauseYouWatched
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 因为你看过: what is like a title watched lately, and not begun yet. */
class BecauseYouWatchedTest {

    private fun film(title: String, director: String, genres: List<String>, progress: PlaybackProgress = PlaybackProgress()) = MediaGroup(
        key = title, title = title, year = 2010, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = 1,
        items = listOf(
            MediaItem("$title-1", "s", "/v/$title.mkv", "$title.mkv", title = title, genres = genres,
                crew = listOf(Person(director, "导演")), progress = progress),
        ),
    )

    private val war = listOf("战争", "剧情")
    private val watchedYesterday = PlaybackProgress(positionMs = 100, durationMs = 6_000_000, updatedAt = 2_000)
    private val watchedLongAgo = PlaybackProgress(positionMs = 6_000_000, durationMs = 6_000_000, watched = true, updatedAt = 1_000)

    @Test fun `the latest title watched with enough like it seeds the row, and only untouched titles fill it`() {
        val library = listOf(
            film("1917", "Mendes", war, watchedYesterday),
            film("Skyfall", "Mendes", listOf("动作")),
            film("Dunkirk", "Nolan", war),
            film("Fury", "Ayer", war),
            film("Hacksaw", "Gibson", war),
            film("Begun", "Mendes", war, PlaybackProgress(positionMs = 10, durationMs = 100, updatedAt = 500)),
            film("Cartoon", "Someone", listOf("动画")),
        )
        val row = BecauseYouWatched.of(library)!!
        assertEquals("1917", row.seed.title)
        assertEquals(setOf("Skyfall", "Dunkirk", "Fury", "Hacksaw"), row.cards.map { it.title }.toSet())
    }

    @Test fun `a seed with too few like it gives way to the next, and nothing watched gives no row`() {
        val library = listOf(
            film("Lonely", "Nobody", listOf("纪录"), watchedYesterday),
            film("1917", "Mendes", war, watchedLongAgo),
            film("Dunkirk", "Nolan", war), film("Fury", "Ayer", war), film("Hacksaw", "Gibson", war), film("Midway", "Emmerich", war),
        )
        assertEquals("1917", BecauseYouWatched.of(library)!!.seed.title)
        assertNull(BecauseYouWatched.of(library.map { g -> g.copy(items = g.items.map { it.copy(progress = PlaybackProgress()) }) }))
    }
}
