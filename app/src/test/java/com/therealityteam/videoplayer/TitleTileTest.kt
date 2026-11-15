package com.therealityteam.videoplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.therealityteam.videoplayer.ui.library.titleGradient
import com.therealityteam.videoplayer.ui.library.titleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover a title gets when no database gave it one.
 *
 * Three things have to hold for a wall of these to look composed rather than accidental: the same
 * title always gets the same cover, different titles rarely get the same one, and every one of them
 * is dark enough to set white text on.
 */
class TitleTileTest {

    private val realTitles = listOf(
        "2002 Sound Horizon Thanatos 2nd CD Story",
        "2003 Sound Horizon Pico Magic CD Pleasure",
        "阿基拉",
        "攻壳机动队",
        "Ghost in the Shell",
        "进击的巨人",
        "00 Footage",
        "01-Pick",
        "1917",
        "白色相簿2",
        "NHK映像的世纪",
        "[NHK][纪录片]明治",
    )

    @Test fun `a title always gets the same cover`() {
        realTitles.forEach { assertEquals(it, titleGradient(it), titleGradient(it)) }
    }

    @Test fun `titles that look alike do not get covers that look alike`() {
        // These two sit next to each other on a real wall and differ only in the middle.
        val a = titleGradient("2002 Sound Horizon Thanatos 2nd CD Story")
        val b = titleGradient("2003 Sound Horizon Pico Magic CD Pleasure")
        assertNotEquals(a, b)
    }

    @Test fun `white text is legible on every stop of every cover`() {
        realTitles.forEach { title ->
            titleGradient(title).forEachIndexed { i, c ->
                // WCAG contrast against white; 3:1 is the bar for large text, and every stop clears
                // it, so the name stays readable wherever it happens to fall on the tile.
                val contrast = (1.0 + 0.05) / (c.luminance() + 0.05)
                assertTrue(
                    "stop $i of \"$title\" is too light for white text (contrast %.2f)".format(contrast),
                    contrast >= 3.0,
                )
            }
        }
    }

    @Test fun `a cover gets darker from one stop to the next`() {
        // What makes it read as lit rather than as a flat colour with noise in it.
        realTitles.forEach { title ->
            val l = titleGradient(title).map { it.luminance() }
            assertTrue("\"$title\" does not darken: $l", l[0] > l[1] && l[1] > l[2])
        }
    }

    @Test fun `a long name is set smaller than a short one`() {
        assertTrue(titleSize("1917").value > titleSize("Ghost in the Shell").value)
        assertTrue(
            titleSize("Ghost in the Shell").value >
                titleSize("2002 Sound Horizon Thanatos 2nd CD Story").value,
        )
    }

    @Test fun `a CJK name is measured by the room it takes, not by its length`() {
        // A CJK character is about twice the width of a Latin one, so five of them want the tile
        // ten Latin ones want. Counted by length instead, 攻壳机动队 would be set a step larger than
        // it fits.
        assertEquals(titleSize("攻壳机动队"), titleSize("0123456789"))
        assertEquals(titleSize("进击的巨人 第二季"), titleSize("0123456789012345"))
    }

    @Test fun `an empty title does not blow up`() {
        assertEquals(3, titleGradient("").size)
        assertTrue(titleSize("").value > 0f)
    }
}
