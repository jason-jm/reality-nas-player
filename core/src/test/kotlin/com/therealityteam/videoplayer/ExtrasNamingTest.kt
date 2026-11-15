package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ContentKind
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.ui.library.DetailPlay
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Extras as discs and groups name them — the cases a real library's 播放 got wrong, each played as the
 * film or the first episode: a Japanese disc's 映像特典, a 制作花絮, a "(Sample)", a [TVCM], a [Menu][01].
 */
class ExtrasNamingTest {

    private fun file(name: String, title: String? = null, original: String? = null, kind: MediaKind = MediaKind.MOVIE) =
        MediaItem(name, "s", "/v/$name", name, title = title ?: name, kind = kind, showTitle = null, originalTitle = original)

    private fun parse(name: String, original: String? = null) = EpisodeNaming.parse(file(name, original = original))

    @Test fun `a disc's extras named in Japanese and Chinese are extras`() {
        mapOf(
            "[アニメ BD] トップをねらえ2! DIEBUSTER 映像特典「BD CF集」(1920x1080 HEVC 10bit FLAC).mkv" to "BONUS",
            "[DMG] 劇場版 さよならの朝に約束の花をかざろう 予告 [BDRip][HEVC_FLAC][1080P_Ma10P](60A39ED9).mkv" to "TRAILER",
            "[DMG] 劇場版 魔法科高校の劣等生 星を呼ぶ少女 第1弹特報 [BDRip].mkv" to "TRAILER",
            "[アニメ BD] 少女革命ウテナ ノンテロップ OP「輪舞-revolution」(1080p x264 FLAC 2ch 10bit).mkv" to "NC",
            "[二次大战启示录.制作花絮].Apocalypse.La.Deuxieme.Guerre.Mondiale.Makingof.mkv" to "MAKING",
            "[DMG] 劇場版 さよならの朝に約束の花をかざろう CM集 [BDRip].mkv" to "CM",
            "[アニメ BD] トップをねらえ2! DIEBUSTER「メニュー画面 1」(1920x1080 HEVC 10bit FLAC).mkv" to "MENU",
            // An episode's preview is the preview, not the episode.
            "R.O.D －THE TV－ 映像特典 ｢第02話予告｣ (BD 720x480 H.264).mp4" to "BONUS",
        ).forEach { (name, type) ->
            val info = parse(name)
            assertEquals(name, Cat.EXTRA, info.cat)
            assertEquals(name, type, info.extraType)
        }
    }

    @Test fun `a leftover goes by its own name, without its brackets`() {
        assertEquals("Mobile Suit Information", EpisodeNaming.ownName(file("Mobile Suit Information.mkv")))
        assertEquals("Making of Part 2", EpisodeNaming.ownName(file("[Group]Making_of_Part_2.mkv")))
        assertEquals(null, EpisodeNaming.ownName(file("[VCB-Studio][1080p].mkv")))
    }

    @Test fun `a preview spelt in romaji is a trailer numbered by its episode`() {
        val info = parse("EP20 15sec. Next EP Yokoku.mkv")
        assertEquals(Cat.EXTRA, info.cat)
        assertEquals("TRAILER", info.extraType)
        assertEquals(20, info.extraNum)
        // The episode itself, beside it, stays the episode.
        assertEquals(Cat.EPISODE, EpisodeNaming.parse(file("EP20.mkv", kind = MediaKind.EPISODE)).cat)
    }

    @Test fun `a token set apart by brackets or a parenthesis is an extra`() {
        val sample = parse("Spider-Man Into the Spider-Verse 2018 UHD 4K Hybrid BluRay 2160p ReMux HDR TrueHD Atmos 7.1-MgB (Sample).mkv")
        assertEquals(Cat.EXTRA, sample.cat)
        assertEquals("SAMPLE", sample.extraType)
        val tvcm = parse("[R2JRAW][Patlabor_The_Movie_1][TVCM].mkv")
        assertEquals(Cat.EXTRA, tvcm.cat)
        assertEquals("CM", tvcm.extraType)
        // The extra's own bracket wins over the number after it.
        val menu = parse("[FLsnow][Magical_Girl_Lyrical_NANOHA_The_MOVIE_2nd_A's][Menu][01][AVC_FLAC][1080p].mkv")
        assertEquals(Cat.EXTRA, menu.cat)
        assertEquals("MENU", menu.extraType)
        assertEquals(1, menu.extraNum)
        val pv = parse("[DBD-Raws][Kusuriya no Hitorigoto][PV][03][1080P][BDRip].mkv")
        assertEquals("PV", pv.extraType)
        assertEquals(3, pv.extraNum)
    }

