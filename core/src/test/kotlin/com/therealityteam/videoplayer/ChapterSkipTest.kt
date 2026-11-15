package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.Chapter
import com.therealityteam.videoplayer.data.playback.ChapterSkip
import com.therealityteam.videoplayer.data.playback.ChapterSkip.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 跳过片头 / 跳过片尾 from chapter names: what a name says, and when a chapter is offered as a skip. */
class ChapterSkipTest {

    @Test fun `openings are named so by release groups, discs and in three scripts`() {
        listOf("OP", "Opening", "OP1", "OP 2", "op - Blue Bird", "OP「unravel」", "Opening Credits", "Opening Song",
            "Intro", "Main Titles", "オープニング", "片头", "片頭曲").forEach {
            assertEquals(it, Kind.INTRO, ChapterSkip.kindOf(it))
        }
    }

    @Test fun `credits too`() {
        listOf("ED", "Ending", "ED2", "ED - Kataomoi", "End Credits", "Closing Credits", "Credits", "Ending Theme",
            "エンディング", "片尾", "片尾曲").forEach {
            assertEquals(it, Kind.OUTRO, ChapterSkip.kindOf(it))
        }
    }

    @Test fun `the story is never a skip, whatever its first word`() {
        listOf("Chapter 01", "Part A", "Avant", "Prologue", "Preview", "次回予告", "Ed and Al", "Opening Scene",
            "Credits Scene", "Opening Act", "Edward", "Operation Overlord", "第二幕 · 追逐", "", "  ").forEach {
            assertNull(it, ChapterSkip.kindOf(it))
        }
    }

    private val episode = listOf(
        Chapter(0, "Avant"),
        Chapter(95_000, "OP"),
        Chapter(185_000, "Part A"),
        Chapter(1_290_000, "ED"),
        Chapter(1_380_000, "Preview"),
    )
    private val length = 1_420_000L

    @Test fun `an opening playing is offered to its end, and only while it plays`() {
        assertEquals(ChapterSkip.Offer(Kind.INTRO, 1, 95_000, 185_000), ChapterSkip.offerAt(episode, 100_000, length))
        assertNull(ChapterSkip.offerAt(episode, 94_999, length))
        assertNull(ChapterSkip.offerAt(episode, 185_000, length))
        // Its last three seconds: nothing left worth a button.
        assertNull(ChapterSkip.offerAt(episode, 182_500, length))
    }

    @Test fun `credits are offered up to the preview after them`() {
        assertEquals(ChapterSkip.Offer(Kind.OUTRO, 3, 1_290_000, 1_380_000), ChapterSkip.offerAt(episode, 1_300_000, length))
    }

    @Test fun `a last chapter runs to the end of the file`() {
        val film = listOf(Chapter(0, "Chapter 1"), Chapter(7_000_000, "End Credits"))
        assertEquals(ChapterSkip.Offer(Kind.OUTRO, 1, 7_000_000, 7_420_000), ChapterSkip.offerAt(film, 7_100_000, 7_420_000))
    }

    @Test fun `a name is not trusted with a scene's length`() {
        // A twelve-minute "Opening" is the film's first act, not a song; four seconds is a bumper.
        assertNull(ChapterSkip.offerAt(listOf(Chapter(0, "Opening"), Chapter(720_000, "Two")), 5_000, 3_000_000))
        assertNull(ChapterSkip.offerAt(listOf(Chapter(0, "OP"), Chapter(4_000, "Part A")), 1_000, 1_400_000))
        // Credits may run long — a film's roll — but not a half hour.
        assertNull(ChapterSkip.offerAt(listOf(Chapter(0, "Story"), Chapter(600_000, "Credits")), 700_000, 2_400_000))
    }

    @Test fun `no chapters, no offer`() {
        assertNull(ChapterSkip.offerAt(emptyList(), 10_000, 100_000))
    }
}
