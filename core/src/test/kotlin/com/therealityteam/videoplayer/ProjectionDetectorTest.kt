package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.ProjectionDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionDetectorTest {

    @Test
    fun flat2dByDefault() {
        val f = ProjectionDetector.detect("Interstellar.2014.2160p.BluRay.x264.mkv")
        assertEquals(StereoMode.MONO, f.stereo)
        assertEquals(ProjectionMode.FLAT, f.projection)
    }

    @Test
    fun sbs3d() {
        val f = ProjectionDetector.detect("Avatar.The.Way.of.Water.2022.2160p.3D.HSBS.mkv")
        assertEquals(StereoMode.SBS, f.stereo)
    }

    @Test
    fun vr360() {
        val f = ProjectionDetector.detect("Concert.360.equirect.5760x2880.mp4")
        assertEquals(ProjectionMode.EQ360, f.projection)
    }

    @Test
    fun vr180() {
        val f = ProjectionDetector.detect("Diving.VR180.8K.SBS.mp4")
        assertEquals(ProjectionMode.EQ180, f.projection)
        assertEquals(StereoMode.SBS, f.stereo)
        assertEquals(ProjectionMode.EQ180, ProjectionDetector.detect("beach_180_sbs.mp4").projection)
        assertEquals(ProjectionMode.EQ360, ProjectionDetector.detect("city_360_tb.mp4").projection)
        assertEquals(StereoMode.OU, ProjectionDetector.detect("city_360_tb.mp4").stereo)
    }

    @Test
    fun fisheyeLensesByTheirNames() {
        // A fisheye's name wins over a VR180 beside it: the picture is the lens's circle.
        val mkx = ProjectionDetector.detect("Diving.VR180.MKX200.8K.mp4")
        assertEquals(ProjectionMode.FISHEYE, mkx.projection)
        assertEquals(200, mkx.lensFov)
        assertEquals(220, ProjectionDetector.detect("scene_MKX220_LR.mp4").lensFov)
        assertEquals(190, ProjectionDetector.detect("hike_rf52_sbs.mp4").lensFov)
        assertEquals(190, ProjectionDetector.detect("talk-fisheye190.mp4").lensFov)
        assertEquals(ProjectionMode.DUAL_FISHEYE, ProjectionDetector.detect("raw_dfisheye.mp4").projection)
    }

    @Test
    fun cubemapsByTheirNames() {
        assertEquals(ProjectionMode.EAC, ProjectionDetector.detect("youtube_eac.webm").projection)
        assertEquals(ProjectionMode.CUBEMAP, ProjectionDetector.detect("room.c3x2.mp4").projection)
    }

    @Test
    fun aFramesShapeIsNotASphere() {
        // 3840x1920 is how many series are framed (2:1); only a name or the file's metadata says sphere.
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("clip.mp4", width = 4096, height = 2048).projection)
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("Stranger.Things.S04E01.2160p.mkv", width = 3840, height = 1920).projection)
    }

    @Test
    fun aNumberIsNotAPanorama() {
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("One Piece - 360 [1080p].mkv").projection)
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("360 (2011).mkv").projection)
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("Naruto Shippuden - 180.mkv").projection)
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("Big Fish (2003).mkv").projection)
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("Movie.x264.AAC.mkv").projection)
        assertEquals(ProjectionMode.EQ360, ProjectionDetector.detect("test_360_mono.mp4").projection)
        assertEquals(ProjectionMode.EQ360, ProjectionDetector.detect("Underwater 360° 8K.mp4").projection)
    }

    @Test
    fun aLensAngleInOneWord() {
        val f = ProjectionDetector.detect("Beach_fisheye180_LR.mp4")
        assertEquals(ProjectionMode.FISHEYE, f.projection)
        assertEquals(180, f.lensFov)
        assertEquals(200, ProjectionDetector.detect("clip_FISH200_TB.mp4").lensFov)
        assertEquals(220, ProjectionDetector.detect("scene_MKX220_SBS.mp4").lensFov)
    }

    @Test
    fun cubeStrips() {
        assertEquals(ProjectionMode.CUBEMAP, ProjectionDetector.detect("hall_c6x1.mp4").projection)
        assertEquals(ProjectionMode.CUBEMAP, ProjectionDetector.detect("tower.cube1x6.mp4").projection)
    }

    @Test
    fun moreNamesInTheWild() {
        // Stereo words run together, as some players' suffixes do.
        assertEquals(StereoMode.OU, ProjectionDetector.detect("Cave_360_3DTB.mp4").stereo)
        assertEquals(StereoMode.SBS, ProjectionDetector.detect("Reef_180_LR3D.mp4").stereo)
        // EQR is equirectangular.
        assertEquals(ProjectionMode.EQ360, ProjectionDetector.detect("harbour_eqr.mp4").projection)
        // A planetarium's fulldome master is a fisheye looking UP (the zenith at its centre, the front at its
        // bottom edge): shown as a forward fisheye its show would be under the viewer's feet — left to the viewer.
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("Aurora_fulldome_4k.mp4").projection)
    }

    @Test
    fun mvHevcIsNoPacking() {
        // Its two views are coded apart: each frame is one eye's whole picture, not halves to split.
        assertEquals(StereoMode.MONO, ProjectionDetector.detect("IMG_0042 spatial video.mov").stereo)
        assertEquals(ProjectionMode.FLAT, ProjectionDetector.detect("IMG_0042 spatial video.mov").projection)
        val vr180 = ProjectionDetector.detect("Canyon_180_MVHEVC.mov")
        assertEquals(StereoMode.MONO, vr180.stereo)
        assertEquals(ProjectionMode.EQ180, vr180.projection)
    }

    @Test
    fun theRightEyeFirst() {
        val rl = ProjectionDetector.detect("Studio_180_RL.mp4")
        assertEquals(StereoMode.SBS, rl.stereo)
        assertEquals(ProjectionMode.EQ180, rl.projection)
        assertEquals(true, rl.rightFirst)
        val bt = ProjectionDetector.detect("Tour_360_BT.mp4")
        assertEquals(StereoMode.OU, bt.stereo)
        assertEquals(ProjectionMode.EQ360, bt.projection)
        assertEquals(true, bt.rightFirst)
        // Left first, as most are; and a BT that is a release group's.
        assertEquals(false, ProjectionDetector.detect("Studio_180_LR.mp4").rightFirst)
        val group = ProjectionDetector.detect("[BT] Some Film 2020 1080p.mkv")
        assertEquals(StereoMode.MONO, group.stereo)
        assertEquals(ProjectionMode.FLAT, group.projection)
        assertEquals(false, group.rightFirst)
    }

    @Test
    fun immersiveFlag() {
        val f = ProjectionDetector.detect("trip.vr360.mp4")
        assertEquals(true, f.isImmersive)
    }
}

class VideoFlagsJsonTest {
    @Test
    fun whatTheFileSaidIsKept() {
        val flags = com.therealityteam.videoplayer.data.model.VideoFlags(
            stereo = StereoMode.SBS, projection = ProjectionMode.EQ180, rightFirst = true, fromFile = true,
        )
        val item = com.therealityteam.videoplayer.data.model.MediaItem("a", "local", "/v/Garden.mp4", "Garden.mp4", title = "Garden", video = flags)
        val back = com.therealityteam.videoplayer.data.model.MediaJson.item(com.therealityteam.videoplayer.data.model.MediaJson.item(item))
        assertEquals(flags, back.video)
        // Older libraries, written before these: neither.
        val plain = com.therealityteam.videoplayer.data.model.MediaJson.item(item.copy(video = com.therealityteam.videoplayer.data.model.VideoFlags()))
        assertEquals(false, plain.has("rightFirst"))
        assertEquals(false, plain.has("videoFromFile"))
    }
}
