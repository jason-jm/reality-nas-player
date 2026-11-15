package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.SeriesTitles
import org.junit.Assert.assertEquals
import org.junit.Test

/** 系列作品, from the titles a real library's cards carry. */
class SeriesTitlesTest {

    private fun card(title: String, year: Int?) = MediaGroup(
        key = "k:$title", title = title, year = year, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = 1,
        items = listOf(MediaItem("i:$title", "s", "/v/$title.mkv", "$title.mkv", title = title, kind = MediaKind.MOVIE)),
    )

    private val library = listOf(
        card("黑客帝国", 1999), card("黑客帝国3：矩阵革命", 2003), card("黑客帝国2：重装上阵", 2003),
        card("星球大战4：新希望", 1977), card("星球大战1：幽灵的威胁", 1999), card("星球大战：幻境", 2021),
        card("星球大战外传1：侠盗一号", 2016), card("星球大战外传2：游侠索罗", 2018),
        card("机动战士高达 逆袭的夏亚", 1988), card("机动战士高达00", 2007), card("机动战士高达SEED FREEDOM", 2024),
        card("银翼杀手", 1982), card("银翼杀手2049", 2017),
        card("星际穿越", 2014), card("星际迷航", 2009),
        card("DJI_20250508130621_0001_D", null), card("DJI_20250731144230_0027_D", null),
        card("七武士", 1954),
    )

    private fun titles(of: String) = SeriesTitles.of(library.first { it.title == of }, library).map { it.title }

    @Test fun `a series in the order it came out`() {
        assertEquals(listOf("黑客帝国2：重装上阵", "黑客帝国3：矩阵革命"), titles("黑客帝国"))
        assertEquals(listOf("黑客帝国", "黑客帝国2：重装上阵"), titles("黑客帝国3：矩阵革命"))
        assertEquals(listOf("银翼杀手2049"), titles("银翼杀手"))
    }

    @Test fun `a name that runs on from the series' name belongs to it`() {
        assertEquals(
            listOf("星球大战4：新希望", "星球大战1：幽灵的威胁", "星球大战外传2：游侠索罗", "星球大战：幻境"),
            titles("星球大战外传1：侠盗一号"),
        )
        assertEquals(listOf("机动战士高达 逆袭的夏亚", "机动战士高达00"), titles("机动战士高达SEED FREEDOM"))
    }

    @Test fun `what comes next is the next one out not yet seen`() {
        fun next(of: String, lib: List<MediaGroup> = library) = SeriesTitles.nextAfter(lib.first { it.title == of }, lib)?.title
        assertEquals("黑客帝国2：重装上阵", next("黑客帝国"))
        // The same year: by name, 2 before 3.
        assertEquals("黑客帝国3：矩阵革命", next("黑客帝国2：重装上阵"))
        assertEquals(null, next("黑客帝国3：矩阵革命"))
        // Seen already: the one after it.
        val seen2 = library.map { c ->
            if (c.title != "黑客帝国2：重装上阵") c
            else c.copy(items = c.items.map { it.copy(progress = PlaybackProgress(positionMs = 10, durationMs = 10, watched = true, updatedAt = 1)) })
        }
        assertEquals("黑客帝国3：矩阵革命", next("黑客帝国", seen2))
        // Where a card stands in its series' row: Rogue One before Solo.
        val rogue = library.first { it.title == "星球大战外传1：侠盗一号" }
        val row = SeriesTitles.of(rogue, library)
        assertEquals("星球大战外传2：游侠索罗", row[SeriesTitles.indexAfter(rogue, row)].title)
    }

    @Test fun `every series of the library, the largest first, each in the order it came out`() {
        val all = SeriesTitles.all(library)
        assertEquals(listOf("星球大战", "机动战士高达", "黑客帝国", "银翼杀手"), all.map { it.name })
        assertEquals(5, all.first().cards.size)
        assertEquals("星球大战4：新希望", all.first().cards.first().title)
        val chapters = listOf(card("the Garden of sinners Chapter 1: Thanatos", 2007), card("the Garden of sinners Chapter 2: Murder Speculation", 2007))
        assertEquals("the Garden of sinners", SeriesTitles.all(chapters).single().name)
        // Within its series, each by what is its own.
        val starWars = all.first()
        assertEquals(listOf("4：新希望", "1：幽灵的威胁", "外传1：侠盗一号", "外传2：游侠索罗", "幻境"), starWars.cards.map { starWars.shortTitle(it) })
        val matrix = all.first { it.name == "黑客帝国" }
        assertEquals(listOf("黑客帝国", "2：重装上阵", "3：矩阵革命"), matrix.cards.map { matrix.shortTitle(it) })
        assertEquals("Chapter 1: Thanatos", SeriesTitles.all(chapters).single().let { it.shortTitle(it.cards.first()) })
    }

    @Test fun `a shared word, a file nobody identified, a film alone are no series`() {
        assertEquals(emptyList<String>(), titles("星际穿越"))
        assertEquals(emptyList<String>(), titles("DJI_20250508130621_0001_D"))
        assertEquals(emptyList<String>(), titles("七武士"))
    }
}
