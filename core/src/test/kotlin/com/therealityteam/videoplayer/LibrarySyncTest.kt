package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.sync.LibrarySync
import com.therealityteam.videoplayer.data.sync.LocalSyncStore
import com.therealityteam.videoplayer.data.sync.SyncCorrection
import com.therealityteam.videoplayer.data.sync.SyncDevice
import com.therealityteam.videoplayer.data.sync.SyncKeys
import com.therealityteam.videoplayer.data.sync.SyncStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.Normalizer

/**
 * Two devices, one sync folder: what one does must reach the other, the newer of two changes must
 * win whichever device syncs first, and nothing but shared files may be written to the NAS.
 */
class LibrarySyncTest {

    private val nasFolder: File = Files.createTempDirectory("aura-sync").toFile()
    private val store = LocalSyncStore(File(nasFolder, ".aura"))

    /** One device: its own library, its own memory, the shared folder. */
    private inner class Device(val id: String, host: String) {
        val dir: File = Files.createTempDirectory("aura-$id").toFile()
        val repo = JsonMediaRepository(dir)
        var clock = 0L
        val sync = LibrarySync(repo, store, SyncDevice(id, "Device $id", "test", "1"), File(dir, "aura/sync-state.json")) { clock }

        // The same two films as this device reaches them, and one file only it has.
        val dune = item("$id-dune", "smb://$host/video/Films/Dune (2021)/Dune.mkv")
        val amelie = item("$id-amelie", "smb://$host/video/Films/Amélie.mkv")
        val home = item("$id-home", "/Users/someone/Movies/Holiday.mp4")

        init { repo.upsertAll(listOf(dune, amelie, home)) }

        fun sync(at: Long) = run { clock = at; sync.sync() }
        fun progress(item: MediaItem) = repo.get(item.id)!!.progress
        fun favorite(item: MediaItem) = repo.get(item.id)!!.favorite
    }

    private fun item(id: String, path: String) = MediaItem(
        id = id, sourceId = "s", path = path, fileName = path.substringAfterLast('/'),
        title = path.substringAfterLast('/'), kind = MediaKind.MOVIE,
    )

    @Test fun `a file has one key whatever the host, case or Unicode form`() {
        val byAddress = SyncKeys.ofSmb("smb://192.168.1.10/Video/Films/Amélie.mkv")
        val byName = SyncKeys.ofSmb("smb://nas.local/video/films/" + Normalizer.normalize("Amélie.mkv", Normalizer.Form.NFD))
        assertEquals("video/films/amélie.mkv", byAddress)
        assertEquals(byAddress, byName)
        assertEquals(byAddress, SyncKeys.of("video", "Films\\Amélie.mkv"))
        assertNull(SyncKeys.ofSmb("smb://nas/video"))
        assertNull(SyncKeys.ofSmb("/Volumes/video/Films/Amélie.mkv"))
        assertNull(SyncKeys.ofSmb("https://nas:5006/video/Films/Amélie.mkv"))
    }

    @Test fun `a position made on one device reaches the other`() {
        val a = Device("a", "192.168.1.10")
        val b = Device("b", "nas.local")
        a.repo.mergeProgress(a.dune.id, PlaybackProgress(3_600_000, 9_000_000, false, updatedAt = 1_000))
        a.sync(at = 1_100)
        val report = b.sync(at = 1_200)
        assertEquals(PlaybackProgress(3_600_000, 9_000_000, false, 1_000), b.progress(b.dune))
        assertEquals(1, report.applied)
        assertEquals(listOf("Device a"), report.devices.map { it.name })
        assertEquals(true, report.writable)
    }

    @Test fun `the newer position wins whichever device syncs first`() {
        val a = Device("a", "nas")
        val b = Device("b", "nas")
        a.repo.mergeProgress(a.dune.id, PlaybackProgress(1_000_000, 9_000_000, false, updatedAt = 1_000))
        b.repo.mergeProgress(b.dune.id, PlaybackProgress(5_000_000, 9_000_000, false, updatedAt = 2_000))
        // The newer device syncs first, the older second: the older must not win on the way back.
        b.sync(at = 2_100)
        a.sync(at = 2_200)
        b.sync(at = 2_300)
        assertEquals(5_000_000, a.progress(a.dune).positionMs)
        assertEquals(5_000_000, b.progress(b.dune).positionMs)
    }

