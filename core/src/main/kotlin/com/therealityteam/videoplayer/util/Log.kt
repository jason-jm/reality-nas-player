package com.therealityteam.videoplayer.util

/**
 * android.util.Log's shape without Android, so the shared code logs one way on every platform.
 * The PICO app points [sink] at logcat, the desktop app at its log file; unset — the JVM tests —
 * a line goes nowhere, as it did when the tests ran against Android's stubs.
 */
object Log {
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    fun interface Sink {
        fun write(priority: Int, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    fun d(tag: String, message: String) = write(DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write(WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write(ERROR, tag, message, error)

    private fun write(priority: Int, tag: String, message: String, error: Throwable?): Int {
        sink?.write(priority, tag, message, error)
        return 0
    }
}

/** Milliseconds on a clock that only runs forward — for timing work, never for dates. */
fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
