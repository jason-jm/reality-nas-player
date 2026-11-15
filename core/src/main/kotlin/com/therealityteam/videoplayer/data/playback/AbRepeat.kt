package com.therealityteam.videoplayer.data.playback

/**
 * A–B repeat: loop the segment between two marks, the way desktop players (Movist, IINA,
 * PotPlayer) do it. Pure state math so it can be unit-tested without a player; the ViewModel
 * holds the marks and drives seeks from [enforce].
 *
 * One button cycles the marks: first tap sets A at the current position, second sets B, a
 * third clears both. B must sit after A — a "B" tapped at or before A instead re-drops A there.
 */
object AbRepeat {

    /** The three states the cycle button reflects. */
    enum class State { IDLE, A_SET, LOOPING }

    fun state(a: Long?, b: Long?): State = when {
        a != null && b != null -> State.LOOPING
        a != null -> State.A_SET
        else -> State.IDLE
    }

    /** Next (a, b) after a tap at [pos]. */
    fun cycle(a: Long?, b: Long?, pos: Long): Pair<Long?, Long?> = when {
        a == null -> pos to null                       // set A
        b == null -> if (pos > a) a to pos else pos to null // set B (must be after A) else move A
        else -> null to null                            // clear
    }

    /**
     * The position to seek to this tick, or null to keep playing: once looping and playback
     * has reached (or passed) B, jump back to A.
     */
    fun enforce(a: Long?, b: Long?, pos: Long): Long? =
        if (a != null && b != null && pos >= b) a else null
}
