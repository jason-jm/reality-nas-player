package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.scrape.ArtworkCache
import com.therealityteam.videoplayer.data.source.JellyfinClient
import com.therealityteam.videoplayer.data.source.JellyfinSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole Jellyfin path against a REAL server, on the device: authenticate → read the library
 * → cache artwork. Server and credentials are instrumentation arguments so nothing sensitive
 * lives in the repo; without `-e server` the test skips and a plain connected run stays green.
 *
 *   adb shell am instrument -w \
 *     -e class com.therealityteam.videoplayer.JellyfinInstrumentedTest \
 *     -e server https://demo.jellyfin.org/stable -e user demo \
 *     com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class JellyfinInstrumentedTest {

    private val args get() = InstrumentationRegistry.getArguments()

    @Test
    fun theLibraryArrivesMatchedWithPostersOnDisk() = runBlocking {
        val server = args.getString("server")
        assumeTrue("pass -e server <url> to run this", server != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val source = JellyfinSource(JellyfinClient(), ArtworkCache(ctx))
        val config = MediaSourceConfig(
            id = "jf_test", type = SourceType.JELLYFIN, name = "test",
            uri = server!!, username = args.getString("user"), password = args.getString("pass"),
        )
        val result = source.listMatched(config)
        val items = result.items
        Log.i(TAG, "items=${items.size} matched=${items.count { it.scrapeStatus == ScrapeStatus.MATCHED }} " +
            "withPoster=${items.count { it.posterPath != null }}")
        items.take(6).forEach {
            Log.i(TAG, "${it.kind} ${it.title} S${it.season}E${it.episode} tmdb=${it.tmdbId} " +
                "poster=${it.posterPath?.substringAfterLast('/')} key=${it.collectionKey}")
        }

        assertTrue("expected a non-empty library", items.isNotEmpty())
        assertTrue("every item must arrive MATCHED — that is the whole point",
            items.all { it.scrapeStatus == ScrapeStatus.MATCHED })
        // 海报要真的落在盘上，不是一个 URL 的许诺。
        val posters = items.mapNotNull { it.posterPath }
        assertTrue("most items should carry a poster (got ${posters.size}/${items.size})",
            posters.size >= items.size / 2)
        assertTrue("poster files must exist and be non-empty",
            posters.all { File(it).length() > 0 })
        assertTrue("token must be returned for playback", result.token.isNotBlank())
        assertTrue("paths stay token-free", items.none { it.path.contains(result.token) })
    }

    private companion object { const val TAG = "JellyfinTest" }
}
