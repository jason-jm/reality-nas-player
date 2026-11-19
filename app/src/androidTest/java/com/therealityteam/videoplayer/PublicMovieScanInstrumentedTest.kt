package com.therealityteam.videoplayer

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.scrape.TmdbConfig
import com.therealityteam.videoplayer.data.source.SourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in scan of a WebDAV fixture in a side-by-side test install with no provider keys. */
@RunWith(AndroidJUnit4::class)
class PublicMovieScanInstrumentedTest {
    @Test fun discoversFilmAndDisplaysItsDownloadedPoster() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("publicMovieFixture")
        assumeTrue(url != null)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName.endsWith(".keyless"))
        assertFalse(TmdbConfig.offline)
        assertTrue(TmdbConfig.effectiveKey.isEmpty())
        val graph = GlobalContext.get()
        val source = MediaSourceConfig("keyless-fixture", SourceType.WEBDAV, "Film fixture", url!!)
        graph.get<MediaRepository>().removeBySource(source.id)
        val previous = TmdbConfig.language
        try {
            TmdbConfig.language = "zh-CN"
            assertEquals(1, graph.get<SourceScanner>().scan(source))
            val result = graph.get<MediaRepository>().all().single { it.sourceId == source.id }
            assertEquals(ScrapeStatus.MATCHED, result.scrapeStatus)
            assertEquals("星际穿越", result.title)
            assertEquals(2014, result.year)
            assertNull(result.tmdbId)
            assertNotNull(result.collectionKey)
            assertTrue(!result.overview.isNullOrBlank())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(result.posterPath, bounds)
            assertTrue("downloaded poster decodes", bounds.outWidth > 50 && bounds.outHeight > 50)
        } finally { TmdbConfig.language = previous }
    }
}
