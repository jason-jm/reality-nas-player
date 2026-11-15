package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.TitleIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** 字母索引: the rail's marks follow the wall's own title order, every mark pointing at a card. */
class TitleIndexTest {

    private fun card(title: String, key: String, named: Boolean = true) = MediaGroup(
        key = title, title = title, year = null, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = 1, titleKey = key,
        items = listOf(
            MediaItem("$title-1", "s", "/v/$title.mkv", "$title.mkv", title = title,
                scrapeStatus = if (named) ScrapeStatus.MATCHED else ScrapeStatus.UNMATCHED),
        ),
    )

    @Test fun `marks run in wall order and point at each letter's first card`() {
        val wall = LibrarySort.apply(
            listOf(
                card("阿基拉", "ajila"), card("1917", "1917"), card("白箱", "baixiang"),
                card("Akira", "akira"), card("爱宠大机密", "aichongdajimi"), card("[raw] clip", "[raw] clip", named = false),
                card("Zootopia", "zootopia"), card("aaa", "aaa", named = false),
            ),
            LibSort.TITLE,
        )
        val index = TitleIndex.of(wall)
        assertEquals(listOf('#', 'A', 'B', 'Z', '?'), index.map { it.first })
        // Each mark lands on a card that carries it.
        assertEquals("1917", wall[index[0].second].title)
        assertEquals("爱宠大机密", wall[index[1].second].title) // aichong… sorts before ajila and akira
        assertEquals("白箱", wall[index[2].second].title)
        assertEquals("Zootopia", wall[index[3].second].title)
        assertEquals(TitleIndex.UNIDENTIFIED, index.last().first)
        assertEquals(false, LibrarySort.identified(wall[index.last().second]))
    }

    @Test fun `the current mark is the last one at or before the first visible card`() {
        val index = listOf('#' to 0, 'A' to 3, 'B' to 10)
        assertEquals('#', TitleIndex.current(index, 0))
        assertEquals('A', TitleIndex.current(index, 9))
        assertEquals('B', TitleIndex.current(index, 40))
        assertEquals('#', TitleIndex.markOf("  "))
        assertEquals('Q', TitleIndex.markOf("qiannianmiaoying"))
    }
}
