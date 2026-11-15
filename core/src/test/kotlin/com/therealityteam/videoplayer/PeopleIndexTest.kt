package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.Person
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.PeopleIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "另有 N 部": a person's other cards, counted once per card, newest first, never the card itself. */
class PeopleIndexTest {

    private fun item(id: String, cast: List<String> = emptyList(), crew: List<String> = emptyList()) = MediaItem(
        id, "s", "/v/$id.mkv", "$id.mkv", title = id,
        cast = cast.map { Person(it, role = "x") }, crew = crew.map { Person(it, role = "导演") },
    )

    private fun card(key: String, year: Int?, vararg items: MediaItem) = MediaGroup(
        key = key, title = key, year = year, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = items.size > 1, seasonal = false,
        category = LibCategory.MOVIE, count = items.size, items = items.toList(),
    )

    private val skyfall = card("skyfall", 2012, item("sky", cast = listOf("Daniel Craig"), crew = listOf("萨姆·门德斯")))
    private val nineteen = card("1917", 2019, item("1917", cast = listOf("George MacKay"), crew = listOf("萨姆·门德斯")))
    private val spectre = card("spectre", 2015, item("spec", cast = listOf("Daniel Craig", " 萨姆·门德斯 ")))
    private val show = card(
        "show", 2020,
        item("e1", cast = listOf("Daniel Craig")), item("e2", cast = listOf("Daniel Craig")), item("e3", cast = listOf("Daniel Craig")),
    )

    @Test fun `a person's cards come newest first, in front of or behind the camera`() {
        val index = PeopleIndex.of(listOf(skyfall, nineteen, spectre, show))
        assertEquals(listOf("1917", "spectre", "skyfall"), index.getValue("萨姆·门德斯").map { it.key })
    }

    @Test fun `a show's episodes credit a person once`() {
        val index = PeopleIndex.of(listOf(skyfall, spectre, show))
        assertEquals(listOf("show", "spectre", "skyfall"), index.getValue("Daniel Craig").map { it.key })
    }

    @Test fun `the card on screen is not one of the others`() {
        val index = PeopleIndex.of(listOf(skyfall, nineteen, spectre))
        assertEquals(listOf("spectre", "skyfall"), PeopleIndex.othersOf(index, "萨姆·门德斯", except = "1917").map { it.key })
        assertTrue(PeopleIndex.othersOf(index, "George MacKay", except = "1917").isEmpty())
        assertTrue(PeopleIndex.othersOf(index, "  ", except = "1917").isEmpty())
    }
}
