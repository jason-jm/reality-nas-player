package com.therealityteam.videoplayer.desktop.platform

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.therealityteam.videoplayer.util.Log

/**
 * Where the computer's sound goes: the default output device, and whether that is its own
 * speakers. Headphones unplugged, AirPods put away, a TV's cable pulled — the sound moves to the
 * built-in speakers, and a film should pause rather than carry on out loud, as it does on the
 * headset (Android's "becoming noisy").
 *
 * macOS reads CoreAudio: the default output's transport (built in, Bluetooth, USB, HDMI…) and,
 * for the built-in output, its data source (internal speakers or the headphone jack — the same
 * device either way). Elsewhere the route is not known and nothing pauses.
 */
object AudioRoute {

    /** One reading: the device, and whether it is the computer's own speakers. */
    data class Route(val device: Int, val transport: Int, val source: Int?) {
        /** The built-in output with no headphones in its jack. */
        val builtInSpeakers: Boolean get() = transport == TRANSPORT_BUILT_IN && source != SOURCE_HEADPHONES

        override fun toString(): String = "device $device · ${fourCc(transport)}" + (source?.let { " · ${fourCc(it)}" } ?: "")
    }

    /** Sound that was going somewhere private now comes from the computer's own speakers. */
    fun becameNoisy(before: Route?, now: Route?): Boolean =
        before != null && now != null && !before.builtInSpeakers && now.builtInSpeakers

    /** The route now, or null where it cannot be read. */
    fun current(): Route? = if (Host.isMac) mac?.let { runCatching { it.read() }.onFailure { e -> Log.w(TAG, "no audio route", e) }.getOrNull() } else null

    private val mac: MacCoreAudio? by lazy { runCatching { MacCoreAudio() }.onFailure { Log.w(TAG, "CoreAudio unavailable", it) }.getOrNull() }

    private class MacCoreAudio {
        private val ca = NativeLibrary.getInstance("/System/Library/Frameworks/CoreAudio.framework/CoreAudio")
        private val get = ca.getFunction("AudioObjectGetPropertyData")

        /** A UInt32 property of [obj], or null when it has none. */
        private fun u32(obj: Int, selector: Int, scope: Int): Int? {
            val address = Memory(12).apply { setInt(0, selector); setInt(4, scope); setInt(8, ELEMENT_MAIN) }
            val size = Memory(4).apply { setInt(0, 4) }
            val out = Memory(4)
            val status = get.invokeInt(arrayOf(obj, address, 0, null, size, out))
            return if (status == 0) out.getInt(0) else null
        }

        fun read(): Route? {
            val device = u32(SYSTEM_OBJECT, DEFAULT_OUTPUT_DEVICE, SCOPE_GLOBAL)?.takeIf { it != 0 } ?: return null
            val transport = u32(device, TRANSPORT_TYPE, SCOPE_GLOBAL) ?: 0
            val source = if (transport == TRANSPORT_BUILT_IN) u32(device, DATA_SOURCE, SCOPE_OUTPUT) else null
            return Route(device, transport, source)
        }
    }

    private fun fourCc(v: Int): String =
        String(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()), Charsets.US_ASCII)

    private fun code(s: String): Int = s.fold(0) { acc, ch -> (acc shl 8) or ch.code }

    private const val SYSTEM_OBJECT = 1
    private const val ELEMENT_MAIN = 0
    private val SCOPE_GLOBAL = code("glob")
    private val SCOPE_OUTPUT = code("outp")
    private val DEFAULT_OUTPUT_DEVICE = code("dOut")
    private val TRANSPORT_TYPE = code("tran")
    private val DATA_SOURCE = code("ssrc")
    internal val TRANSPORT_BUILT_IN = code("bltn")
    internal val TRANSPORT_BLUETOOTH = code("blue")
    internal val SOURCE_SPEAKERS = code("ispk")
    internal val SOURCE_HEADPHONES = code("hdpn")
    private const val TAG = "AuraAudioRoute"
}
