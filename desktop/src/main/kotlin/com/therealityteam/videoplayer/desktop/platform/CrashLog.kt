package com.therealityteam.videoplayer.desktop.platform

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import com.therealityteam.videoplayer.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The headset's crash notes, on the desktop: an uncaught exception's stack goes to the log folder's
 * crash/ before the program dies, so "it crashed" comes with something to attach — Settings › About
 * says when the last one happened and shows the file. Nothing is sent anywhere.
 */
object CrashLog {
    private const val KEEP = 5

    val dir: File get() = File(Host.logDir, "crash")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(dir, thread, throwable) }
            runCatching { Log.e("AuraCrash", "uncaught on ${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    internal fun write(into: File, thread: Thread, throwable: Throwable): File {
        val d = into.apply { mkdirs() }
        val now = LocalDateTime.now()
        val f = File(d, "crash-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt")
        f.writeText(
            buildString {
                appendLine("time: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                appendLine("app: $PRODUCT_NAME ${Host.appVersion}")
                appendLine("os: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
                appendLine("java: ${System.getProperty("java.version")}")
                appendLine("thread: ${thread.name}")
                appendLine(throwable.stackTraceToString())
            },
        )
        // A crash loop must not fill the disk: the newest few are kept.
        d.listFiles()?.filter { it.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        return f
    }

    /** The newest crash file, if any. */
    fun latest(): File? = dir.listFiles()?.filter { it.name.startsWith("crash-") }?.maxByOrNull { it.name }
}
