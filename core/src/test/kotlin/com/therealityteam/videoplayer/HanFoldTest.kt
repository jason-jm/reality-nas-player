package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.HanFold
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import com.therealityteam.videoplayer.ui.library.PeopleIndex
import com.therealityteam.videoplayer.ui.library.PeopleSearch
import com.therealityteam.videoplayer.ui.library.SearchKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A name typed in simplified Chinese finds it written in Japanese. The traditional forms go by the
 * platform's ICU, which a JVM test has none of; the Japanese forms ICU does not know go by the table,
 * and those are what is tested here: 錬, 鋼, 沢, 撃.
 */
class HanFoldTest {

    @Test fun `the Japanese forms fold to the simplified ones, and nothing else changes`() {
        assertEquals("钢の炼金术", HanFold.fold("鋼の錬金术"))
        assertEquals("泽城みゆき", HanFold.fold("沢城みゆき"))
        assertEquals("进击", HanFold.fold("进撃"))
        assertEquals("Fullmetal Alchemist", HanFold.fold("Fullmetal Alchemist"))
        assertEquals("借りぐらしのアリエッティ", HanFold.fold("借りぐらしのアリエッティ"))
    }

    private fun anime(id: String, title: String, original: String, cast: List<String> = emptyList()) = MediaItem(
        id = id, sourceId = "s", path = "smb://n/video/Anime/$id/$id.mkv", fileName = "$id.mkv",
        title = title, originalTitle = original, year = 2003, collectionKey = "anilist:${id.hashCode()}",
        kind = MediaKind.EPISODE, episode = 1, showTitle = title, scrapeStatus = ScrapeStatus.MATCHED,
        cast = cast.map { Person(it, "声") },
    )

    @Test fun `a title written in Japanese is found by its simplified Chinese`() {
        val groups = LibraryGrouping.romanized(
            LibraryGrouping.group(listOf(anime("fma", "Fullmetal Alchemist", "鋼の錬金術師"))),
        ) { null }
        assertEquals(1, LibraryGrouping.filter(groups, LibSection.ALL, "炼金").size)
        assertEquals(1, LibraryGrouping.filter(groups, LibSection.ALL, "鋼の錬金").size)
        assertTrue(SearchKeys.matches(groups.single().searchKeys, "钢の炼"))
    }

    @Test fun `one person credited in two scripts is one face holding both their cards`() {
        val groups = LibraryGrouping.group(
            listOf(
                anime("drrr", "Durarara", "デュラララ!!", cast = listOf("沢城みゆき")),
                anime("lupin", "Lupin III", "ルパン三世", cast = listOf("泽城みゆき")),
                anime("lupin2", "Lupin III Part 5", "ルパン三世 PART5", cast = listOf("沢城みゆき")),
            ),
        )
        val index = PeopleIndex.of(groups)
        val hits = PeopleSearch.match(index, PeopleSearch.keys(index), "泽城")
        assertEquals(1, hits.size)
        assertEquals("沢城みゆき", hits.single().person.name)
        assertEquals(3, hits.single().works.size)
    }

    @Test fun `a person credited in Japanese is found by the simplified name`() {
        val groups = LibraryGrouping.group(listOf(anime("dnf", "Durarara", "デュラララ!!", cast = listOf("沢城みゆき"))))
        val index = PeopleIndex.of(groups)
        val hits = PeopleSearch.match(index, PeopleSearch.keys(index), "泽城")
        assertEquals(listOf("沢城みゆき"), hits.map { it.person.name })
    }
}
