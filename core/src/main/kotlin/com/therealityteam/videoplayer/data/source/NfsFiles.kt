package com.therealityteam.videoplayer.data.source

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * An NFS server, version 3 over TCP — the protocol a NAS exports its folders by besides SMB — spoken
 * here without a library: ONC RPC is a few dozen lines, and the four calls a player needs (MOUNT to
 * find the export, LOOKUP to walk down to a file, READDIRPLUS to list a folder with its entries'
 * types and sizes, READ by offset) a few dozen more.
 *
 * The address names a folder on the server as the NAS shows it (`nfs://nas/volume1/video/电影`); the
 * export it lies in is found in the server's export list (the longest that holds it), mounted, and
 * the rest looked up from there. Credentials are AUTH_SYS: a uid and gid — the source's user name
 * may be written `1026:100`; empty means 0:0, which a NAS that squashes root reads as its guest.
 *
 * A NAS that only answers clients on privileged ports (Linux's default "secure" export) cannot be
 * read by an app, which may not open one: its refusal is reported as [SourceFault.NFS_REFUSED], with
 * what to change on the NAS.
 */
internal class NfsFiles(
    private val host: String,
    private val port: Int,
    username: String?,
) : RemoteFiles {

    private val uid: Int
    private val gid: Int

    init {
        val ids = username?.trim().orEmpty().split(':').mapNotNull { it.trim().toIntOrNull() }
        uid = ids.getOrElse(0) { 0 }
        gid = ids.getOrElse(1) { uid }
    }

    private val idle = ArrayDeque<Rpc>()
    private val gate = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

    /** Mounted exports, by their path, with their root handle. */
    private val exports = HashMap<String, ByteArray>()

    /** Handles of paths already looked up — a film's, its folder's — kept a while. */
    private val handles = object : LinkedHashMap<String, Handle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Handle>?) = size > MAX_HANDLES
    }

    private class Handle(val fh: ByteArray, val isDir: Boolean, val size: Long)

    override fun list(dir: String): List<DirectoryWalk.Entry>? = try {
        val h = resolve(dir)
        if (!h.isDir) null else readdir(h.fh)
    } catch (e: SourceException) {
        if (e.fault == SourceFault.NO_FOLDER) null else throw e
    } catch (e: IOException) {
        RemoteServers.log("nfs $host: list $dir failed: ${e.message}")
        null
    }

    override fun size(path: String): Long? = runCatching { resolve(path).takeIf { !it.isDir }?.size }.getOrNull()

    override fun read(path: String, start: Long, length: Long, out: OutputStream): Long {
        if (length <= 0) return 0
        val fh = resolve(path).fh
        var copied = 0L
        while (copied < length) {
            val want = minOf(CHUNK.toLong(), length - copied).toInt()
            val (data, eof) = rpc { it.read(fh, start + copied, want) }
            if (data.isNotEmpty()) {
                // The reader hanging up (a player's seek) ends the run; the connection stays good.
                if (runCatching { out.write(data) }.isFailure) break
                copied += data.size
            }
            if (eof || data.isEmpty()) break
        }
        return copied
    }

    override fun close() {
        val all = synchronized(idle) { idle.toList().also { idle.clear() } }
        all.forEach { it.close() }
    }

    // ---- paths -----------------------------------------------------------------------------

    /** [path]'s handle: from the export it lies in, one LOOKUP a component, each kept for next time. */
    private fun resolve(path: String): Handle {
        val clean = "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
        synchronized(handles) { handles[clean] }?.let { return it }
        val (export, rootFh) = exportFor(clean)
        var current = clean.removePrefix(export).trim('/')
        // The deepest folder on the way already known saves its lookups.
        var at = export.trimEnd('/')
        var handle = Handle(rootFh, true, 0)
        val parts = current.split('/').filter { it.isNotEmpty() }
        for ((i, name) in parts.withIndex()) {
            val next = "$at/$name".let { if (at.isEmpty()) "/$name" else it }
            val known = synchronized(handles) { handles[next] }
            handle = known ?: rpc { it.lookup(handle.fh, name) }.also { h -> synchronized(handles) { handles[next] = h } }
            at = next
            if (!handle.isDir && i < parts.size - 1) throw SourceException(SourceFault.NO_FOLDER, path)
        }
        current = clean
        synchronized(handles) { handles[current] = handle }
        return handle
    }

    /** The export [path] lies in — the longest the server lists that holds it — mounted. */
    private fun exportFor(path: String): Pair<String, ByteArray> {
        synchronized(exports) {
            exports.keys.filter { holds(it, path) }.maxByOrNull { it.length }?.let { return it to exports.getValue(it) }
        }
        val mountd = mountRpc()
        try {
            val listed = runCatching { mountd.exportList() }.getOrDefault(emptyList())
            val candidates = (listed.filter { holds(it, path) }.sortedByDescending { it.length }).ifEmpty {
                // No list (or a server that hides it): the path itself, then each folder above it.
                generateSequence(path) { p -> p.substringBeforeLast('/', "").ifEmpty { null } }.toList() + "/"
            }
            var refused: SourceException? = null
            for (export in candidates) {
                when (val r = mountd.mount(export)) {
                    is Mounted -> {
                        synchronized(exports) { exports[export] = r.fh }
                        RemoteServers.log("nfs $host: mounted $export")
                        return export to r.fh
                    }
                    is MountRefused -> refused = r.error
                    MountMissing -> Unit
                }
            }
            throw refused ?: SourceException(SourceFault.NO_FOLDER, path)
        } finally {
            mountd.close()
        }
    }

    private fun holds(export: String, path: String) =
        export == "/" || path == export || path.startsWith(export.trimEnd('/') + "/")

    // ---- connections ------------------------------------------------------------------------

    private inline fun <T> rpc(block: (Rpc) -> T): T {
        gate.acquire()
        try {
            val c = synchronized(idle) { idle.removeLastOrNull() } ?: Rpc.open(host, port, uid, gid)
            val result = try {
                block(c)
            } catch (e: SourceException) {
                synchronized(idle) { idle.addLast(c) } // an answer, not a broken connection
                throw e
            } catch (e: Exception) {
                c.close()
                throw e
            }
            synchronized(idle) { if (idle.size < MAX_CONNECTIONS) idle.addLast(c) else c.close() }
            return result
        } finally {
            gate.release()
        }
    }

    /** The MOUNT service: where the portmapper says it is, else on the NFS port (as one-port servers have it). */
    private fun mountRpc(): Rpc {
        val mountPort = runCatching {
            Rpc.open(host, PORTMAP_PORT, uid, gid, connectMs = 3_000).use { it.getPort(MOUNT_PROG, 3) }
        }.getOrNull()?.takeIf { it > 0 } ?: port
        return Rpc.open(host, mountPort, uid, gid)
    }

    private sealed interface MountResult
    private class Mounted(val fh: ByteArray) : MountResult
    private class MountRefused(val error: SourceException) : MountResult
    private object MountMissing : MountResult

    // ---- ONC RPC ----------------------------------------------------------------------------

    /** One TCP connection to an RPC service: calls one at a time, records marked (RFC 5531). */
    private class Rpc private constructor(private val socket: Socket, private val uid: Int, private val gid: Int) : java.io.Closeable {
        private val input = DataInputStream(socket.getInputStream().buffered(256 * 1024))
        private val output = socket.getOutputStream().buffered(64 * 1024)
        private var xid = Random.nextInt()

        companion object {
            fun open(host: String, port: Int, uid: Int, gid: Int, connectMs: Int = CONNECT_MS): Rpc {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(host, port), connectMs)
                    s.soTimeout = READ_MS
                    s.tcpNoDelay = true
                } catch (e: IOException) {
                    runCatching { s.close() }
                    throw SourceException(SourceFault.UNREACHABLE, "nfs $host:$port: ${e.message}", e)
                }
                return Rpc(s, uid, gid)
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }

        /** [proc] of [prog]/[vers] with [args]; the reply's results, past the RPC header. */
        @Synchronized
        fun call(prog: Int, vers: Int, proc: Int, args: (Xdr) -> Unit = {}): Reader {
            val id = ++xid
            val x = Xdr()
            x.int(id); x.int(0) /* CALL */; x.int(2) /* RPC v2 */; x.int(prog); x.int(vers); x.int(proc)
            // AUTH_SYS: who we are on the server.
            val cred = Xdr().apply { int(0); string("aura"); int(uid); int(gid); int(1); int(gid) }.bytes()
            x.int(1); x.opaque(cred)
            x.int(0); x.int(0) // verifier: AUTH_NONE
            args(x)
            val body = x.bytes()
            output.write(ByteBuffer.allocate(4).putInt(0x80000000.toInt() or body.size).array())
            output.write(body)
            output.flush()
            while (true) {
                val r = Reader(readRecord())
                if (r.int() != id) continue // a late answer to a call given up on
                if (r.int() != 1) throw IOException("not an RPC reply")
                if (r.int() != 0) {
                    // Denied: a version we do not speak, or credentials it will not take — the latter
                    // what a NAS that wants a privileged port answers.
                    val why = r.int()
                    throw if (why == 1) SourceException(SourceFault.NFS_REFUSED, "rpc auth error ${r.int()}")
                    else IOException("rpc mismatch")
                }
                r.int(); r.opaque() // verifier
                when (val stat = r.int()) {
                    0 -> return r
                    1, 2 -> throw IOException("rpc program $prog v$vers unavailable ($stat)")
                    else -> throw IOException("rpc error $stat")
                }
            }
        }

        private fun readRecord(): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val header = try { input.readInt() } catch (e: EOFException) { throw IOException("connection closed", e) }
                val len = header and 0x7FFFFFFF
                val buf = ByteArray(len)
                input.readFully(buf)
                out.write(buf)
                if (header < 0) return out.toByteArray()
            }
        }

        // -- portmapper, MOUNT v3, NFS v3 --

        fun getPort(prog: Int, vers: Int): Int =
            call(PORTMAP_PROG, 2, 3) { it.int(prog); it.int(vers); it.int(6) /* TCP */; it.int(0) }.int()

        fun exportList(): List<String> {
            val r = call(MOUNT_PROG, 3, 5)
            val out = mutableListOf<String>()
            while (r.bool()) {
                out += r.string()
                while (r.bool()) r.string() // the groups allowed: the server decides, not us
            }
            return out
        }

        fun mount(path: String): MountResult {
            val r = call(MOUNT_PROG, 3, 1) { it.string(path) }
            return when (val status = r.int()) {
                0 -> Mounted(r.opaque())
                2, 20 -> MountMissing // no such folder, or not a folder: try the next one up
                1, 13 -> MountRefused(SourceException(SourceFault.NFS_REFUSED, "mount $path: refused ($status)"))
                else -> MountRefused(SourceException(SourceFault.SERVER, "mount $path: error $status"))
            }
        }

        fun lookup(dir: ByteArray, name: String): Handle {
            val r = call(NFS_PROG, 3, 3) { it.opaque(dir); it.string(name) }
            val status = r.int()
            if (status != 0) throw nfsError(status, name)
            val fh = r.opaque()
            val attr = r.postOpAttr()
            return Handle(fh, attr?.isDir ?: false, attr?.size ?: 0)
        }

        fun readdirPlus(dir: ByteArray, cookie: Long, verifier: ByteArray): Triple<List<Pair<DirectoryWalk.Entry, Long>>, ByteArray, Boolean> {
            val r = call(NFS_PROG, 3, 17) {
                it.opaque(dir); it.long(cookie); it.fixed(verifier); it.int(DIRCOUNT); it.int(MAXCOUNT)
            }
            val status = r.int()
            r.postOpAttr()
            if (status != 0) throw nfsError(status, "readdirplus")
            val nextVerifier = r.fixed(8)
            val entries = mutableListOf<Pair<DirectoryWalk.Entry, Long>>()
            while (r.bool()) {
                r.long() // fileid
                val name = r.string()
                val next = r.long()
                val attr = r.postOpAttr()
                if (r.bool()) r.opaque() // its handle: looked up again when wanted, by name
                if (name == "." || name == "..") continue
                // A link is left out: following one means READLINK and a lookup elsewhere.
                if (attr == null || attr.type == 5) continue
                entries += DirectoryWalk.Entry(name, attr.isDir, attr.size.takeIf { !attr.isDir }) to next
            }
            return Triple(entries, nextVerifier, r.bool())
        }

        fun read(fh: ByteArray, offset: Long, count: Int): Pair<ByteArray, Boolean> {
            val r = call(NFS_PROG, 3, 6) { it.opaque(fh); it.long(offset); it.int(count) }
            val status = r.int()
            r.postOpAttr()
            if (status != 0) throw nfsError(status, "read")
            r.int() // count
            val eof = r.bool()
            return r.opaque() to eof
        }

        private fun nfsError(status: Int, what: String): SourceException = when (status) {
            2, 20 -> SourceException(SourceFault.NO_FOLDER, "$what: not found ($status)")
            1, 13 -> SourceException(SourceFault.AUTH, "$what: not allowed ($status)")
            else -> SourceException(SourceFault.SERVER, "$what: NFS error $status")
        }
    }

    private fun readdir(dir: ByteArray): List<DirectoryWalk.Entry> {
        val out = mutableListOf<DirectoryWalk.Entry>()
        var cookie = 0L
        var verifier = ByteArray(8)
        while (true) {
            val (entries, next, eof) = rpc { it.readdirPlus(dir, cookie, verifier) }
            out += entries.map { it.first }
            if (eof || entries.isEmpty()) return out
            cookie = entries.last().second
            verifier = next
        }
    }

    /** What a folder entry or a lookup says of a file: its type, size. */
    private class Attr(val type: Int, val size: Long) {
        val isDir get() = type == 2
    }

    /** XDR, written. */
    private class Xdr {
        private val out = ByteArrayOutputStream()
        fun int(v: Int) { out.write(ByteBuffer.allocate(4).putInt(v).array()) }
        fun long(v: Long) { out.write(ByteBuffer.allocate(8).putLong(v).array()) }
        fun fixed(b: ByteArray) { out.write(b) }
        fun opaque(b: ByteArray) { int(b.size); out.write(b); repeat((4 - b.size % 4) % 4) { out.write(0) } }
        fun string(s: String) = opaque(s.toByteArray(Charsets.UTF_8))
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** XDR, read. */
    private class Reader(bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes)
        fun int(): Int = b.int
        fun long(): Long = b.long
        fun bool(): Boolean = b.int != 0
        fun fixed(n: Int): ByteArray = ByteArray(n).also { b.get(it) }
        fun opaque(): ByteArray {
            val n = b.int
            val v = ByteArray(n).also { b.get(it) }
            b.position(b.position() + (4 - n % 4) % 4)
            return v
        }
        fun string(): String = String(opaque(), Charsets.UTF_8)

        /** post_op_attr: the fattr3 when it follows. */
        fun postOpAttr(): Attr? {
            if (!bool()) return null
            val type = int()
            int(); int(); int(); int() // mode, nlink, uid, gid
            val size = long()
            long() // used
            int(); int() // rdev
            long(); long() // fsid, fileid
            repeat(6) { int() } // atime, mtime, ctime
            return Attr(type, size)
        }
    }

    private companion object {
        const val PORTMAP_PORT = 111
        const val PORTMAP_PROG = 100000
        const val MOUNT_PROG = 100005
        const val NFS_PROG = 100003
        const val MAX_CONNECTIONS = 4
        const val MAX_HANDLES = 4096
        const val CONNECT_MS = 8_000
        const val READ_MS = 20_000
        /** A READ's size: what servers grant without asking (most allow 1 MiB; a few cap at 256 KiB or less). */
        const val CHUNK = 128 * 1024
        const val DIRCOUNT = 32 * 1024
        const val MAXCOUNT = 128 * 1024
    }
}
