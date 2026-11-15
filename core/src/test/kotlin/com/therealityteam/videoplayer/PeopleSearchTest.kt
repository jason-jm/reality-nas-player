package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.PeopleIndex
import com.therealityteam.videoplayer.ui.library.PeopleSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人物 in a search: names that hold the query, the name itself first, with a face where one exists. */
class PeopleSearchTest {

    private fun item(id: String, cast: List<Person> = emptyList(), crew: List<Person> = emptyList()) =
        MediaItem(id, "s", "/v/$id.mkv", "$id.mkv", title = id, cast = cast, crew = crew)

    private fun card(key: String, year: Int, vararg items: MediaItem) = MediaGroup(
        key = key, title = key, year = year, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = items.size, items = items.toList(),
    )

    private val library = listOf(
        card("lalaland", 2016, item("ll", cast = listOf(Person("艾玛·斯通", "Mia", profileUrl = "emma"), Person("瑞恩·高斯林", "Sebastian")))),
        card("cruella", 2021, item("cr", cast = listOf(Person("艾玛·斯通", "Cruella")))),
        card("drive", 2011, item("dr", cast = listOf(Person("瑞恩·高斯林", "Driver")), crew = listOf(Person("尼古拉斯·温丁·雷弗恩", "导演")))),
        card("abc", 2001, item("ab", cast = listOf(Person("Stone Cold", "Self")))),
        card("pets", 2016, item("pt", cast = listOf(Person("埃里克·斯通斯特里特", "Duke")))),
    )
    private val index = PeopleIndex.of(library)
    private val keys = PeopleSearch.keys(index)

    @Test fun `a name is found by any part of it, the middle dot and spaces aside`() {
        val hits = PeopleSearch.match(index, keys, "斯通")
        // The surname itself before a longer name that holds it.
        assertEquals(listOf("艾玛·斯通", "埃里克·斯通斯特里特"), hits.map { it.person.name })
        assertEquals(listOf("cruella", "lalaland"), hits[0].works.map { it.key })
        assertEquals("emma", hits[0].person.profileUrl)
        assertEquals(null, hits[0].person.role)
    }

    @Test fun `the name itself comes first, then names starting with the query, then more cards`() {
        val stone = PeopleSearch.match(index, keys, "stone")
        assertEquals(listOf("Stone Cold"), stone.map { it.person.name })
        assertEquals(listOf("瑞恩·高斯林"), PeopleSearch.match(index, keys, "高斯林").map { it.person.name })
    }

    @Test fun `one CJK character or two letters find no one`() {
        assertTrue(PeopleSearch.match(index, keys, "斯").isEmpty())
        assertTrue(PeopleSearch.match(index, keys, "st").isEmpty())
        assertTrue(PeopleSearch.match(index, keys, "  ").isEmpty())
    }
}
