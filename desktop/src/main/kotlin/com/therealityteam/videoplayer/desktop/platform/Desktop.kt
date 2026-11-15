package com.therealityteam.videoplayer.desktop.platform

import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.util.Log
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

/** The desktop's own verbs: show a file where it lives, copy text, open a link. */
object Desktop {

    /** A path on this computer's own disks (not smb://, not http://). */
    fun isLocal(path: String): Boolean =
        path.startsWith("/") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(path) || path.startsWith("\\\\")

    fun canReveal(path: String): Boolean = isLocal(path) && File(path).exists()

    /** Show [path] selected in Finder / File Explorer (a folder opens). */
    fun reveal(path: String) {
        runCatching {
            val f = File(path)
            when {
                Host.isMac -> ProcessBuilder("open", "-R", f.absolutePath).start()
                Host.isWindows -> ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
                else -> ProcessBuilder("xdg-open", (if (f.isDirectory) f else f.parentFile).absolutePath).start()
            }
        }.onFailure { Log.w("AuraDesktop", "could not reveal $path", it) }
    }

    fun revealLabel(s: Strings): String = s[
        when {
            Host.isMac -> "d_show_in_finder"
            Host.isWindows -> "d_show_in_explorer"
            else -> "d_show_in_files"
        },
    ]

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    fun browse(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(URI(url)) }
            .onFailure { Log.w("AuraDesktop", "could not open $url", it) }
    }

    fun open(file: File) {
        runCatching { java.awt.Desktop.getDesktop().open(file) }
            .onFailure { Log.w("AuraDesktop", "could not open $file", it) }
    }
}
