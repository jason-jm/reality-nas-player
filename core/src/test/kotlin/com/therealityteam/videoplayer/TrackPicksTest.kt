package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.MediaTrack
import com.therealityteam.videoplayer.data.playback.TrackPicks
import com.therealityteam.videoplayer.data.repository.TrackPickStore
import com.therealityteam.videoplayer.data.repository.TrackPickStore.ShowTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 整季沿用音轨与字幕: the pick from one episode found again in the next, whose ids and names differ. */
class TrackPicksTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sidecar(id: String, name: String, language: String? = null) =
        MediaTrack(id, name, language = language, name = name, external = true)

    @Test fun `a subtitle file is found again by the part of its name every episode shares`() {
        val ep1 = "[Grp] Show - 01 [1080p].mkv"
        val picked = TrackPicks.of(sidecar("sidecar:1", "[Grp] Show - 01 [1080p].chs.ass"), ep1)
        assertEquals(".chs.ass", picked.tail)
        val ep2 = "[Grp] Show - 02 [1080p].mkv"
        val next = listOf(
            sidecar("sidecar:0", "[Grp] Show - 02 [1080p].cht.ass"),
            sidecar("sidecar:1", "[Grp] Show - 02 [1080p].chs.ass"),
        )
        assertEquals("sidecar:1", TrackPicks.find(picked, next, ep2))
        // Named some other way than the video, the last two dot-parts are what episodes share.
        assertEquals(".sc.ass", TrackPicks.tailOf("Show EP03.sc.ass", "[Grp] Show - 03.mkv"))
        assertEquals(".srt", TrackPicks.tailOf("subs.srt", "movie.mkv"))
    }

    @Test fun `between two Chinese streams the one with the picked title wins, whatever its place`() {
        val ep1 = listOf(
            MediaTrack("2:0", "繁體中文", language = "chi", name = "繁體中文"),
            MediaTrack("2:1", "简体中文", language = "chi", name = "简体中文"),
        )
        val picked = TrackPicks.of(ep1[1], "S01E01.mkv")
        val ep2 = listOf(
            MediaTrack("3:0", "简体中文", language = "chi", name = "简体中文"),
            MediaTrack("3:1", "繁體中文", language = "chi", name = "繁體中文"),
        )
        assertEquals("3:0", TrackPicks.find(picked, ep2, "S01E02.mkv"))
    }

    @Test fun `sound follows language and layout when the titles change`() {
        val picked = TrackPicks.Pick(language = "jpn", name = "Japanese FLAC", channels = 2)
        val next = listOf(
            MediaTrack("1:0", "国语", language = "chi", name = "国语", channels = 6),
            MediaTrack("1:1", "日语 5.1", language = "jpn", name = "日语 5.1", channels = 6),
            MediaTrack("1:2", "日语 2.0", language = "jpn", name = "日语 2.0", channels = 2),
        )
        assertEquals("1:2", TrackPicks.find(picked, next, "e.mkv"))
    }

    @Test fun `nothing like the pick leaves the engine's own choice`() {
        val picked = TrackPicks.Pick(language = "jpn", name = "日本語")
        assertNull(TrackPicks.find(picked, listOf(MediaTrack("1:0", "English", language = "eng", name = "English")), "e.mkv"))
        assertNull(TrackPicks.find(picked, emptyList(), "e.mkv"))
        // A nameless, languageless file pick with no file of that kind next time: nothing to go on.
        val bare = TrackPicks.Pick(external = true, tail = ".ass")
        assertNull(TrackPicks.find(bare, listOf(MediaTrack("2:0", "中文", language = "chi", name = "中文")), "e.mkv"))
    }

    @Test fun `a show's picks are kept, turned off and read back`() {
        val store = TrackPickStore(tmp.newFolder())
        val audio = TrackPicks.Pick(language = "jpn", name = "日语", channels = 2)
        val subs = TrackPicks.Pick(name = "a.chs.ass", external = true, tail = ".chs.ass")
        store.setAudio("dir:/anime/Show", audio)
        store.setSubtitle("dir:/anime/Show", subs)
        assertEquals(ShowTracks(audio = audio, subtitle = subs), store.of("dir:/anime/Show"))
        store.setSubtitle("dir:/anime/Show", null)
        assertEquals(ShowTracks(audio = audio, subtitlesOff = true), store.of("dir:/anime/Show"))
        assertTrue(store.of("elsewhere").isEmpty)
        val all = mapOf("a" to ShowTracks(audio = audio), "b" to ShowTracks(subtitle = subs), "c" to ShowTracks(subtitlesOff = true))
        assertEquals(all, TrackPickStore.decode(TrackPickStore.encode(all)))
    }
}
