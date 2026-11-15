package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.MetaResult
import com.therealityteam.videoplayer.data.scrape.ParsedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A search hit confirmed by one of its other names. The names are TMDB's, as it listed them. */
class MatcherAlternativeTest {

    private val andor = MetaResult(providerId = 83867, kind = MediaKind.SHOW, title = "Andor", year = 2022, popularity = 42.7)

    @Test fun `a season named with its franchise finds the show TMDB files without it`() {
        val q = ParsedName("Star Wars Andor", season = 2, episode = 1)
        // By its own name it is not the answer…
        assertNull(Matcher.best(q, listOf(andor)))
        // …by one of its others it is, exactly.
        val alts = mapOf(83867 to listOf("Andor: Uma História Star Wars", "Star Wars - Andor", "Star Wars: Andor", "安多"))
        assertEquals(andor, Matcher.bestByAlternative(q, listOf(andor), alts))
    }

    @Test fun `a documentary's episode name TMDB keeps under the series`() {
        val series = MetaResult(providerId = 223223, kind = MediaKind.SHOW, title = "JAPANデビュー", year = 2009)
        val q = ParsedName("天皇与宪法：日本宪政的120年")
        assertEquals(series, Matcher.bestByAlternative(q, listOf(series), mapOf(223223 to listOf("天皇与宪法：日本宪政的120年"))))
    }

    @Test fun `a release named in another region's words`() {
        // Taiwan's name for the film, as the rip spells it; TMDB files it under the mainland name.
        val arrietty = MetaResult(providerId = 51739, kind = MediaKind.MOVIE, title = "借东西的小人阿莉埃蒂",
            originalTitle = "借りぐらしのアリエッティ", year = 2010, popularity = 11.7)
        val q = ParsedName("借物少女艾莉媞")
        assertNull(Matcher.best(q, listOf(arrietty)))
        val alts = mapOf(51739 to listOf("The Borrower Arrietty", "借东西的小人阿莉埃蒂", "借物少女艾莉媞", "借物少女", "The Secret World of Arrietty"))
        assertEquals(arrietty, Matcher.bestByAlternative(q, listOf(arrietty), alts))
    }

    @Test fun `a resemblance among many other names is not a match`() {
        // A recording called 最后-Final4 once reached this comedy through 「Friday 4」, across scripts.
        val friday = MetaResult(providerId = 487560, kind = MediaKind.MOVIE, title = "最后的星期五", originalTitle = "Last Friday")
        val alts = mapOf(487560 to listOf("Friday 4", "The Last Friday of The Month", "The Final Friday", "Friday After That"))
        assertNull(Matcher.bestByAlternative(ParsedName("最后-Final4"), listOf(friday), alts))
    }

    @Test fun `a year the file gives must agree`() {
        val alts = mapOf(83867 to listOf("Star Wars: Andor"))
        assertNull(Matcher.bestByAlternative(ParsedName("Star Wars Andor", year = 2016), listOf(andor), alts))
        assertEquals(andor, Matcher.bestByAlternative(ParsedName("Star Wars Andor", year = 2023), listOf(andor), alts))
    }

    @Test fun `a name too short to mean anything is not asked`() {
        val hit = MetaResult(providerId = 1, kind = MediaKind.MOVIE, title = "Something")
        assertNull(Matcher.bestByAlternative(ParsedName("AB"), listOf(hit), mapOf(1 to listOf("AB"))))
    }

    @Test fun `the search's own order decides between two that both answer`() {
        val first = MetaResult(providerId = 1, kind = MediaKind.MOVIE, title = "One", year = 2004)
        val second = MetaResult(providerId = 2, kind = MediaKind.MOVIE, title = "Two", year = 2004)
        val alts = mapOf(1 to listOf("Fahrenheit 911"), 2 to listOf("Fahrenheit 911"))
        assertEquals(first, Matcher.bestByAlternative(ParsedName("Fahrenheit 911"), listOf(first, second), alts))
    }
}
