package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** An anime's banner, asked for many works to a request and kept per work — a missing one too. */
class AniListBannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bodies = CopyOnWriteArrayList<String>()

    /** Every even id has a banner, every odd one none — as AniList answers `bannerImage: null`. */
    private val transport = AniListClient.Transport { body ->
        bodies += body
        val data = JSONObject()
        Regex("""b(\d+): Media\(id: (\d+)""").findAll(JSONObject(body).getString("query")).forEach { m ->
            val id = m.groupValues[2].toInt()
            data.put("b$id", JSONObject().put("bannerImage", if (id % 2 == 0) "https://s4.anilist.co/banner/$id.jpg" else JSONObject.NULL))
        }
        AniListClient.Reply(200, JSONObject().put("data", data).toString(), null)
    }

    private fun client() = AniListClient(cacheDir = tmp.root, transport = transport, minIntervalMs = 0)

    @Test fun `many works to a request, each banner to its own work`() = runBlocking {
        val ids = (1..60).toList()
        val got = client().banners(ids)
        assertEquals((2..60 step 2).associateWith { "https://s4.anilist.co/banner/$it.jpg" }, got)
        assertEquals(3, bodies.size) // 25 + 25 + 10
    }

    @Test fun `asked once, a missing banner included`() = runBlocking {
        client().banners(listOf(7, 8))
        val again = client().banners(listOf(7, 8)) // a new process: the disk answers
        assertEquals(mapOf(8 to "https://s4.anilist.co/banner/8.jpg"), again)
        assertEquals(1, bodies.size)
        assertTrue(client().banners(emptyList()).isEmpty())
    }
}
