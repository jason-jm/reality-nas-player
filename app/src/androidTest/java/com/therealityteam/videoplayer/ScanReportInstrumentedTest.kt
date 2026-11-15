package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.SourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Reports what each configured source actually yields, WITHOUT the error swallowing that
 * `SourceScanner.scanAll` does (`runCatching { scan(it) }.getOrDefault(0)`), which is why a source
 * that fails outright looks identical to an empty one — two of the five configured shares scanned
 * to zero files and nothing said why.
 *
 *   adb shell am instrument -w -e class \
 *     com.therealityteam.videoplayer.ScanReportInstrumentedTest \
 *     com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ScanReportInstrumentedTest {

    @Test
    fun reportPerSource() {
        runBlocking {
            val koin = GlobalContext.get()
            val sources = koin.get<SourceRepository>().all()
            val scanner = koin.get<SourceScanner>()
            Log.i(TAG, "configured sources: ${sources.size}")
            // What does the share ACTUALLY contain? A source that lists zero files is
            // indistinguishable from a missing/renamed folder without this.
            val smb = koin.get<com.therealityteam.videoplayer.data.source.SmbSource>()
            sources.map { it.uri }.filter { it.startsWith("smb://") }.distinctBy {
                it.removePrefix("smb://").split('/').take(2).joinToString("/")
            }.forEach { uri ->
                val parts = uri.removePrefix("smb://").split('/')
                val cfg = sources.first { it.uri == uri }
                runCatching { smb.browse(parts[0], parts[1], "", cfg.username, cfg.password) }
                    .onSuccess { e ->
                        Log.i(TAG, "SHARE ${parts[0]}/${parts[1]} contains ${e.size} entries")
                        e.forEach { Log.i(TAG, "        ${it}") }
                    }
                    .onFailure { Log.e(TAG, "SHARE ${parts[1]} browse failed: ${it.message}") }
            }
            sources.forEach { config ->
                runCatching { scanner.discover(config) }
                    .onSuccess { Log.i(TAG, "OK   ${config.uri} -> $it files") }
                    .onFailure { Log.e(TAG, "FAIL ${config.uri} -> ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "AuraScanReport"
    }
}
