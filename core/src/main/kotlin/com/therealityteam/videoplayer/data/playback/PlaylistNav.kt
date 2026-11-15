package com.therealityteam.videoplayer.data.playback

import kotlin.random.Random

/** Auto-advance behaviour for the playing queue — Movist's 循环 / 随机 modes. */
enum class PlaylistMode(val label: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ALL("列表循环"),
    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放"),
}

/**
 * Pure queue navigation: given the current index and queue size, which index plays next.
 * Indices are 0-based; a returned -1 means "nothing to play" (stop at the end, or a disabled
 * skip button). No player or SDK deps, so every rule is unit-testable in isolation.
 */
object PlaylistNav {

    /** Index to auto-play when the current item ends, or -1 to stop. [REPEAT_ONE] returns itself. */
    fun onCompleted(mode: PlaylistMode, current: Int, size: Int, random: Random = Random.Default): Int {
        if (current !in 0 until size) return -1
        return when (mode) {
            PlaylistMode.SEQUENTIAL -> if (current < size - 1) current + 1 else -1
            PlaylistMode.REPEAT_ALL -> (current + 1) % size
            PlaylistMode.REPEAT_ONE -> current
            PlaylistMode.SHUFFLE -> randomOther(size, current, random)
        }
    }

    /** Index for an explicit "next" tap. Repeat-one still steps forward (never re-plays itself). */
    fun manualNext(mode: PlaylistMode, current: Int, size: Int, random: Random = Random.Default): Int {
        if (current !in 0 until size) return -1
        return when (mode) {
            PlaylistMode.SHUFFLE -> randomOther(size, current, random)
            PlaylistMode.REPEAT_ALL -> (current + 1) % size
            else -> if (current < size - 1) current + 1 else -1
        }
    }

    /** Index for an explicit "previous" tap. */
    fun manualPrev(mode: PlaylistMode, current: Int, size: Int, random: Random = Random.Default): Int {
        if (current !in 0 until size) return -1
        return when (mode) {
            PlaylistMode.SHUFFLE -> randomOther(size, current, random)
            PlaylistMode.REPEAT_ALL -> (current - 1 + size) % size
            else -> if (current > 0) current - 1 else -1
        }
    }

    /** Whether the "next" button should be enabled (wrapping modes always can, if size > 1). */
    fun hasNext(mode: PlaylistMode, current: Int, size: Int): Boolean {
        if (current !in 0 until size || size <= 1) return false
        return when (mode) {
            PlaylistMode.REPEAT_ALL, PlaylistMode.SHUFFLE -> true
            else -> current < size - 1
        }
    }

    /** Whether the "previous" button should be enabled. */
    fun hasPrev(mode: PlaylistMode, current: Int, size: Int): Boolean {
        if (current !in 0 until size || size <= 1) return false
        return when (mode) {
            PlaylistMode.REPEAT_ALL, PlaylistMode.SHUFFLE -> true
            else -> current > 0
        }
    }

    /** A uniformly-chosen index in [0,size) that isn't [current]; [current] itself when size < 2. */
    private fun randomOther(size: Int, current: Int, random: Random): Int {
        if (size <= 1) return current
        val r = random.nextInt(size - 1)
        return if (r >= current) r + 1 else r
    }
}
