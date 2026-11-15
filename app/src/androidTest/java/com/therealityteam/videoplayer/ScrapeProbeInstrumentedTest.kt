package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.data.scrape.Matcher
import com.therealityteam.videoplayer.data.scrape.ParsedName
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.scrape.TmdbMetadataClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Dev tool: what ONE file's identification does on the device, step by step — the TMDB answers
 * the device gets, and what the engine makes of them. `-e only <substring>` picks the file.
 * The targeted rescrape only shows the end state; when that disagrees with the JVM probe, this
 * says where.
 */
@RunWith(AndroidJUnit4::class)
class ScrapeProbeInstrumentedTest {
    @Test
    fun probeOne() {
        val only = InstrumentationRegistry.getArguments().getString("only") ?: return
        val koin = GlobalContext.get()
        val repo = koin.get<MediaRepository>()
        val engine = koin.get<ScrapeEngine>()
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (!repo.loaded.value && android.os.SystemClock.elapsedRealtime() - t0 < 120_000) Thread.sleep(50)
        val item = repo.items.value.firstOrNull { it.path.contains(only, ignoreCase = true) } ?: run {
            Log.i(TAG, "no item contains '$only'"); return
        }
        val parsed = FilenameParser.parseFromPath(item.path)
        val parent = item.path.substringBeforeLast('/', "").substringAfterLast('/')
        val programme = FilenameParser.programmeTitle(item.fileName, parent)
        Log.i(TAG, "file=${item.fileName} parsed='${parsed.title}' y=${parsed.year} s=${parsed.season} e=${parsed.episode} parent='$parent' programme='$programme' status=${item.scrapeStatus}")
        val client = TmdbMetadataClient()
        Log.i(TAG, "tmdb configured=${client.isConfigured}")
        runBlocking {
            for ((label, q) in listOf("file/movie" to parsed, "file/tv" to parsed.copy(season = 1)) +
                (programme?.let { listOf("programme/tv" to parsed.copy(title = it, season = 1, year = null), "programme/movie" to parsed.copy(title = it)) } ?: emptyList())) {
                val hits = runCatching { client.search(q) }
                val best = hits.getOrNull()?.let { Matcher.best(q, it) }
                Log.i(TAG, "search[$label] '${q.title}' -> ${hits.fold({ "${it.size} hits" }, { "FAILED ${it.javaClass.simpleName}: ${it.message}" })} best=${best?.let { "${it.title} (${it.year}) #${it.providerId} ${it.kind}" }}")
            }
            val result = runCatching { engine.scrape(item) }
            Log.i(TAG, "engine.scrape -> ${result.fold({ "status=${it.scrapeStatus} title='${it.title}' tmdb=${it.tmdbId} key=${it.collectionKey} kind=${it.kind}" }, { "THREW ${it.javaClass.simpleName}: ${it.message}" })}")
        }
    }

    private companion object { const val TAG = "ScrapeProbe" }
}
