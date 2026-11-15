package com.therealityteam.videoplayer.data.playback

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 全景声 where mpv plays the sound — the desktop, and the headset's mpv (Media3's goes through
 * AmbisonicDecoder): a file's sound field (ambisonics, ACN / SN3D — [AmbisonicAudio]) heard as two
 * microphones pointing left and right of where the view looks. mpv runs none of our code on its sound,
 * so the decode is a libavfilter graph in mpv's `af`; the two gains that turn it with the view are
 * volume filters, changed by `af-command` — the graph is never rebuilt, which would restart the sound.
 *
 * Each ear hears (W ± k·S) / (1 + k), S being the field's left-right axis from where the view faces:
 * S = cos(yaw)·Y + sin(yaw)·X, yaw to the right. k = 1 would be a cardioid, silent to the far ear;
 * [WIDTH] leaves it some of the other side, as a head does (about 12 dB apart for a sound at one side).
 * Height is left out — a view looks round the field more than up through it — and higher orders are heard
 * through their first. A head-locked pair (narration, music) goes to the ears as it is.
 */
object SoundFieldFilter {

    /** mpv's label for the filter, for `af-command`. */
    const val LABEL = "aura_field"

    /** How much of the left-right axis each ear takes beside the whole field (W): 1 is a cardioid. */
    const val WIDTH = 0.6

    /** libavfilter's names for the two turning gains: on Y (left) and on X (ahead). */
    const val ON_Y = "volume@aura_y"
    const val ON_X = "volume@aura_x"

    /** The graph for [audio], or null when its channels cannot hold its components. */
    fun graph(audio: AmbisonicAudio): String? {
        if (audio.order < 1 || audio.channels < audio.components) return null
        fun channel(k: Int) = audio.channelMap?.getOrNull(k) ?: k
        val w = channel(0)
        val y = channel(1)
        val x = channel(3)
        val gain = f(1 / (1 + WIDTH))
        val locked = if (audio.headLocked && audio.channels >= audio.components + 2) audio.components else null
        val g = StringBuilder()
        g.append(if (locked != null) "asplit=4[fw][fy][fx][fh];" else "asplit=3[fw][fy][fx];")
        g.append("[fw]pan=mono|c0=c$w,asplit[w1][w2];")
        g.append("[fy]pan=mono|c0=c$y,$ON_Y=1:precision=float[y];")
        g.append("[fx]pan=mono|c0=c$x,$ON_X=0:precision=float[x];")
        g.append("[y][x]amix=inputs=2:normalize=0,volume=${f(WIDTH)}:precision=float,asplit[s1][s2];")
        g.append("[s2]volume=-1:precision=float[sn];")
        g.append("[w1][s1]amix=inputs=2:normalize=0,volume=$gain:precision=float[l];")
        g.append("[w2][sn]amix=inputs=2:normalize=0,volume=$gain:precision=float[r];")
        if (locked != null) {
            g.append("[fh]pan=stereo|c0=c$locked|c1=c${locked + 1}[hl];")
            g.append("[l][r]join=inputs=2:channel_layout=stereo[field];[field][hl]amix=inputs=2:normalize=0")
        } else {
            g.append("[l][r]join=inputs=2:channel_layout=stereo")
        }
        return g.toString()
    }

    /** mpv's `af` value for [audio]'s graph, labelled for `af-command`. */
    fun af(audio: AmbisonicAudio): String? = graph(audio)?.let { "@$LABEL:lavfi=[$it]" }

    /** The gains on Y and on X for a view [yaw] radians to the right of the film's front. */
    fun turn(yaw: Double): Pair<Double, Double> = cos(yaw) to sin(yaw)

    fun f(d: Double): String = String.format(Locale.ROOT, "%.6f", d)
}
