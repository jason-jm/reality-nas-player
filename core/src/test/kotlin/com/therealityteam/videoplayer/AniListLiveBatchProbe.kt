package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The one thing a fake transport cannot prove: that the live API accepts the body this client
 * generates. Run by hand, against the network.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*AniListLiveBatchProbe*' -Daura.anilist.live=1
 */
class AniListLiveBatchProbe {

    @Test fun batchedAgainstTheRealApi() {
        if (System.getProperty("aura.anilist.live") == null) return
        val queries = listOf(
            "Cowboy Bebop", "Serial Experiments Lain", "Turn A Gundam", "SHIROBAKO",
            "Guilty Crown", "Macross Frontier", "FLCL", "Lucky Star",
        )
        // A transport that reports what the server actually said about the batched body.
        val spy = AniListClient.Transport { body ->
            val r = AniListClient.httpTransport().post(body)
            if (body.contains("a1:")) {
                println("ALB REQ " + body.take(260))
                println("ALB RSP code=" + r?.code + " " + (r?.body?.take(300) ?: "null"))
            }
            r
        }
        listOf(false, true).forEach { batching ->
            val c = AniListClient(cacheDir = null, transport = spy, minIntervalMs = 2_500, batching = batching)
            val t = System.currentTimeMillis()
            val got = runBlocking { queries.map { q -> async { q to c.search(q) } }.awaitAll() }
            val hits = got.count { it.second.isNotEmpty() }
            println("ALB batching=$batching  有结果 $hits/${queries.size}  用时 ${System.currentTimeMillis() - t}ms")
            got.forEach { (q, r) -> println("ALB    $q -> ${r.firstOrNull()?.providerId} ${r.firstOrNull()?.romajiTitle}") }
        }
    }
}
