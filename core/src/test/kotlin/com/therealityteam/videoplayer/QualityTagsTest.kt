package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.QualityTags
import com.therealityteam.videoplayer.data.playback.QualityTags.Tag
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a release name says about the picture and the sound — read from a real library's names. */
class QualityTagsTest {

    @Test fun `a remux names its resolution, its HDR, its sound and its source`() {
        assertEquals(
            listOf(Tag.UHD_4K, Tag.DOLBY_VISION, Tag.ATMOS, Tag.REMUX),
            QualityTags.of("Dune.Part.Two.2024.UHD.BluRay.2160p.TrueHD.Atmos.7.1.DV.HEVC.REMUX-FraMeSToR.mkv"),
        )
        assertEquals(
            listOf(Tag.UHD_4K, Tag.HDR10, Tag.ATMOS, Tag.REMUX),
            QualityTags.of("Spider-Man Into the Spider-Verse 2018 UHD 4K Hybrid BluRay 2160p ReMux HDR TrueHD Atmos 7.1-MgB.mkv"),
        )
    }

    @Test fun `one tag of a kind, the best the name claims`() {
        // Dolby Vision over the HDR10 it also carries; Atmos over the TrueHD it rides on.
        assertEquals(
            listOf(Tag.UHD_4K, Tag.DOLBY_VISION, Tag.REMUX),
            QualityTags.of("Seven.Samurai.1954.2160p.UHD.Blu-ray.Remux.DV.HDR.HEVC.FLAC.1.0-CiNEPHiLES.mkv"),
        )
        assertEquals(
            listOf(Tag.UHD_4K, Tag.DOLBY_VISION, Tag.ATMOS),
            QualityTags.of("Movie 2021 2160p WEB-DL DoVi HDR10+ DDP5.1 Atmos.mkv"),
        )
    }

    @Test fun `the formats in the ways release groups spell them`() {
        assertEquals(listOf(Tag.UHD_4K, Tag.HDR10_PLUS, Tag.DTS_X), QualityTags.of("Film.2016.2160p.UHD.BluRay.x265.HDR10+.DTS-X.mkv"))
        assertEquals(listOf(Tag.FHD, Tag.DTS_HD_MA), QualityTags.of("Film.2010.1080p.BluRay.DTS-HD.MA.5.1.x264-Grp.mkv"))
        assertEquals(listOf(Tag.UHD_4K, Tag.HLG), QualityTags.of("Planet.Earth.II.2016.2160p.UHD.BluRay.HLG.mkv"))
        assertEquals(listOf(Tag.UHD_8K), QualityTags.of("Demo.4320p.HEVC.mkv"))
        assertEquals(listOf(Tag.FHD, Tag.REMUX), QualityTags.of("[BDRemux] Film (1080p).mkv"))
        assertEquals(listOf(Tag.FHD, Tag.THREE_D), QualityTags.of("Avatar.2009.1080p.3D.HSBS.BluRay.x264.mkv"))
    }

    @Test fun `what only looks like a format is not one`() {
        // A rip named for HDR is not HDR; a codec is not DTS:X; a CG demo and a director are not 3D.
        assertEquals(emptyList<Tag>(), QualityTags.of("Film.2019.HDRip.XviD.avi"))
        assertEquals(listOf(Tag.FHD), QualityTags.of("Film.2019.1080p.DTS-x264.mkv"))
        assertEquals(emptyList<Tag>(), QualityTags.of("[ 3D BG Concept Demo ].mp4"))
        assertEquals(listOf(Tag.FHD), QualityTags.of("The.Assassin.2015.Hou.Hsiao-Hsien.1080p.mkv"))
    }

    @Test fun `a panorama says so, a numbered episode does not`() {
        assertEquals(listOf(Tag.UHD_8K, Tag.THREE_D, Tag.VR180), QualityTags.of("Diving_VR180_8K_SBS.mp4"))
        assertEquals(listOf(Tag.UHD_8K, Tag.VR360), QualityTags.of("Underwater 360° 8K.mp4"))
        assertEquals(listOf(Tag.VR180), QualityTags.of("Scene_MKX200_LR.mp4").filter { it.kind == QualityTags.Kind.PANORAMA })
        assertEquals(listOf(Tag.FHD), QualityTags.of("One Piece - 360 [1080p].mkv"))
    }

    @Test fun `a panorama the file or the viewer named outranks the name`() {
        val vf = { p: com.therealityteam.videoplayer.data.model.ProjectionMode, file: Boolean, user: Boolean ->
            com.therealityteam.videoplayer.data.model.VideoFlags(projection = p, fromFile = file, userOverridden = user)
        }
        val eq360 = com.therealityteam.videoplayer.data.model.ProjectionMode.EQ360
        val flat = com.therealityteam.videoplayer.data.model.ProjectionMode.FLAT
        // Its metadata said 360 as it played; the name says nothing.
        assertEquals(listOf(Tag.VR360), QualityTags.of("Morning walk.mp4", vf(eq360, true, false)))
        // The viewer said flat: no tag, whatever the name.
        assertEquals(emptyList<Tag>(), QualityTags.of("trip_360_mono.mp4", vf(flat, false, true)))
        // A scan's flags are only the name again.
        assertEquals(emptyList<Tag>(), QualityTags.of("Morning walk.mp4", vf(eq360, false, false)))
    }

    @Test
    fun threeDByTheFilesOwnMarking() {
        // A plain name, a flat film the file marks as side by side (st3d, StereoMode, packing SEI): 3D.
        val learned = com.therealityteam.videoplayer.data.model.VideoFlags(
            com.therealityteam.videoplayer.data.model.StereoMode.SBS, com.therealityteam.videoplayer.data.model.ProjectionMode.FLAT, fromFile = true,
        )
        org.junit.Assert.assertTrue(QualityTags.Tag.THREE_D in QualityTags.of("Holiday clip.mp4", learned))
        // Flags guessed from a name only add nothing the name did not say.
        org.junit.Assert.assertFalse(QualityTags.Tag.THREE_D in QualityTags.of("Holiday clip.mp4", learned.copy(fromFile = false)))
    }
}
