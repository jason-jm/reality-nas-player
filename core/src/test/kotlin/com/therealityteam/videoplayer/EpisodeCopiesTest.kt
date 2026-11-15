package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.DetailSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A show held twice — a BDRip and a TVRip of every episode — lists, plays and counts one of each. */
class EpisodeCopiesTest {

    private fun file(id: String, dir: String, name: String, progress: PlaybackProgress = PlaybackProgress()) =
        MediaItem(id, "s", "/video/Anime/$dir/$name", name, title = "86", progress = progress)

    private val tv1 = file("tv1", "86.TVRip.1080p", "86 不存在的战区.S01E01.TVRip.1080p.HEVC-10bit.ACC.Sakurato.mkv")
    private val tv2 = file("tv2", "86.TVRip.1080p", "86 不存在的战区.S01E02.TVRip.1080p.HEVC-10bit.ACC.Sakurato.mkv")
    private val bd1 = file("bd1", "86.BDRip.1080p", "[UHA-WINGS&VCB-Studio] EIGHTY SIX [01][Ma10p_1080p][x265_flac_aac].mkv")
    private val bd2 = file("bd2", "86.BDRip.1080p", "[UHA-WINGS&VCB-Studio] EIGHTY SIX [02][Ma10p_1080p][x265_flac_aac].mkv")

    private fun episodes(all: List<MediaItem>) =
        DetailSections.of(all).first { it.kind == DetailSections.Kind.EPISODES }.items.map { it.id }

    @Test fun `the Blu-ray copy is the episode, whatever order the files came in`() {
        assertEquals(listOf("bd1", "bd2"), episodes(listOf(tv1, tv2, bd1, bd2)))
        assertEquals(listOf("bd1", "bd2"), episodes(listOf(bd2, tv2, bd1, tv1)))
        assertEquals(setOf("tv1", "tv2"), DetailSections.copies(listOf(tv1, bd1, tv2, bd2)))
    }

    @Test fun `the play button offers the listed episode, never a copy filed under extras`() {
        val sortedByName = listOf(tv1, bd1, tv2, bd2)
        assertEquals("bd1", DetailPlay.choose(sortedByName)!!.item.id)
        assertEquals(listOf("bd1", "bd2"), DetailPlay.mains(sortedByName).map { it.id })
        assertTrue(DetailPlay.earlierUnwatched(sortedByName, tv2).isEmpty())
    }

    @Test fun `the copy somebody has been watching stays the episode`() {
        val watching = tv1.copy(progress = PlaybackProgress(positionMs = 60_000, durationMs = 1_400_000))
        assertEquals(listOf("tv1", "bd2"), episodes(listOf(watching, tv2, bd1, bd2)))
    }

    @Test fun `sources rank Blu-ray, web, unknown, then TV`() {
        assertEquals(0, DetailSections.sourceRank("/a/Show.S01E01.1080p.BluRay.x264.mkv"))
        assertEquals(0, DetailSections.sourceRank("/a/Show [BD 1080p]/01.mkv"))
        assertEquals(1, DetailSections.sourceRank("/a/Show.S01E01.1080p.WEB-DL.mkv"))
        assertEquals(2, DetailSections.sourceRank("/a/Show/01.mkv"))
        assertEquals(3, DetailSections.sourceRank("/a/Show.S01E01.HDTV.mkv"))
    }
}
