package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.SourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The APP'S OWN scan path against a real Jellyfin server — not a hand-wired copy of it. Pulls
 * SourceScanner and the repositories from the app's Koin graph, registers the server as a
 * source the way the UI would, scans, and asserts what the poster wall will read: a library of
 * MATCHED items on disk and a fresh token on the source for playback.
 *
 *   adb shell am instrument -w \
 *     -e class com.therealityteam.videoplayer.JellyfinScanInstrumentedTest \
 *     -e server https://demo.jellyfin.org/stable -e user demo \
 *     com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class JellyfinScanInstrumentedTest {

    @Test
    fun theAppsOwnScannerFillsTheLibraryFromTheServer() = runBlocking {
        val server = InstrumentationRegistry.getArguments().getString("server")
        assumeTrue("pass -e server <url> to run this", server != null)

        val koin = GlobalContext.get()
        val scanner = koin.get<SourceScanner>()
        val sources = koin.get<SourceRepository>()
        val repo = koin.get<MediaRepository>()

        val config = MediaSourceConfig(
            id = "jf_demo", type = SourceType.JELLYFIN, name = "Demo 服务器",
            uri = server!!,
            username = InstrumentationRegistry.getArguments().getString("user"),
            password = InstrumentationRegistry.getArguments().getString("pass"),
        )
        sources.upsert(config)
        val n = scanner.scan(config)

        val mine = repo.all().filter { it.sourceId == config.id }
        Log.i(TAG, "scan=$n items=${mine.size} posters=${mine.count { it.posterPath != null }} " +
            "cards=${mine.mapNotNull { it.collectionKey }.distinct().size}")
        assertTrue("scan should find items", n > 0)
        assertEquals("scan count and library must agree", n, mine.size)
        assertTrue("all MATCHED", mine.all { it.scrapeStatus == ScrapeStatus.MATCHED })
        assertTrue("posters cached", mine.count { it.posterPath != null } >= mine.size / 2)
        assertTrue("token stored on the source for playback",
            !sources.get(config.id)?.token.isNullOrBlank())
    }

    private companion object { const val TAG = "JellyfinScanTest" }
}
