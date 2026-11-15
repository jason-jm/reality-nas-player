package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.scrape.ScrapeEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class QDeviceReplayTest {
    @Test
    fun scrapeOneForReal(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val repo = koin.get<MediaRepository>()
        val engine = koin.get<ScrapeEngine>()
        val item = repo.all().first { it.fileName.contains("Gundam_0083_13") }
        engine.forget(listOf(item))
        val forgotten = repo.all().first { it.id == item.id }
        val scraped = engine.scrape(forgotten)
        Log.i("QReplay2", "scraped key=${scraped.collectionKey} title=${scraped.title}")
    }
}
