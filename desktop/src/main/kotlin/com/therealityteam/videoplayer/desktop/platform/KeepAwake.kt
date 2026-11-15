package com.therealityteam.videoplayer.desktop.platform

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.therealityteam.videoplayer.util.Log
import java.util.concurrent.Executors

/**
 * The screen stays on while a video plays, as the headset's does. mpv on its own does this; mpv
 * inside an app (libmpv's render API, which mediamp uses) leaves it to the app, and without it a
 * film goes dark at the system's idle timeout — a few minutes on a laptop's battery.
 *
 * macOS: an IOKit power assertion against idle display sleep, the kind `pmset -g assertions`
 * lists under the app's name. Windows: SetThreadExecutionState, always from one thread of ours,
 * since the state belongs to the thread that set it. Elsewhere nothing, yet.
 */
object KeepAwake {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-keep-awake").apply { isDaemon = true } }
    private val backend: Backend? = runCatching {
        when {
            Host.isMac -> MacAssertion()
            Host.isWindows -> WindowsExecutionState()
            else -> null
        }
    }.onFailure { Log.w(TAG, "no way to keep the screen on here", it) }.getOrNull()

    @Volatile private var held = false

    /** Hold the screen on, or let it go; repeated calls with the same answer do nothing. */
    fun hold(on: Boolean) {
        val b = backend ?: return
        thread.execute {
            if (on == held) return@execute
            runCatching { if (on) b.acquire() else b.release() }
                .onSuccess { held = on }
                .onFailure { Log.w(TAG, "keep-awake ${if (on) "hold" else "release"} failed", it) }
        }
    }

    /** Whether the screen is being held on (for checks). */
    val isHeld: Boolean get() = held

    /** Wait until the calls made so far have been carried out (for checks). */
    internal fun settle() {
        thread.submit {}.get()
    }

    private interface Backend {
        fun acquire()
        fun release()
    }

    private class MacAssertion : Backend {
        private val iokit = NativeLibrary.getInstance("/System/Library/Frameworks/IOKit.framework/IOKit")
        private val cf = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        private var id: Int = 0

        private fun cfString(s: String): Pointer =
            cf.getFunction("CFStringCreateWithCString").invokePointer(arrayOf(Pointer.NULL, s, K_CF_STRING_ENCODING_UTF8))

        override fun acquire() {
            if (id != 0) return
            val type = cfString("PreventUserIdleDisplaySleep")
            val name = cfString("$PRODUCT_NAME is playing a video")
            try {
                val out = IntByReference()
                val r = iokit.getFunction("IOPMAssertionCreateWithName").invokeInt(arrayOf(type, K_IOPM_ASSERTION_LEVEL_ON, name, out))
                check(r == 0) { "IOPMAssertionCreateWithName returned $r" }
                id = out.value
            } finally {
                cf.getFunction("CFRelease").invokeVoid(arrayOf(type))
                cf.getFunction("CFRelease").invokeVoid(arrayOf(name))
            }
        }

        override fun release() {
            if (id == 0) return
            iokit.getFunction("IOPMAssertionRelease").invokeInt(arrayOf(id))
            id = 0
        }

        private companion object {
            const val K_CF_STRING_ENCODING_UTF8 = 0x08000100
            const val K_IOPM_ASSERTION_LEVEL_ON = 255
        }
    }

    private class WindowsExecutionState : Backend {
        private val kernel32 = NativeLibrary.getInstance("kernel32")

        private fun set(flags: Int) {
            kernel32.getFunction("SetThreadExecutionState").invokeInt(arrayOf(flags))
        }

        override fun acquire() = set(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)

        override fun release() = set(ES_CONTINUOUS)

        private companion object {
            const val ES_CONTINUOUS = 0x80000000.toInt()
            const val ES_SYSTEM_REQUIRED = 0x00000001
            const val ES_DISPLAY_REQUIRED = 0x00000002
        }
    }

    private const val TAG = "AuraAwake"
}
