package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.ShelfFit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a shelf the person filed accepts from a database, by the genres TMDB gives. */
class ShelfFitTest {

    @Test fun `a music shelf takes music, documentary, or an entry with no genres`() {
        assertTrue(ShelfFit.music(listOf("音乐")))
        assertTrue(ShelfFit.music(listOf("Music", "Documentary")))
        assertTrue(ShelfFit.music(listOf("纪录")))
        // Recorded concerts on TMDB mostly carry none (Sound Horizon's, Animelo Summer Live's).
        assertTrue(ShelfFit.music(emptyList()))
    }

    @Test fun `a music shelf refuses the films its category folders were answered with`() {
        // Music/Audio -> Audio Tour, Music/Lesson -> 课程 (1994), Music/Keyboard -> Keyboard (2022).
        assertFalse(ShelfFit.music(listOf("喜剧")))
        assertFalse(ShelfFit.music(listOf("剧情", "爱情")))
        assertFalse(ShelfFit.music(listOf("剧情", "惊悚")))
    }

    @Test fun `a documentary shelf takes documentary or nothing`() {
        assertTrue(ShelfFit.documentary(listOf("纪录")))
        assertTrue(ShelfFit.documentary(emptyList()))
        assertFalse(ShelfFit.documentary(listOf("剧情")))
    }
}
