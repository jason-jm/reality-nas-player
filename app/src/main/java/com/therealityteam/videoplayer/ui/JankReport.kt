package com.therealityteam.videoplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How even a run of frames was — the gaps between the frames Compose drew while something moved — said
 * in one log line (AuraJank): the usual gap, the slow tail, and how many frames came late. A frame is
 * late when its gap is past one and a half usual ones (a vsync missed), long when past two and a half.
 */
object JankReport {

    fun log(what: String, gapsNs: List<Long>) {
        if (gapsNs.size < 3) {
            android.util.Log.i(TAG, "$what: too few frames (${gapsNs.size})")
            return
        }
        val ms = gapsNs.map { it / 1e6 }.sorted()
        fun at(q: Double) = ms[((ms.size - 1) * q).toInt()]
        val usual = at(0.5)
        val late = ms.count { it > usual * 1.5 }
        val long = ms.count { it > usual * 2.5 }
        // The time lost to lateness: what the late frames took past one usual frame each.
        val lost = ms.filter { it > usual * 1.5 }.sumOf { it - usual }
        // A point in every language: read by scripts, and "11,1" in Russian slipped past them.
        android.util.Log.i(
            TAG,
            "$what: ${ms.size} frames, usual %.1f ms (%.0f fps), p95 %.1f, p99 %.1f, worst %.1f ms; late %d (%.1f%%), long %d, %.0f ms lost"
                .format(java.util.Locale.ROOT, usual, 1000 / usual, at(0.95), at(0.99), ms.last(), late, 100.0 * late / ms.size, long, lost),
        )
    }

    private const val TAG = "AuraJank"
}

/**
 * Automation (`-e aura.jank <ms>[:<what>]`): every frame of the window this sits in timed for that long
 * from the request, and said as [JankReport] says it — a menu opening, a page turning, whatever the same
 * launch asks for. The library's and the player's windows share one frame clock, so one of them reports. The headset's
 * spatial panels give `dumpsys gfxinfo` nothing.
 */
private val jankTaken = java.util.concurrent.atomic.AtomicReference<Pair<Long, String>?>(null)

@Composable
fun JankWatch(window: String) {
    val request by com.therealityteam.videoplayer.platform.DeepLink.jankRequest.collectAsState()
    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        // The windows share one frame clock: the first to take the request times it, for all of them. (Taken
        // aside, not cleared: clearing the key this effect runs on would cancel it.)
        if (jankTaken.getAndSet(asked) === asked) return@LaunchedEffect
        val (ms, what) = asked
        val gaps = ArrayList<Long>()
        withTimeoutOrNull(ms + 1_000L) {
            val end = System.nanoTime() + ms * 1_000_000L
            var last = withFrameNanos { it }
            while (System.nanoTime() < end) {
                val now = withFrameNanos { it }
                gaps += now - last
                last = now
            }
        }
        JankReport.log("$window window, $what (${ms} ms)", gaps)
    }
}

