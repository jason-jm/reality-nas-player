package com.therealityteam.videoplayer.data.source

/**
 * Folders a video library never lives in, which every walk steps around: a NAS's recycle bin and
 * snapshot and thumbnail folders (deleted films kept coming back from Synology's #recycle), the
 * operating systems' own housekeeping, anything hidden, and the insides of other apps — a video
 * editor's cache under ~/Movies once put its tutorial clip on the wall as a 1965 film.
 *
 * Only what a walk finds on its way down is judged; a folder the user named as a source is always
 * read, hidden or not.
 */
object SkippedFolders {

    private val NAMES = setOf(
        // NAS housekeeping: Synology, QNAP, and the shares' recycle bins and snapshots.
        "#recycle", "@recycle", "#snapshot", "@eadir", "@__thumb", "@tmp",
        // Windows and macOS housekeeping.
        "\$recycle.bin", "recycler", "system volume information", "lost+found",
        // Other apps' working folders (CapCut / 剪映 keep theirs under ~/Movies).
        "user data", "cache", "caches",
    )

    /** macOS packages that show as one file in Finder but are folders underneath. */
    private val PACKAGES = setOf(
        "app", "bundle", "framework", "plugin", "photoslibrary", "imovielibrary", "fcpbundle",
        "tvlibrary", "musiclibrary", "aplibrary", "theater", "lrdata",
    )

    fun skip(name: String): Boolean {
        if (name.startsWith('.')) return true
        val n = name.lowercase()
        if (n in NAMES) return true
        val ext = n.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext in PACKAGES
    }
}
