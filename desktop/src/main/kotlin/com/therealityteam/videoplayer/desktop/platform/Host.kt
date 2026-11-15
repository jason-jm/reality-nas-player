package com.therealityteam.videoplayer.desktop.platform

import com.therealityteam.videoplayer.util.PRODUCT_NAME
import java.io.File

/** Which desktop this is, and where it keeps an app's files. */
object Host {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()

    val isMac: Boolean = "mac" in osName
    val isWindows: Boolean = "win" in osName
    val isLinux: Boolean = !isMac && !isWindows

    /** The version people see — in About and in the sync folder. Kept with packageVersion. */
    const val appVersion = "1.0.0"

    private val home = File(System.getProperty("user.home"))

    /**
     * The app's own data: library.json, the caches and artwork live under `aura/` in here, laid
     * out as on the headset. Not roaming on Windows — artwork runs to hundreds of megabytes.
     */
    val dataDir: File by lazy {
        System.getProperty("aura.data")?.let { return@lazy File(it).apply { mkdirs() } }
        when {
            isMac -> File(home, "Library/Application Support/Aura")
            isWindows -> File(System.getenv("LOCALAPPDATA") ?: File(home, "AppData/Local").path, "Aura")
            else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "aura")
        }.apply { mkdirs() }
    }

    val logDir: File by lazy {
        when {
            isMac -> File(home, "Library/Logs/Aura")
            else -> File(dataDir, "logs")
        }.apply { mkdirs() }
    }

    /** Where screenshots go: the user's Pictures folder, in an album named after the app. */
    val screenshotDir: File
        get() = File(File(home, "Pictures"), PRODUCT_NAME)

    /** The folders a computer keeps films in, if they exist — offered as a first source. */
    val videoFolders: List<File>
        get() = listOf(File(home, "Movies"), File(home, "Videos"))
            .filter { it.isDirectory }
            .distinctBy { it.canonicalPath }
}
