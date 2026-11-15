package com.therealityteam.videoplayer.platform

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash notes for an app with no crash-reporting backend: an uncaught exception's stack is written
 * to files/aura/crash/ before the process dies, so a user reporting "it crashed" has something to
 * attach (设置 → 关于 shows when the last one happened, and where the file is). Nothing leaves the
 * device — see the privacy page.
 */
object CrashLog {

    private const val KEEP = 5

    fun dir(context: Context): File = File(context.filesDir, "aura/crash")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val d = dir(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(d, "crash-$stamp.txt").writeText(
            buildString {
                appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("thread: ${thread.name}")
                appendLine(throwable.stackTraceToString())
            },
        )
        // Oldest beyond the cap go; a crash loop must not fill the disk.
        d.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** The newest crash file, if any — the About page shows when it happened. */
    fun latest(context: Context): File? =
        dir(context).listFiles()?.filter { it.name.startsWith("crash-") }?.maxByOrNull { it.name }
}
