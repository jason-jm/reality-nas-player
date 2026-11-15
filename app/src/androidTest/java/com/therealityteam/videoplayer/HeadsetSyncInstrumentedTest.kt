package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.PlaybackProgress
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.AppSettings
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.sync.HeadsetSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The headset's side of a sync with a desktop, through a real SMB share both reach (`-e smb
 * 10.0.2.2:4445/video` reaches a share on the computer running the emulator). Between the
 * desktop's two steps of SyncE2EProbe: it takes the desktop's place in Dune and writes its own in
 * Arrival. Uses its own library and settings, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class HeadsetSyncInstrumentedTest {
    @Test
    fun syncWithDesktop() {
        val target = InstrumentationRegistry.getArguments().getString("smb") ?: return
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val host = target.substringBefore('/')
        val share = target.substringAfter('/')
        val dir = File(ctx.cacheDir, "sync-e2e").apply { deleteRecursively(); mkdirs() }
        val repo = JsonMediaRepository(dir)
        runBlocking { repo.loaded.first { it } }
        repo.upsertAll(
            listOf(
                MediaItem("dune", "nas", "smb://$host/$share/Films/Dune.mkv", "Dune.mkv", title = "Dune"),
                MediaItem(
                    "arrival", "nas", "smb://$host/$share/Films/Arrival.mkv", "Arrival.mkv", title = "Arrival",
                    progress = PlaybackProgress(positionMs = 600_000, durationMs = 6_960_000, updatedAt = System.currentTimeMillis()),
                ),
            ),
        )
        val config = MediaSourceConfig("nas", SourceType.SMB, "nas", "smb://$host/$share", username = "guest", password = "")
        val sources = object : SourceRepository {
            override val sources: StateFlow<List<MediaSourceConfig>> = MutableStateFlow(listOf(config))
            override fun all() = listOf(config)
            override fun get(id: String) = config.takeIf { it.id == id }
            override fun upsert(source: MediaSourceConfig) {}
            override fun remove(id: String) {}
        }
        val settings = object : SettingsRepository {
            private val flow = MutableStateFlow(AppSettings(syncEnabled = true, deviceId = "headset-e2e", deviceName = "PICO test"))
            override val settings: StateFlow<AppSettings> = flow
            override fun get() = flow.value
            override fun update(value: AppSettings) { flow.value = value }
        }
        // The real engine and TMDB, over this test's own library: a fix made on the desktop is
        // filed here by fetching its entry, as it would be on the headset.
        val engine = com.therealityteam.videoplayer.data.scrape.ScrapeEngine(
            com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient(dir),
            com.therealityteam.videoplayer.data.scrape.ArtworkCache(dir) { _, _, _ -> null },
            repo,
            com.therealityteam.videoplayer.data.scrape.AniListClient(dir),
            com.therealityteam.videoplayer.data.scrape.BangumiClient(dir),
            com.therealityteam.videoplayer.data.scrape.OmdbClient(dir),
            com.therealityteam.videoplayer.data.scrape.JikanClient(dir),
            com.therealityteam.videoplayer.data.scrape.ImdbDatasetClient(dir),
        )
        val sync = HeadsetSync(ctx, repo, sources, settings, engine)
        val report = runBlocking { sync.syncNow() }!!
        val dune = repo.get("dune")!!
        Log.i(TAG, "report: applied=${report.applied} writable=${report.writable} error=${report.error} devices=${report.devices.map { "${it.name}/${it.platform}" }}")
        Log.i(TAG, "dune here now: ${dune.progress.positionMs} ms of ${dune.progress.durationMs}; tmdb=${dune.tmdbId} '${dune.title}' pinned=${dune.userMatched}")
        assertNull(report.error)
        assertTrue(report.devices.any { it.name == "Test Mac" })
        assertEquals(1_234_000L, dune.progress.positionMs)
        assertEquals(438631, dune.tmdbId)
        assertTrue(dune.userMatched)
        dir.deleteRecursively()
    }

    private companion object { const val TAG = "HeadsetSyncE2E" }
}
