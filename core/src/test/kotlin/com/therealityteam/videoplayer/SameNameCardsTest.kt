package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two cards with one name between them.
 *
 * 逮捕令's 1994 OVA and its 1996 TV series really are two works with one name — a database tells
 * them apart by a field this app does not keep — and on the wall they were the same cover, the same
 * title, twice. The year printed underneath was not enough; what a person reads is the name.
 */
class SameNameCardsTest {

    private fun item(id: String, title: String, year: Int?, key: String, folder: String) = MediaItem(
        id = id, sourceId = "s", path = "smb://n/video/Anime/$folder/$id.mkv", fileName = "$id.mkv",
        title = title, year = year, collectionKey = key, tmdbId = id.hashCode(),
        kind = MediaKind.EPISODE, episode = 1, showTitle = title, scrapeStatus = ScrapeStatus.MATCHED,
    )

    private fun titles(vararg items: MediaItem) =
        LibraryGrouping.group(items.toList()).map { it.title }.sorted()

    @Test fun `two works with one name take their years`() {
        assertEquals(
            listOf("逮捕令 (1994)", "逮捕令 (1996)"),
            titles(
                item("a", "逮捕令", 1994, "anilist:1577", "OVA"),
                item("b", "逮捕令", 1996, "anilist:1372", "TV"),
            ),
        )
    }

    @Test fun `a name only one card has is left alone`() {
        assertEquals(
            listOf("攻壳机动队", "逮捕令"),
            titles(
                item("a", "逮捕令", 1994, "anilist:1577", "OVA"),
                item("b", "攻壳机动队", 1995, "anilist:43", "GITS"),
            ),
        )
    }

    @Test fun `one name and one year is a fault, not a pair to label`() {
        // Stamping the same year on both would hide a scraping error behind a label.
        assertEquals(
            listOf("无职转生", "无职转生"),
            titles(
                item("a", "无职转生", 2021, "anilist:108465", "S1"),
                item("b", "无职转生", 2021, "id:94664", "S2"),
            ),
        )
    }

    @Test fun `cards with no year are left alone`() {
        // Nothing to tell them apart WITH. A folder of somebody's own clips has no year to give,
        // and a label that says "(null)" would be worse than the repetition.
        assertEquals(
            listOf("00 Footage", "00 Footage"),
            titles(
                item("a", "00 Footage", null, "anilist:111", "F1"),
                item("b", "00 Footage", null, "anilist:222", "F2"),
            ),
        )
    }

    @Test fun `the name a database gave is still what search answers to`() {
        val groups = LibraryGrouping.group(
            listOf(
                item("a", "逮捕令", 1994, "anilist:1577", "OVA"),
                item("b", "逮捕令", 1996, "anilist:1372", "TV"),
            ),
        )
        val found = LibraryGrouping.filter(groups, LibSection.ALL, "逮捕令", com.therealityteam.videoplayer.ui.library.LibSort.RECENT)
        assertEquals("both cards must still answer to the plain name", 2, found.size)
        assertTrue(found.all { it.title.startsWith("逮捕令 (") })
    }
}
