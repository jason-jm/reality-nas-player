package com.therealityteam.videoplayer.util

import java.io.File

/**
 * A small file the app cannot afford to lose — the sources (with their accounts) and the settings —
 * written so that a kill mid-write, or a copy that comes out short, leaves the last whole version to
 * fall back on. Both used to be written in place and read back as "nothing there" when they did not
 * parse, and the next save then wrote that nothing over them: every share and its password, gone.
 *
 * [write] goes through a temp file and keeps the version it replaces as `<name>.bak`; [read] takes the
 * file when it reads, else sets it aside (`<name>.damaged-<ms>`) and takes the `.bak`.
 */
object DurableFile {

    /** Writes [text] to [file] whole or not at all; the version it replaces is kept beside it. False if it failed. */
    fun write(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        val bak = backupOf(file)
        // The old one becomes the backup, then the new one takes its place: a kill between the two
        // leaves no file but a backup, which [read] takes.
        if (file.exists()) {
            bak.delete()
            file.renameTo(bak)
        }
        if (!tmp.renameTo(file)) error("rename failed: ${tmp.name}")
        true
    }.getOrElse {
        Log.w(TAG, "write of ${file.name} failed: ${it.message}")
        false
    }

    /**
     * [file]'s text when [valid] accepts it; else the backup's (the bad file set aside, the backup put
     * back in its place); null when there is neither — the file never written.
     */
    fun read(file: File, valid: (String) -> Boolean): String? {
        val text = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        if (text != null && runCatching { valid(text) }.getOrDefault(false)) return text
        val bak = backupOf(file)
        val saved = bak.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { runCatching { valid(it) }.getOrDefault(false) }
        if (text != null) {
            val aside = File(file.parentFile, file.name + ".damaged-" + System.currentTimeMillis())
            file.renameTo(aside)
            Log.e(TAG, "${file.name} unreadable (kept as ${aside.name}); ${if (saved != null) "restored from ${bak.name}" else "no backup"}")
        } else if (saved != null) {
            Log.w(TAG, "${file.name} missing; restored from ${bak.name}")
        }
        if (saved != null) runCatching { bak.copyTo(file, overwrite = true) }
        return saved
    }

    private fun backupOf(file: File) = File(file.parentFile, file.name + ".bak")

    private const val TAG = "AuraFile"
}