    @Test fun `marking a film unwatched reaches the device that watched it`() {
        val a = Device("a", "nas")
        val b = Device("b", "nas")
        a.repo.mergeProgress(a.amelie.id, PlaybackProgress(7_000_000, 7_000_000, true, updatedAt = 1_000))
        a.sync(at = 1_100)
        b.sync(at = 1_200)
        assertTrue(b.progress(b.amelie).watched)
        // B resets it — position 0, not watched, but a time — and A must hear of that.
        b.repo.mergeProgress(b.amelie.id, PlaybackProgress(0, 7_000_000, false, updatedAt = 3_000))
        b.sync(at = 3_100)
        a.sync(at = 3_200)
        assertFalse(a.progress(a.amelie).watched)
        assertEquals(0, a.progress(a.amelie).positionMs)
    }

    @Test fun `a favourite marked here is not undone by an older unmark from elsewhere`() {
        val a = Device("a", "nas")
        val b = Device("b", "nas")
        // B marks, syncs, unmarks, syncs: the folder says "not a favourite, as of 200".
        b.repo.setFavorite(b.dune.id, true); b.sync(at = 100)
        b.repo.setFavorite(b.dune.id, false); b.sync(at = 200)
        // A, later, marks it.
        a.repo.setFavorite(a.dune.id, true)
        a.sync(at = 300)
        assertTrue("A's own newer mark stands", a.favorite(a.dune))
        b.sync(at = 400)
        assertTrue("and reaches B", b.favorite(b.dune))
        // B unmarks again, later still: that wins on A.
        b.repo.setFavorite(b.dune.id, false); b.sync(at = 500)
        a.sync(at = 600)
        assertFalse(a.favorite(a.dune))
    }

    @Test fun `only files on a share are written, and only into the device's own folder`() {
        val a = Device("a", "nas")
        val b = Device("b", "nas")
        b.sync(at = 50)
        val bFiles = File(nasFolder, ".aura/devices/b").listFiles()!!.associate { it.name to it.readText() }
        a.repo.mergeProgress(a.home.id, PlaybackProgress(60_000, 600_000, false, updatedAt = 900))
        a.repo.mergeProgress(a.dune.id, PlaybackProgress(60_000, 9_000_000, false, updatedAt = 1_000))
        a.repo.setFavorite(a.home.id, true)
        a.sync(at = 1_100)
        val written = File(nasFolder, ".aura/devices/a").listFiles()!!.joinToString("\n") { it.readText() }
        assertFalse("a file on this computer's own disk stays off the NAS", written.contains("holiday", ignoreCase = true))
        assertTrue(written.contains("video/films/dune (2021)/dune.mkv"))
        assertEquals("B's files untouched", bFiles, File(nasFolder, ".aura/devices/b").listFiles()!!.associate { it.name to it.readText() })
        assertEquals(setOf("README.txt", "devices"), File(nasFolder, ".aura").list()!!.toSet())
    }

    @Test fun `a read-only share still brings positions in`() {
        val b = Device("b", "nas")
        b.repo.mergeProgress(b.dune.id, PlaybackProgress(2_000_000, 9_000_000, false, updatedAt = 1_000))
        b.sync(at = 1_100)
        val dir = Files.createTempDirectory("aura-ro").toFile()
        val repo = JsonMediaRepository(dir).apply { upsertAll(listOf(item("ro-dune", "smb://nas/video/Films/Dune (2021)/Dune.mkv"))) }
        val readOnly = object : SyncStore by store {
            override fun writeAtomic(path: String, bytes: ByteArray) = throw IOException("read-only")
            override fun mkdirs(dir: String) = throw IOException("read-only")
        }
        val report = LibrarySync(repo, readOnly, SyncDevice("ro", "RO", "test", "1"), File(dir, "state.json")) { 2_000 }.sync()
        assertEquals(false, report.writable)
        assertNull(report.error)
        assertEquals(2_000_000, repo.get("ro-dune")!!.progress.positionMs)
    }

    @Test fun `a sync with nothing new leaves the NAS alone`() {
        val a = Device("a", "nas")
        val writes = mutableListOf<String>()
        val counting = object : SyncStore by store {
            override fun writeAtomic(path: String, bytes: ByteArray) { writes += path; store.writeAtomic(path, bytes) }
        }
        val sync = LibrarySync(a.repo, counting, SyncDevice("a", "Device a", "test", "1"), File(a.dir, "aura/sync-state.json")) { a.clock }
        a.repo.mergeProgress(a.dune.id, PlaybackProgress(60_000, 9_000_000, false, updatedAt = 1_000))
        a.clock = 1_100; sync.sync()
        assertTrue(writes.isNotEmpty())
        writes.clear()
        a.clock = 1_200
        assertNull("nothing tried", sync.sync().writable)
        assertEquals(emptyList<String>(), writes)
        // One position moves: that file is written again, and who wrote it — nothing else.
        a.repo.mergeProgress(a.dune.id, PlaybackProgress(120_000, 9_000_000, false, updatedAt = 1_300))
        a.clock = 1_400; sync.sync()
        assertEquals(listOf("devices/a/progress.json", "devices/a/device.json"), writes)
        // Removed from elsewhere: everything is written again at the next sync.
        writes.clear()
        File(nasFolder, ".aura").deleteRecursively()
        a.clock = 1_500; sync.sync()
        assertEquals(setOf("README.txt", "devices/a/progress.json", "devices/a/favorites.json", "devices/a/device.json"), writes.toSet())
    }

