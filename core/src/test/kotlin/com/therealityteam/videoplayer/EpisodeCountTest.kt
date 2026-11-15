package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Assert.assertEquals
import org.junit.Test

/** A card's 集 counts episodes, not files: a show held as a BDRip and a TVRip is still its own length. */
class EpisodeCountTest {

    private fun ep(id: String, dir: String, name: String, season: Int?, episode: Int) = MediaItem(
        id, "s", "/video/Anime/$dir/$name", name, title = "86", kind = MediaKind.EPISODE, tmdbId = 100,
        season = season, episode = episode, contentTag = ContentKind.MAIN, scrapeStatus = ScrapeStatus.MATCHED,
    )

    @Test fun `two releases of the same episodes count once`() {
        val bd = (1..3).map { ep("bd$it", "86.BDRip", "[VCB-Studio] EIGHTY SIX [%02d][Ma10p_1080p].mkv".format(it), null, it) }
        val tv = (1..3).map { ep("tv$it", "86.TVRip", "86 不存在的战区.S01E%02d.TVRip.1080p.mkv".format(it), 1, it) }
        val card = LibraryGrouping.group(bd + tv).single()
        assertEquals(3, card.count)
    }

    @Test fun `a plain run counts every episode`() {
        val run = (1..4).map { ep("e$it", "Show", "Show.S01E%02d.mkv".format(it), 1, it) }
        assertEquals(4, LibraryGrouping.group(run).single().count)
    }
}
