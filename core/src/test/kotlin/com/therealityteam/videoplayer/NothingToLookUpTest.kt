package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.FilenameParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files no database could possibly have an entry for.
 *
 * Getting this wrong is expensive both ways. Too narrow and a pass spends a sixth of itself asking
 * questions with no answer and believing whatever comes back — `C1284.MP4` off a camera returned a
 * 1947 work called "C1284", a folder of guitar covers returned a 2021 film called 鹿角. Too wide and
 * a real film is never identified at all, which is worse, because nothing ever retries it.
 */
class NothingToLookUpTest {

    private fun ask(path: String, title: String = path.substringAfterLast('/').substringBeforeLast('.')) =
        FilenameParser.nothingToLookUp(path, path.substringAfterLast('/'), title)

    @Test fun `somebody's own recording has nothing to look up`() {
        listOf(
            "smb://n/video/My/20250917新的旅程/C1284.MP4",
            "smb://n/video/我的/家庭录像/2025.mp4",
            "smb://n/video/DCIM/100MEDIA/DSC_0031.MOV",
            "smb://n/video/Home Videos/birthday.mp4",
            // …and a camera's name gives it away wherever it has been moved to.
            "smb://n/video/Movie/IMG_4410.mp4",
            "smb://n/video/Movie/VID_20250903_101500.mp4",
        ).forEach { assertTrue(it, ask(it)) }
    }

    @Test fun `game footage has nothing to look up`() {
        listOf(
            "smb://n/video/Game/Diablo 3 Collector Movies/cinematic.mkv",
            "smb://n/video/游戏/塞尔达/01.mp4",
            "smb://n/video/ゲーム/FF7/opening.mp4",
            "smb://n/video/Movie/Dark Souls gameplay part 3.mkv",
            "smb://n/video/Movie/2012 OSL Season 2 Final.mkv",
            "smb://n/video/Movie/塞尔达 实况 01.mkv",
        ).forEach { assertTrue(it, ask(it)) }
    }

    @Test fun `an ordinary film is still looked up`() {
        listOf(
            "smb://n/video/Movie/Alien.1979.1080p.BluRay.mkv",
            "smb://n/video/Movie/2001太空漫游/2001.mkv",
            "smb://n/video/Anime/进击的巨人/S01E01.mkv",
            "smb://n/video/Documentary/NHK/映像の世紀.mkv",
            // A concert is published, and often IS in a database.
            "smb://n/video/Music/Clark Terry Live in Concert.mkv",
        ).forEach { assertFalse(it, ask(it)) }
    }

    @Test fun `the industry's own events are game footage`() {
        listOf(
            "smb://n/video/Movie/BlizzCon 2011 - Starcraft 2 Panel.mp4",
            "smb://n/video/Movie/Nintendo Direct 2024.09.mp4",
            "smb://n/video/Movie/gamescom 2023 opening night.mkv",
        ).forEach { assertTrue(it, ask(it)) }
    }

    @Test fun `State of Play is a film before it is a showcase`() {
        // Sony's showcase shares its name with a 2003 BBC serial and a 2009 film. A recording left
        // alone merely keeps a plain name; a film left alone is never identified and never retried.
        assertFalse(ask("smb://n/video/Documentary/State of Play 1080p.mp4", "State of Play"))
    }

    @Test fun `a title that merely mentions a game is not game footage`() {
        // The Game, Gamer, Game Night, 游戏规则 — films with the word in the name, not recordings of
        // somebody playing. The markers are the verbs (gameplay, 实况, speedrun), not the noun.
        listOf(
            "smb://n/video/Movie/The Game 1997.mkv" to "The Game",
            "smb://n/video/Movie/Game Night 2018.mkv" to "Game Night",
            "smb://n/video/Movie/欺凌游戏.mkv" to "欺凌游戏",
            "smb://n/video/Movie/Ender's Game.mkv" to "Ender's Game",
        ).forEach { (p, t) -> assertFalse(t, ask(p, t)) }
    }

    @Test fun `a name that merely starts with a letter and digits is not a camera file`() {
        listOf(
            "smb://n/video/Anime/86/E01.mkv" to "E01",
            "smb://n/video/Movie/K-19 The Widowmaker.mkv" to "K-19 The Widowmaker",
            "smb://n/video/Movie/2012.mkv" to "2012",
            "smb://n/video/Movie/1917.mkv" to "1917",
        ).forEach { (p, t) -> assertFalse(t, ask(p, t)) }
    }
}
