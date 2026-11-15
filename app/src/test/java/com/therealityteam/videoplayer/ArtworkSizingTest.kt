package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.ui.library.reductionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that decides what a poster costs in memory: it should cost what it is shown at. */
class ArtworkSizingTest {

    @Test fun `a poster lands on the width the card asked for`() {
        // The two shapes a real library is full of: a TMDB w500 and a Bangumi cover.
        assertEquals(400, reductionFor(srcWidth = 500, reqWidthPx = 400).targetWidth)
        assertEquals(400, reductionFor(srcWidth = 2000, reqWidthPx = 400).targetWidth)
    }

    @Test fun `sub-sampling still does most of the work`() {
        // The decoder should not be asked to scale 2000 to 400 in one step — it reads a quarter of
        // the pixels first, then finishes the reduction.
        val big = reductionFor(srcWidth = 2000, reqWidthPx = 400)
        assertEquals(4, big.sample)
        assertTrue("sampled width stays above the target", 2000 / big.sample > big.targetWidth)
    }

    @Test fun `a poster smaller than the card is left alone`() {
        val small = reductionFor(srcWidth = 300, reqWidthPx = 400)
        assertEquals(1, small.sample)
        assertEquals(300, small.targetWidth)
    }

    @Test fun `an exact fit is not rescaled`() {
        assertEquals(Pair(1, 400), reductionFor(400, 400).let { it.sample to it.targetWidth })
    }

    @Test fun `a nonsense request does not divide by zero`() {
        assertEquals(500, reductionFor(srcWidth = 500, reqWidthPx = 0).targetWidth)
        assertEquals(1, reductionFor(srcWidth = 500, reqWidthPx = 0).sample)
    }

    @Test
    fun `a card asks for the width it is drawn at, in steps`() {
        // Measured once on one headset, 400 was right there and wrong elsewhere: the grid sizes its
        // cells to the window, so the same 160.dp minimum is ~320px at 2x and ~480px at 3x.
        assertEquals(320, com.therealityteam.videoplayer.ui.library.posterRequestPx(320))
        assertEquals(512, com.therealityteam.videoplayer.ui.library.posterRequestPx(480))
        // Neighbouring columns differ by a pixel once the grid shares out the remainder; they must
        // still ask the same question, or every column decodes and caches its own copy.
        assertEquals(
            com.therealityteam.videoplayer.ui.library.posterRequestPx(331),
            com.therealityteam.videoplayer.ui.library.posterRequestPx(333),
        )
        // …and a card that has not been measured yet still gets something sensible.
        assertEquals(400, com.therealityteam.videoplayer.ui.library.posterRequestPx(0))
        assertEquals(400, com.therealityteam.videoplayer.ui.library.posterRequestPx(Int.MAX_VALUE))
        assertEquals(1024, com.therealityteam.videoplayer.ui.library.posterRequestPx(4000))
    }
}
