package com.therealityteam.videoplayer.data.source

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A browsable entry inside SMB: a share, a folder, or a video file. */
data class SmbEntry(val name: String, val isDirectory: Boolean, val isShare: Boolean = false)

/**
 * SMB2/3 provider (smbj). Lists video files on a share recursively. Library/scrape works;
 * direct streaming playback of smb:// is a known limitation of the built-in player and is
 * tracked separately (would need a local proxy / fd bridge).
 */
class SmbSource : MediaSourceProvider {

    override val type = SourceType.SMB

    override suspend fun list(config: MediaSourceConfig): List<DiscoveredVideo> = listing(config).videos

    override suspend fun listing(config: MediaSourceConfig): SourceListing =
        withContext(Dispatchers.IO) {
            val loc = parse(config.uri)
                ?: throw SourceException(SourceFault.NO_ADDRESS, config.uri)
            // Connection / auth / share errors propagate so the UI can report them (wrong
            // password, host unreachable, no such share). Per-directory list errors are tolerated.
            //
            // The first lane is opened exactly as the single-connection walk always was, so a wrong
            // password or a missing share still fails in the same place with the same message. The
            // rest are extra: a server that will not give them simply leaves fewer lanes, and the
            // walk then behaves as it did before, only slower.
            val lanes = mutableListOf<Lane>()
            val unread = java.util.concurrent.ConcurrentLinkedQueue<String>()
            try {
                lanes += openLane(config, loc)
                repeat(LANES - 1) { runCatching { openLane(config, loc) }.getOrNull()?.let { lanes += it } }
                DirectoryWalk.walk(
                    lanes = lanes.map { lane ->
                        DirectoryWalk.Lister { dir -> runCatching { lane.share.list(dir).map(::entryOf) }.getOrNull() }
                    },
                    root = loc.path,
                    maxDepth = MAX_DEPTH,
                    maxFiles = MAX_FILES,
                    separator = SEP,
                    // The walk lists every folder anyway; keeping the nfo files and the named
                    // pictures it passes costs nothing and saves asking each folder again.
                    keep = { isVideo(it) || Sidecars.isCandidate(it) },
                    onRootFailure = {
                        throw SourceException(
                            SourceFault.NO_FOLDER,
                            loc.share + "/" + loc.path.replace(SEP, '/'),
                        )
                    },
                    onUnread = { unread += it },
                ).let { found ->
                    fun smbPath(dir: String, name: String): String {
                        val child = if (dir.isEmpty()) name else dir + SEP + name
                        return "smb://" + loc.host + "/" + loc.share + "/" + child.replace(SEP, '/')
                    }
                    val (videos, sidecars) = found.partition { isVideo(it.name) }
                    val candidates = sidecars.groupBy { it.dir }.mapValues { (_, fs) -> fs.associate { it.name to it.size } }
                    val local = Sidecars.pickAll(videos.map { it.dir to it.name }, candidates, SEP, ::smbPath)
                    SourceListing(
                        videos = videos.mapIndexed { i, f ->
                            DiscoveredVideo(path = smbPath(f.dir, f.name), fileName = f.name, size = f.size, local = local[i])
                        },
                        // "smb://nas/video/Anime/Show/": a folder's own path and a slash, so a sibling
                        // that only starts with its name ("Show 2") is not taken for it.
                        unread = unread.map { d -> smbPath(d, "").trimEnd('/') + "/" },
                        cut = found.size >= MAX_FILES,
                    )
                }
            } finally {
                lanes.forEach { it.close() }
            }
        }

