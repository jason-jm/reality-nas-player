package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.util.Log
import java.io.Closeable
import java.io.OutputStream

/**
 * A file server reached by a login of its own — FTP, SFTP or NFS — read the three ways Aura needs: a
 * folder's entries, a file's size, and a run of a file's bytes. The walk lists through it, the stream
 * server serves a film from it by range, and the subtitles, dubs and nfo beside a film are found and
 * read through it.
 *
 * Paths are the server's own ("/video/电影/Film.mkv"), not percent-escaped: the addresses Aura keeps
 * are written the way SMB ones are, `ftp://nas/video/电影/Film.mkv`.
 */
internal interface RemoteFiles : Closeable {
    /** The entries of [dir], or null when it cannot be listed. */
    fun list(dir: String): List<DirectoryWalk.Entry>?

    /** [path]'s length, or null when it cannot be found. */
    fun size(path: String): Long?

    /** Copies up to [length] bytes of [path] from [start] into [out]; how many it copied. */
    fun read(path: String, start: Long, length: Long, out: OutputStream): Long
}

/** Where a remote file lives: `ftp://` (port 21), `sftp://` (22), `nfs://` (2049), with the server's own path. */
data class RemoteAddress(val scheme: String, val host: String, val port: Int, val path: String) {

    /** The server, for keying connections: one pool per scheme, host, port and login. */
    val server: String get() = "$scheme://$host:$port"

    /** This address with [path] in place of its own. */
    fun at(path: String): String = "$scheme://$host${if (port == defaultPort(scheme)) "" else ":$port"}$path"

    companion object {
        val SCHEMES = setOf("ftp", "sftp", "nfs")

        fun defaultPort(scheme: String): Int = when (scheme) { "sftp" -> 22; "nfs" -> 2049; else -> 21 }

        /**
         * `ftp://host[:port]/path` or `sftp://…`, written as people write them: spaces and CJK in the
         * path as they are (java.net.URI would refuse them), a `user@` before the host ignored (the
         * login is the source's). An IPv6 host goes in brackets. Null for anything else.
         */
        fun parse(uri: String): RemoteAddress? {
            val scheme = uri.substringBefore("://", "").lowercase()
            if (scheme !in SCHEMES) return null
            val rest = uri.substringAfter("://")
            val authority = rest.substringBefore('/').substringAfterLast('@')
            if (authority.isBlank()) return null
            val path = "/" + rest.substringAfter('/', "").trimEnd().let { if (it.endsWith("/") && it.length > 1) it.trimEnd('/') else it }
            val (host, port) = if (authority.startsWith("[")) {
                val h = authority.substringAfter('[').substringBefore(']')
                h to authority.substringAfter("]:", "").toIntOrNull()
            } else {
                authority.substringBefore(':') to authority.substringAfter(':', "").toIntOrNull()
            }
            if (host.isBlank()) return null
            return RemoteAddress(scheme, host, port ?: defaultPort(scheme), path.ifEmpty { "/" })
        }
    }
}

/** Remote files, opened per server and login and kept while they are used. */
internal object RemoteServers {

    /** Logins are part of the key: two sources on one server under two accounts see different files. */
    private data class Key(val server: String, val user: String, val password: String)

    private val open = HashMap<Key, RemoteFiles>()

    /** The [RemoteFiles] for [address]'s server under this login, opened on first use. */
    @Synchronized
    fun files(address: RemoteAddress, username: String?, password: String?): RemoteFiles {
        val key = Key(address.server, username.orEmpty(), password.orEmpty())
        return open.getOrPut(key) {
            when (address.scheme) {
                "sftp" -> SftpFiles(address.host, address.port, username.orEmpty(), password.orEmpty())
                "nfs" -> NfsFiles(address.host, address.port, username)
                else -> FtpFiles(address.host, address.port, username, password)
            }
        }
    }

    /** Lets go of every connection (the app going to the background, a test's end). */
    @Synchronized
    fun closeAll() {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    internal fun log(message: String) = Log.i("AuraRemote", message)
}
