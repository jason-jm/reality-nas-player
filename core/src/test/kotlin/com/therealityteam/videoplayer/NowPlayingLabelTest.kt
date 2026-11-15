package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.NowPlayingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingLabelTest {

    @Test
    fun anEpisodeShowsShowAndCodeNotTheYear() {
        assertEquals("机动战士高达 SEED · S01E02", NowPlayingLabel.subtitle("机动战士高达 SEED", 1, 2, 2024))
    }

    @Test
    fun aFilmShowsItsYear() {
        assertEquals("2024", NowPlayingLabel.subtitle(null, null, null, 2024))
        assertEquals("2020", NowPlayingLabel.subtitle("", null, null, 2020))
    }

    @Test
    fun partialEpisodeInfoStillReads() {
        assertEquals("Show", NowPlayingLabel.subtitle("Show", null, null, null))
        assertEquals("S03E10", NowPlayingLabel.subtitle(null, 3, 10, null))
        assertEquals("Show", NowPlayingLabel.subtitle("Show", 3, null, null))
    }

    @Test
    fun nothingKnownIsNullNotEmpty() {
        assertNull(NowPlayingLabel.subtitle(null, null, null, null))
        assertNull(NowPlayingLabel.subtitle("  ", null, null, null))
    }
}
