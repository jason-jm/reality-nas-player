package com.therealityteam.videoplayer.data.sync

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.therealityteam.videoplayer.data.source.connectTo
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The folder sync writes to — the `.aura` folder at the root of a share or a folder the user
 * chose. Paths are relative to it, `/`-separated. Everything here is small and whole-file:
 * one device's files are rewritten in one go, never appended to or locked.
 */
interface SyncStore {
    /** The folder's own name for people ("NAS/video", "/Volumes/video"), for the settings page. */
    val description: String

    fun read(path: String): ByteArray?

    /** Write via a temporary file and a rename, so no reader ever sees half a file. */
    fun writeAtomic(path: String, bytes: ByteArray)

    /** Names directly inside [dir] ("" is the store's root); directories end with `/`. */
    fun list(dir: String): List<String>

    fun mkdirs(dir: String)

    /** Remove [dir] and everything in it. */
    fun deleteTree(dir: String)

    /** Total bytes under [dir]. */
    fun size(dir: String): Long

    /** Hide [dir] from file browsers where the file system has such a notion (SMB's hidden attribute). */
    fun hide(dir: String) {}

    /** Run [block], letting the calls inside it share one connection where the store has one. */
    fun <T> batch(block: () -> T): T = block()
}

/** A folder on this device: a local disk, or a NAS share the OS has mounted (/Volumes/video). */
class LocalSyncStore(private val root: File) : SyncStore {
    override val description: String = root.absolutePath

    private fun f(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun read(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()

    override fun writeAtomic(path: String, bytes: ByteArray) {
        val target = f(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    override fun list(dir: String): List<String> =
        f(dir).listFiles()?.filterNot { it.name.endsWith(".tmp") }?.map { if (it.isDirectory) it.name + "/" else it.name }.orEmpty()

    override fun mkdirs(dir: String) {
        if (!f(dir).mkdirs() && !f(dir).isDirectory) throw IOException("cannot create ${f(dir)}")
    }

    override fun deleteTree(dir: String) {
        f(dir).deleteRecursively()
    }

    override fun size(dir: String): Long = f(dir).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The dot already hides it on a Mac; Windows wants the attribute (a mapped share keeps it). */
    override fun hide(dir: String) {
        runCatching { Files.setAttribute(f(dir).toPath(), "dos:hidden", true) }
    }
}

/**
 * A folder on an SMB share, reached with smbj as the scanner reaches it. [base] is the folder the
 * store stands in, relative to the share ("" = the share's root).
 */
class SmbSyncStore(
    private val host: String,
    private val share: String,
    private val base: String,
    private val username: String?,
    private val password: String?,
) : SyncStore {

    override val description: String = "smb://$host/$share" + (if (base.isEmpty()) "" else "/$base")

    private fun p(path: String): String =
        listOf(base, path).filter { it.isNotEmpty() }.joinToString("/").trim('/').replace('/', '\\')

    /** The share a [batch] holds open on this thread. */
    private val open = ThreadLocal<DiskShare?>()

    private fun <T> disk(block: (DiskShare) -> T): T {
        open.get()?.let { return block(it) }
        return SMBClient().use { client ->
            client.connectTo(host).use { conn ->
                val auth = if (username.isNullOrEmpty()) AuthenticationContext.anonymous()
                else AuthenticationContext(username, (password ?: "").toCharArray(), null)
                val session = conn.authenticate(auth)
                (session.connectShare(share) as DiskShare).use(block)
            }
        }
    }

    /** One connection for a whole sync instead of one per file: a dozen round trips of setup saved. */
    override fun <T> batch(block: () -> T): T {
        if (open.get() != null) return block()
        return disk { d ->
            open.set(d)
            try { block() } finally { open.remove() }
        }
    }

    override fun read(path: String): ByteArray? = disk { d ->
        if (!d.fileExists(p(path))) return@disk null
        d.openFile(p(path), setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            .use { it.inputStream.use { s -> s.readBytes() } }
    }

    override fun writeAtomic(path: String, bytes: ByteArray) = disk { d ->
        val target = p(path)
        val dir = target.substringBeforeLast('\\', "")
        if (dir.isNotEmpty()) mkdirsOn(d, dir)
        val tmp = (if (dir.isEmpty()) "" else "$dir\\") + ".${target.substringAfterLast('\\')}.${UUID.randomUUID()}.tmp"
        val f = d.openFile(
            tmp,
            setOf(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        try {
            f.outputStream.use { it.write(bytes) }
            f.rename(target, true)
        } catch (e: Exception) {
            runCatching { f.deleteOnClose() }
            throw e
        } finally {
            f.close()
        }
    }

    override fun list(dir: String): List<String> = disk { d ->
        if (!d.folderExists(p(dir))) return@disk emptyList()
        d.list(p(dir)).mapNotNull { e ->
            val name = e.fileName
            if (name == "." || name == ".." || name.endsWith(".tmp")) return@mapNotNull null
            val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            if (isDir) "$name/" else name
        }
    }

    override fun mkdirs(dir: String) = disk { d -> mkdirsOn(d, p(dir)) }

    private fun mkdirsOn(d: DiskShare, path: String) {
        var at = ""
        for (part in path.split('\\').filter { it.isNotEmpty() }) {
            at = if (at.isEmpty()) part else "$at\\$part"
            if (!d.folderExists(at)) d.mkdir(at)
        }
    }

    override fun deleteTree(dir: String) = disk { d -> if (d.folderExists(p(dir))) d.rmdir(p(dir), true) }

    override fun size(dir: String): Long = disk { d -> sizeOn(d, p(dir)) }

    private fun sizeOn(d: DiskShare, path: String): Long {
        if (!d.folderExists(path)) return 0
        return d.list(path).sumOf { e ->
            val name = e.fileName
            when {
                name == "." || name == ".." -> 0L
                (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L -> sizeOn(d, "$path\\$name")
                else -> e.endOfFile
            }
        }
    }

    override fun hide(dir: String) {
        runCatching {
            disk { d ->
                val info = d.getFileInformation(p(dir)).basicInformation
                d.setFileInformation(
                    p(dir),
                    FileBasicInformation(
                        info.creationTime, info.lastAccessTime, info.lastWriteTime, info.changeTime,
                        info.fileAttributes or FileAttributes.FILE_ATTRIBUTE_HIDDEN.value,
                    ),
                )
            }
        }
    }
}
