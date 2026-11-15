package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.util.Http
import org.junit.Test

/**
 * What a host that is simply DOWN costs a scrape.
 *
 * Measured on a real pass over 329 documentaries: api.jikan.moe took four calls, sixty-seven
 * seconds and failed every one — an average of 16.8s each, because every call climbs the whole
 * retry ladder before giving up, and the host is no more alive on the fourth try than the first.
 * The rate-limit cooldown does not help: it only trips on 429, and a dead host never answers at all.
 *
 * 192.0.2.1 is TEST-NET-1, reserved by RFC 5737 and routed nowhere, so this is a connect timeout
 * every time and does not bother anybody's server. Needs -Daura.net=1 because it takes minutes.
 */
class DeadHostProbe {
    @Test fun whatDoesADeadHostCost() {
        if (System.getProperty("aura.net") != "1") { println("skipped (pass -Daura.net=1)"); return }
        Http.forgetRateLimits()
        val calls = (System.getProperty("aura.dead.calls") ?: "5").toInt()
        var total = 0L
        repeat(calls) { i ->
            val t0 = System.nanoTime()
            val body = Http.get("http://192.0.2.1/api/v4/anime?q=test$i")
            val ms = (System.nanoTime() - t0) / 1_000_000
            total += ms
            println("DEADHOST call $i: ${ms}ms body=${body?.length ?: -1}")
        }
        println("DEADHOST $calls calls in ${total}ms (${total / calls}ms each)")
    }
}
