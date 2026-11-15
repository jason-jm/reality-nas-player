package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.scrape.ResponseCache
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Does the response cache actually write on this device? Run when the cache stays empty. */
@RunWith(AndroidJUnit4::class)
class ResponseCacheProbeTest {
    @Test
    fun putThenGet() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val c = ResponseCache(ctx.filesDir, 60_000)
        c.put("probe key 探针", """{"data":{"Page":{"media":[]}}}""")
        val got = c.get("probe key 探针")
        val dir = File(ctx.filesDir, "aura/cache/responses")
        Log.i("CACHEPROBE", "filesDir=${ctx.filesDir} exists=${dir.exists()} canWrite=${dir.canWrite()}")
        Log.i("CACHEPROBE", "entries=${dir.listFiles()?.joinToString { it.name + ":" + it.length() }}")
        Log.i("CACHEPROBE", "get=${got?.take(40)}")
    }
}
