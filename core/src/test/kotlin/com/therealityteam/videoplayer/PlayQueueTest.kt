package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Test

/** What plays next is the list the detail page shows a file in — never a show's extras after its episodes. */
class PlayQueueTest {

    private fun file(name: String, tag: ContentKind? = null) =
        MediaItem(name, "s", "/v/Show/$name", name, title = "Show", contentTag = tag)

    private fun card(series: Boolean, vararg items: MediaItem) = MediaGroup(
        key = "anilist:1", title = "Show", year = 2021, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = series, seasonal = series,
        category = LibCategory.ANIME, count = items.size, items = items.toList(),
    )

    // As the library holds them: an MV first, an NC version after episode 2.
    private val mv = file("「Hands Up」 MV.mkv", ContentKind.EXTRA)
    private val e1 = file("[Grp] Show [01].mkv", ContentKind.MAIN)
    private val e2 = file("[Grp] Show [02].mkv", ContentKind.MAIN)
    private val nc = file("[Grp] Show [EP02 B-Part Non-Credit Ver.].mkv", ContentKind.EXTRA)
    private val recap = file("[Grp] Show [02.5].mkv", ContentKind.EXTRA)
    private val e3 = file("[Grp] Show [03].mkv", ContentKind.MAIN)
    private val op = file("[Grp] Show [NCOP01].mkv", ContentKind.EXTRA)
    private val show = card(true, mv, e1, e2, nc, recap, e3, op)

    private fun names(q: List<MediaItem>) = q.map { it.fileName }

    @Test fun `an episode is followed by the episodes, in the page's order`() {
        assertEquals(names(listOf(e1, e2, recap, e3)), names(PlayQueue.of(show, e2)))
    }

    @Test fun `an extra is followed by the extras`() {
        val q = PlayQueue.of(show, op)
        assertEquals(true, q.contains(op))
        assertEquals(false, q.any { it in listOf(e1, e2, e3) })
    }

    @Test fun `episodes run on across seasons`() {
        val s1 = file("Show.S01E01.mkv")
        val s2 = file("Show.S01E02.mkv")
        val s3 = file("Show.S02E01.mkv")
        assertEquals(names(listOf(s1, s2, s3)), names(PlayQueue.of(card(true, s3, s1, s2), s2)))
    }

    @Test fun `a film plays its own parts, and a trailer the extras`() {
        val cd1 = MediaItem("cd1", "s", "/v/Film/Film.2020.CD1.mkv", "Film.2020.CD1.mkv", title = "Film", contentTag = ContentKind.MAIN)
        val cd2 = MediaItem("cd2", "s", "/v/Film/Film.2020.CD2.mkv", "Film.2020.CD2.mkv", title = "Film", contentTag = ContentKind.MAIN)
        val trailer = MediaItem("tr", "s", "/v/Film/Film.2020.Trailer.mkv", "Film.2020.Trailer.mkv", title = "Film", contentTag = ContentKind.EXTRA)
        val film = card(false, trailer, cd2, cd1)
        assertEquals(listOf("cd1", "cd2"), PlayQueue.of(film, cd1).map { it.id })
        assertEquals(listOf("tr"), PlayQueue.of(film, trailer).map { it.id })
    }
}
