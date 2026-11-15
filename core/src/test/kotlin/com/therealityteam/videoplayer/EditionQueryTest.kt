package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.AnimeQueries
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An edition is not part of a work's name, and a database has never heard of it.
 *
 * `Mobile Suit Gundam SEED Destiny HD Remaster` returns nothing at all from AniList; drop the two
 * words and the series is the first hit. On a real library that cost 88 files, which took the base
 * series' TMDB entry instead and left two cards both called 机动战士高达SEED.
 */
class EditionQueryTest {

    @Test fun `an edition is lifted out of a name`() {
        assertEquals("Mobile Suit Gundam SEED Destiny", FilenameParser.withoutEdition("Mobile Suit Gundam SEED Destiny HD Remaster"))
        assertEquals("白箱", FilenameParser.withoutEdition("白箱 BD-BOX"))
        assertEquals("Akira", FilenameParser.withoutEdition("Akira Digital Remaster"))
        assertEquals("Gunbuster", FilenameParser.withoutEdition("Gunbuster Remastered"))
    }

    @Test fun `a name with no edition in it is left alone`() {
        listOf("Mobile Suit Gundam SEED Destiny", "攻壳机动队", "Cowboy Bebop", "R.O.D -THE TV-")
            .forEach { assertNull(it, FilenameParser.withoutEdition(it)) }
    }

    @Test fun `a name that is nothing BUT an edition is not worth asking about`() {
        assertNull(FilenameParser.withoutEdition("HD Remaster"))
        assertNull(FilenameParser.withoutEdition("Remastered"))
    }

    @Test fun `the stripped form is asked for, and after the one that was written`() {
        val p = "smb://n/video/Anime/Mobile Suit Gundam SEED Destiny HD Remaster 1080P BDRIP/" +
            "[VCB-Studio] Mobile Suit Gundam SEED Destiny HD Remaster - 03 - [AVC_10bit_1080P FLAC] - kewenyu.mkv"
        val parsed = FilenameParser.parseFromPath(p)
        // The episode number lives AFTER the edition in this name, which is why the edition is not
        // in the noise vocabulary: cutting the title there would take the number with it.
        assertEquals(3, parsed.episode)

        val qs = AnimeQueries.build(p, parsed, AnimeParse.parse(p.substringAfterLast('/')))
        assertTrue("stripped form missing: $qs", "Mobile Suit Gundam SEED Destiny" in qs)
        assertTrue(
            "the written form must still be asked first: $qs",
            qs.indexOf("Mobile Suit Gundam SEED Destiny HD Remaster") <
                qs.indexOf("Mobile Suit Gundam SEED Destiny"),
        )
    }
}
