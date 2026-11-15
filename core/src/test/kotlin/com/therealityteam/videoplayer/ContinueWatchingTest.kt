package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.ContinueWatching
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

/** 继续观看: one entry per title, the file last touched, most recent first, finished files left out. */
class ContinueWatchingTest {

    private fun ep(id: String, show: String, n: Int, pos: Long, dur: Long, at: Long, watched: Boolean = false) = MediaItem(
        id = id, sourceId = "s", path = "smb://nas/video/TV/$show/S01E0$n.mkv", fileName = "S01E0$n.mkv", title = show,
        showTitle = show, season = 1, episode = n, kind = MediaKind.EPISODE, scrapeStatus = ScrapeStatus.MATCHED,
        collectionKey = "id:$show", tmdbId = show.hashCode(),
        progress = PlaybackProgress(positionMs = pos, durationMs = dur, watched = watched, updatedAt = at),
    )

    private fun film(id: String, title: String, pos: Long, dur: Long, at: Long) = MediaItem(
        id = id, sourceId = "s", path = "smb://nas/video/Movies/$title/$title.mkv", fileName = "$title.mkv", title = title,
        kind = MediaKind.MOVIE, scrapeStatus = ScrapeStatus.MATCHED, collectionKey = "id:$id", tmdbId = id.toInt(),
        progress = PlaybackProgress(positionMs = pos, durationMs = dur, updatedAt = at),
    )

    @Test
    fun theRowResumesTheLastTouchedFileOfEachTitle() {
        val groups = LibraryGrouping.group(
            listOf(
                ep("a1", "Alpha", 1, 2_000_000, 2_400_000, at = 10, watched = true), // finished
                ep("a2", "Alpha", 2, 600_000, 2_400_000, at = 50),                    // in progress, latest of Alpha
                ep("a3", "Alpha", 3, 0, 2_400_000, at = 0),                           // not started
                film("7", "Heat", 5_000_000, 10_200_000, at = 70),                    // in progress
                film("8", "Ran", 9_900_000, 10_000_000, at = 90),                     // 99% — done
                film("9", "Seven", 0, 0, at = 0),                                     // never started
            ),
        )
        val row = ContinueWatching.of(groups)
        assertEquals(listOf("Heat", "Alpha"), row.map { it.group.title })
        assertEquals("a2", row[1].item.id)
        // The subtitle's FACTS, not its wording (the words are the reader's — see LocalizedStringsTest):
        // the episode it names, and the minutes left. A film names no episode, only its remainder.
        assertEquals(ContinueWatching.Sub(false, row[1].item, 30, null), ContinueWatching.sub(row[1]))
        assertEquals(ContinueWatching.Sub(false, null, 86, null), ContinueWatching.sub(row[0]))
        assertEquals(1, ContinueWatching.of(groups, limit = 1).size)
    }

    @Test
    fun aSeriesYouFinishedAnEpisodeOfStaysInTheRowWithTheNextOne() {
        // Alpha: episode 1 finished at t=10, episode 2 untouched → the row offers episode 2.
        val alpha = listOf(ep("a1", "Alpha", 1, 1_500_000, 1_500_000, 10, watched = true), ep("a2", "Alpha", 2, 0, 1_500_000, 0))
        // Beta: everything finished → nothing next, nothing in the row. Gamma: never started → nothing.
        val beta = listOf(ep("b1", "Beta", 1, 1_500_000, 1_500_000, 30, watched = true))
        val gamma = listOf(ep("g1", "Gamma", 1, 0, 1_500_000, 0), ep("g2", "Gamma", 2, 0, 1_500_000, 0))
        // Heat: part-way at t=20 — a resume, which outranks the next-up by its own time.
        val heat = film("7", "Heat", 600_000, 6_000_000, 20)
        val groups = LibraryGrouping.group(alpha + beta + gamma + listOf(heat))
        val row = ContinueWatching.of(groups)
        assertEquals(listOf("Heat", "Alpha"), row.map { it.group.title })
        assertEquals("a2", row[1].item.id)
        assertEquals(true, row[1].nextUp)
        assertEquals(ContinueWatching.Sub(true, row[1].item, null, null), ContinueWatching.sub(row[1]))
    }
}
