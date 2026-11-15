package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ParsedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sequel's number the file never asked for decides between acceptable answers, and only there. */
class MatcherUnaskedNumberTest {

    // TMDB's answer for the query, in its order, with the popularity it reported (2026-09-23).
    private val patlabor2 = MetaResult(providerId = 14572, kind = MediaKind.MOVIE, title = "Patlabor 2: The Movie",
        originalTitle = "機動警察パトレイバー 2 the Movie", year = 1993, popularity = 4.3)
    private val patlabor1 = MetaResult(providerId = 14571, kind = MediaKind.MOVIE, title = "Patlabor: The Movie",
        originalTitle = "機動警察パトレイバー 劇場版", year = 1989, popularity = 2.9)

    @Test fun `the film the name asks for beats its numbered sequel`() {
        val q = ParsedName("Kidou Keisatsu Patlabor The Movie")
        // Rank and popularity put the sequel first…
        assertEquals(patlabor2, Matcher.ranked(q, listOf(patlabor2, patlabor1)).first().result)
        // …and the number it carries that the query does not sends the choice to the first film.
        assertEquals(patlabor1, Matcher.best(q, listOf(patlabor2, patlabor1)))
    }

    @Test fun `a number the query has is asked for`() {
        assertFalse(Matcher.unaskedNumber("Kidou Keisatsu Patlabor 2 The Movie", patlabor2))
        assertFalse(Matcher.unaskedNumber("Patlabor II", patlabor2))
        assertTrue(Matcher.unaskedNumber("Kidou Keisatsu Patlabor The Movie", patlabor2))
        assertFalse(Matcher.unaskedNumber("Kidou Keisatsu Patlabor The Movie", patlabor1))
    }

    @Test fun `a lone numbered answer stands`() {
        // The folder says 第二次 (the second); the query cannot, and the concert is still the one.
        val concert = MetaResult(providerId = 891675, kind = MediaKind.MOVIE,
            title = "2008 Sound Horizon Triumph of Territorial Expansion II", year = 2008)
        val q = ParsedName("Sound Horizon Triumph")
        assertEquals(concert, Matcher.best(q, listOf(concert)))
    }

    @Test fun `a year inside a name is not a sequel number`() {
        val film = MetaResult(providerId = 1, kind = MediaKind.MOVIE, title = "Blade Runner 2049", year = 2017)
        assertFalse(Matcher.unaskedNumber("Blade Runner", film))
    }
}
