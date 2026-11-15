package com.therealityteam.videoplayer.data.playback

import kotlin.math.abs
import kotlin.math.exp

/**
 * Where 沉浸空间's subtitles stand round the viewer: ahead of where the head points, but lazily — a
 * glance aside leaves them be (they would swim with every look round a panorama), a turn takes them
 * along, eased in over a moment, and they stop once they are ahead again. Yaw only: looking up at a
 * sky does not lift the lines off the horizon.
 */
object CaptionFollow {

    data class State(val yaw: Float, val following: Boolean = false)

    /** The lines' yaw after [dt] seconds with the head at [headYaw] (degrees, counter-clockwise). */
    fun step(state: State?, headYaw: Float, dt: Float): State {
        if (state == null) return State(headYaw)
        val gap = wrap(headYaw - state.yaw)
        val following = state.following || abs(gap) > START_DEGREES
        if (!following) return state
        val moved = wrap(state.yaw + gap * (1f - exp(-RATE * dt.coerceAtLeast(0f))))
        val left = wrap(headYaw - moved)
        return State(moved, following = abs(left) > STOP_DEGREES)
    }

    /** [degrees] brought into (−180, 180]. */
    fun wrap(degrees: Float): Float {
        var d = degrees % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** A look this far aside takes the lines along… */
    const val START_DEGREES = 25f
    /** …until they are this near ahead again. */
    const val STOP_DEGREES = 2f
    /** How fast they come round: about a third of a second to cover most of the way. */
    private const val RATE = 6f
}
