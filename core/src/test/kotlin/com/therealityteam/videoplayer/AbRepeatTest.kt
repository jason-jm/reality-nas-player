package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AbRepeat
import com.therealityteam.videoplayer.data.playback.AbRepeat.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbRepeatTest {

    @Test
    fun cycleGoesIdleToAtoBtoCleared() {
        var (a, b) = null.toL() to null.toL()
        assertEquals(State.IDLE, AbRepeat.state(a, b))

        AbRepeat.cycle(a, b, 1_000).let { a = it.first; b = it.second }
        assertEquals(1_000L, a); assertNull(b)
        assertEquals(State.A_SET, AbRepeat.state(a, b))

        AbRepeat.cycle(a, b, 5_000).let { a = it.first; b = it.second }
        assertEquals(1_000L, a); assertEquals(5_000L, b)
        assertEquals(State.LOOPING, AbRepeat.state(a, b))

        AbRepeat.cycle(a, b, 9_000).let { a = it.first; b = it.second }
        assertNull(a); assertNull(b)
        assertEquals(State.IDLE, AbRepeat.state(a, b))
    }

    @Test
    fun bBeforeAjustMovesA() {
        // A at 5s, then "B" tapped at 2s (earlier) — treat as re-dropping A, not an inverted loop.
        val (a, b) = AbRepeat.cycle(5_000, null, 2_000)
        assertEquals(2_000L, a); assertNull(b)
    }

    @Test
    fun enforceSeeksToAonlyWhenLoopingAndPastB() {
        assertNull("idle never seeks", AbRepeat.enforce(null, null, 9_000))
        assertNull("A-only never seeks", AbRepeat.enforce(1_000, null, 9_000))
        assertNull("before B keeps playing", AbRepeat.enforce(1_000, 5_000, 4_999))
        assertEquals("at/after B jumps to A", 1_000L, AbRepeat.enforce(1_000, 5_000, 5_000))
        assertEquals(1_000L, AbRepeat.enforce(1_000, 5_000, 6_200))
    }

    private fun Nothing?.toL(): Long? = null
}
