package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AspectFit
import com.therealityteam.videoplayer.data.playback.AspectMode
import com.therealityteam.videoplayer.data.playback.FrameCrop
import com.therealityteam.videoplayer.data.playback.Letterbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

/** Black bars found in frames sampled across a film, and the panel shaped by them. */
class LetterboxTest {

    /** A [w]×[h] frame: black (with compression noise) outside [top]..h-[bottom] × [left]..w-[right], a picture inside. */
    private fun frame(w: Int = 192, h: Int = 108, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0, seed: Int = 1): Letterbox.Frame {
        val r = Random(seed)
        val luma = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inPicture = y >= top && y < h - bottom && x >= left && x < w - right
            if (inPicture) 30 + r.nextInt(200) else r.nextInt(12)
        }
        return Letterbox.Frame(w, h, luma)
    }

    @Test fun `a scope film in a 16 9 frame loses its bars top and bottom`() {
        val crop = Letterbox.detect((1..5).map { frame(top = 14, bottom = 14, seed = it) })
        assertEquals(14 / 108f, crop.top, 0.001f)
        assertEquals(14 / 108f, crop.bottom, 0.001f)
        assertEquals(0f, crop.left, 0f)
        // 16:9 with 13 % off each side is a 2.39:1 picture.
        assertEquals(2.3f, crop.aspectOf(16f / 9f), 0.1f)
    }

    @Test fun `a 4 3 picture in a 16 9 frame loses its bars at the sides`() {
        val crop = Letterbox.detect((1..4).map { frame(left = 24, right = 24, seed = it) })
        assertEquals(24 / 192f, crop.left, 0.001f)
        assertEquals(24 / 192f, crop.right, 0.001f)
        assertEquals(0f, crop.top, 0f)
    }

    @Test fun `one frame lit in the bar keeps that edge whole`() {
        // An IMAX scene opens the picture up: the top and bottom stay.
        val frames = (1..4).map { frame(top = 14, bottom = 14, seed = it) } + frame(seed = 9)
        assertFalse(Letterbox.detect(frames).crops)
    }

    @Test fun `black frames say nothing and too few frames cut nothing`() {
        val fades = (1..3).map { frame(top = 108, seed = it) }
        val crop = Letterbox.detect(fades + (1..3).map { frame(top = 14, bottom = 14, seed = 10 + it) })
        assertEquals(14 / 108f, crop.top, 0.001f)
        assertFalse(Letterbox.detect((1..2).map { frame(top = 14, bottom = 14, seed = it) }).crops)
    }

    @Test fun `what is left must be a picture's shape`() {
        val frames = (1..4).map { frame(top = 14, bottom = 14, seed = it) }
        // Scope out of 16:9: kept.
        assertEquals(14 / 108f, Letterbox.detect(frames, 16f / 9f).top, 0.001f)
        // The same bars over a 4:3 frame would leave 1.78 — a shape too, so kept as well.
        assertEquals(14 / 108f, Letterbox.detect(frames, 4f / 3f).top, 0.001f)
    }

    @Test fun `bars come in pairs`() {
        // Black over the top of every sample and none below: a night sky, not a bar.
        assertFalse(Letterbox.detect((1..4).map { frame(top = 30, seed = it) }, 16f / 9f).crops)
        assertFalse(Letterbox.detect((1..4).map { frame(top = 14, bottom = 30, seed = it) }, 16f / 9f).crops)
        // A row's difference is an encoder's rounding: kept.
        val crop = Letterbox.detect((1..4).map { frame(top = 14, bottom = 15, seed = it) }, 16f / 9f)
        assertEquals(15 / 108f, crop.bottom, 0.001f)
    }

    @Test fun `a hairline is not worth another shape`() {
        // 1.85:1 in 16:9: 2 rows of 108 each side.
        assertFalse(Letterbox.detect((1..4).map { frame(top = 2, bottom = 2, seed = it) }).crops)
    }

    @Test fun `the panel takes the picture's shape only in auto`() {
        val crop = FrameCrop(0f, 0.128f, 0f, 0.128f)
        val (w, h) = AspectFit.panelSize(AspectMode.AUTO, 16f / 9f, 1.6f, 0.9f, crop)
        assertEquals(1.6f, w, 0.001f)
        // 2.39:1 across the window's width: 1.6 m by 0.67 m.
        assertEquals(1.6f / (16f / 9f / 0.744f), h, 0.005f)
        // A chosen 16:9 shows the frame as stored, bars and all.
        assertEquals(1.6f to 0.9f, AspectFit.panelSize(AspectMode.R_16_9, 16f / 9f, 1.6f, 0.9f, crop))
    }
}