    @Test fun `a word of the work's own name is the work, not a kind of extra`() {
        // The film called The Menu; a concert film; the film called 予告犯.
        assertEquals(Cat.FEATURE, parse("The.Menu.2016.1080p.BluRay.x264-WiKi.mkv", original = "The Menu").cat)
        assertEquals(Cat.FEATURE, parse("Clark Terry - Live in Concert.avi", original = "Clark Terry - Live in Concert").cat)
        assertEquals(Cat.FEATURE, parse("予告犯.2015.1080p.BluRay.mkv", original = "予告犯").cat)
        // …but an extra titled by its own name is still an extra.
        assertEquals(Cat.EXTRA, EpisodeNaming.parse(file("Theatrical Trailer.mkv", title = "Theatrical Trailer")).cat)
    }

    @Test fun `a disc's plurals are its kinds of extra`() {
        mapOf(
            "Ready.Player.One.Featurettes.(Play.All)-Grym.mkv" to "FEATURETTE",
            "Interviews-Grym@BTNET.mkv" to "INTERVIEW",
            "Blizzard trailers.flv" to "TRAILER",
        ).forEach { (name, type) ->
            assertEquals(name, Cat.EXTRA, parse(name).cat)
            assertEquals(name, type, parse(name).extraType)
        }
    }

    @Test fun `a disc's catch-all words name an extra by what follows them`() {
        val anatomy = parse("Memento.Extras1.Anatomy.of.a.Scene-Grym.mkv")
        assertEquals(Cat.EXTRA, anatomy.cat)
        assertEquals("BONUS", anatomy.extraType)
        assertEquals(1, anatomy.extraNum)
        assertEquals("Anatomy of a Scene", anatomy.name)
        assertEquals("rahxephon clean ending", parse("cor.extras.rahxephon.clean.ending.[F3667520].mkv").name)
        // Something more particular said wins over the catch-all.
        assertEquals("INTERVIEW", parse("cor.extras.rahxephon.interview.with.hiroki.kanno.[E459A244].mkv").extraType)
        val deleted = parse("Deleted.Scenes-Grym.mkv")
        assertEquals(Cat.EXTRA, deleted.cat)
        assertEquals("DELETED", deleted.extraType)
        assertEquals(null, deleted.name)
        assertEquals("with Commentary", parse("Deleted.Scenes.with.Commentary-Grym.mkv").name)
    }

    @Test fun `IV alone is four, an interview carries its number`() {
        listOf(
            "Star.Wars.Episode.IV.A.New.Hope.1977.BluRay.1080p.DTS-HD.MA.6.1.x264-EPiC.mkv",
            "Apocalypse.The.Second.World.War.IV.2009.Blu-ray.720p.x264.DTS.MySilu.mkv",
            "10 Theme of Love (FF IV).avi",
        ).forEach { assertEquals(it, Cat.FEATURE, parse(it).cat) }
        listOf("[VCB-Studio] Full Metal Panic! The Second Raid [IV01][Hi10p_480p][x264_ac3].mkv",
            "[Airota&Nekomoe kissaten&VCB-Studio] Yuru Camp Season 2 [IV02_1][Ma10p_1080p][x265_aac].mkv",
            "[Kamigami&BeanSub&FZSD] Fate stay night Heaven's Feel I.presage flower - IV01 [BD 480p x265 Ma10p AC3].mkv",
        ).forEach {
            assertEquals(it, Cat.EXTRA, parse(it).cat)
            assertEquals(it, "IV", parse(it).extraType)
        }
    }

