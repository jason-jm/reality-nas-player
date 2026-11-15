package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.AspectFit
import com.therealityteam.videoplayer.data.playback.AspectMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AspectFitTest {

    // A 16:9 window plane, the app's fixed video window.
    private val winW = 1.60f
    private val winH = 0.90f
    private val eps = 1e-4f

    private fun assertSize(expW: Float, expH: Float, actual: Pair<Float, Float>) {
        assertEquals(expW, actual.first, eps)
        assertEquals(expH, actual.second, eps)
    }

    @Test
    fun sixteenNineSourceFillsTheWholeWindow() {
        // AUTO on a 16:9 source == the old full-window behaviour, no letterbox.
        assertSize(winW, winH, AspectFit.panelSize(AspectMode.AUTO, 16f / 9f, winW, winH))
    }

    @Test
    fun wideScopeSourceLetterboxesTopAndBottom() {
        // 2.35:1 is wider than 16:9 → full width, reduced height.
        val (w, h) = AspectFit.panelSize(AspectMode.AUTO, 2.35f, winW, winH)
        assertEquals(winW, w, eps)
        assertEquals(winW / 2.35f, h, eps)
        assert(h < winH) { "scope content must be shorter than the window" }
    }

    @Test
    fun academySourcePillarboxesSides() {
        // 4:3 is taller than 16:9 → full height, reduced width.
        val (w, h) = AspectFit.panelSize(AspectMode.AUTO, 4f / 3f, winW, winH)
        assertEquals(winH, h, eps)
        assertEquals(winH * 4f / 3f, w, eps)
        assert(w < winW) { "4:3 content must be narrower than the window" }
    }

    @Test
    fun fillAlwaysUsesTheWholePlane() {
        assertSize(winW, winH, AspectFit.panelSize(AspectMode.FILL, 2.35f, winW, winH))
        assertSize(winW, winH, AspectFit.panelSize(AspectMode.FILL, 4f / 3f, winW, winH))
    }

    @Test
    fun unknownAutoAspectFallsBackToFullWindow() {
        // Before the first frame's size arrives, don't guess — fill the window.
        assertSize(winW, winH, AspectFit.panelSize(AspectMode.AUTO, 0f, winW, winH))
    }

    @Test
    fun forcedRatiosIgnoreTheSourceAspect() {
        // Forcing 4:3 on a 16:9 source pillarboxes it regardless of what's stored.
        val (w, h) = AspectFit.panelSize(AspectMode.R_4_3, 16f / 9f, winW, winH)
        assertEquals(winH, h, eps)
        assertEquals(winH * 4f / 3f, w, eps)
    }

    @Test
    fun oneEyesShapeForPackedFrames() {
        val sbs = com.therealityteam.videoplayer.data.model.StereoMode.SBS
        val ou = com.therealityteam.videoplayer.data.model.StereoMode.OU
        // Full side by side (3840×1080): each eye 16:9. Half (1920×1080): each shown 16:9 as well.
        org.junit.Assert.assertEquals(16f / 9f, AspectFit.eyeAspect(sbs, 3840f / 1080f), 1e-4f)
        org.junit.Assert.assertEquals(16f / 9f, AspectFit.eyeAspect(sbs, 1920f / 1080f), 1e-4f)
        // Full over-under (1920×2160) and half (1920×1080).
        org.junit.Assert.assertEquals(16f / 9f, AspectFit.eyeAspect(ou, 1920f / 2160f), 1e-4f)
        org.junit.Assert.assertEquals(16f / 9f, AspectFit.eyeAspect(ou, 1920f / 1080f), 1e-4f)
        // One picture, or a shape not yet known: as it is.
        org.junit.Assert.assertEquals(2.39f, AspectFit.eyeAspect(null, 2.39f), 1e-4f)
        org.junit.Assert.assertEquals(0f, AspectFit.eyeAspect(sbs, 0f), 1e-4f)
    }
}
