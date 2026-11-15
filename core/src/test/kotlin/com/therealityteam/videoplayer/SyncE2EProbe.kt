package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaKind
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.sync.LibrarySync
import com.therealityteam.videoplayer.data.sync.SmbSyncStore
import com.therealityteam.videoplayer.data.sync.SyncDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The desktop's side of a sync with the headset, through a real SMB share both reach: step 1 writes
 * where this "computer" stopped in Dune; step 3 reads what the headset wrote back (its place in
 * Arrival) after HeadsetSyncInstrumentedTest ran on the emulator in between. Dune is also fixed by
 * hand here, so the headset is offered that fix and files its own copy the same way.
 *
 *   ./gradlew :core:test --tests '*SyncE2EProbe*' -Daura.sync.e2e=127.0.0.1:4445/video -Daura.sync.step=1
 */
class SyncE2EProbe {
    @Test fun step(): Unit = runBlocking {
        val target = System.getProperty("aura.sync.e2e")
        val step = System.getProperty("aura.sync.step")
        assumeTrue(target != null && step != null)
        val host = target!!.substringBefore('/')
        val share = target.substringAfter('/')
        val dir = File(System.getProperty("java.io.tmpdir"), "aura-sync-e2e-desktop").apply { mkdirs() }
        val repo = JsonMediaRepository(dir)
        repo.loaded.first { it }
        val dune = "smb://$host/$share/Films/Dune.mkv"
        val arrival = "smb://$host/$share/Films/Arrival.mkv"
        if (step == "1") {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            val fresh = JsonMediaRepository(dir).also { it.loaded.first { l -> l } }
            fresh.upsertAll(
                listOf(
                    // Fixed by hand here (修正匹配) to Dune (2021): the headset is to take that in too.
                    MediaItem("dune", "nas", dune, "Dune.mkv", title = "Dune", tmdbId = 438631, kind = MediaKind.MOVIE, userMatched = true,
                        progress = PlaybackProgress(positionMs = 1_234_000, durationMs = 9_000_000, updatedAt = System.currentTimeMillis())),
                    MediaItem("arrival", "nas", arrival, "Arrival.mkv", title = "Arrival"),
                ),
            )
            fresh.flush()
            println("SYNC-E2E step 1: " + sync(fresh, host, share, dir))
        } else {
            println("SYNC-E2E step 3: " + sync(repo, host, share, dir))
            val a = repo.all().first { it.id == "arrival" }
            println("SYNC-E2E arrival here now: ${a.progress.positionMs} ms of ${a.progress.durationMs}")
        }
    }

    private fun sync(repo: JsonMediaRepository, host: String, share: String, dir: File) = LibrarySync(
        repo = repo,
        store = SmbSyncStore(host, share, LibrarySync.FOLDER, "guest", ""),
        device = SyncDevice("desktop-e2e", "Test Mac", "macOS", "Aura test"),
        stateFile = File(dir, "sync-state.json"),
    ).sync().let { r -> "applied=${r.applied} writable=${r.writable} error=${r.error} devices=${r.devices.map { "${it.name}/${it.platform}" }}" }
}
