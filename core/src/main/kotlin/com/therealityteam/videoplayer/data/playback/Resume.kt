package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.PlaybackProgress

/**
 * Where a film opens, and whether to say so. Pure so the rule is unit-tested: a watched file
 * starts over; a file left off past [PROMPT_AFTER_MS] resumes there and the player says
 * "从 12:34 继续" with a way back to the start; anything earlier just starts from the top.
 */
object Resume {
    /** Below this, a resume point is noise — start from the beginning without a word. */
    const val PROMPT_AFTER_MS = 30_000L

    data class Start(val positionMs: Long, val prompt: Boolean)

    fun decide(progress: PlaybackProgress, override: Long?): Start = when {
        override != null -> Start(override, prompt = false)
        progress.watched -> Start(0L, prompt = false)
        progress.positionMs >= PROMPT_AFTER_MS -> Start(progress.positionMs, prompt = true)
        else -> Start(0L, prompt = false)
    }
}