    @Test fun `a disc that names its feature has said which file is the film`() {
        val disc = listOf(
            "[FLsnow][Magical_Girl_Lyrical_NANOHA_The_MOVIE_2nd_A's][Lyrical_Party_IV][AVC_FLAC][720p].mkv",
            "[FLsnow][Magical_Girl_Lyrical_NANOHA_The_MOVIE_2nd_A's][MAIN_MOVIE][AVC_FLAC_DTS][CHS_CHT_JPN_ENG][1080p].mkv",
            "[FLsnow][Magical_Girl_Lyrical_NANOHA_The_MOVIE_2nd_A's][SP][ACE2012][AVC_FLAC][720p].mkv",
        ).map { file(it) }.sortedWith(compareBy({ EpisodeNaming.sortKey(it) }, { it.fileName.lowercase() }))
        val main = parse(disc[0].fileName)
        assertEquals(Cat.FEATURE, main.cat)
        assertEquals(null, main.name)
        assertEquals(listOf(disc[0]), DetailPlay.mains(disc))
        assertEquals(disc[0], DetailPlay.choose(disc)!!.item)
    }

    @Test fun `a film's 播放 plays the film, not its sample`() {
        val film = listOf(
            file("Spider-Man Into the Spider-Verse 2018 UHD 4K Hybrid BluRay 2160p ReMux HDR TrueHD Atmos 7.1-MgB (Sample).mkv"),
            file("Spider-Man Into the Spider-Verse 2018 UHD 4K Hybrid BluRay 2160p ReMux HDR TrueHD Atmos 7.1-MgB.mkv"),
        ).map { it.copy(contentTag = ContentKind.MAIN) }.sortedWith(compareBy({ EpisodeNaming.sortKey(it) }, { it.fileName.lowercase() }))
        assertEquals(
            "Spider-Man Into the Spider-Verse 2018 UHD 4K Hybrid BluRay 2160p ReMux HDR TrueHD Atmos 7.1-MgB.mkv",
            DetailPlay.choose(film)!!.item.fileName,
        )
    }

    @Test fun `a run of brackets names its part last — after the group and the work, before the release's tags`() {
        mapOf(
            // The group comes first even where it does not open the name.
            "Sound Horizon [POPGO][Linked_Horizon_Luxendarc_Kikou].mkv" to "Linked Horizon Luxendarc Kikou",
            // Each part of a disc by its own name, not the disc's.
            "[Macross F Galaxy Tour Final in Budokan][Disc2][LIVE DOCUMENT][DVDRIP][848x480][H264_AACx2].mp4" to "LIVE DOCUMENT",
            "[CASO][Dog_Days][TVSpot01][BDRIP][1920x1080][x264_FLAC][A5AEF4BD].mkv" to "TVSpot01",
            // A form word between them is no name, and the name after it still is.
            "[Shftp&CASO][Mobile_Suit_Gundam_00][MOVIE][A_wakening_of_the_Trailblaze][BDRIP][1920x1080].mkv" to "A wakening of the Trailblaze",
            // …and a name after the source tag is still the part's own.
            "[philosophy-raws][Sword Art Online II][BDRIP][Sword Art Offline II 1][Hi10P FLAC][1920X1080].mkv" to "Sword Art Offline II 1",
            // Release tags at the end never are: subtitle formats, sources, raws, codecs, containers.
            "[A.I.R.nesSub][Turn_A_Gundam][Movie_I_Earth_Light][BDRIP][1080p][AVC-Hi10p_FLACx2][PGS].mkv" to "Movie I Earth Light",
            "[POPGO][FREEWIND][The_Sky_Crawlers][Full_HD-BDRIP][H.264_DD].mkv" to "The Sky Crawlers",
            "[DBD-Raws][借物少女艾莉媞][1080P][BDRip][HEVC-10bit][国日粤三语][内封中日字幕][DTS-HDMA+DTSx2].mkv" to "借物少女艾莉媞",
        ).forEach { (name, expected) -> assertEquals(name, expected, parse(name).name) }
        // A language, or "Movie" alone, names nothing: the film is the film.
        listOf(
            "RUROUNIKENSHIN[GB][BDRip][720P][x264_Hi10P_AAC_5.1CH].mkv",
            "[KTXP&VCB-S] Expelled From Paradise [Movie][1080p][x264_aac].mp4",
            "All The President's Men 1976 DvDrip[Eng]-RTO.avi",
        ).forEach { assertEquals(it, null, parse(it).name) }
    }
}
