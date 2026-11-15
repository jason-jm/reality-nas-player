package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.LibSort
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.LibrarySort
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The plain cards of a regroup sort as they will once romanised, so the wall is not reshuffled twice. */
class KnownTitleKeysTest {

    private fun card(title: String, titleKey: String = title.lowercase()) = MediaGroup(
        key = title, title = title, year = null, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = 1, titleKey = titleKey,
        items = listOf(MediaItem("$title-1", "s", "/v/$title.mkv", "$title.mkv", title = title)),
    )

    @Test fun `known names take their romanised keys and keep the order romanising will give`() {
        val plain = listOf(card("白箱"), card("Akira"), card("Zootopia"), card("新作"))
        // Plain keys: the CJK names sort after every Latin one.
        assertEquals(listOf("Akira", "Zootopia", "新作", "白箱"), LibrarySort.apply(plain, LibSort.TITLE).map { it.title })
        val known = mapOf("白箱" to "baixiang", "Akira" to "akira")
        val carried = LibraryGrouping.withKnownTitleKeys(plain, known)
        // 白箱 is back under B straight away; a name never romanised (新作) waits for the pass.
        assertEquals(listOf("Akira", "白箱", "Zootopia", "新作"), LibrarySort.apply(carried, LibSort.TITLE).map { it.title })
    }

    @Test fun `nothing known leaves the cards as they are`() {
        val plain = listOf(card("白箱"))
        assertSame(plain, LibraryGrouping.withKnownTitleKeys(plain, emptyMap()))
        assertSame(plain[0], LibraryGrouping.withKnownTitleKeys(plain, mapOf("白箱" to "白箱")).single())
    }
}
