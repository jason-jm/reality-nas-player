package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType

/** A discovered playable video file from a source (before scraping). */
data class DiscoveredVideo(
    val path: String,
    val fileName: String,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    /** The nfo and pictures beside it, where the source can see them (local folders, SMB). */
    val local: com.therealityteam.videoplayer.data.model.LocalMeta? = null,
)

/**
 * What a walk of a source found, and the folders it could not read — a NAS that went quiet for a
 * moment, a folder it would not open. The files under [unread] are not known to be gone; nor is
 * anything when the walk was [cut] short by its file limit.
 */
data class SourceListing(
    val videos: List<DiscoveredVideo>,
    /** Path prefixes ("smb://nas/video/Anime/Show/") the walk could not list. */
    val unread: List<String> = emptyList(),
    val cut: Boolean = false,
)

/** Pluggable source backend (local folder, SMB, WebDAV, DLNA…). */
interface MediaSourceProvider {
    val type: SourceType
    suspend fun list(config: MediaSourceConfig): List<DiscoveredVideo>

    /** [list], with what the walk could not read; a source that cannot tell says it read everything. */
    suspend fun listing(config: MediaSourceConfig): SourceListing = SourceListing(list(config))
}

/** Common video container extensions used across providers. */
val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "mov", "avi", "webm", "ts", "m2ts", "mts", "flv", "wmv",
    "vob", "mpg", "mpeg", "m2p", "m4v", "3gp", "asf", "rmvb", "rm",
    "ogm", "ogv", "m2v", "divx", "f4v", "mpe",
    // 原盘: a Blu-ray's or DVD's image, its film read out of it (DiscTitle); SourceScanner keeps the likely ones.
    "iso",
)
