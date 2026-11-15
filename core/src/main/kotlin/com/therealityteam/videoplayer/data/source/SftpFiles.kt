package com.therealityteam.videoplayer.data.source

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import java.io.File
import java.io.OutputStream
import java.util.Base64
import java.util.Vector

/**
 * An SFTP server, through JSch: one SSH session per server and login, a channel per concurrent use
 * (a channel is one round trip to open, a session a handful plus the key exchange), kept for reuse.
 * A run of a file is a read from its offset — SFTP reads at any offset natively.
 */
internal class SftpFiles(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteFiles {

    private var session: Session? = null
    private val idle = ArrayDeque<ChannelSftp>()

    override fun list(dir: String): List<DirectoryWalk.Entry>? = runCatching {
        use { ch ->
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(dir.ifEmpty { "/" }) as Vector<ChannelSftp.LsEntry>
            entries.filter { it.filename != "." && it.filename != ".." }.map { e ->
                // A link to a folder (a NAS's share of a share) is a folder to walk into.
                val isDir = e.attrs.isDir || (e.attrs.isLink && runCatching { ch.stat("${dir.trimEnd('/')}/${e.filename}").isDir }.getOrDefault(false))
                DirectoryWalk.Entry(e.filename, isDir, e.attrs.size.takeIf { !isDir })
            }
        }
    }.getOrElse { e ->
        if (e is SourceException && e.fault != SourceFault.NO_FOLDER) throw e
        RemoteServers.log("sftp $host: list $dir failed: ${e.message}")
        null
    }

    override fun size(path: String): Long? = runCatching { use { it.stat(path).size } }.getOrNull()

    override fun read(path: String, start: Long, length: Long, out: OutputStream): Long {
        if (length <= 0) return 0
        return use { ch ->
            var copied = 0L
            ch.get(path, null, start).use { input ->
                val buf = ByteArray(256 * 1024)
                while (copied < length) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - copied).toInt())
                    if (n < 0) break
                    // The reader hanging up (a player's seek) ends the run; the channel stays good.
                    if (runCatching { out.write(buf, 0, n) }.isFailure) break
                    copied += n
                }
            }
            copied
        }
    }

    @Synchronized
    override fun close() {
        idle.forEach { runCatching { it.disconnect() } }
        idle.clear()
        runCatching { session?.disconnect() }
        session = null
    }

    /** [block] on an open channel, kept for the next use unless it failed. */
    private inline fun <T> use(block: (ChannelSftp) -> T): T {
        val ch = borrow()
        val result = try {
            block(ch)
        } catch (e: SftpException) {
            // An answer about a file (none such, not allowed) leaves the channel good.
            giveBack(ch)
            throw if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) SourceException(SourceFault.NO_FOLDER, e.message, e)
            else if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) SourceException(SourceFault.AUTH, e.message, e)
            else e
        } catch (e: Exception) {
            runCatching { ch.disconnect() }
            throw e
        }
        giveBack(ch)
        return result
    }

    private fun borrow(): ChannelSftp {
        synchronized(this) {
            while (idle.isNotEmpty()) {
                val ch = idle.removeLast()
                if (ch.isConnected && !ch.isClosed) return ch
            }
        }
        val s = session()
        return try {
            (s.openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_MS) }
        } catch (e: JSchException) {
            // The session died under us (the server restarted, the link dropped): once more, afresh.
            synchronized(this) { runCatching { session?.disconnect() }; session = null }
            (session().openChannel("sftp") as ChannelSftp).apply { connect(CONNECT_MS) }
        }
    }

    private fun giveBack(ch: ChannelSftp) {
        synchronized(this) {
            if (ch.isConnected && idle.size < MAX_CHANNELS) idle.addLast(ch) else runCatching { ch.disconnect() }
        }
    }

    @Synchronized
    private fun session(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        val s = JSch().apply { hostKeyRepository = PinnedHostKeyRepository }.getSession(username, host, port)
        s.setPassword(password)
        // The pinned key decides (PinnedHostKeys): a first sight is kept, a changed one refused.
        s.setConfig("StrictHostKeyChecking", "yes")
        // Password logins only: no keyboard-interactive prompt to hang on, no agent, no keys of ours.
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.userInfo = PasswordOnly(password)
        s.timeout = READ_MS
        try {
            s.connect(CONNECT_MS)
        } catch (e: JSchChangedHostKeyException) {
            throw SourceException(SourceFault.HOST_CHANGED, "sftp $host: host key changed", e)
        } catch (e: JSchException) {
            val m = e.message.orEmpty()
            throw when {
                // JSch says so in a plain JSchException as often as in its own subclass.
                m.contains("HostKey has been changed", true) -> SourceException(SourceFault.HOST_CHANGED, "sftp $host: host key changed", e)
                m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> SourceException(SourceFault.AUTH, "sftp $host: $m", e)
                else -> SourceException(SourceFault.UNREACHABLE, "sftp $host:$port: $m", e)
            }
        }
        session = s
        return s
    }

    /** Answers a keyboard-interactive "Password:" with the password; asks nothing else of anyone. */
    private class PasswordOnly(private val password: String) : UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?) = true
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? = prompt?.map { password }?.toTypedArray()
    }

    private companion object {
        const val MAX_CHANNELS = 4
        const val CONNECT_MS = 8_000
        const val READ_MS = 20_000
    }
}

/**
 * The SFTP servers' keys, as first seen: trust on first use, the way ssh asks "are you sure you want
 * to continue connecting" — without asking, since a headset has no one to ask at that moment — and a
 * key that differs later is refused (SourceFault.HOST_CHANGED) until the source is saved again.
 * Kept in [file] when the platform names one (the app's data folder), else for the run.
 */
object PinnedHostKeys {

    @Volatile var file: File? = null

    private val keys = HashMap<String, String>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        file?.takeIf { it.isFile }?.readLines()?.forEach { line ->
            val host = line.substringBefore(' ')
            val key = line.substringAfter(' ', "")
            if (host.isNotBlank() && key.isNotBlank()) keys[host] = key
        }
    }

    /** Whether [key] may be [host]'s ("[host]:port" off port 22): the pinned one, or the first seen. */
    @Synchronized
    internal fun accept(host: String, key: ByteArray): Boolean {
        load()
        val seen = Base64.getEncoder().encodeToString(key)
        val pinned = keys[host]
        if (pinned == null) {
            keys[host] = seen
            file?.let { f -> runCatching { f.parentFile?.mkdirs(); f.appendText("$host $seen\n") } }
            RemoteServers.log("sftp $host: key pinned on first sight")
            return true
        }
        return pinned == seen
    }

    /** Forgets [host]'s key — the user saved the source again, accepting the server as it now is. */
    @Synchronized
    fun forget(host: String, port: Int) {
        load()
        val name = if (port == 22) host else "[$host]:$port"
        if (keys.remove(name) != null) {
            file?.let { f -> runCatching { f.writeText(keys.entries.joinToString("") { "${it.key} ${it.value}\n" }) } }
        }
    }

    /** For tests: nothing pinned, nothing on disk. */
    @Synchronized
    internal fun reset() {
        keys.clear()
        loaded = file == null
    }
}

/** [PinnedHostKeys] as JSch asks it. */
internal object PinnedHostKeyRepository : HostKeyRepository {
    override fun check(host: String, key: ByteArray): Int =
        if (PinnedHostKeys.accept(host, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "aura-pinned"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
