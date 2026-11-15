package com.therealityteam.videoplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.ui.library.cachedArtwork
import com.therealityteam.videoplayer.ui.library.decodeArtwork
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * What the poster wall pays to fill itself, and how much of it stays filled.
 *
 * Two numbers decide whether the wall feels instant: milliseconds per poster, and bytes held per
 * poster — the second sets how many cards survive in the memory cache, i.e. whether scrolling back
 * over the wall re-reads the disk. The bench decodes the same corpus twice, once the way
 * sub-sampling alone would (largest power-of-two step, the shipped behaviour before this) and once
 * through the real [decodeArtwork], so the comparison needs no second build and no second device.
 *
 * Prefers the device's own cached artwork; with none there it draws a corpus at the sizes real
 * providers hand us, so the bench runs on a fresh install too.
 *
 *   adb shell am instrument -w -e class \
 *     com.therealityteam.videoplayer.ArtworkBenchInstrumentedTest \
 *     com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 *
 * (Use `am instrument`, never `gradle connectedAndroidTest` — that one uninstalls the app when it
 * finishes and takes the library and the artwork cache with it.)
 */
@RunWith(AndroidJUnit4::class)
class ArtworkBenchInstrumentedTest {

    private val tag = "ArtworkBench"

    @Test fun posterWallDecodeCost() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val real = File(ctx.filesDir, "aura/artwork").listFiles()
            ?.filter { it.isFile && it.length() > 4096 }?.sortedBy { it.name }.orEmpty()
        val corpus = if (real.size >= SAMPLE) {
            Log.i(tag, "corpus: device artwork (${real.size} files)"); real.take(SAMPLE)
        } else {
            Log.i(tag, "corpus: synthesised (device artwork has ${real.size} files)")
            synthesise(File(ctx.cacheDir, "artwork-bench"))
        }
        // Warm the page cache so we time decoding, not the first read of a cold file.
        corpus.forEach { f -> f.inputStream().use { it.readBytes() } }

        val budget = (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
        Log.i(tag, "heap max=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB cache budget=${budget / 1024 / 1024}MB")

        val sub = measure(corpus, "sub-sampled") { f -> subSampledOnly(f, CARD_PX) }
        val now = measure(corpus, "decodeArtwork") { f ->
            runBlocking { decodeArtwork(f.absolutePath, CARD_PX) }?.let { it.width to it.height }
        }
        val hits = corpus.count { cachedArtwork(it.absolutePath, CARD_PX) != null }

        Log.i(tag, "RESULT n=${corpus.size} " +
            "sub=${sub.kb}KB/${sub.width}px/${"%.1f".format(sub.ms)}ms " +
            "now=${now.kb}KB/${now.width}px/${"%.1f".format(now.ms)}ms " +
            "cached=$hits/${corpus.size}")
    }

    private class Cost(val kb: Long, val width: Int, val ms: Double)

    private fun measure(corpus: List<File>, name: String, decode: (File) -> Pair<Int, Int>?): Cost {
        var bytes = 0L
        var width = 0
        var n = 0
        val started = System.nanoTime()
        corpus.forEach { f ->
            decode(f)?.let { (w, h) -> bytes += w.toLong() * h * 4; width += w; n++ }
        }
        val ms = (System.nanoTime() - started) / 1_000_000.0
        if (n == 0) return Cost(0, 0, ms)
        Log.i(tag, "$name: ${bytes / n / 1024}KB each, ${width / n}px wide, ${"%.1f".format(ms / n)}ms each")
        return Cost(bytes / n / 1024, width / n, ms / n)
    }

    /** The shipped rule before this change: halve until one more halving would undershoot. */
    private fun subSampledOnly(f: File, reqWidthPx: Int): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidthPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val wh = bmp.width to bmp.height
        bmp.recycle()
        return wh
    }

    /** Posters at the sizes real providers hand us: TMDB w500, Bangumi covers, the odd huge one. */
    private fun synthesise(dir: File): List<File> {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val shapes = List(30) { 500 } + List(20) { 1400 } + List(10) { 2000 }
        val rnd = Random(7)
        return shapes.mapIndexed { i, w ->
            val h = w * 3 / 2
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    0xFF000000.toInt() or rnd.nextInt(0xFFFFFF),
                    0xFF000000.toInt() or rnd.nextInt(0xFFFFFF),
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            File(dir, "poster_%03d.jpg".format(i)).also { f ->
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
            }
        }
    }

    private companion object {
        const val SAMPLE = 60      // a couple of screenfuls of wall
        const val CARD_PX = 400    // what PosterCard asks for
    }
}