    @Test fun `removing the folder takes everything with it`() {
        val a = Device("a", "nas")
        a.repo.setFavorite(a.dune.id, true)
        a.sync(at = 100)
        assertTrue(File(nasFolder, ".aura/README.txt").isFile)
        a.sync.remove()
        assertFalse(File(nasFolder, ".aura").exists())
    }

    /** What the user was told the folder costs: kilobytes, even for a heavy viewer. */
    @Test fun `a thousand watched files make a small file`() {
        val dir = Files.createTempDirectory("aura-big").toFile()
        val repo = JsonMediaRepository(dir)
        repo.upsertAll((0 until 1_000).map { item("f$it", "smb://nas/video/Series/Show Name S01/Show.Name.S01E${it % 100}.1080p.WEB-DL.x264-GROUP.$it.mkv") })
        (0 until 1_000).forEach { repo.mergeProgress("f$it", PlaybackProgress(1_234_567, 2_700_000, it % 3 == 0, updatedAt = 1_000L + it)) }
        (0 until 50).forEach { repo.setFavorite("f$it", true) }
        val report = LibrarySync(repo, store, SyncDevice("big", "Big", "test", "1"), File(dir, "state.json")) { 5_000 }.sync()
        println("SYNC 1000 played files, 50 favourites: ${report.bytes} bytes in the folder")
        assertTrue(report.bytes in 1..200_000)
    }

    /** A match fixed by hand on [d], as 修正匹配 files it: pinned to a TMDB entry or an AniList one. */
    private fun pin(d: Device, item: MediaItem, tmdb: Int? = null, anilist: Int? = null, kind: MediaKind = MediaKind.MOVIE) {
        val cur = d.repo.get(item.id)!!
        d.repo.upsert(cur.copy(userMatched = true, tmdbId = tmdb, collectionKey = anilist?.let { "anilist:$it" }, kind = kind))
    }

    @Test fun `a match fixed by hand on one device is offered to the other until it is filed so`() {
        val a = Device("a", "192.168.1.10")
        val b = Device("b", "nas.local")
        pin(a, a.dune, tmdb = 438631)
        a.sync(at = 1_000)
        val offered = listOf(SyncCorrection(listOf(b.dune.id), "tmdb", 438631, MediaKind.MOVIE))
        assertEquals(offered, b.sync(at = 1_100).corrections)
        // Not taken in until the files are filed that way (adopting fetches the entry): offered again.
        assertEquals(offered, b.sync(at = 1_200).corrections)
        // Filed so, as the engine's adoptMatch files it: this device's own now, offered no more…
        pin(b, b.dune, tmdb = 438631)
        assertTrue(b.sync(at = 1_300).corrections.isEmpty())
        // …and never offered back to the device that made it.
        assertTrue(a.sync(at = 1_400).corrections.isEmpty())
    }

    @Test fun `the later of two fixes wins on both devices`() {
        val a = Device("a", "nas")
        val b = Device("b", "nas")
        pin(a, a.dune, tmdb = 1)
        a.sync(at = 1_000)
        pin(b, b.dune, tmdb = 2)
        // This device's own fix is the later one: the earlier is not offered to it.
        assertTrue(b.sync(at = 2_000).corrections.isEmpty())
        assertEquals(listOf(SyncCorrection(listOf(a.dune.id), "tmdb", 2, MediaKind.MOVIE)), a.sync(at = 3_000).corrections)
    }

    @Test fun `an anime fix names its AniList entry, and a file only this device has stays here`() {
        val a = Device("a", "nas")
        val b = Device("b", "nas")
        pin(a, a.amelie, anilist = 21, kind = MediaKind.EPISODE)
        pin(a, a.home, tmdb = 5)
        a.sync(at = 1_000)
        assertEquals(listOf(SyncCorrection(listOf(b.amelie.id), "anilist", 21, MediaKind.SHOW)), b.sync(at = 1_100).corrections)
        val written = File(nasFolder, ".aura/devices/a/corrections.json").readText()
        assertTrue(written.contains("anilist"))
        assertFalse(written.contains("holiday", ignoreCase = true))
    }
}
