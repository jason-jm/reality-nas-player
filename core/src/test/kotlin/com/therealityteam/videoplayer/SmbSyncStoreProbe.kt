package com.therealityteam.videoplayer

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.sync.LibrarySync
import com.therealityteam.videoplayer.data.sync.SmbSyncStore
import com.therealityteam.videoplayer.data.sync.SyncDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The sync folder on a real SMB server: whole-file replacement by rename, the hidden attribute,
 * removal — and two devices syncing through it. Point it at a share it may write to:
 *
 *   ./gradlew :core:test --tests '*SmbSyncStoreProbe*' -Daura.smb.sync=127.0.0.1:4445/video
 *
 * (A throwaway Samba on the loopback: `samba-dot-org-smbd --foreground -s smb.conf` with a
 * guest share and `smb ports = 4445`.)
 */
class SmbSyncStoreProbe {
    private val target = System.getProperty("aura.smb.sync")

    @Test fun `the folder behaves on a real server`() {
        assumeTrue("set -Daura.smb.sync=host[:port]/share", target != null)
        val host = target!!.substringBefore('/')
        val share = target.substringAfter('/')
        val base = ".aura-probe-${System.nanoTime()}"
        val store = SmbSyncStore(host, share, base, "guest", "")

        store.mkdirs("")
        store.writeAtomic("devices/a/progress.json", "one".toByteArray())
        assertArrayEquals("one".toByteArray(), store.read("devices/a/progress.json"))
        // Replaced whole, through the rename, over the old file.
        store.writeAtomic("devices/a/progress.json", "two, longer".toByteArray())
        assertArrayEquals("two, longer".toByteArray(), store.read("devices/a/progress.json"))
        assertEquals(listOf("a/"), store.list("devices"))
        assertEquals(listOf("progress.json"), store.list("devices/a"))
        assertNull(store.read("devices/a/nothing.json"))
        assertEquals(11L, store.size(""))

        store.hide("")
        val hidden = raw(host, share) { d -> d.getFileInformation(base).basicInformation.fileAttributes }
        println("SMBPROBE attributes of $base: 0x${hidden.toString(16)}")
        assertTrue("hidden attribute set", hidden and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L)

        // Two devices through it, one connection per sync.
        fun repo(id: String) = JsonMediaRepository(Files.createTempDirectory("aura-smbprobe").toFile()).apply {
            upsertAll(listOf(MediaItem(id = "$id-x", sourceId = "s", path = "smb://nas/video/Films/X.mkv", fileName = "X.mkv", title = "X", kind = MediaKind.MOVIE)))
        }
        val ra = repo("a")
        val rb = repo("b")
        ra.mergeProgress("a-x", PlaybackProgress(1_000_000, 2_000_000, false, 1_000))
        val syncA = LibrarySync(ra, store, SyncDevice("dev-a", "A", "test", "1"), File.createTempFile("state", ".json").apply { delete() }) { 1_100 }
        val syncB = LibrarySync(rb, store, SyncDevice("dev-b", "B", "test", "1"), File.createTempFile("state", ".json").apply { delete() }) { 1_200 }
        val t0 = System.nanoTime()
        val reportA = syncA.sync()
        val t1 = System.nanoTime()
        val reportB = syncB.sync()
        val t2 = System.nanoTime()
        println("SMBPROBE sync A ${(t1 - t0) / 1_000_000} ms (wrote ${reportA.writable}), B ${(t2 - t1) / 1_000_000} ms, folder ${reportB.bytes} bytes")
        assertEquals(null, reportA.error)
        assertEquals(1_000_000, rb.get("b-x")!!.progress.positionMs)
        assertEquals(listOf("A"), reportB.devices.map { it.name })

        store.deleteTree("")
        assertFalse(raw(host, share) { d -> d.folderExists(base) })
    }

    private fun <T> raw(host: String, share: String, block: (DiskShare) -> T): T =
        SMBClient().use { c ->
            val port = host.substringAfter(':', "").toIntOrNull()
            (if (port != null) c.connect(host.substringBefore(':'), port) else c.connect(host)).use { conn ->
                (conn.authenticate(AuthenticationContext("guest", CharArray(0), null)).connectShare(share) as DiskShare).use(block)
            }
        }
}
