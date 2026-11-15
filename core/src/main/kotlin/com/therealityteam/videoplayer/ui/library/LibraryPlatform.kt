package com.therealityteam.videoplayer.ui.library

import java.io.File

/** What the library needs from the app it runs in, and only that app can say. */
interface LibraryPlatform {
    /** The app's private data directory: library.json, the caches and the wall snapshot live under it. */
    val filesDir: File

    /** When this build of the app was installed — a code change then invalidates the cached wall by itself. */
    val installedAt: Long

    /** Folders on this device worth scanning for videos without the user naming them. */
    fun localScanDirs(): List<String>

    /** A long library task is starting: keep the process alive past its window (a foreground service on the headset). */
    fun keepWorking()
}
