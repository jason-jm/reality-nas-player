package com.therealityteam.videoplayer.desktop.sync

import com.therealityteam.videoplayer.data.sync.SyncKeys
import com.therealityteam.videoplayer.desktop.platform.Host
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The network shares this computer has mounted, so that a library folder on one — `/Volumes/video`
 * on a Mac, `Z:\` or `\\nas\video` on Windows — is known by the same sync key as the headset's
 * `smb://nas/video/…`.
 *
 * @property point where the share is mounted (`/Volumes/video`, `Z:`).
 * @property share the share's name on the server.
 * @property sub the folder inside the share that was mounted, when not its root ("" usually).
 */
data class NetworkMount(val point: String, val share: String, val sub: String, val type: String) {
    private val root = point.trimEnd('/', '\\')

    /** Whether [path] is this mount or lies somewhere on it. */
    fun contains(path: String): Boolean {
        if (!path.startsWith(root, ignoreCase = Host.isWindows)) return false
        return path.length == root.length || path[root.length] == '/' || path[root.length] == '\\'
    }

    /** The sync key of [path] if it lies on this mount. */
    fun keyOf(path: String): String? {
        if (!contains(path)) return null
        val rest = path.substring(root.length)
        return SyncKeys.of(share, if (sub.isEmpty()) rest else "$sub/$rest")
    }
}

object NetworkMounts {
    private val NETWORK_TYPES = setOf("smbfs", "afpfs", "nfs", "nfs4", "webdav", "cifs", "smb3")

    /** What is mounted now; empty when nothing (or the listing failed). */
    fun list(): List<NetworkMount> = runCatching {
        when {
            Host.isWindows -> parseNetUse(run("net", "use"))
            Host.isMac -> parseMount(run("/sbin/mount"))
            else -> parseProcMounts(File("/proc/mounts").readText())
        }
    }.getOrDefault(emptyList())

    /** The key of a file on a mounted share — a UNC path names its share itself. */
    fun keyOf(path: String, mounts: List<NetworkMount>): String? {
        if (path.startsWith("\\\\") || (Host.isWindows && path.startsWith("//"))) {
            val parts = path.trimStart('\\', '/').split('\\', '/').filter { it.isNotEmpty() }
            return if (parts.size >= 3) SyncKeys.of(parts[1], parts.drop(2).joinToString("/")) else null
        }
        return mounts.sortedByDescending { it.point.length }.firstNotNullOfOrNull { it.keyOf(path) }
    }

    /** macOS `mount`: `//user@nas._smb._tcp.local/My%20Videos on /Volumes/My Videos (smbfs, …)`. */
    internal fun parseMount(out: String): List<NetworkMount> = out.lines().mapNotNull { line ->
        val m = Regex("""^(.+?) on (/.+) \(([^,)]+)""").find(line) ?: return@mapNotNull null
        val (from, point, type) = m.destructured
        if (type !in NETWORK_TYPES) return@mapNotNull null
        mountOf(from, point, type)
    }

    /** Linux `/proc/mounts`: `//nas/video /mnt/video cifs rw,… 0 0`, spaces written `\040`. */
    internal fun parseProcMounts(text: String): List<NetworkMount> = text.lines().mapNotNull { line ->
        val f = line.split(' ')
        if (f.size < 3 || f[2] !in NETWORK_TYPES) return@mapNotNull null
        fun unescape(s: String) = Regex("""\\([0-7]{3})""").replace(s) { it.groupValues[1].toInt(8).toChar().toString() }
        mountOf(unescape(f[0]), unescape(f[1]), f[2])
    }

    /** Windows `net use`: `OK           Z:        \\nas\video    Microsoft Windows Network`. */
    internal fun parseNetUse(out: String): List<NetworkMount> = out.lines().mapNotNull { line ->
        val m = Regex("""\b([A-Za-z]):\s+\\\\([^\\\s]+)\\(\S+)""").find(line) ?: return@mapNotNull null
        val (drive, _, path) = m.destructured
        val parts = path.split('\\').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@mapNotNull null
        NetworkMount("${drive.uppercase()}:", parts[0], parts.drop(1).joinToString("/"), "smb")
    }

    /** The share (and folder in it) a mount's source names, by the protocol's own spelling. */
    private fun mountOf(from: String, point: String, type: String): NetworkMount? {
        val parts: List<String> = when {
            // nas:/volume1/video — an export path; the SMB share of the same folder is its last name.
            type.startsWith("nfs") -> listOf(from.substringAfter(':').trimEnd('/').substringAfterLast('/'))
            // http(s)://nas:5006/video
            type == "webdav" -> from.substringAfter("://").substringAfter('/').split('/')
            // //user@host/share/sub, afp_…@host/share
            else -> from.removePrefix("//").substringAfter('/').split('/')
        }.map(::percentDecode).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return NetworkMount(point, parts[0], parts.drop(1).joinToString("/"), type)
    }

    /** `%20` → space; a `+` stays a plus (these are paths, not form data). */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(ch.toString().toByteArray())
            i++
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        return text
    }
}
