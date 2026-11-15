package com.therealityteam.videoplayer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.model.CreditsTable
import com.therealityteam.videoplayer.data.model.JsonArrayStream
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.MediaJson
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What pooling the credits is worth, measured on this device's own library.
 *
 * Both shapes are written from the SAME items and read by the SAME code, and the two arms alternate
 * in blocks so neither gets all the warm cache or all the cold. That matters more here than the
 * arithmetic: this emulator's timings drift several-fold with its state, so a before-and-after taken
 * an hour apart says nothing at all.
 */
@RunWith(AndroidJUnit4::class)
class CreditsPoolBenchTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pooledAgainstInline() {
        val src = File(ctx.filesDir, "aura/library.json")
        if (!src.isFile) {
            println("POOLBENCH no library on this device"); return
        }
        val items = read(src).first
        if (items.size < 100) {
            println("POOLBENCH only ${items.size} items — not worth timing"); return
        }

        val inline = File(ctx.cacheDir, "bench-inline.json").also { writeInline(items, it) }
        val pooled = File(ctx.cacheDir, "bench-pooled.json").also { writePooled(items, it) }
        println("POOLBENCH items=${items.size} inline=${inline.length()} pooled=${pooled.length()}")

        var inlineMs = 0L
        var pooledMs = 0L
        repeat(BLOCKS) { block ->
            val order = if (block % 2 == 0) listOf(inline, pooled) else listOf(pooled, inline)
            order.forEach { f ->
                val t0 = SystemClock.elapsedRealtime()
                val (got, credits) = read(f)
                val ms = SystemClock.elapsedRealtime() - t0
                check(got.size == items.size) { "read ${got.size} of ${items.size} from ${f.name}" }
                // The whole point is that nobody is lost on the way, so say so out loud.
                check(got.sumOf { it.cast.size + it.crew.size } == items.sumOf { it.cast.size + it.crew.size }) {
                    "credits went missing reading ${f.name}"
                }
                if (f === inline) inlineMs += ms else pooledMs += ms
                if (block == 0) println("POOLBENCH  ${f.name}: ${ms}ms, table=${credits?.size ?: 0}")
            }
        }
        inline.delete(); pooled.delete()
        println("POOLBENCH inline=${inlineMs}ms pooled=${pooledMs}ms over $BLOCKS blocks each")
        println("POOLBENCH saving=${"%.1f".format(100.0 * (inlineMs - pooledMs) / inlineMs)}%")
    }

    /** The repository's own read, so what is timed is what actually runs at startup. */
    private fun read(f: File): Pair<List<MediaItem>, CreditsTable?> {
        val out = ArrayList<MediaItem>(4096)
        var credits: CreditsTable? = null
        var first = true
        f.bufferedReader().use { r ->
            JsonArrayStream.forEachObject(r) { text ->
                val o = JSONObject(text)
                if (first && CreditsTable.isHeader(o)) credits = CreditsTable.read(o) else out.add(MediaJson.item(o, credits))
                first = false
            }
        }
        return out to credits
    }

    private fun writeInline(items: List<MediaItem>, f: File) = f.bufferedWriter().use { w ->
        w.write("[")
        items.forEachIndexed { i, it -> if (i > 0) w.write(","); w.write(MediaJson.item(it).toString()) }
        w.write("]")
    }

    private fun writePooled(items: List<MediaItem>, f: File) {
        val (table, keyOf) = CreditsTable.of(items)
        f.bufferedWriter().use { w ->
            w.write("[")
            if (table.size > 0) w.write(table.header().toString())
            items.forEachIndexed { i, it ->
                if (i > 0 || table.size > 0) w.write(",")
                w.write(MediaJson.item(it, keyOf[it.id]).toString())
            }
            w.write("]")
        }
    }

    private companion object {
        const val BLOCKS = 3
    }
}
