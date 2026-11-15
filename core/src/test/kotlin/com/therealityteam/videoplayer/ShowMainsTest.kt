package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A show is its page's episode list: what 播放 starts, what the badge counts, what 已看完 waits for.
 * Built from the real cases — 进击的巨人's music videos named for their songs, 86's recaps.
 */
class ShowMainsTest {

    private fun file(id: String, name: String, tag: ContentKind = ContentKind.MAIN, episode: Int? = null) =
        MediaItem(id, "s", "/v/Show/$name", name, title = "Show", kind = MediaKind.EPISODE, contentTag = tag, episode = episode)

    private fun ordered(vararg items: MediaItem) =
        items.sortedWith(compareBy({ EpisodeNaming.sortKey(it) }, { it.fileName.lowercase() }))

    private val aot = ordered(
        file("e1", "[ReinForce] Shingeki no Kyojin - 01 (BDRip 1920x1080 x264 FLAC).mkv", episode = 1),
        file("e2", "[ReinForce] Shingeki no Kyojin - 02 (BDRip 1920x1080 x264 FLAC).mkv", episode = 2),
        file("e3", "[ReinForce] Shingeki no Kyojin - 03 (BDRip 1920x1080 x264 FLAC).mkv", episode = 3),
        // Tagged MAIN by the scan, named for a song: sorted ahead of episode 1 as a "feature".
        file("mv", "[ReinForce] Shingeki no Kyojin - Guren no Yumiya (BDRip 1920x1080 x264 FLAC).mkv"),
        file("op", "[ReinForce] Shingeki no Kyojin - OP1 (BDRip 1920x1080 x264 FLAC).mkv", tag = ContentKind.EXTRA, episode = 1),
    )

    @Test fun `a show's 播放 starts at its first episode, not at a song filed beside it`() {
        assertEquals("mv", aot.first().id) // why the old reading went wrong: it sorts first
        assertEquals("e1", DetailPlay.choose(aot, seasonal = true)!!.item.id)
        assertEquals(listOf("e1", "e2", "e3"), DetailPlay.mains(aot, seasonal = true).map { it.id })
    }

    @Test fun `a film's card keeps its feature`() {
        val film = ordered(
            file("f", "Film (2019).mkv").copy(kind = MediaKind.MOVIE),
            file("m1", "Film - Making of Part 1.mkv", tag = ContentKind.EXTRA),
        )
        assertEquals("f", DetailPlay.choose(film)!!.item.id)
    }

    @Test fun `recaps the scan filed as extras stay listed but are not episodes to count`() {
        val eightySix = ordered(
            file("11", "[VCB-Studio] EIGHTY SIX [11][Ma10p_1080p].mkv", episode = 11),
            file("11.5", "[VCB-Studio] EIGHTY SIX [11.5][Ma10p_1080p].mkv", tag = ContentKind.EXTRA, episode = 11),
            file("12", "[VCB-Studio] EIGHTY SIX [12][Ma10p_1080p].mkv", episode = 12),
        )
        assertEquals(listOf("11", "11.5", "12"), DetailPlay.listed(eightySix).map { it.id })
        assertEquals(listOf("11", "12"), DetailPlay.mains(eightySix, seasonal = true).map { it.id })
    }

    private fun card(items: List<MediaItem>, count: Int, isSeries: Boolean = true) = MediaGroup(
        key = "anilist:16498", title = "Show", year = 2013, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = null, certification = null, ratings = emptyList(), isSeries = isSeries, seasonal = true,
        category = LibCategory.ANIME, count = count, items = items,
    )

    @Test fun `the badge counts what the page lists`() {
        // The cheap count took the song for an episode: 4. The page lists three.
        assertEquals(3, card(aot, count = 4).shownCount)
        // A folder of files says how many files it holds.
        assertEquals(4, card(aot, count = 4, isSeries = false).shownCount)
        // Every file plainly numbered, once each: nothing to re-read.
        val plain = ordered(file("a", "Show [01].mkv", episode = 1), file("b", "Show [02].mkv", episode = 2))
        assertEquals(2, card(plain, count = 2).shownCount)
        // No episode list on the page (two OVAs): the count stands.
        val ovas = ordered(file("o1", "Show OVA 1.mkv"), file("o2", "Show OVA 2.mkv"))
        assertEquals(2, card(ovas, count = 2).shownCount)
    }

    @Test fun `last episodes and re-releases are episodes by their numbers`() {
        mapOf(
            "[X2][D.Gray-man][103Fin][BIG5][RV10].rmvb" to 103.0,
            "[Grp][Show][12END][1080p].mkv" to 12.0,
            "[Grp][Show][24 END][1080p].mkv" to 24.0,
            "[Grp][Show][26完][GB].mp4" to 26.0,
            "Show - 12 END (BD 1080p).mkv" to 12.0,
            "Show - 13 Fin.mkv" to 13.0,
            "Show 26完.mp4" to 26.0,
            "[Grp][Show][12v2][1080p].mkv" to 12.0,
        ).forEach { (name, ep) ->
            val info = EpisodeNaming.parse(file("x", name))
            assertEquals(name, EpisodeNaming.Cat.EPISODE, info.cat)
            assertEquals(name, ep, info.episode!!, 0.0)
        }
        listOf("[Grp][Show][03 完全版].mkv", "Final Fantasy VII Advent Children.mkv", "[Grp][Show][Final][1080p].mkv").forEach {
            assertEquals(it, EpisodeNaming.Cat.FEATURE, EpisodeNaming.parse(file("x", it)).cat)
        }
    }
}