    /** One connection's worth of the walk: its own client, so nothing is shared across lanes. */
    private class Lane(
        private val client: SMBClient,
        private val connection: com.hierynomus.smbj.connection.Connection,
        val share: DiskShare,
    ) {
        fun close() {
            runCatching { share.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private fun openLane(config: MediaSourceConfig, loc: SmbLocation): Lane {
        val client = SMBClient()
        val connection = client.connectTo(loc.host)
        val session = connection.authenticate(authOf(config))
        return Lane(client, connection, session.connectShare(loc.share) as DiskShare)
    }

    private fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    private fun entryOf(e: FileIdBothDirectoryInformation) = DirectoryWalk.Entry(
        name = e.fileName,
        isDirectory = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
        size = runCatching { e.endOfFile }.getOrNull(),
    )

    private fun authOf(config: MediaSourceConfig): AuthenticationContext =
        authOf(config.username, config.password)

    private fun authOf(username: String?, password: String?): AuthenticationContext =
        if (username.isNullOrEmpty()) AuthenticationContext.anonymous()
        else AuthenticationContext(username, (password ?: "").toCharArray(), null)

    /**
     * Enumerate the host's disk shares (via srvsvc/IPC$) so the user can browse from just a
     * host address — no need to know share names. Excludes hidden/admin ($) and non-disk shares.
     */
    suspend fun listShares(host: String, username: String?, password: String?): List<SmbEntry> =
        withContext(Dispatchers.IO) {
            SMBClient().use { client ->
                client.connectTo(host).use { connection ->
                    val session = connection.authenticate(authOf(username, password))
                    val server = ServerService(SMBTransportFactories.SRVSVC.getTransport(session))
                    server.getShares1()
                        .filter { (it.type and 0x0FFFFFFF) == STYPE_DISKTREE && !it.netName.endsWith("$") }
                        .map { SmbEntry(it.netName, isDirectory = true, isShare = true) }
                        .sortedBy { it.name.lowercase() }
                }
            }
        }

    /**
     * Every plain file name at share/path, unfiltered — the sidecar-subtitle lookup wants
     * .srt/.ass neighbours that [browse]'s video filter would drop.
     */
    suspend fun listFileNames(
        host: String,
        share: String,
        path: String,
        username: String?,
        password: String?,
        /** Folders too, their names ending in "/" (a release's Subs/). */
        withFolders: Boolean = false,
    ): List<String> = withContext(Dispatchers.IO) {
        val dir = path.trim('/').replace('/', '\\')
        SMBClient().use { client ->
            client.connectTo(host).use { connection ->
                val session = connection.authenticate(authOf(username, password))
                (session.connectShare(share) as DiskShare).use { disk ->
                    disk.list(dir).mapNotNull { e ->
                        val name = e.fileName
                        val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        when {
                            name == "." || name == ".." -> null
                            isDir -> if (withFolders) "$name/" else null
                            else -> name
                        }
                    }
                }
            }
        }
    }

    /** List immediate sub-folders + video files at share/path (for the browse UI). */
    suspend fun browse(
        host: String,
        share: String,
        path: String,
        username: String?,
        password: String?,
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        val dir = path.trim('/').replace('/', '\\')
        SMBClient().use { client ->
            client.connectTo(host).use { connection ->
                val session = connection.authenticate(authOf(username, password))
                (session.connectShare(share) as DiskShare).use { disk ->
                    disk.list(dir).mapNotNull { e ->
                        val name = e.fileName
                        if (name == "." || name == "..") return@mapNotNull null
                        val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        when {
                            isDir -> SmbEntry(name, isDirectory = true)
                            name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS ->
                                SmbEntry(name, isDirectory = false)
                            else -> null
                        }
                    }.sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                }
            }
        }
    }

    private data class SmbLocation(val host: String, val share: String, val path: String)

    /** Parse smb://host/share/sub/dir into host, share, and a backslash path. */
    private fun parse(uri: String): SmbLocation? {
        val stripped = uri.removePrefix("smb://").removePrefix("//").trim('/')
        val parts = stripped.split('/')
        if (parts.size < 2) return null
        val host = parts[0]
        val share = parts[1]
        val path = parts.drop(2).joinToString("\\")
        return SmbLocation(host, share, path)
    }

    private companion object {
        // A real NAS anime/film library is huge (one share here holds 6000+ files across deeply nested
        // BDMV / multi-disc / 全集 folders). A small cap silently drops whole shows past the limit, so
        // keep these generous — listing is cheap metadata; only matched scraping is rate-limited.
        const val MAX_DEPTH = 12
        const val MAX_FILES = 50_000

        /**
         * How many connections the walk spreads its listings over.
         *
         * A real library sits under 1,133 directories, and a serial walk makes that many round trips
         * end to end — the link idle for nearly all of it. Measured on that exact tree with the
         * latency swept: at 20ms a listing, one lane takes 28.7s, four take 7.3s, eight take 3.8s.
         * Four is the conservative pick; a NAS is a small machine and this is not the only thing
         * asking it for something.
         */
        const val LANES = 4

        /** SMB writes its paths with backslashes. */
        const val SEP = '\\'
        const val STYPE_DISKTREE = 0 // srvsvc share type for a normal disk share (vs IPC/print)
    }
}
