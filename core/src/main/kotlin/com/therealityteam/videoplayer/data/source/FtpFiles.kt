package com.therealityteam.videoplayer.data.source

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * An FTP server, through Apache Commons Net: passive mode, binary, a few logged-in connections kept
 * for reuse — a login is three round trips, and a seek in a film, a page of a folder, a block of a
 * disc image each want a connection.
 *
 * A run of a file is a RETR after a REST to its start, cut off when enough has come: the server
 * answers the cut with a 426 and the connection serves the next request. The stream server's reads
 * are open-ended (a player asks from a point to the end and hangs up at its next seek), so nearly
 * every read ends that way.
 *
 * Names: UTF-8 where the server says it speaks it (FEAT) — and where it does not, a Chinese NAS or a
 * Windows server most often lists in GBK. A listing that comes back with undecodable names in UTF-8
 * is asked again in GBK, and the server is spoken to in GBK from then on.
 */
internal class FtpFiles(
    private val host: String,
    private val port: Int,
    private val username: String?,
    private val password: String?,
) : RemoteFiles {

    private val idle = ArrayDeque<Pair<FTPClient, Long>>()
    private val gate = Semaphore(MAX_CONNECTIONS)
    @Volatile private var encoding = "UTF-8"

    override fun list(dir: String): List<DirectoryWalk.Entry>? {
        val first = listOnce(dir) ?: return null
        if (encoding == "UTF-8" && first.any { '�' in it.name }) {
            RemoteServers.log("ftp $host: names are not UTF-8, speaking GBK")
            encoding = "GBK"
            dropIdle()
            return listOnce(dir)
        }
        return first
    }

    private fun listOnce(dir: String): List<DirectoryWalk.Entry>? = runCatching {
        use { c ->
            val files: Array<FTPFile?> = if (c.hasFeature("MLST")) c.mlistDir(dir) else c.listFiles(dir)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) return@use null
            // Some servers list a folder that is not there as an empty one: an empty answer is
            // only believed of a folder the server will go into.
            if (files.isEmpty() && !c.changeWorkingDirectory(dir)) return@use null
            files.filterNotNull()
                .filter { it.name != null && it.name != "." && it.name != ".." }
                .map { f ->
                    // A LIST line may carry the whole path; MLSD gives the bare name.
                    val name = f.name.substringAfterLast('/')
                    DirectoryWalk.Entry(name, f.isDirectory || (f.isSymbolicLink && f.link?.endsWith("/") == true), f.size.takeIf { it >= 0 && f.isFile })
                }
        }
    }.getOrElse { e ->
        if (e is SourceException) throw e
        RemoteServers.log("ftp $host: list $dir failed: ${e.message}")
        null
    }

    override fun size(path: String): Long? = runCatching {
        use { c ->
            // MLST's facts first — but a server that takes a name's `?` or `[…]` for a pattern (Apache FtpServer)
            // answers "What If? (2020).mkv" with its folder's, a few hundred bytes the player then reads past
            // (416). SIZE takes the name as it is.
            (if (c.hasFeature("MLST")) c.mlistFile(path)?.takeUnless { it.isDirectory }?.size?.takeIf { it >= 0 } else null)
                ?: c.sendCommand("SIZE", path).let { code ->
                    if (code == FTPReply.FILE_STATUS) c.replyString.trim().substringAfter(' ').trim().toLongOrNull() else null
                }
        }
    }.getOrNull()

    override fun read(path: String, start: Long, length: Long, out: OutputStream): Long {
        if (length <= 0) return 0
        gate.acquire()
        var c: FTPClient? = null
        var copied = 0L
        try {
            c = borrow()
            c.restartOffset = start
            val input = c.retrieveFileStream(path) ?: throw IOException("RETR $path: ${c.replyString?.trim()}")
            val buf = ByteArray(256 * 1024)
            var ended = false
            try {
                while (copied < length) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - copied).toInt())
                    if (n < 0) { ended = true; break }
                    // The reader hanging up (a player's seek) is the usual end, not a failure of the server.
                    if (runCatching { out.write(buf, 0, n) }.isFailure) break
                    copied += n
                }
            } finally {
                runCatching { input.close() }
            }
            // A transfer cut short is answered 426 (or 451); one that ran to its end, 226. Either way the
            // connection can take the next command once that answer is read.
            val done = runCatching { c.completePendingCommand() }.getOrDefault(false)
            if (done || ended || c.replyCode in CUT_REPLIES) giveBack(c) else disconnect(c)
            c = null
            return copied
        } catch (e: Exception) {
            c?.let(::disconnect)
            if (copied > 0) return copied // the server stopped mid-run: what came stands
            throw e
        } finally {
            gate.release()
        }
    }

    override fun close() {
        dropIdle()
    }

    /** [block] on a logged-in connection, given back after it unless it failed. */
    private inline fun <T> use(block: (FTPClient) -> T): T {
        gate.acquire()
        try {
            val c = borrow()
            val result = try {
                block(c)
            } catch (e: Exception) {
                disconnect(c)
                throw e
            }
            giveBack(c)
            return result
        } finally {
            gate.release()
        }
    }

    private fun borrow(): FTPClient {
        while (true) {
            val (c, since) = synchronized(idle) { idle.removeLastOrNull() } ?: return connect()
            // A connection idle a while may have been dropped by the server (most close one after a
            // few minutes): asked a NOOP first, a dead one is replaced rather than failing a read.
            if (System.currentTimeMillis() - since < FRESH_MS || runCatching { c.sendNoOp() }.getOrDefault(false)) return c
            disconnect(c)
        }
    }

    private fun giveBack(c: FTPClient) {
        if (!c.isConnected || c.controlEncoding != encoding) return disconnect(c)
        synchronized(idle) {
            if (idle.size < MAX_CONNECTIONS) idle.addLast(c to System.currentTimeMillis()) else disconnect(c)
        }
    }

    private fun dropIdle() {
        val all = synchronized(idle) { idle.toList().also { idle.clear() } }
        all.forEach { disconnect(it.first) }
    }

    private fun disconnect(c: FTPClient) {
        runCatching { if (c.isConnected) c.disconnect() }
    }

    private fun connect(): FTPClient {
        val c = FTPClient()
        c.controlEncoding = encoding
        c.connectTimeout = CONNECT_MS
        c.defaultTimeout = READ_MS
        c.setDataTimeout(Duration.ofMillis(READ_MS.toLong()))
        try {
            c.connect(host, port)
        } catch (e: IOException) {
            // No answer, no such host, refused: nothing is listening there.
            throw SourceException(SourceFault.UNREACHABLE, "ftp $host:$port: ${e.message}", e)
        }
        try {
            if (!FTPReply.isPositiveCompletion(c.replyCode)) {
                throw SourceException(SourceFault.SERVER, "ftp $host: ${c.replyString?.trim()}")
            }
            c.soTimeout = READ_MS
            val user = username?.takeIf { it.isNotBlank() } ?: "anonymous"
            val pass = if (username.isNullOrBlank()) "aura@" else password.orEmpty()
            if (!c.login(user, pass)) throw SourceException(SourceFault.AUTH, "ftp $host: ${c.replyString?.trim()}")
            // Spoken to in UTF-8 only where it says it can: OPTS UTF8 ON switches the ones that need it.
            if (encoding == "UTF-8" && c.hasFeature("UTF8")) runCatching { c.sendCommand("OPTS UTF8 ON") }
            c.setFileType(FTP.BINARY_FILE_TYPE)
            c.enterLocalPassiveMode()
            return c
        } catch (e: Exception) {
            disconnect(c)
            throw e
        }
    }

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val CONNECT_MS = 8_000
        const val READ_MS = 20_000
        /** A connection used this recently is taken as alive without a NOOP. */
        const val FRESH_MS = 30_000L
        /** The answers to a transfer the reader cut short, after which the connection still serves. */
        val CUT_REPLIES = setOf(426, 450, 451, 226, 250)
    }
}
