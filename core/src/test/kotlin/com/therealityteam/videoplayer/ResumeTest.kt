package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.playback.Resume
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeTest {
    @Test
    fun `an explicit start wins and never prompts`() =
        assertEquals(Resume.Start(1_653_000, false), Resume.decide(PlaybackProgress(positionMs = 900_000), override = 1_653_000))

    @Test
    fun `a watched file starts over`() =
        assertEquals(Resume.Start(0, false), Resume.decide(PlaybackProgress(positionMs = 5_000_000, durationMs = 5_400_000, watched = true), null))

    @Test
    fun `a film left off resumes there with a prompt`() =
        assertEquals(Resume.Start(754_000, true), Resume.decide(PlaybackProgress(positionMs = 754_000, durationMs = 5_400_000), null))

    @Test
    fun `a few seconds in is not worth resuming`() =
        assertEquals(Resume.Start(0, false), Resume.decide(PlaybackProgress(positionMs = 12_000, durationMs = 5_400_000), null))
}
