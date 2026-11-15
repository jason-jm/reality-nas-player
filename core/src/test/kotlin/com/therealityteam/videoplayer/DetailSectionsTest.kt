package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.ui.library.DetailSections
import com.therealityteam.videoplayer.ui.library.DetailSections.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailSectionsTest {

    private fun file(name: String) = MediaItem(id = name, sourceId = "s", path = "/v/Show/$name", fileName = name, title = "Show")

    @Test fun `one season is one episode list, and an NC version goes to the extras`() {
        val s = DetailSections.of(
            listOf(file("[Grp] Show [02].mkv"), file("[Grp] Show [01].mkv"), file("[Grp] Show [01(NC Ver.)].mkv"), file("[Grp] Show [NCOP].mkv")),
        )
        assertEquals(listOf(Kind.EPISODES, Kind.EXTRAS), s.map { it.kind })
        assertEquals(listOf("[Grp] Show [01].mkv", "[Grp] Show [02].mkv"), s[0].items.map { it.fileName })
        assertEquals(setOf("[Grp] Show [01(NC Ver.)].mkv", "[Grp] Show [NCOP].mkv"), s[1].items.map { it.fileName }.toSet())
    }

    @Test fun `第1季01 is season 1 episode 1 — the season's 第 is not an episode number`() {
        val files = (1..3).map { file("[YYsoR]好汉两个半第1季%02d.英语中字.d-vb.rmvb".format(it)) } +
            (1..2).map { file("[YYsoR]好汉两个半第2季%02d.英语中字.d-vb.rmvb".format(it)) } +
            file("好汉两个半 第3季第05集.mkv")
        val s = DetailSections.of(files)
        assertEquals(listOf(Kind.SEASON to 1, Kind.SEASON to 2, Kind.SEASON to 3), s.map { it.kind to it.season })
        assertEquals(listOf(3, 2, 1), s.map { it.items.size })
        val ep = { name: String -> com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(file(name)) }
        assertEquals(1 to 3.0, ep("[YYsoR]好汉两个半第1季03.英语中字.d-vb.rmvb").let { it.season to it.episode })
        assertEquals(3 to 5.0, ep("好汉两个半 第3季第05集.mkv").let { it.season to it.episode })
        assertEquals(4 to 2.0, ep("[YYsoR]好汉两个半第四季02.英语中字.hdtv-vb.rmvb").let { it.season to it.episode })
        // A plain 第N集 is still the episode, and a season with no number after it names no episode.
        assertEquals(4.0, ep("某剧第4集.mkv").episode)
        assertEquals(null, ep("好汉两个半 第1季 花絮.mkv").episode)
        // Nor is a promo's, a piece's, a volume's or a span's 第: they are specials, not episodes.
        assertEquals(null, ep("[DMG] 劇場版 魔法科高校の劣等生 星を呼ぶ少女 第1弹特報 [BDRip].mkv").episode)
        assertEquals(null, ep("第１弾ＰＶ.mkv").episode)
        assertEquals(null, ep("ピアノ 『ソナタ第2番 第一楽章』.flv").episode)
        assertEquals(null, ep("科学講座 第1~3回「エーテル宇宙」.mkv").episode)
        assertEquals(3.0, ep("[NHK]纪录片-日米安保50年-第3回「被藏起来的美军」.avi").episode)
    }

    @Test fun `a name the patterns cannot read keeps the episode number the scan found`() {
        fun ep(name: String, n: Int?, kind: com.therealityteam.videoplayer.data.model.MediaKind = com.therealityteam.videoplayer.data.model.MediaKind.EPISODE,
               tag: com.therealityteam.videoplayer.data.model.ContentKind? = null) =
            com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(file(name).copy(kind = kind, episode = n, contentTag = tag))
        val geass = (1..3).map { file("[Coalgirls]_Code_Geass_R2_%02d_(1920x1080_Blu-ray_FLAC)_[54DA8E43].mkv".format(it)).copy(
            kind = com.therealityteam.videoplayer.data.model.MediaKind.EPISODE, episode = it) }
        val s = DetailSections.of(geass)
        assertEquals(listOf(Kind.EPISODES), s.map { it.kind })
        assertEquals(3, s[0].items.size)
        assertEquals(5.0, ep("You're.Under.Arrest.DVDRip.05.avi", 5).episode)
        // Not for a film, nor for a file the scan filed as an extra, nor without a number.
        assertEquals(null, ep("Some.Film.05.mkv", 5, kind = com.therealityteam.videoplayer.data.model.MediaKind.MOVIE).episode)
        assertEquals(null, ep("SC 01.mkv", 1, tag = com.therealityteam.videoplayer.data.model.ContentKind.EXTRA).episode)
        assertEquals(null, ep("You're.Under.Arrest.Specia001.rmvb", null).episode)
        // The title's own number is not an episode: the scan read C.E.73's menu as episode 73.
        val menu = file("[アニメ BD] 機動戦士ガンダムSEED C.E.73 STARGAZER メニュー (1920x1080i x264 AAC).mp4").copy(
            title = "机动战士高达SEED C.E.73 观星者", kind = com.therealityteam.videoplayer.data.model.MediaKind.EPISODE, episode = 73)
        assertEquals(null, com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(menu).episode)
        // …nor a number the name does not hold on its own.
        assertEquals(null, ep("eden_demomovie_16x9.wmv", 9).episode)
        assertEquals(1.0, ep("[OZC]MS IGLOO - Apocalypse 0079 E01 'In the Skies of Jaburo' [v2].mkv", 1).episode)
        assertEquals(2.0, ep("[POPGO][FREEWIND][You_Are_Under_Arrest_2][DVDRIP][XVID_MP3][02v2].avi", 2).episode)
        assertEquals(3.0, ep("[Zero-Raws] Suzumiya Haruhi no Yuuutsu - 03 (BD 1920x1080 x264 FLAC).mkv", 3).episode)
        // Season x episode reads on its own; a resolution or an aspect ratio does not.
        assertEquals(2 to 5.0, ep("the.west.wing.2x05.and.its.surely.to.their.credit.dvdrip.avi", null).let { it.season to it.episode })
        assertEquals(null, ep("[Grp] Some Film (1920x1080 x264).mkv", null).episode)
    }

    @Test fun `a special is named by its own words, not its releaser's tag or checksum`() {
        val name = { n: String -> com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(file(n)).name }
        assertEquals("PILOT FILM：Act Scene 00", name("[VCB-Studio] Full Metal Panic! The Second Raid [PILOT FILM：Act Scene 00][Hi10p_480p][x264_flac].mkv"))
        assertEquals(null, name("[DMG] 劇場版 さよならの朝に約束の花をかざろう [BDRip][HEVC_FLAC_PSG][1080P_Ma10P](B05B7617).mkv"))
        // Its words after the disc's "extras", without the checksum.
        assertEquals("rahxephon clean ending", name("cor.extras.rahxephon.clean.ending.[F3667520].mkv"))
        assertEquals(null, name("[NHK][纪录片]明治.序章.向明治学习什么.wmv"))
        // A bracket that is the whole name is still the name.
        assertEquals("Survival Camp", name("[Survival Camp].mkv"))
    }

    @Test fun `a file named by its number, or as part N of M, is that episode`() {
        val ep = { n: String -> com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(file(n)).episode }
        assertEquals(1.0, ep("1.rmvb"))
        assertEquals(9.0, ep("09.avi"))
        assertEquals(1.0, ep("[PBS.拿破仑传奇].Empires.Napoleon.1of4.to.destiny.xvid.avi"))
        assertEquals(10.0, ep("Rick.Steves.Europe.10of11.Spain.Madrid.720p.avi"))
        assertEquals(null, ep("2001.mkv"))
        assertEquals(null, ep("00001.m2ts"))
        assertEquals(null, com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(
            file("300.mkv").copy(kind = com.therealityteam.videoplayer.data.model.MediaKind.MOVIE)).episode)
    }

    @Test fun `seasons split, then OVA`() {
        val s = DetailSections.of(listOf(file("Show S02E01.mkv"), file("Show S01E01.mkv"), file("Show S01E02.mkv"), file("Show OVA 1.mkv")))
        assertEquals(listOf(Kind.SEASON to 1, Kind.SEASON to 2, Kind.OVA to null), s.map { it.kind to it.season })
        assertEquals(2, s[0].items.size)
    }
}
