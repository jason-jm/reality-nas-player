package com.therealityteam.videoplayer.data.disc

import com.therealityteam.videoplayer.data.model.SourceType

/**
 * Disc images (原盘 .iso) among a source's files. A share's .iso is as often an installer as a film, and
 * the walk opens no file: one is taken as a film's when it is big enough for one (500 MB — a DVD's film
 * is gigabytes, a Blu-ray's tens of them; a size the share did not give is taken) and its name is not
 * an operating system's or a program's. Played, an image without a film says so ([DiscTitle.Found.NoFilm]).
 */
object DiscImages {

    /**
     * A video inside a data disc, as the player addresses it — `smb://nas/Tutorials.iso#/Videos/01 Intro.mov` —
     * split into the image and the path in it; null for any other path.
     */
    fun innerOf(path: String): Pair<String, String>? {
        val at = path.indexOf(".iso#/", ignoreCase = true).takeIf { it > 0 } ?: return null
        return path.substring(0, at + 4) to path.substring(at + 6)
    }

    /** The player's address of [inner] inside the image at [image]. */
    fun videoPath(image: String, inner: String): String = "$image#/$inner"

    fun isImage(pathOrName: String): Boolean =
        pathOrName.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").equals("iso", ignoreCase = true)

    /**
     * The folder a disc ripped as its files lives in — the one holding its BDMV or VIDEO_TS — for a file
     * of it ("…/Film (2010)/BDMV/STREAM/00800.m2ts" → "…/Film (2010)"), or null for a file of no disc.
     */
    fun folderOf(path: String): String? {
        val norm = path.replace('\\', '/')
        for (marker in listOf("/BDMV/", "/VIDEO_TS/")) {
            val at = norm.indexOf(marker, ignoreCase = true)
            if (at > 0) return norm.substring(0, at)
        }
        return null
    }

    /**
     * [folderOf] for a file a player opens through the stream server's disc folders: on a share, an FTP /
     * SFTP / NFS or WebDAV server, or the device ([type] of its source, null for none) — not a media
     * server's stream, whose path is the server's, not a folder's.
     */
    fun ripFolderOf(path: String, type: SourceType?): String? =
        folderOf(path)?.takeIf { type == null || type in FOLDER_SOURCES }

    private val FOLDER_SOURCES = setOf(SourceType.LOCAL, SourceType.SMB, SourceType.WEBDAV, SourceType.NFS, SourceType.FTP, SourceType.SFTP)

    fun mayBeFilm(fileName: String, size: Long?): Boolean {
        if (size != null && size in 0 until MIN_SIZE) return false
        val words = fileName.lowercase().substringBeforeLast('.').split(SEPARATORS).filter { it.isNotEmpty() }.toSet()
        return words.none { it in SOFTWARE } && SOFTWARE_PREFIXES.none { p -> words.any { it.startsWith(p) } }
    }

    private const val MIN_SIZE = 500L * 1024 * 1024
    private val SEPARATORS = Regex("[\\s._\\-\\[\\]()+,]+")
    private val SOFTWARE = setOf(
        "windows", "ubuntu", "debian", "fedora", "centos", "linux", "macos", "osx", "office", "msdn", "setup",
        "install", "installer", "server", "x64", "x86", "amd64", "arm64", "i386", "i686", "drivers", "driver",
        "firmware", "recovery", "esxi", "proxmox", "kali", "mint", "manjaro", "freebsd", "truenas", "unraid",
        "synology", "dsm", "vmware", "virtualbox", "boot", "live", "netinst", "desktop",
    )
    private val SOFTWARE_PREFIXES = listOf("win7", "win8", "win10", "win11", "winxp", "rhel", "ubuntu", "vs20")
}
