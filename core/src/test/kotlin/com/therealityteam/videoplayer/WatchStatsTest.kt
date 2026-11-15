package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.WatchStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 观影统计: this year's finished films (by card) and episodes, and the time they took. */
class WatchStatsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 2, 21, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(year: Int, month: Int = 6) = LocalDateTime.of(year, month, 1, 20, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun item(id: String, position: Long, duration: Long, watched: Boolean, updatedAt: Long) =
        MediaItem(id, "s", "/v/$id.mkv", "$id.mkv", title = id, progress = PlaybackProgress(position, duration, watched, updatedAt))

    private fun group(key: String, series: Boolean, vararg items: MediaItem) = MediaGroup(
        key = key, title = key, year = null, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = series, seasonal = false,
        category = if (series) LibCategory.TV else LibCategory.MOVIE, count = items.size, items = items.toList(),
    )

    @Test fun `films count by card, episodes by file, time by length or by how far it got`() {
        val stats = WatchStats.ofYear(
            listOf(
                // One film in two versions, both seen through this year: one film, both lengths.
                group("film", false, item("f1", 119 * minute, 120 * minute, true, at(2026)), item("f2", 100 * minute, 100 * minute, true, at(2026, 7))),
                // A series: two episodes finished, one half-way.
                group("show", true,
                    item("e1", 24 * minute, 24 * minute, true, at(2026)),
                    item("e2", 24 * minute, 24 * minute, true, at(2026)),
                    item("e3", 12 * minute, 24 * minute, false, at(2026))),
                // Last year's film is not this year's.
                group("old", false, item("o1", 90 * minute, 90 * minute, true, at(2025))),
                // A film started but not finished: time, no film.
                group("started", false, item("s1", 30 * minute, 110 * minute, false, at(2026))),
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(WatchStats.Summary(films = 1, episodes = 2, minutes = 120 + 100 + 24 + 24 + 12 + 30L), stats)
    }

    @Test fun `an extra played to its end is time spent, not an episode or a film`() {
        fun named(id: String, name: String, watched: Boolean, length: Long) =
            MediaItem(id, "s", "/v/Show/$name", name, title = "Show", progress = PlaybackProgress(length, length, watched, at(2026)))
        val stats = WatchStats.ofYear(
            listOf(
                group("show", true,
                    named("e1", "[Grp] Show [01].mkv", true, 24 * minute),
                    named("op", "[Grp] Show [NCOP].mkv", true, 2 * minute)),
                group("film", false,
                    named("f", "Film.2020.1080p.mkv", false, 0),
                    named("t", "Film.2020.Trailer.mkv", true, 3 * minute)),
            ),
            nowMs = now, zone = zone,
        )
        assertEquals(WatchStats.Summary(films = 0, episodes = 1, minutes = 24 + 2 + 3L), stats)
    }

    @Test fun `nothing played this year is empty`() {
        assertTrue(WatchStats.ofYear(emptyList(), nowMs = now, zone = zone).isEmpty)
        val untouched = group("x", false, item("x1", 0, 100 * minute, false, at(2026)))
        assertTrue(WatchStats.ofYear(listOf(untouched), nowMs = now, zone = zone).isEmpty)
    }
}
