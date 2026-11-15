package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.ProjectionGuess
import com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.imageio.ImageIO

/**
 * Frames from tools/dev/pano/make-guess-fixtures.sh: panoramas FFmpeg's v360 made from six textured cube
 * faces (as a camera's stitcher makes them), lens circles masked black round, and flat pictures of the
 * same textures — 512 wide, JPEG-coded, as the player reads a film's frames to guess.
 */
class ProjectionGuessTest {

    private fun frame(name: String): ProjectionGuess.Luma {
        val img = ImageIO.read(javaClass.getResource("/pano/guess/$name")!!)
        val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return ProjectionGuess.Luma.ofArgb(argb, img.width, img.height)
    }

    @Test
    fun panoramasAreRecognised() {
        val expected = mapOf(
            "pos_eq360.jpg" to Guess(ProjectionMode.EQ360, StereoMode.MONO),
            "pos_eq360_tb.jpg" to Guess(ProjectionMode.EQ360, StereoMode.OU),
            "pos_vr180_sbs.jpg" to Guess(ProjectionMode.EQ180, StereoMode.SBS),
            "pos_fisheye.jpg" to Guess(ProjectionMode.FISHEYE, StereoMode.MONO),
            "pos_fisheye_sbs.jpg" to Guess(ProjectionMode.FISHEYE, StereoMode.SBS),
            "pos_dfisheye.jpg" to Guess(ProjectionMode.DUAL_FISHEYE, StereoMode.MONO),
            // As the emulator copied it off the playing picture (PixelCopy): its black came out 31/255, not 0.
            "device_fisheye.jpg" to Guess(ProjectionMode.FISHEYE, StereoMode.MONO),
        )
        for ((name, guess) in expected) assertEquals(name, guess, ProjectionGuess.guess(frame(name)))
    }

    @Test
    fun flatPicturesAreLeftAlone() {
        for (name in listOf("neg_flat_2x1.jpg", "neg_flat_1x1.jpg", "neg_card_2x1.jpg", "neg_letterbox_2x1.jpg", "neg_vignette_1x1.jpg")) {
            assertNull(name, ProjectionGuess.guess(frame(name)))
        }
    }

    @Test
    fun aBlackFrameSaysNothing() {
        assertNull(ProjectionGuess.guess(ProjectionGuess.Luma(512, 256, FloatArray(512 * 256) { 0.06f })))
    }

    @Test
    fun theFramesMustAgree() {
        val sphere = Guess(ProjectionMode.EQ360, StereoMode.MONO)
        val lens = Guess(ProjectionMode.FISHEYE, StereoMode.MONO)
        assertEquals(sphere, ProjectionGuess.agree(listOf(sphere, null, sphere)))
        assertEquals(sphere, ProjectionGuess.agree(listOf(sphere, sphere, lens)))
        assertNull(ProjectionGuess.agree(listOf(sphere, null, null)))
        assertNull(ProjectionGuess.agree(listOf(sphere, lens, null, null)))
        assertNull(ProjectionGuess.agree(listOf(null, null, null)))
    }
}
