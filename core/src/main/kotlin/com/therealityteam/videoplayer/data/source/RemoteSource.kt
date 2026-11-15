package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * An FTP, SFTP or NFS source: its folder walked the way a share is (DirectoryWalk, a few listings in
 * flight at once over the server's kept connections), the nfo files and named pictures beside the
 * videos kept for Sidecars. Videos are addressed as the source is — `ftp://nas/video/电影/Film.mkv`,
 * the server's own path, unescaped — so the stream server, the subtitle lookup and the folder tree
 * read them back the same way.
 */
class RemoteSource(override val type: SourceType) : MediaSourceProvider {

    init {
        require(type == SourceType.FTP || type == SourceType.SFTP || type == SourceType.NFS) { "not a remote type: $type" }
    }

    override suspend fun list(config: MediaSourceConfig): List<DiscoveredVideo> = listing(config).videos

    override suspend fun listing(config: MediaSourceConfig): SourceListing = withContext(Dispatchers.IO) {
        val address = RemoteAddress.parse(config.uri) ?: throw SourceException(SourceFault.NO_ADDRESS, config.uri)
        val files = RemoteServers.files(address, config.username, config.password)
        // The walk's paths are relative-looking ("" for the server's root, "video/Anime"); the
        // server is asked for them absolute.
        fun full(dir: String) = if (dir.startsWith("/")) dir else "/$dir"
        val root = address.path.trimEnd('/')
        val unread = ConcurrentLinkedQueue<String>()
        val found = DirectoryWalk.walk(
            lanes = List(LANES) { DirectoryWalk.Lister { dir -> files.list(full(dir)) } },
            root = root,
            maxDepth = MAX_DEPTH,
            maxFiles = MAX_FILES,
            separator = '/',
            keep = { isVideo(it) || Sidecars.isCandidate(it) },
            onRootFailure = { throw SourceException(SourceFault.NO_FOLDER, address.path) },
            onUnread = { unread += it },
        )
        fun pathOf(dir: String, name: String): String = address.at(full(if (dir.isEmpty()) name else "$dir/$name"))
        val (videos, sidecars) = found.partition { isVideo(it.name) }
        val candidates = sidecars.groupBy { it.dir }.mapValues { (_, fs) -> fs.associate { it.name to it.size } }
        val local = Sidecars.pickAll(videos.map { it.dir to it.name }, candidates, '/', ::pathOf)
        SourceListing(
            videos = videos.mapIndexed { i, f ->
                DiscoveredVideo(path = pathOf(f.dir, f.name), fileName = f.name, size = f.size, local = local[i])
            },
            // A folder's own address and a slash, so a sibling that only starts with its name is not taken for it.
            unread = unread.map { d -> address.at(full(d)).trimEnd('/') + "/" },
            cut = found.size >= MAX_FILES,
        )
    }

    private fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private companion object {
        /** Listings in flight at once: the servers' pools hold four connections; one is left for a film. */
        const val LANES = 3
        const val MAX_DEPTH = 12
        const val MAX_FILES = 20_000
    }
}
