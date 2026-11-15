package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.DetailSections
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Patlabor's 1988 OVAs as the library holds them: seven files named `[Patlabor_OVA][EARLY_DAYS]…[01]`,
 * the disc's menu named the same way, and the box's bonus clips beside them — all of them filed by
 * the scan as extras of one AniList entry.
 */
class OvaReleaseTest {

    private val dir = "smb://n/video/Anime/Patlabor 機動警察パトレイバー"
    private val release = "$dir/[1988-1989] Patlabor_機動警察(機動警察パトレイバー Early Days)_OVA.U2 (前期OVA)"

    private fun file(id: String, path: String, episode: Int?) = MediaItem(
        id = id, sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        title = "机动警察", originalTitle = "機動警察パトレイバー", year = 1988, collectionKey = "anilist:1288",
        tmdbId = 1288, kind = MediaKind.EPISODE, episode = episode, contentTag = ContentKind.EXTRA,
        scrapeStatus = ScrapeStatus.MATCHED,
    )

    private val ovas = (1..7).map { n -> file("ova$n", "$release/[Patlabor_OVA][EARLY_DAYS][BDRip][1080P][X264_FLAC][0$n].mkv", n) }
    private val menu = file("menu", "$release/[Patlabor_OVA][EARLY_DAYS][BDRip][1080P][MENU].m2ts", null)
    private val bonus = listOf(
        file("b1", "$dir/bonus_映像特典.U2/[Patlabor Music Collection][Concert Tour 92][DVDRIP][X264_AAC].mp4", 92),
        file("b2", "$dir/bonus_映像特典.U2/[Patlabor Music Collection][Music Clips][DVDRIP][X264_AAC].mp4", null),
    )

    @Test fun `an OVA is numbered by its file, and the disc's menu is the release's extra`() {
        val third = EpisodeNaming.parse(ovas[2])
        assertEquals(EpisodeNaming.Cat.OVA, third.cat)
        assertEquals(3, third.extraNum)
        val m = EpisodeNaming.parse(menu)
        assertEquals(EpisodeNaming.Cat.EXTRA, m.cat)
        assertEquals("MENU", m.extraType)
    }

    @Test fun `the page lists the seven OVAs in order, and play starts the first of them`() {
        val card = LibraryGrouping.group(ovas.reversed() + menu + bonus).single()
        val sections = DetailSections.of(card.items)
        assertEquals((1..7).map { "ova$it" }, sections.first { it.kind == DetailSections.Kind.OVA }.items.map { it.id })
        assertEquals("ova1", DetailPlay.choose(card.items, seasonal = card.seasonal)!!.item.id)
        assertEquals(7, card.shownCount)
    }
}
