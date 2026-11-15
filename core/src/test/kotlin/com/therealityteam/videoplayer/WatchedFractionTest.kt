package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.WallFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card is as seen as its episodes are: extras and a second copy of an episode do not hold it back. */
class WatchedFractionTest {

    private val seen = PlaybackProgress(positionMs = 100, durationMs = 100, watched = true)

    private fun file(id: String, name: String, tag: ContentKind = ContentKind.MAIN, progress: PlaybackProgress = PlaybackProgress()) =
        MediaItem(id, "s", "/v/Show/$name", name, title = "Show", contentTag = tag, progress = progress)

    private fun card(vararg items: MediaItem) = MediaGroup(
        key = "anilist:1", title = "Show", year = 2020, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = true, seasonal = true,
        category = LibCategory.ANIME, count = 3, items = items.toList(),
    )

    @Test fun `every episode seen is seen, whatever extras ride along`() {
        val show = card(
            file("e1", "[Grp] Show [01].mkv", progress = seen),
            file("e2", "[Grp] Show [02].mkv", progress = seen),
            file("e3", "[Grp] Show [03].mkv", progress = seen),
            file("op", "[Grp] Show [NCOP].mkv"),                       // tagged MAIN by the scan, an extra by its name
            file("pv", "[Grp] Show [PV01].mkv", tag = ContentKind.EXTRA),
        )
        assertEquals(1f, show.watchedFraction, 0.001f)
        assertEquals(WallFilter.Watch.WATCHED, WallFilter.watchOf(show))
    }

    @Test fun `a second release of the episodes does not halve what was seen`() {
        val show = card(
            file("bd1", "[VCB-Studio] Show [01][Ma10p_1080p].mkv", progress = seen),
            file("bd2", "[VCB-Studio] Show [02][Ma10p_1080p].mkv", progress = seen),
            file("tv1", "Show.S01E01.TVRip.1080p.mkv"),
            file("tv2", "Show.S01E02.TVRip.1080p.mkv"),
        )
        assertEquals(1f, show.watchedFraction, 0.001f)
    }

    @Test fun `half the episodes is half, and an extra alone is nothing begun`() {
        val half = card(file("e1", "Show [01].mkv", progress = seen), file("e2", "Show [02].mkv"), file("pv", "Show [PV].mkv", tag = ContentKind.EXTRA))
        assertEquals(0.5f, half.watchedFraction, 0.001f)
        val extraOnly = card(file("e1", "Show [01].mkv"), file("e2", "Show [02].mkv"), file("pv", "Show [PV].mkv", tag = ContentKind.EXTRA, progress = seen))
        assertEquals(0f, extraOnly.watchedFraction, 0.001f)
        assertEquals(WallFilter.Watch.UNWATCHED, WallFilter.watchOf(extraOnly))
    }
}
