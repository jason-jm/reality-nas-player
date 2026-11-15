package com.therealityteam.videoplayer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.repository.JsonMediaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the on-device library read actually takes — the poster wall's cold-start floor.
 *
 * The read happens on its own thread, so this waits for it rather than asking the moment the
 * repository is constructed. (It did not, and reported `items=0 ms=11` for a 9,597-item library:
 * a bench that measures nothing looks exactly like a bench that measures something very fast.)
 */
@RunWith(AndroidJUnit4::class)
class LibraryLoadBenchTest {
    @Test
    fun measureLoad() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        repeat(3) { round ->
            val t0 = SystemClock.elapsedRealtime()
            val repo = JsonMediaRepository(ctx.filesDir)
            val n = runBlocking { repo.loaded.first { it }; repo.all().size }
            println("LOADBENCH round=$round items=$n ms=${SystemClock.elapsedRealtime() - t0}")
        }
    }
}
