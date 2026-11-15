package com.therealityteam.videoplayer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import com.therealityteam.videoplayer.data.scrape.ContentClassifier
import com.therealityteam.videoplayer.data.scrape.FilenameParser
import com.therealityteam.videoplayer.ui.library.LibraryGrouping
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the poster wall's cold start actually goes, on the device rather than the JVM.
 *
 * The same 9597 items group in 0.46 s on a laptop JVM and ~47 s on the headset/emulator — ART's
 * regex engine, not the algorithm. This splits the wall time into: parsing every file name once
 * (cold cache), classifying every name, and the grouping proper with those caches warm.
 */
@RunWith(AndroidJUnit4::class)
class GroupingBenchInstrumentedTest {
    @Test
    fun measureGrouping() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = JsonMediaRepository(ctx.filesDir)
        val t0 = SystemClock.elapsedRealtime()
        while (!repo.loaded.value && SystemClock.elapsedRealtime() - t0 < 120_000) Thread.sleep(50)
        val items = repo.all()
        println("GROUPBENCH load items=${items.size} ms=${SystemClock.elapsedRealtime() - t0}")

        // Cold caches: this process has parsed nothing yet.
        var t = SystemClock.elapsedRealtime()
        items.forEach { FilenameParser.parse(it.fileName) }
        val parseMs = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        items.forEach { ContentClassifier.classify(it.fileName, it.path) }
        val classifyMs = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        val warm = LibraryGrouping.group(items)
        val warmMs = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        LibraryGrouping.group(items)
        val secondMs = SystemClock.elapsedRealtime() - t
        println("GROUPBENCH parse=${parseMs}ms classify=${classifyMs}ms groupWarm=${warmMs}ms groupAgain=${secondMs}ms cards=${warm.size}")
    }
}
