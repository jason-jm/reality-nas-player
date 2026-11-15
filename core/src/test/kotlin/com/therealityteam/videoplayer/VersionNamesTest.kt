package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.playback.QualityTags.Tag
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.DetailPlay.VersionLabel
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A film's files told apart on its page — the cases a real library got wrong: 七武士's two versions
 * read "Samurai 1954 Restored…", 摇曳露营's four read "1080p]", and Spotlight's 播放 played the
 * RARBG advert that came with it.
 */
class VersionNamesTest {

    private fun film(name: String) = MediaItem(name, "s", "/v/$name", name, title = "Film", kind = MediaKind.MOVIE)

    private fun tags(vararg t: Tag) = VersionLabel.Tags(t.toList())

    @Test fun `versions of one film say what each one is`() {
        assertEquals(
            mapOf(0 to tags(Tag.HD), 1 to tags(Tag.UHD_4K, Tag.DOLBY_VISION, Tag.REMUX)),
            DetailPlay.versionLabels(listOf(
                "Seven Samurai 1954 Restored 720p BRRip x264 AAC-BeLLBoY (Kingdom-Release).mp4",
                "Seven.Samurai.1954.2160p.UHD.Blu-ray.Remux.DV.HDR.HEVC.FLAC.1.0-CiNEPHiLES.mkv",
            )),
        )
        assertEquals(
            mapOf(0 to tags(Tag.FHD), 1 to tags(Tag.UHD_4K, Tag.DOLBY_VISION), 2 to tags(Tag.UHD_4K, Tag.HDR10), 3 to tags(Tag.UHD_4K)),
            DetailPlay.versionLabels(listOf(
                "[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp Movie [Ma10p_1080p][x265_flac].mkv",
                "[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp Movie [Ma10p_2160p_DoVi][x265_flac].mkv",
                "[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp Movie [Ma10p_2160p_HDR10][x265_flac].mkv",
                "[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp Movie [Ma10p_2160p_SDR][x265_flac].mkv",
            )),
        )
    }

    @Test fun `a version its tags cannot tell apart goes by its own words`() {
        // 乱: what the remux is, and the rip's words past the shared title.
        assertEquals(
            mapOf(0 to VersionLabel.Name("BRRip.H264.AAC.Gopo"), 1 to tags(Tag.UHD_4K, Tag.DOLBY_VISION, Tag.DTS_HD_MA, Tag.REMUX)),
            DetailPlay.versionLabels(listOf("Ran.1985.BRRip.H264.AAC.Gopo.mp4", "Ran.1985.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC.REMUX-FraMeSToR.mkv")),
        )
        // Two releases at one resolution: their own words.
        assertEquals(
            mapOf(0 to VersionLabel.Name("x264-A"), 1 to VersionLabel.Name("x265-B")),
            DetailPlay.versionLabels(listOf("Film.2001.1080p.x264-A.mkv", "Film 2001 1080p x265-B.mkv")),
        )
    }

    @Test fun `only versions`() {
        // A film's versions among its extras: the versions alone.
        assertEquals(
            setOf(1, 2),
            DetailPlay.versionLabels(listOf(
                "Images from the Frontlines - The Photography of Letters from Iwo Jima.mkv",
                "Letters from Iwo Jima (2006) (1080p BluRay x265 r00t).mkv",
                "Letters from Iwo Jima 2006 720p  BRRip x264 RmD (HDScene Release).mkv",
                "November 2006 Press Conference.mkv",
            )).keys,
        )
        // A film and its sequel; an album's tracks; the two halves of one film.
        assertEquals(emptyMap<Int, VersionLabel>(), DetailPlay.versionLabels(listOf("Blade.Runner.1982.1080p.mkv", "Blade.Runner.2049.2017.2160p.mkv")))
        assertEquals(emptyMap<Int, VersionLabel>(), DetailPlay.versionLabels(listOf("Artist - Song A 1080p.mkv", "Artist - Song B 720p.mkv")))
        assertEquals(emptyMap<Int, VersionLabel>(), DetailPlay.versionLabels(listOf("Film.2019.Part.1.1080p.mkv", "Film.2019.Part.2.720p.mkv")))
        assertEquals(emptyMap<Int, VersionLabel>(), DetailPlay.versionLabels(listOf("Film 2019 CD1 1080p.mkv", "Film 2019 CD2 720p.mkv")))
    }

    @Test fun `names are compared as words, and cut between whole brackets`() {
        assertEquals(
            listOf("Restored", "Criterion"),
            DetailPlay.distinctNames(listOf("Seven Samurai 1954 Restored.mp4", "Seven.Samurai.1954.Criterion.mkv")),
        )
        assertEquals(
            listOf("John Mearsheimer", "Kenneth Waltz"),
            DetailPlay.distinctNames(listOf("Conversations With History - John Mearsheimer.mp4", "Conversations with History - Kenneth Waltz.mp4")),
        )
        assertEquals(
            listOf("[EX_BOX1]", "[EX_BOX2]"),
            DetailPlay.distinctNames(listOf("[Show][EX_BOX1][BD].mkv", "[Show][EX_BOX2][BD].mkv")),
        )
        // One name's bracket left open in its middle does not hold back the cut of a shared tail.
        assertEquals(
            listOf("（1）「上」", "（2）「下（続"),
            DetailPlay.distinctNames(listOf("Show（1）「上」[字].mp4", "Show（2）「下（続[字].mp4")),
        )
    }

    @Test fun `a site's advert is not a second feature`() {
        val ad = EpisodeNaming.parse(film("RARBG.com.mp4"))
        assertEquals(EpisodeNaming.Cat.EXTRA, ad.cat)
        assertEquals("AD", ad.extraType)
        assertEquals(EpisodeNaming.Cat.FEATURE, EpisodeNaming.parse(film("www.dream2008.cn@科洛弗档案.rmvb")).cat)
        val spotlight = listOf(film("RARBG.com.mp4"), film("Spotlight.2015.1080p.WEB-DL.DD5.1.H264-RARBG.mkv"))
            .sortedWith(compareBy({ EpisodeNaming.sortKey(it) }, { it.fileName.lowercase() }))
        assertEquals("Spotlight.2015.1080p.WEB-DL.DD5.1.H264-RARBG.mkv", DetailPlay.choose(spotlight)!!.item.fileName)
    }
}
