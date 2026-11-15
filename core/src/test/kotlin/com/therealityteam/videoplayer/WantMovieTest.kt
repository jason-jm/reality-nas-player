package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.scrape.AnimeParse
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which files the scraper knows to look for a FILM for.
 *
 * [ScrapeEngine] asks for a film when the name says so, and it used to ask [AnimeParse] alone —
 * which reports only the type element Anitomy lifted out of the file's own name. A film whose
 * marker is glued inside a bracket, or left in the folder, yields no type at all. Measured over a
 * real 5,370-file anime shelf, 26 files came back as ordinary episodes: the Macross F films, all
 * three EVA Rebuilds, Gundam 00 the Movie, three G-Reco films, 朝花夕誓. Each is a whole card that
 * was matched with no preference for a film over the series it spun off from — which is how the
 * second Macross F film ended up on the 2008 TV entry.
 *
 * [ContentClassifier] reads the path as well, and already called every one of them a film. These
 * cases are why both are asked now.
 */
class WantMovieTest {

    // Real names off a real shelf, not invented ones: what Anitomy makes of a name depends on the
    // exact separators around the word, so a tidied-up example proves nothing about the shelf.
    private val films = listOf(
        "[POPGO][FREEWIND][Macross_Frontier_The_Movie-The_Wings_of_Goodbye][Full_HD-BDRIP][AVC_DTS].mkv"
            to "smb://n/video/Anime/Macross Frontier 剧场版2 1080P BDRIP/",
        "[DMG] 劇場版 さよならの朝に約束の花をかざろう [BDRip][HEVC_FLAC_PSG][1080P_Ma10P](B05B7617).mkv"
            to "smb://n/video/Anime/朝花夕誓 劇場版 さよならの朝に約束の花をかざろう [1080P BDRip]/",
        "[NEO·QSW]劇場版 GのレコンギスタⅢ 宇宙からの遺産[BDRIP HEVC 1080P].mkv"
            to "smb://n/video/Anime/高达G之复国运动剧场版.BDRip.1080p/[NEO·QSW]劇場版 GのレコンギスタⅢ 宇宙からの遺産[BDRIP HEVC 1080P]/",
    )

    @Test fun `the file's own type element misses a film whose marker is not a type`() {
        films.forEach { (name, _) ->
            assertEquals("$name should have been read as a film by neither reader alone",
                ContentKind.MAIN, AnimeParse.parse(name).contentKind)
        }
    }

    @Test fun `reading the path finds them`() {
        films.forEach { (name, dir) ->
            assertEquals(name, ContentKind.MOVIE, ContentClassifier.classify(name, dir + name))
        }
    }

    @Test fun `an ordinary episode is still not a film`() {
        val name = "[Nekomoe kissaten][Yuru Camp][01][1080P].mkv"
        val path = "smb://n/video/Anime/Yuru Camp/$name"
        assertEquals(ContentKind.MAIN, AnimeParse.parse(name).contentKind)
        assertEquals(ContentKind.MAIN, ContentClassifier.classify(name, path))
    }
}
