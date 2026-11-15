package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Scans a local directory tree for video files. */
class LocalSource : MediaSourceProvider {

    override val type = SourceType.LOCAL

    override suspend fun list(config: MediaSourceConfig): List<DiscoveredVideo> = listing(config).videos

    override suspend fun listing(config: MediaSourceConfig): SourceListing =
        withContext(Dispatchers.IO) {
            val root = File(config.uri)
            if (!root.exists() || !root.isDirectory) return@withContext SourceListing(emptyList())
            val videos = mutableListOf<File>()
            // Folders that would not open (a card pulled out mid-walk, no permission): not known empty.
            val unread = mutableListOf<String>()
            val candidates = HashMap<String, HashMap<String, Long?>>()
            root.walkTopDown()
                .onEnter { it == root || !SkippedFolders.skip(it.name) }
                .onFail { dir, _ -> unread += dir.absolutePath.trimEnd(File.separatorChar) + File.separator }
                .maxDepth(MAX_DEPTH)
                .filter { it.isFile }
                .forEach { f ->
                    when {
                        f.extension.lowercase() in VIDEO_EXTENSIONS -> if (videos.size < MAX_FILES) videos += f
                        // The nfo files and named pictures beside them, for Sidecars to choose from.
                        Sidecars.isCandidate(f.name) ->
                            candidates.getOrPut(f.parentFile?.absolutePath.orEmpty()) { HashMap() }[f.name] = f.length()
                    }
                }
            val local = Sidecars.pickAll(
                videos.map { it.parentFile?.absolutePath.orEmpty() to it.name },
                candidates,
                File.separatorChar,
            ) { dir, name -> File(dir, name).absolutePath }
            SourceListing(
                videos = videos.mapIndexed { i, f ->
                    DiscoveredVideo(path = f.absolutePath, fileName = f.name, size = f.length(), local = local[i])
                },
                unread = unread,
                cut = videos.size >= MAX_FILES,
            )
        }

    private companion object {
        const val MAX_DEPTH = 12
        const val MAX_FILES = 50_000
    }
}
