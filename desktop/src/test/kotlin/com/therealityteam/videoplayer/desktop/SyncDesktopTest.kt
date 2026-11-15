package com.therealityteam.videoplayer.desktop

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.desktop.sync.NetworkMount
import com.therealityteam.videoplayer.desktop.sync.NetworkMounts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's half of sync: a share mounted by the system is known by the same keys as the
 * headset's `smb://` paths, and two computers pointed at one folder end up in the same place.
 */
class SyncDesktopTest {

    @Test
    fun `a Mac's mount table names its shares`() {
        val out = """
            /dev/disk3s1s1 on / (apfs, sealed, local, read-only, journaled)
            //GUEST:@DS920._smb._tcp.local/video on /Volumes/video (smbfs, nodev, nosuid, mounted by anna)
            //anna@192.168.1.10/My%20Films on /Volumes/My Films (smbfs, nodev, nosuid, mounted by anna)
            nas.local:/volume1/anime on /Volumes/anime (nfs, nodev, nosuid, mounted by anna)
            /dev/disk5s1 on /Volumes/Backup (hfs, local, nodev, nosuid, journaled)
        """.trimIndent()
        val mounts = NetworkMounts.parseMount(out)
        assertEquals(
            listOf(
                NetworkMount("/Volumes/video", "video", "", "smbfs"),
                NetworkMount("/Volumes/My Films", "My Films", "", "smbfs"),
                NetworkMount("/Volumes/anime", "anime", "", "nfs"),
            ),
            mounts,
        )
        // The Mac's path and the headset's URL of one file: one key.
        assertEquals("video/films/dune.mkv", NetworkMounts.keyOf("/Volumes/video/Films/Dune.mkv", mounts))
        assertEquals("my films/amélie.mkv", NetworkMounts.keyOf("/Volumes/My Films/Amélie.mkv", mounts))
        // A local disk, and a folder whose name merely starts like a mount, are not shares.
        assertNull(NetworkMounts.keyOf("/Volumes/Backup/Films/Dune.mkv", mounts))
        assertNull(NetworkMounts.keyOf("/Volumes/video2/Dune.mkv", mounts))
    }

    @Test
    fun `Windows drive letters and Linux mounts name theirs too`() {
        val netUse = """
            New connections will be remembered.

            Status       Local     Remote                    Network
            -------------------------------------------------------------------------------
            OK           Z:        \\nas\video               Microsoft Windows Network
            OK           Y:        \\nas\media\Anime         Microsoft Windows Network
            The command completed successfully.
        """.trimIndent()
        val win = NetworkMounts.parseNetUse(netUse)
        assertEquals(listOf(NetworkMount("Z:", "video", "", "smb"), NetworkMount("Y:", "media", "Anime", "smb")), win)
        assertEquals("media/anime/frieren/01.mkv", win[1].keyOf("Y:\\Frieren\\01.mkv"))
        assertEquals("video/films/dune.mkv", NetworkMounts.keyOf("\\\\NAS\\video\\Films\\Dune.mkv", emptyList()))

        val linux = NetworkMounts.parseProcMounts("//nas/video /mnt/nas\\040video cifs rw,relatime 0 0\n/dev/sda1 / ext4 rw 0 0")
        assertEquals(listOf(NetworkMount("/mnt/nas video", "video", "", "cifs")), linux)
    }

    /** Two computers, one folder: B opens the film where A left it, and sees A in its list. */
    @Test
    fun `two computers pointed at one folder agree`() {
        val nas = Files.createTempDirectory("aura-nas").toFile()
        fun computer(host: String): AppGraph {
            val g = AppGraph(Files.createTempDirectory("aura-desk").toFile())
            g.media.upsertAll(listOf(
                MediaItem(id = "$host-dune", sourceId = "s", path = "smb://$host/video/Films/Dune.mkv", fileName = "Dune.mkv", title = "Dune", kind = MediaKind.MOVIE),
            ))
            runBlocking { g.media.loaded.first { it } }
            g.settings.update { it.copy(syncEnabled = true, syncLocation = "folder:${nas.absolutePath}", deviceName = "Computer $host") }
            return g
        }
        val a = computer("192.168.1.10")
        val b = computer("nas.local")
        a.media.mergeProgress("192.168.1.10-dune", PlaybackProgress(4_200_000, 9_000_000, false, System.currentTimeMillis() - 5_000))
        val ra = runBlocking { a.sync.syncNow() }
        assertNotNull(ra)
        assertEquals(true, ra.writable, "A wrote: ${ra.error}")
        assertTrue(File(nas, ".aura/devices/${a.settings.value.deviceId}/progress.json").isFile)
        val rb = runBlocking { b.sync.syncNow() }
        assertNotNull(rb)
        assertEquals(4_200_000, b.media.get("nas.local-dune")!!.progress.positionMs)
        assertEquals(listOf("Computer 192.168.1.10"), rb.devices.map { it.name })
        assertEquals("folder:${nas.absolutePath}", b.sync.status.value.location?.id)
    }
}
