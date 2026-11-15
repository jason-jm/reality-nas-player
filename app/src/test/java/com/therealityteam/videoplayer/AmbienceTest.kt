package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.player.Ambience
import org.junit.Assert.assertEquals
import org.junit.Test

class AmbienceTest {

    private fun frame(w: Int, h: Int, argb: (Int, Int) -> Int) = IntArray(w * h) { argb(it % w, it / w) }

    @Test
    fun `a white film filling the screen lights every zone at the screen's white over the bake's mean`() {
        val f = Ambience.zones(frame(48, 20) { _, _ -> 0xFFFFFFFF.toInt() }, 48, 20, 2.39f, 2.39f)
        f.forEach { assertEquals(1f / Ambience.BAKED_MEAN, it, 1e-3f) }
    }

    @Test
    fun `a narrower film leaves the side zones partly dark`() {
        // 16:9 inside 2.39:1: the film spans 0.128–0.872 of the screen's width; the left third holds 0.205 of it
        val f = Ambience.zones(frame(48, 27) { _, _ -> 0xFFFFFFFF.toInt() }, 48, 27, 16f / 9f, 2.39f)
        val full = 1f / Ambience.BAKED_MEAN
        assertEquals(full * 0.6157f, f[0], 0.01f)
        assertEquals(full, f[3], 0.01f)
        assertEquals(full * 0.6157f, f[6], 0.01f)
    }

    @Test
    fun `a red left and a blue right reach their own zones`() {
        val f = Ambience.zones(frame(48, 20) { x, _ -> if (x < 16) 0xFFFF0000.toInt() else if (x >= 32) 0xFF0000FF.toInt() else 0xFF000000.toInt() },
            48, 20, 2.39f, 2.39f)
        assertEquals(1f / Ambience.BAKED_MEAN, f[0], 1e-3f) // left red
        assertEquals(0f, f[2], 1e-6f) // left blue
        assertEquals(0f, f[3] + f[4] + f[5], 1e-6f) // centre dark
        assertEquals(1f / Ambience.BAKED_MEAN, f[8], 1e-3f) // right blue
    }

    @Test
    fun `a dark frame puts the light out`() {
        Ambience.zones(frame(48, 20) { _, _ -> 0xFF000000.toInt() }, 48, 20, 2.39f, 2.39f).forEach { assertEquals(0f, it, 1e-6f) }
    }
}
