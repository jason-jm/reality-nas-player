package com.therealityteam.videoplayer.data.playback

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare

/**
 * Prototype SMB→player bridge using a native seekable file descriptor instead of a loopback
 * HTTP proxy: [StorageManager.openProxyFileDescriptor] hands the player a fd whose reads are
 * served by [android.os.storage.ProxyFileDescriptorCallback], which random-reads the SMB file.
 * No HTTP server, no cleartext config. Compared against [LocalStreamServer] behind a setting.
 */
class SmbFdBridge(private val context: Context) {

    /** Open resources held for the duration of one playback; close on stop/switch. */
    class Handle internal constructor(
        val pfd: ParcelFileDescriptor,
        val size: Long,
        private val onClose: () -> Unit,
    ) {
        fun close() {
            runCatching { pfd.close() }
            onClose()
        }
    }

    fun open(smbUri: String, username: String?, password: String?): Handle? {
        val loc = parseSmb(smbUri) ?: return null
        val client = streamingSmbClient()
        return try {
            val connection = client.connect(loc.host)
            val auth = if (username.isNullOrEmpty()) AuthenticationContext.anonymous()
            else AuthenticationContext(username, (password ?: "").toCharArray(), null)
            val session = connection.authenticate(auth)
            val disk = session.connectShare(loc.share) as DiskShare
            val size = disk.getFileInformation(loc.path).standardInformation.endOfFile
            val file = disk.openFile(
                loc.path,
                setOf(AccessMask.GENERIC_READ),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val thread = HandlerThread("aura-smb-fd").apply { start() }
            val handler = Handler(thread.looper)
            val storage = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

            val callback = object : android.os.ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size

                override fun onRead(offset: Long, requested: Int, data: ByteArray): Int {
                    var read = 0
                    while (read < requested) {
                        val n = synchronized(file) { file.read(data, offset + read, read, requested - read) }
                        if (n <= 0) break
                        read += n
                    }
                    return read
                }

                override fun onRelease() { /* resources freed by Handle.close() */ }
            }

            val pfd = storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
            Handle(pfd, size) {
                runCatching { file.close() }
                runCatching { disk.close() }
                runCatching { client.close() }
                thread.quitSafely()
            }
        } catch (e: Exception) {
            runCatching { client.close() }
            null
        }
    }

    private data class SmbLoc(val host: String, val share: String, val path: String)

    private fun parseSmb(uri: String): SmbLoc? {
        val parts = uri.removePrefix("smb://").removePrefix("//").trim('/').split('/')
        if (parts.size < 3) return null
        return SmbLoc(parts[0], parts[1], parts.drop(2).joinToString("\\"))
    }
}
