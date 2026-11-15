package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.subtitle.SubtitleFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Subtitles in a folder of their own: which belong to which file. As the NAS holds them. */
class SubtitleFoldersTest {

    @Test fun `the folder is found by its name, whatever its case`() {
        assertEquals("Subs", SubtitleFolders.folderIn(listOf("Subs/", "a.mkv")))
        assertEquals("sub", SubtitleFolders.folderIn(listOf("a.mkv", "sub/")))
        assertNull(SubtitleFolders.folderIn(listOf("Extras/", "a.mkv")))
    }

    @Test fun `a film takes the folder's unnumbered subtitles, not its trailer`() {
        val inside = listOf(
            "[R2JRAW][Patlabor_The_Movie_1].ssa",
            "Patlabor.The.Movie.I.1989.BluRay.720p.x264.DTS-WiKi.炎之川_sc(DVD)-Fix.ssa",
            "[POPGO][FREEWIND][Patlabor_The_Movie_I][BDRIP][H.264_DD].srt",
            "Patlabor_The_Mobile_Police_Movie.rar",
        )
        assertEquals(3, SubtitleFolders.match("Kidou.Keisatsu.Patlabor.-.Movie.[Salender-Raws][0287417C].mkv", inside, isFeature = true).size)
        assertEquals(0, SubtitleFolders.match("Kidou.Keisatsu.Patlabor.-.Trailer.[Salender-Raws][3EBEB022].mkv", inside, isFeature = false).size)
        assertEquals(listOf("English.srt"), SubtitleFolders.match("The.Great.War.2019.mkv", listOf("Dekadansu.rus.srt.xltd", "English.srt"), true).map { it.fileName })
    }

    @Test fun `an episode takes the subtitles of its own number`() {
        val inside = listOf(
            "[YYDM-11FANS][Xam'd Lost Memories][01][BDrip][720P][X264-10bit_AAC][FA76EA10].KTXP-sc.ass",
            "[YYDM-11FANS][Xam'd Lost Memories][02][BDrip][720P][X264-10bit_AAC][2B0C1D11].KTXP-sc.ass",
            "Xam'd_Lost_Memories_Ep01_Xam'd_at_the_Dawn_of_War_[1080p,BluRay,x264,DTS]_-_THORA.ass",
            "Xam'd_Lost_Memories_Ep02_Blackout_on_Sentan_Island_[1080p,BluRay,x264,DTS]_-_THORA.ass",
        )
        val ep1 = SubtitleFolders.match("Xam'd_Lost_Memories_Ep01_Xam'd_at_the_Dawn_of_War_[1080p,BluRay,x264,DTS]_-_THORA.mkv", inside, isFeature = false)
        assertEquals(2, ep1.size)
        val ova = listOf("[R2JRAW][Patlabor_OVA][EARLY_DAYS][06].ssa", "[R2JRAW][Patlabor_OVA][EARLY_DAYS][02].ssa")
        assertEquals(listOf("[R2JRAW][Patlabor_OVA][EARLY_DAYS][06].ssa"),
            SubtitleFolders.match("[R2JRAW][Patlabor_OVA][EARLY_DAYS][06][BDRip].mkv", ova, isFeature = false).map { it.fileName })
    }

    @Test fun `a VobSub in Subs is offered to the engine that draws pictures, with its pictures beside it`() {
        val video = "Arrival.2016.1080p.BluRay.x264-SPARKS.mkv"
        val inside = listOf("Arrival.2016.1080p.BluRay.x264-SPARKS.idx", "Arrival.2016.1080p.BluRay.x264-SPARKS.sub")
        assertEquals(0, SubtitleFolders.match(video, inside, isFeature = true).size)
        val pictures = SubtitleFolders.match(video, inside, isFeature = true, imageFormats = true)
        assertEquals(listOf("Arrival.2016.1080p.BluRay.x264-SPARKS.idx"), pictures.map { it.fileName })
        assertEquals(true, com.therealityteam.videoplayer.data.subtitle.SubtitleEngine.isPicture(pictures.single().mimeType))
        // Lower-cased by its release group: matched as the film's own, not by name.
        val lower = listOf("darkest.hour.2017.1080p.bluray.x264-drones.sub", "darkest.hour.2017.1080p.bluray.x264-drones.idx")
        assertEquals(listOf("darkest.hour.2017.1080p.bluray.x264-drones.idx"),
            SubtitleFolders.match("Darkest.Hour.2017.1080p.BluRay.x264-DRONES.mkv", lower, isFeature = true, imageFormats = true).map { it.fileName })
        // An index without its pictures shows nothing: not offered.
        assertEquals(0, SubtitleFolders.match(video, inside.take(1), isFeature = true, imageFormats = true).size)
    }

    @Test fun `a VobSub beside the video needs its sub file too`() {
        val e = com.therealityteam.videoplayer.data.subtitle.SubtitleEngine
        assertEquals(listOf("Film.idx"), e.matchSidecars("Film.mkv", listOf("Film.idx", "Film.SUB"), imageFormats = true).map { it.fileName })
        assertEquals(0, e.matchSidecars("Film.mkv", listOf("Film.idx"), imageFormats = true).size)
        assertEquals(0, e.matchSidecars("Film.mkv", listOf("Film.idx", "Film.sub")).size)
        assertEquals(false, e.isPicture("application/x-subrip"))
    }
}
