package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spellings a filename loses, and a database keeps.
 *
 * Three separate ways a name can be written so that AniList answers it with nothing at all, each
 * costing a whole card on a real library: a ripper strips the spaces out, an apostrophe cannot
 * survive in a filename, and two fansub groups sign a release with one name joined by `&`.
 */
class RespellQueryTest {

    private fun queries(path: String) = AnimeQueries.build(
        path, FilenameParser.parseFromPath(path), AnimeParse.parse(path.substringAfterLast('/')),
    )

    // ── spaces a ripper stripped out ──────────────────────────────────────────────────────────

    @Test fun `a glued name is asked with its spaces back`() {
        assertEquals("Starship Operators", AnimeQueries.unglued("StarshipOperators"))
        assertEquals("Cowboy Bebop", AnimeQueries.unglued("CowboyBebop"))
    }

    @Test fun `a capital inside a short word is a spelling, not a lost space`() {
        // These are written this way on purpose, and `Jo Jo` / `xxx HOLi C` find nothing.
        listOf("JoJo", "xxxHOLiC", "ReLIFE", "K", "AKIRA").forEach { assertNull(it, AnimeQueries.unglued(it)) }
        // …and a name that still carries a filename's punctuation is a filename.
        assertNull(AnimeQueries.unglued("StarshipOperators-01-CountDown"))
    }

    @Test fun `the glued name reaches the query list`() {
        val qs = queries("smb://n/video/Anime/星船操作员/StarshipOperators-01-CountDown(anime-mx)[31D39C0E].mkv")
        assertTrue("$qs", "Starship Operators" in qs)
    }

    // ── an apostrophe a filename could not keep ───────────────────────────────────────────────

    @Test fun `a possessive is asked with its apostrophe back`() {
        assertEquals("Gundam Char's Counterattack", AnimeQueries.withPossessive("Gundam Chars Counterattack"))
        assertEquals("Kiki's Delivery Service", AnimeQueries.withPossessive("Kikis Delivery Service"))
        assertEquals("Howl's Moving Castle", AnimeQueries.withPossessive("Howls Moving Castle"))
    }

    @Test fun `a trailing s is a plural, and one word owns nothing`() {
        assertNull(AnimeQueries.withPossessive("Starship Operators"))
        assertNull(AnimeQueries.withPossessive("Bakemonogatari"))
        assertNull(AnimeQueries.withPossessive("Cowboy Bebop"))
    }

    // ── two groups signing one release ────────────────────────────────────────────────────────

    @Test fun `a bracket joining two groups is not the title`() {
        // "most words, then longest" is the contest a doubled group name wins: this searched for
        // manmiV9&HinA, and Futakoi2 — which AniList answers with the right entry — was never asked.
        val qs = queries("smb://n/video/Anime/双恋2/[双恋2].[manmiV9&HinA][Futakoi2][jap-chn][01][xvid].avi")
        assertTrue("$qs", "Futakoi2" in qs)
        assertTrue("the group must not be asked first: $qs", qs.indexOf("Futakoi2") < qs.indexOf("manmiV9&HinA"))
    }

    @Test fun `a title outside the brackets still wins over the groups that signed it`() {
        val qs = queries(
            "smb://n/video/Anime/[Nekomoe kissaten&VCB-Studio] Mushoku Tensei ~Isekai Ittara Honki Dasu~ [Ma10p_1080p]/" +
                "[Nekomoe kissaten&VCB-Studio] Mushoku Tensei ~Isekai Ittara Honki Dasu~ [01][Ma10p_1080p][x265_flac].mkv",
        )
        assertTrue("the work, not the groups, is asked: $qs", qs.first().startsWith("Mushoku Tensei"))
        assertTrue("a group pair must never be a query: $qs", qs.none { '&' in it })
    }
}
