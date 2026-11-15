package com.therealityteam.videoplayer.desktop.platform

import java.awt.Taskbar
import java.awt.Window
import kotlin.math.roundToInt

/**
 * A long task's progress on the app's icon — the Dock tile on macOS, the taskbar button on
 * Windows — so a first scan of a big library, or a model downloading, can be left to run behind
 * other windows and checked at a glance. The Dock has no "working, amount unknown", so there it
 * shows only what can be counted.
 */
object DockProgress {
    private val taskbar: Taskbar? = runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else null }.getOrNull()
    private var shown: Int? = null

    /** Whether this system shows progress on the icon at all (for checks). */
    val supported: Boolean
        get() = taskbar?.let { it.isSupported(Taskbar.Feature.PROGRESS_VALUE) || it.isSupported(Taskbar.Feature.PROGRESS_VALUE_WINDOW) } == true

    /**
     * Show [fraction] (0..1) of a task, or that one runs whose size is not known yet
     * ([fraction] null, [running] true), or nothing. Calls that change nothing visible are dropped.
     */
    fun show(window: Window?, fraction: Float?, running: Boolean = fraction != null) {
        val t = taskbar ?: return
        // -1: nothing; -2: running, amount unknown; else a percentage.
        val value = when {
            fraction != null -> (fraction * 100).roundToInt().coerceIn(0, 100)
            running -> -2
            else -> -1
        }
        if (value == shown) return
        shown = value
        runCatching {
            when {
                t.isSupported(Taskbar.Feature.PROGRESS_VALUE) -> t.setProgressValue(if (value >= 0) value else -1)
                window != null && t.isSupported(Taskbar.Feature.PROGRESS_STATE_WINDOW) -> when (value) {
                    -1 -> t.setWindowProgressState(window, Taskbar.State.OFF)
                    -2 -> t.setWindowProgressState(window, Taskbar.State.INDETERMINATE)
                    else -> {
                        t.setWindowProgressState(window, Taskbar.State.NORMAL)
                        t.setWindowProgressValue(window, value)
                    }
                }
            }
        }
    }
}
