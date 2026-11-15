package com.therealityteam.videoplayer.desktop.player

import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.desktop.AppGraph
import com.therealityteam.videoplayer.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures of episodes nothing else has — an unidentified series, a show TMDB has no stills
 * for, a library scraped offline: a frame a third of the way in (past the recap and the opening,
 * before the end), made the first time a row asks for it and kept with the artwork. Rows ask as
 * they are drawn, so the ones in sight come first; one headless mpv does them in turn and stays a
 * moment for the next. The setting for frame posters covers these too.
 */
class FrameStills(private val graph: AppGraph) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var grabber: FrameGrabber? = null
    private var idle: Job? = null

    /** Files no picture could be taken from, not tried again this session. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** The picture for [item], made now if need be; null when there can be none. */
    suspend fun still(item: MediaItem): String? {
        graph.artwork.existing(item.id, TAG_ART)?.let { return it }
        if (!graph.settings.value.framePosters || !FrameGrabber.reachable(item) || item.id in failed) return null
        return mutex.withLock {
            graph.artwork.existing(item.id, TAG_ART)?.let { return@withLock it }
            idle?.cancel()
            val g = grabber ?: FrameGrabber(graph).also { grabber = it }
            val t0 = System.currentTimeMillis()
            val frame = runCatching { g.frame(item, fraction = 1.0 / 3, minMs = 60_000, maxMs = Long.MAX_VALUE) }
                .onFailure { Log.w(TAG, "no picture from ${item.fileName}", it) }.getOrNull()
            val path = frame?.let { f ->
                try {
                    val jpeg = withContext(Dispatchers.Default) { scaled(f) }
                    graph.artwork.store(item.id, TAG_ART) { out -> out.write(jpeg); true }
                } finally {
                    f.close()
                }
            }
            if (path == null) failed += item.id else Log.i(TAG, "picture of ${item.fileName} in ${System.currentTimeMillis() - t0} ms")
            // Kept for the row after this one; let go when none comes.
            idle = scope.launch {
                delay(10_000)
                mutex.withLock {
                    val done = grabber
                    grabber = null
                    if (done != null) withContext(Dispatchers.Main) { done.close() }
                }
            }
            path
        }
    }

    private companion object {
        const val TAG = "AuraStill"
        const val TAG_ART = "framestill"
        const val WIDTH = 480

        /** The frame at a row's size, 16:9 whatever the film's shape (cropped to the middle). */
        fun scaled(frame: Image): ByteArray {
            val h = WIDTH * 9 / 16
            val surface = Surface.makeRasterN32Premul(WIDTH, h)
            try {
                val scale = maxOf(WIDTH.toFloat() / frame.width, h.toFloat() / frame.height)
                val cw = frame.width * scale
                val ch = frame.height * scale
                surface.canvas.drawImageRect(
                    frame, Rect.makeWH(frame.width.toFloat(), frame.height.toFloat()),
                    Rect.makeXYWH((WIDTH - cw) / 2f, (h - ch) / 2f, cw, ch),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true,
                )
                return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 85)!!.bytes
            } finally {
                surface.close()
            }
        }
    }
}
