package com.therealityteam.videoplayer.data.playback

/**
 * The controller's playback language (PRD 7.4): thumbstick left / right step the film back and
 * forward, up / down step the volume, and the trigger held down plays at double speed until it
 * is let go. Pure state math — it is fed per-frame snapshots (or pointer events, in Shared
 * Space) and answers with intents — so the dead zone, the repeat cadence and the hold
 * threshold are unit-tested without a headset.
 */
object ControllerModel {
    /** Deflection past which the stick counts as pushed; below it, drift and rest. */
    const val DEAD_ZONE = 0.5f
    /** After the first step, a held stick repeats at this cadence. */
    const val FIRST_REPEAT_MS = 450L
    const val REPEAT_MS = 250L
    /** A trigger held this long (not a click) is a request for speed. */
    const val HOLD_MS = 400L

    enum class Axis { NONE, LEFT, RIGHT, UP, DOWN }

    enum class Intent { SEEK_BACK, SEEK_FORWARD, VOLUME_UP, VOLUME_DOWN, BOOST_ON, BOOST_OFF }

    data class State(
        val axis: Axis = Axis.NONE,
        val nextRepeatAt: Long = 0L,
        val triggerDownAt: Long? = null,
        val boosting: Boolean = false,
    )

    /** Which way a stick at ([x], [y]) is pushed, if past the dead zone — the larger axis wins. */
    fun axisOf(x: Float, y: Float): Axis = when {
        kotlin.math.abs(x) < DEAD_ZONE && kotlin.math.abs(y) < DEAD_ZONE -> Axis.NONE
        kotlin.math.abs(x) >= kotlin.math.abs(y) -> if (x < 0) Axis.LEFT else Axis.RIGHT
        else -> if (y > 0) Axis.UP else Axis.DOWN
    }

    /** One frame: the stick's deflection, whether the trigger is down, and the clock. */
    fun step(state: State, x: Float, y: Float, triggerDown: Boolean, nowMs: Long): Pair<State, List<Intent>> {
        val intents = mutableListOf<Intent>()
        var next = state

        val axis = axisOf(x, y)
        when {
            axis == Axis.NONE -> next = next.copy(axis = Axis.NONE, nextRepeatAt = 0L)
            axis != state.axis -> {                                   // a fresh push: step at once
                intents += axis.intent()
                next = next.copy(axis = axis, nextRepeatAt = nowMs + FIRST_REPEAT_MS)
            }
            nowMs >= state.nextRepeatAt -> {                          // held: keep stepping
                intents += axis.intent()
                next = next.copy(nextRepeatAt = nowMs + REPEAT_MS)
            }
        }

        when {
            triggerDown && state.triggerDownAt == null -> next = next.copy(triggerDownAt = nowMs)
            triggerDown && !state.boosting && nowMs - (state.triggerDownAt ?: nowMs) >= HOLD_MS -> {
                intents += Intent.BOOST_ON
                next = next.copy(boosting = true)
            }
            !triggerDown && state.triggerDownAt != null -> {
                if (state.boosting) intents += Intent.BOOST_OFF
                next = next.copy(triggerDownAt = null, boosting = false)
            }
        }
        return next to intents
    }

    private fun Axis.intent(): Intent = when (this) {
        Axis.LEFT -> Intent.SEEK_BACK
        Axis.RIGHT -> Intent.SEEK_FORWARD
        Axis.UP -> Intent.VOLUME_UP
        Axis.DOWN -> Intent.VOLUME_DOWN
        Axis.NONE -> error("no intent at rest")
    }
}
