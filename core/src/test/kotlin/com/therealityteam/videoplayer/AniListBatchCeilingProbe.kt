package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import org.json.JSONObject
import org.junit.Test

/**
 * How many searches fit in one request now that the search is light.
 *
 * The cap was eleven because each aliased block cost 42 of AniList's 500 complexity budget, and
 * most of that 42 was the characters and staff the search no longer asks for. Rather than guess the
 * new number, this asks the server: build a batch of N, post it, and see what comes back. AniList
 * refuses an over-budget query outright and says so, so the ceiling is wherever it starts refusing.
 */
class AniListBatchCeilingProbe {

    @Test fun whereDoesTheServerSayStop() {
        if (System.getProperty("aura.net") != "1") { println("skipped"); return }
        val t = AniListClient.httpTransport()
        val terms = listOf(
            "Attack on Titan", "Lucky Star", "Ghost in the Shell", "Macross Frontier", "Steins Gate",
            "Cowboy Bebop", "Code Geass", "Clannad", "Bakemonogatari", "Nichijou", "Toradora",
            "Angel Beats", "Haruhi", "K-On", "Gintama", "One Punch Man", "Mushishi", "Monster",
            "Hyouka", "Barakamon", "Usagi Drop", "Shirobako", "Working", "Yuru Camp", "Non Non Biyori",
            "Aria", "Planetes", "Dennou Coil", "Kaiba", "Tatami Galaxy", "Ping Pong", "Mononoke",
            "Kino no Tabi", "Haibane Renmei", "Texhnolyze", "Boogiepop", "Serial Experiments Lain",
            "Paranoia Agent", "Perfect Blue", "Millennium Actress",
        )
        listOf(11, 20, 30, 40).forEach { n ->
            if (n > terms.size) return@forEach
            val pick = terms.take(n)
            val decl = pick.indices.joinToString(", ") { "\$s$it: String" }
            val blocks = pick.indices.joinToString("\n") { AniListClient.BATCH_BLOCK.replace("@I@", it.toString()) }
            val vars = JSONObject().apply { pick.forEachIndexed { i, q -> put("s$i", q) } }
            val body = JSONObject().put("query", "query ($decl) {\n$blocks\n}").put("variables", vars).toString()
            val t0 = System.nanoTime()
            val r = t.post(body)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val parsed = runCatching { JSONObject(r?.body ?: "") }.getOrNull()
            val data = parsed?.optJSONObject("data")
            val got = data?.keys()?.asSequence()?.count { k ->
                data.optJSONObject(k)?.optJSONArray("media")?.length()?.let { it > 0 } == true
            } ?: -1
            val err = parsed?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            println("CEILING n=%-3d code=%-4d %5dms bytes=%-7d answered=%-3d %s"
                .format(n, r?.code ?: -1, ms, r?.body?.length ?: -1, got, err ?: ""))
            Thread.sleep(2_600)
        }
    }
}
