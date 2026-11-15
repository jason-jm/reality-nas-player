package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A film kept twice — 七武士 in 720p and in 4K — is one film: seen in 4K, its 重看 had offered the
 * 720p as the next thing to watch, its card read half seen and "2 个文件". Its halves on two files
 * stay two parts.
 */
class FilmVersionsTest {

    private fun film(id: String, name: String, watched: Boolean = false, positionMs: Long = 0, at: Long = 0, tag: ContentKind? = null) =
        MediaItem(
            id, "s", "/v/Film/$name", name, title = "七武士", kind = MediaKind.MOVIE, contentTag = tag,
            progress = PlaybackProgress(positionMs = positionMs, durationMs = 10_000, watched = watched, updatedAt = at),
        )

    private fun ordered(vararg items: MediaItem) =
        items.sortedWith(compareBy({ EpisodeNaming.sortKey(it) }, { it.fileName.lowercase() }))

    private val sd = "Seven Samurai 1954 Restored 720p BRRip x264 AAC-BeLLBoY (Kingdom-Release).mp4"
    private val uhd = "Seven.Samurai.1954.2160p.UHD.Blu-ray.Remux.DV.HDR.HEVC.FLAC.1.0-CiNEPHiLES.mkv"

    private fun card(items: List<MediaItem>) = MediaGroup(
        key = "id:346|movie", title = "七武士", year = 1954, posterPath = null, backdropPath = null, dominantColor = null,
        runtime = 207, certification = null, ratings = emptyList(), isSeries = false, seasonal = false,
        category = LibCategory.MOVIE, count = items.size, items = items,
    )

    @Test fun `a film kept twice is one film to play`() {
        val fresh = ordered(film("sd", sd), film("uhd", uhd))
        assertEquals(listOf("sd"), DetailPlay.mains(fresh).map { it.id })
        assertEquals(DetailPlay.Choice(fresh.first { it.id == "sd" }, DetailPlay.Kind.START), DetailPlay.choose(fresh))
        // Seen in 4K: 重看 replays the 4K; the 720p is not "next".
        val seen4k = ordered(film("sd", sd), film("uhd", uhd, watched = true, positionMs = 10_000, at = 5))
        assertEquals(DetailPlay.Choice(seen4k.first { it.id == "uhd" }, DetailPlay.Kind.RESTART), DetailPlay.choose(seen4k))
        // Seen in 720p: the same, the other way round.
        val seenSd = ordered(film("sd", sd, watched = true, positionMs = 10_000, at = 5), film("uhd", uhd))
        assertEquals(DetailPlay.Choice(seenSd.first { it.id == "sd" }, DetailPlay.Kind.RESTART), DetailPlay.choose(seenSd))
        // Part-way through the 4K: that is where 继续 goes.
        val midway = ordered(film("sd", sd), film("uhd", uhd, positionMs = 4_000, at = 5))
        assertEquals("uhd", DetailPlay.choose(midway)!!.item.id)
        assertEquals(DetailPlay.Kind.RESUME, DetailPlay.choose(midway)!!.kind)
    }

    @Test fun `the card counts one film, and seeing either finishes it`() {
        val seen4k = card(ordered(film("sd", sd), film("uhd", uhd, watched = true, positionMs = 10_000, at = 5)))
        assertEquals(1, seen4k.shownCount)
        assertFalse(seen4k.isCollection)
        assertEquals(1f, seen4k.watchedFraction, 0f)
        val half = card(ordered(film("sd", sd), film("uhd", uhd, positionMs = 5_000, at = 5)))
        assertEquals(0.5f, half.watchedFraction, 0.001f)
    }

    @Test fun `either version plays on its own, ahead of no extra`() {
        val trailer = film("tr", "Seven Samurai - Trailer.mkv", tag = ContentKind.EXTRA)
        val group = card(ordered(film("sd", sd), film("uhd", uhd), trailer))
        assertEquals(listOf("uhd"), PlayQueue.of(group, group.items.first { it.id == "uhd" }).map { it.id })
        assertEquals(listOf("sd"), PlayQueue.of(group, group.items.first { it.id == "sd" }).map { it.id })
        assertEquals(listOf("tr"), PlayQueue.of(group, trailer).map { it.id })
    }

    @Test fun `the versions of a film, and what playing a chosen one does`() {
        val trailer = film("tr", "Seven Samurai - Trailer.mkv", tag = ContentKind.EXTRA)
        val list = ordered(film("sd", sd), film("uhd", uhd, positionMs = 4_000, at = 5), trailer)
        val uhdItem = list.first { it.id == "uhd" }
        assertEquals(setOf("sd", "uhd"), DetailPlay.versionsOf(list, uhdItem).map { it.id }.toSet())
        assertEquals(setOf("sd", "uhd"), DetailPlay.versionsOf(list, list.first { it.id == "sd" }).map { it.id }.toSet())
        assertEquals(listOf("tr"), DetailPlay.versionsOf(list, trailer).map { it.id })
        assertEquals(DetailPlay.Kind.RESUME, DetailPlay.choiceFor(uhdItem).kind)
        assertEquals(DetailPlay.Kind.START, DetailPlay.choiceFor(list.first { it.id == "sd" }).kind)
    }

    @Test fun `a film's halves are still two parts to play in turn`() {
        val one = "Vanity Fair (2004) DVDRip XviD-KJS (Eng - Subs PT-BR)1.avi"
        val two = "Vanity Fair (2004) DVDRip XviD-KJS (Eng - Subs PT-BR)2.avi"
        val halves = ordered(film("1", one, watched = true, positionMs = 10_000, at = 5), film("2", two))
        assertEquals(listOf("1", "2"), DetailPlay.mains(halves).map { it.id })
        assertEquals(DetailPlay.Choice(halves.first { it.id == "2" }, DetailPlay.Kind.NEXT), DetailPlay.choose(halves))
        val cds = ordered(film("a", "Inglourious.Basterds.DVDRip.XviD-iMBT-cd1.avi"), film("b", "Inglourious.Basterds.DVDRip.XviD-iMBT-cd2.avi"))
        assertEquals(2, DetailPlay.mains(cds).size)
    }
}
