package com.therealityteam.videoplayer.data.source

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.EnumSet

/** Reads the small files beside a library item — its nfo, its poster — from wherever the item lives. */
fun interface SidecarReader {
    /** [path]'s bytes, or null when it cannot be read or holds more than [maxBytes]. */
    suspend fun read(item: MediaItem, path: String, maxBytes: Int): ByteArray?

    /** The pass that was reading is over: let go of what it opened. */
    fun release() {}
}

/**
 * Reads them from the library's own sources: a local file directly, a file on a share through one
 * connection per share, kept while a pass is reading and closed by [close] (opening a connection
 * costs more than the 3 KB nfo it would fetch, and a pass reads hundreds).
 */
class SourceSidecarReader(private val sources: SourceRepository) : SidecarReader {

    private class Share(val client: SMBClient, val connection: Connection, val disk: DiskShare) {
        fun close() {
            runCatching { disk.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private val shares = HashMap<String, Share>()

    override suspend fun read(item: MediaItem, path: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        when {
            path.startsWith("smb://", ignoreCase = true) -> readSmb(item, path, maxBytes)
            path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> readWebDav(item, path, maxBytes)
            RemoteAddress.parse(path) != null -> readRemote(item, path, maxBytes)
            path.contains("://") -> null
            else -> File(path).takeIf { it.isFile && it.length() <= maxBytes }?.let { runCatching { it.readBytes() }.getOrNull() }
        }
    }

    /** A file on a WebDAV source, with the source's own account; nothing from a media server. */
    private fun readWebDav(item: MediaItem, path: String, maxBytes: Int): ByteArray? {
        val config = sources.get(item.sourceId)?.takeIf { it.type == SourceType.WEBDAV } ?: return null
        val conn = runCatching { java.net.URL(path).openConnection() as java.net.HttpURLConnection }.getOrNull() ?: return null
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            if (!config.username.isNullOrEmpty()) {
                val token = java.util.Base64.getEncoder().encodeToString("${config.username}:${config.password ?: ""}".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            if (conn.responseCode !in 200..299 || conn.contentLengthLong > maxBytes) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null
                }
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** A file on an FTP or SFTP source, over the server's kept connections, with the source's own login. */
    private fun readRemote(item: MediaItem, path: String, maxBytes: Int): ByteArray? {
        val config = sources.get(item.sourceId)?.takeIf { it.type == SourceType.FTP || it.type == SourceType.SFTP || it.type == SourceType.NFS } ?: return null
        val address = RemoteAddress.parse(path) ?: return null
        return runCatching {
            val files = RemoteServers.files(address, config.username, config.password)
            val size = files.size(address.path) ?: return@runCatching null
            if (size > maxBytes) return@runCatching null
            val out = ByteArrayOutputStream(size.toInt())
            files.read(address.path, 0, size, out)
            out.toByteArray().takeIf { it.size.toLong() == size }
        }.getOrNull()
    }

    private fun readSmb(item: MediaItem, path: String, maxBytes: Int): ByteArray? {
        val config = sources.get(item.sourceId) ?: return null
        val parts = path.removePrefix("smb://").removePrefix("SMB://").split('/')
        if (parts.size < 3) return null
        val (host, share) = parts[0] to parts[1]
        val file = parts.drop(2).joinToString("\\")
        val key = "${config.id}|$host|$share"
        // One reader at a time per share: the files are small, and smbj's share handles are not
        // documented as safe to open from many threads at once.
        val opened = synchronized(shares) {
            shares[key] ?: runCatching { open(host, share, config.username, config.password) }
                .onFailure { Log.w("Aura", "sidecar: cannot open \\\\$host\\$share: ${it.message}") }
                .getOrNull()?.also { shares[key] = it }
        } ?: return null
        return synchronized(opened) {
            runCatching {
                opened.disk.openFile(
                    file,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { f ->
                    val size = f.fileInformation.standardInformation.endOfFile
                    if (size > maxBytes) return@runCatching null
                    val out = ByteArrayOutputStream(size.toInt().coerceAtLeast(0))
                    f.inputStream.use { it.copyTo(out) }
                    out.toByteArray()
                }
            }.getOrElse { e ->
                // A file that is not there (or not ours to read) leaves the connection as it was;
                // anything else may have broken it, and the next read opens a fresh one.
                if (e !is com.hierynomus.mssmb2.SMBApiException) synchronized(shares) { shares.remove(key)?.close() }
                null
            }
        }
    }

    private fun open(host: String, share: String, username: String?, password: String?): Share {
        val client = SMBClient()
        val connection = client.connectTo(host)
        val auth = if (username.isNullOrEmpty()) AuthenticationContext.anonymous()
        else AuthenticationContext(username, (password ?: "").toCharArray(), null)
        val session = connection.authenticate(auth)
        return Share(client, connection, session.connectShare(share) as DiskShare)
    }

    /** Closes every connection a pass opened. */
    override fun release() {
        val all = synchronized(shares) { shares.values.toList().also { shares.clear() } }
        all.forEach { it.close() }
    }
}
