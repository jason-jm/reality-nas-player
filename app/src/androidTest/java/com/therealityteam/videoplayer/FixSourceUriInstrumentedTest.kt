package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.repository.SourceRepository
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Repoints a configured source whose folder was renamed or moved — the UI can only add and remove
 * sources, so a stale path otherwise has to be deleted and re-added (losing nothing, but fiddly
 * inside a spatial scene).
 *
 *   adb shell am instrument -w -e from smb://host/video/Movies -e to smb://host/video/Movie \
 *     -e class com.therealityteam.videoplayer.FixSourceUriInstrumentedTest \
 *     com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FixSourceUriInstrumentedTest {

    @Test
    fun repointSource() {
        val args = InstrumentationRegistry.getArguments()
        val from = args.getString("from") ?: return
        val to = args.getString("to") ?: return
        val repo = GlobalContext.get().get<SourceRepository>()
        val target = repo.all().firstOrNull { it.uri == from }
        if (target == null) {
            Log.e(TAG, "no source with uri $from; have ${repo.all().map { it.uri }}")
            return
        }
        repo.upsert(target.copy(uri = to))
        Log.i(TAG, "repointed ${target.id}: $from -> $to")
        Log.i(TAG, "sources now: ${repo.all().map { it.uri }}")
    }

    private companion object {
        const val TAG = "AuraFixSource"
    }
}
