package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last resort for a romanisation the database does not use.
 *
 * Romanisers disagree about the formal words at the front of a Japanese title and leave the name
 * itself alone: a fansub writes `Mahoushoujo Ririkaru Nanoha A's` where AniList writes
 * `Mahou Shoujo Lyrical Nanoha A's`. AniList answers the first with nothing at all and the tail
 * `Nanoha A's` with that exact entry, first. 27 files took season one's TMDB entry over it.
 */
class LatinTailQueryTest {

    private fun queries(path: String) = AnimeQueries.build(
        path, FilenameParser.parseFromPath(path), AnimeParse.parse(path.substringAfterLast('/')),
    )

    @Test fun `the tail is asked, and asked last`() {
        val qs = queries("smb://n/video/Anime/魔法少女奈叶 A's 1080P BDRIP/[FLsnow][Mahoushoujo_Ririkaru_Nanoha_A's][01][BDRIP][AVC_FLAC].mkv")
        assertTrue("tail missing: $qs", "Nanoha A's" in qs)
        assertEquals("the tail must be the last resort: $qs", qs.size - 1, qs.indexOf("Nanoha A's"))
        // …and the name as written still goes first.
        assertTrue("$qs", qs.indexOf("Mahoushoujo Ririkaru Nanoha A's") < qs.indexOf("Nanoha A's"))
    }

    @Test fun `a short name keeps every word it has`() {
        // Under four words there is no formal prefix to disagree about, and dropping two would
        // throw away the name itself.
        val qs = queries("smb://n/video/Anime/Cowboy Bebop/[a] Cowboy Bebop - 03 [BD].mkv")
        assertTrue("$qs", qs.none { it == "Cowboy Bebop".split(' ').takeLast(2).joinToString(" ") && it != "Cowboy Bebop" })
        assertTrue("Cowboy Bebop" in qs)
    }

    @Test fun `a CJK name is never cut this way`() {
        // Words are not what a CJK title is made of, and its last two "words" are not its name.
        val qs = queries("smb://n/video/Anime/银河英雄传说 全新命题/[U2] 银河英雄传说 全新命题 邂逅 第01话.mkv")
        assertTrue("$qs", qs.none { it.count { c -> c == ' ' } == 1 && it.length <= 8 && "银河" !in it })
    }
}
