package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import com.therealityteam.videoplayer.data.source.SourceScanner
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Headless full re-scrape, for the poster-wall evaluation loop (see `eval/README.md`).
 *
 * Rebuilding the library is otherwise a UI-only flow (设置 → 清空媒体库, 来源 → 扫描全部, 刮削), which
 * can't be driven from a script inside a spatial scene. This runs the same three steps against the
 * real repositories on the device, so a scraping change can be verified end-to-end and the resulting
 * `library.json` fed back into the evaluator.
 *
 * Clears the library and artwork cache first unless `-e clear false` is passed, which resumes an
 * interrupted run instead (needsScrape skips already-MATCHED items). Run deliberately:
 *
 *   adb shell am instrument -w -e class \
 *     com.therealityteam.videoplayer.RescrapeInstrumentedTest \
 *     com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `-e clear false -e only "/TV/Friends/"` re-identifies just the paths containing that substring —
 * a full pass is ~12h of throttled API calls, so a fix aimed at one show is measured this way.
 *
 * Use a substring with NO SPACES and no `!`: `am instrument` mangles those, and the run then either
 * fails with "Invalid userId -2" or silently matches nothing — which twice made a working fix look
 * ineffective. `2nd_Season` works where `"Gundam 00"` does not.
 */
@RunWith(AndroidJUnit4::class)
class RescrapeInstrumentedTest {

    @Test
    fun clearScanAndScrape() {
        runBlocking {
            val koin = GlobalContext.get()
            val repo = koin.get<MediaRepository>()
            val scanner = koin.get<SourceScanner>()
            val engine = koin.get<ScrapeEngine>()

            // The library is read on its own thread now: nothing to target until it has arrived.
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (!repo.loaded.value && android.os.SystemClock.elapsedRealtime() - t0 < 120_000) Thread.sleep(50)
            Log.i(TAG, "library loaded: ${repo.items.value.size} items after ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            val clear = InstrumentationRegistry.getArguments().getString("clear") != "false"
            // `-e scan true` rescans WITHOUT wiping: a run that died part-way (the spatial shell
            // tearing the window down takes the process with it) leaves the library half-filled,
            // and re-clearing to pick up a newly added source would throw away everything already
            // identified. Scanning is additive — the repository keys on path.
            val rescan = InstrumentationRegistry.getArguments().getString("scan") == "true"
            if (!clear && rescan) {
                val found = scanner.scanAll()
                Log.i(TAG, "rescan without clearing: $found files, library now ${repo.items.value.size}")
            }
            if (clear) {
                Log.i(TAG, "clearing library (was ${repo.items.value.size} items)")
                engine.clearLibrary()
                val found = scanner.scanAll()
                Log.i(TAG, "scan complete: $found files")
            } else {
                Log.i(TAG, "resuming: ${repo.items.value.size} items already in the library")
            }

            // A fix usually touches one corner of the library, and a full pass costs hours of
            // throttled API calls — `-e only <substring>` re-identifies just the affected paths.
            // Forces them through regardless of ratingsVersion, so the fix can be measured at once.
            val only = InstrumentationRegistry.getArguments().getString("only")
            // `-e forget false` re-runs the pass over the same paths WITHOUT clearing what is known
            // about them — which is what the app's own 刮削 does. Needed to see anything a pass
            // REMEMBERS between runs: forgetting resets framePosterFailed by design, so with the
            // default the frame stage can never be observed skipping a file it gave up on before.
            val forget = InstrumentationRegistry.getArguments().getString("forget") != "false"
            if (only != null) {
                val hit = repo.items.value.filter { it.path.contains(only, ignoreCase = true) }
                Log.i(TAG, "targeted: '$only' matches ${hit.size} of ${repo.items.value.size} items (forget=$forget)")
                if (forget) engine.forget(hit)
            }
            val targets = repo.items.value
                .filter { only == null || it.path.contains(only, ignoreCase = true) }
            // Never-scraped and unidentified files in their OWN pass, first. A full pass re-checks
            // every anime-shelf file whether or not it can change (needsScrape re-routes any file
            // without an AniList key), so at the ~20 files/min AniList allows, the 1527 items that
            // CAN change sat behind 4400 that can't. Sorting the list is not enough: scrapeAll
            // launches every item as a coroutine at once and the concurrency semaphore admits them
            // in no particular order — the order only holds if the batches are separate calls.
            // PENDING first of all: those have never been looked up, so every one of them can
            // change. An already-UNMATCHED file re-scrapes to UNMATCHED with identical fields —
            // 250 of them went by without moving a single number and looked like a broken run.
            val pending = targets.filter { it.scrapeStatus == ScrapeStatus.PENDING }
            val unmatched = targets.filter { it.scrapeStatus == ScrapeStatus.UNMATCHED }
            val rest = targets.filter { it.scrapeStatus == ScrapeStatus.MATCHED }
            Log.i(TAG, "targets=${targets.size}: ${pending.size} never scraped, " +
                "${unmatched.size} unmatched, ${rest.size} matched")

            var last = 0
            listOf("pending" to pending, "unmatched" to unmatched, "rest" to rest).forEach { (label, batch) ->
                last = 0
                engine.scrapeAll(batch) { done, total ->
                    if (done - last >= 50 || done == total) {
                        last = done
                        Log.i(TAG, "scrape $label $done/$total")
                    }
                }
                Log.i(TAG, "batch $label complete")
            }
            Log.i(TAG, "DONE items=${repo.items.value.size}")
        }
    }

    private companion object {
        const val TAG = "AuraRescrape"
    }
}
