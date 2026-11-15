package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.scrape.AniListClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The batched AniList request: many searches in one POST, each answer handed back to its own caller.
 *
 * AniList counts REQUESTS, not queries — a reply carrying eleven aliased searches costs one of the
 * thirty a minute it allows. The danger is silent: a body the server rejects, or an alias mapped to
 * the wrong waiter, returns "no such anime" for every file rather than an error. The first version
 * of this code declared `${'$'}s0` and asked for `${'$'}search`, and every anime query on the shelf
 * came back empty without a single log line.
 */
class AniListBatchTest {

    private val bodies = CopyOnWriteArrayList<String>()

    /** Answers whatever it is asked, echoing each alias back with a media id derived from its query. */
    private val transport = AniListClient.Transport { body ->
        bodies += body
        val req = JSONObject(body)
        val vars = req.getJSONObject("variables")
        val data = JSONObject()
        vars.keys().forEach { k ->
            val alias = "a" + k.removePrefix("s")
            val title = vars.getString(k)
            data.put(
                alias,
                JSONObject().put(
                    "media",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("id", title.length)
                            .put("title", JSONObject().put("romaji", title)),
                    ),
                ),
            )
        }
        AniListClient.Reply(200, JSONObject().put("data", data).toString(), null)
    }

    private fun client() = AniListClient(cacheDir = null, transport = transport, minIntervalMs = 0, batching = true)

    @Test fun eachCallerGetsItsOwnAnswer() = runBlocking {
        val c = client()
        val queries = listOf("Cowboy Bebop", "Lain", "Turn A Gundam", "SHIROBAKO", "FLCL")
        val got = queries.map { q -> async { q to c.search(q) } }.awaitAll()
        got.forEach { (q, results) ->
            assertEquals("$q got another query's answer", q, results.firstOrNull()?.romajiTitle)
        }
    }

    @Test fun theVariablesAndTheAliasesAgree() = runBlocking {
        client().search("Cowboy Bebop")
        val body = bodies.first()
        val req = JSONObject(body)
        val query = req.getString("query")
        req.getJSONObject("variables").keys().forEach { k ->
            assertTrue("declares ${'$'}$k", query.contains("${'$'}$k: String"))
            assertTrue("uses ${'$'}$k in a search", query.contains("search: ${'$'}$k,"))
            assertTrue("has the matching alias", query.contains("a" + k.removePrefix("s") + ": Page("))
        }
    }

    @Test fun aBatchNeverExceedsTheServersComplexityBudget() {
        // A refusal is every file in the batch unmatched, so the cap has to stay under the budget.
        // The per-block price is the server's own: forty blocks of the current search came back
        // "Max query complexity should be 500 but got 640", which is 16 each, and thirty were
        // answered in full. (It was 42 a block while the search still dragged back characters and
        // staff; if those ever come back, this number has to be re-measured, not adjusted to fit.)
        assertTrue(
            "batch cap leaves room: ${AniListClient.BATCH_MAX} x $COMPLEXITY_PER_BLOCK",
            AniListClient.BATCH_MAX * COMPLEXITY_PER_BLOCK <= 500,
        )
    }

    /** What AniList charges for one aliased search block, measured by AniListBatchCeilingProbe. */
    private val COMPLEXITY_PER_BLOCK = 16

    @Test fun twoCallersAskingTheSameThingCostOneQuery() = runBlocking {
        val c = client()
        listOf("Lain", "Lain", "Lain").map { async { c.search(it) } }.awaitAll()
        val vars = JSONObject(bodies.first()).getJSONObject("variables")
        assertEquals("asked once", 1, vars.length())
    }

    @Test fun theAliasBlockIsBalanced() {
        // One closing brace too many closed the whole query, so block zero swallowed the rest and
        // the server said `Syntax Error: Unexpected Name "a1"`. Nothing in the app reported it —
        // every anime query simply returned nothing.
        val b = AniListClient.BATCH_BLOCK
        assertEquals("braces balance", b.count { it == '{' }, b.count { it == '}' })
        assertTrue("the alias placeholder is there", b.contains("a@I@: Page("))
        assertTrue("and the variable matches it", b.contains("search: ${'$'}s@I@,"))
    }
}
