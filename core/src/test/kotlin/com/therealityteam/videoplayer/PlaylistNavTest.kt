package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.PlaylistMode
import com.therealityteam.videoplayer.data.playback.PlaylistNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistNavTest {

    @Test
    fun sequentialStopsAtTheEnd() {
        assertEquals(3, PlaylistNav.onCompleted(PlaylistMode.SEQUENTIAL, 2, 5))
        assertEquals(-1, PlaylistNav.onCompleted(PlaylistMode.SEQUENTIAL, 4, 5))
        assertFalse(PlaylistNav.hasNext(PlaylistMode.SEQUENTIAL, 4, 5))
        assertTrue(PlaylistNav.hasPrev(PlaylistMode.SEQUENTIAL, 4, 5))
    }

    @Test
    fun repeatAllWrapsBothWays() {
        assertEquals(0, PlaylistNav.onCompleted(PlaylistMode.REPEAT_ALL, 4, 5))
        assertEquals(0, PlaylistNav.manualNext(PlaylistMode.REPEAT_ALL, 4, 5))
        assertEquals(4, PlaylistNav.manualPrev(PlaylistMode.REPEAT_ALL, 0, 5))
        // Skip buttons never dead-end while wrapping.
        assertTrue(PlaylistNav.hasNext(PlaylistMode.REPEAT_ALL, 4, 5))
        assertTrue(PlaylistNav.hasPrev(PlaylistMode.REPEAT_ALL, 0, 5))
    }

    @Test
    fun repeatOneReplaysItselfButSkipStillMovesOn() {
        assertEquals(2, PlaylistNav.onCompleted(PlaylistMode.REPEAT_ONE, 2, 5))
        // An explicit Next in repeat-one steps forward, it does not re-play the same item.
        assertEquals(3, PlaylistNav.manualNext(PlaylistMode.REPEAT_ONE, 2, 5))
        assertEquals(1, PlaylistNav.manualPrev(PlaylistMode.REPEAT_ONE, 2, 5))
    }

    @Test
    fun shuffleAlwaysPicksADifferentItem() {
        val rng = Random(42)
        repeat(500) {
            val next = PlaylistNav.onCompleted(PlaylistMode.SHUFFLE, 3, 6, rng)
            assertTrue("in range", next in 0 until 6)
            assertTrue("never repeats the current item", next != 3)
        }
    }

    @Test
    fun shuffleIsUniformOverTheOtherItems() {
        val rng = Random(1)
        val counts = IntArray(4)
        repeat(4000) { counts[PlaylistNav.onCompleted(PlaylistMode.SHUFFLE, 1, 4, rng)]++ }
        assertEquals("current item never chosen", 0, counts[1])
        // The other three should each get roughly a third; loose bound guards against bias.
        listOf(0, 2, 3).forEach { assertTrue("index $it ~uniform", counts[it] in 1000..1670) }
    }

    @Test
    fun singleItemQueueHasNoSkipsButRepeatOneStillLoops() {
        PlaylistMode.values().forEach { mode ->
            assertFalse(PlaylistNav.hasNext(mode, 0, 1))
            assertFalse(PlaylistNav.hasPrev(mode, 0, 1))
        }
        assertEquals(0, PlaylistNav.onCompleted(PlaylistMode.REPEAT_ONE, 0, 1))
        assertEquals(0, PlaylistNav.onCompleted(PlaylistMode.REPEAT_ALL, 0, 1))
        assertEquals(-1, PlaylistNav.onCompleted(PlaylistMode.SEQUENTIAL, 0, 1))
    }

    @Test
    fun anUnknownCurrentIndexIsSafe() {
        assertEquals(-1, PlaylistNav.onCompleted(PlaylistMode.REPEAT_ALL, -1, 5))
        assertEquals(-1, PlaylistNav.manualNext(PlaylistMode.SHUFFLE, -1, 5))
        assertFalse(PlaylistNav.hasNext(PlaylistMode.REPEAT_ALL, -1, 5))
    }
}
