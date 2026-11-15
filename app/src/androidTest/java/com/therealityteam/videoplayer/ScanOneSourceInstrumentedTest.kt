package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.SourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Scan ONE configured source through the app's own scanner, with the error LOUD. scanAll swallows
 * a failed walk into "0 files", which is how a whole shelf silently missed a full-library pass.
 *
 *   adb shell am instrument -w -e class com.therealityteam.videoplayer.ScanOneSourceInstrumentedTest \
 *     -e sourceId smb_dl com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ScanOneSourceInstrumentedTest {

    @Test
    fun scanOneSourceLoudly() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("sourceId")
        assumeTrue("pass -e sourceId <id>", id != null)
        val koin = GlobalContext.get()
        val config = koin.get<SourceRepository>().all().firstOrNull { it.id == id }
        assumeTrue("no source $id configured", config != null)
        val n = koin.get<SourceScanner>().scan(config!!) { done, total ->
            if (done % 100 == 0) Log.i(TAG, "scrape $done/$total")
        }
        Log.i(TAG, "scan ${config.uri} -> $n items")
        assertTrue("expected files from ${config.uri}", n > 0)
    }

    private companion object { const val TAG = "AuraScanOne" }
}
