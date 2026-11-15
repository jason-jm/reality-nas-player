package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.Chapter
import com.therealityteam.videoplayer.data.playback.ChapterStillPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterStillPlanTest {

    @Test
    fun ordersOutwardFromThePlayingChapter() {
        assertEquals(listOf(3, 4, 2, 5, 1, 0), ChapterStillPlan.order(6, 3))
        assertEquals(listOf(0, 1, 2, 3), ChapterStillPlan.order(4, 0))
        assertEquals(listOf(3, 2, 1, 0), ChapterStillPlan.order(4, 3))
        // A position before the first mark (or past the list) starts at the nearest end.
        assertEquals(listOf(0, 1, 2), ChapterStillPlan.order(3, -1))
        assertEquals(listOf(2, 1, 0), ChapterStillPlan.order(3, 9))
        assertEquals(emptyList<Int>(), ChapterStillPlan.order(0, 0))
    }

    @Test
    fun looksAFewSecondsInButInsideAShortChapter() {
        val chapters = listOf(Chapter(0, ""), Chapter(600_000, ""), Chapter(606_000, ""))
        assertEquals(4_000L, ChapterStillPlan.firstTryMs(chapters, 0, 3_600_000))
        // Six seconds long: a third of it.
        assertEquals(602_000L, ChapterStillPlan.firstTryMs(chapters, 1, 3_600_000))
        // The last runs to the end of the film.
        assertEquals(610_000L, ChapterStillPlan.firstTryMs(chapters, 2, 3_600_000))
        assertEquals(621_000L, ChapterStillPlan.secondTryMs(chapters, 2, 3_600_000))
        assertEquals(603_000L, ChapterStillPlan.secondTryMs(chapters, 1, 3_600_000))
    }

    @Test
    fun lastChapterOfAFilmOfUnknownLength() {
        val chapters = listOf(Chapter(0, ""), Chapter(90_000, ""))
        assertEquals(94_000L, ChapterStillPlan.firstTryMs(chapters, 1, 0))
        assertEquals(105_000L, ChapterStillPlan.secondTryMs(chapters, 1, 0))
    }
}
