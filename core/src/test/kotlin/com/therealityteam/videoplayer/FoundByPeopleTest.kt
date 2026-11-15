package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「高达」 finds the Gundam cards by their names and 火星救援 through its writer 德鲁·高达: the second
 * kind follows the first, apart, rather than sorting in among them.
 */
class FoundByPeopleTest {

    private fun film(id: String, title: String, original: String, crew: List<String> = emptyList(), cast: List<String> = emptyList()) = MediaItem(
        id = id, sourceId = "s", path = "smb://n/video/Movies/$id/$id.mkv", fileName = "$id.mkv",
        title = title, originalTitle = original, year = 2015, tmdbId = id.hashCode(), kind = MediaKind.MOVIE,
        scrapeStatus = ScrapeStatus.MATCHED,
        crew = crew.map { Person(it, "编剧") },
        cast = cast.map { Person(it, "角色") },
    )

    private val cards = LibraryGrouping.group(
        listOf(
            film("martian", "火星救援", "The Martian", crew = listOf("德鲁·高达", "雷德利·斯科特"), cast = listOf("马特·达蒙")),
            film("greco", "高达G之复国运动 剧场版I", "Gundam Reconguista in G Movie I"),
            film("gundam_doc", "高达：创造者们", "Gundam Creators", crew = listOf("富野由悠季")),
        ),
    )
    private fun card(id: String) = cards.first { g -> g.items.any { it.id == id } }

    @Test fun `a work found only through somebody in it is told apart`() {
        assertTrue(LibraryGrouping.foundOnlyByPeople(card("martian"), "高达"))
        assertFalse(LibraryGrouping.foundOnlyByPeople(card("greco"), "高达"))
        assertFalse(LibraryGrouping.foundOnlyByPeople(card("gundam_doc"), "高达"))
    }

    @Test fun `a work found by its own name is never one of them`() {
        assertFalse(LibraryGrouping.foundOnlyByPeople(card("martian"), "火星"))
        assertFalse(LibraryGrouping.foundOnlyByPeople(card("martian"), "martian"))
        // …and asked by its person alone, it is found through them.
        assertTrue(LibraryGrouping.foundOnlyByPeople(card("martian"), "达蒙"))
    }
}
