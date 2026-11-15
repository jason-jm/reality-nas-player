package com.therealityteam.videoplayer.data.playback

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** libass as app/natives/ass/aura_ass.c gives it (build-ass.sh): a handle per renderer, one thread per handle. */
internal object AssNative {
    /** Whether libass is here: libmpv.so, which has it, and libaurass.so are packaged and load. */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("mpv")
            System.loadLibrary("aurass")
        }.onFailure { android.util.Log.w("AuraAss", "libass not loaded: ASS stays plain text", it) }.isSuccess
    }

    @JvmStatic external fun create(fontsConf: String?, family: String?): Long
    @JvmStatic external fun setSize(handle: Long, frameW: Int, frameH: Int, videoW: Int, videoH: Int)
    @JvmStatic external fun setFontScale(handle: Long, scale: Double)
    @JvmStatic external fun loadScript(handle: Long, utf8: ByteArray): Boolean
    @JvmStatic external fun newTrack(handle: Long, header: ByteArray): Boolean
    @JvmStatic external fun addEvent(handle: Long, chunk: ByteArray, startMs: Long, durationMs: Long)
    @JvmStatic external fun addFont(handle: Long, name: String, font: ByteArray)
    @JvmStatic external fun clearFonts(handle: Long)
    @JvmStatic external fun refreshFonts(handle: Long)
    @JvmStatic external fun render(handle: Long, timeMs: Long, force: Boolean, box: IntArray): Int
    @JvmStatic external fun copy(handle: Long, bitmap: Bitmap): Boolean
    @JvmStatic external fun free(handle: Long)
}

/**
 * A fansub's .ass as its author set it, over the picture Media3 plays — signs set on the signs
 * they translate, an opening's karaoke filling syllable by syllable, a line drawn in layers drawn
 * once — where Media3's own reader leaves plain lines in three lanes. libass draws it (the one in
 * libmpv: mpv draws the ASS it plays itself) into a picture of the frame's size; [frame] is what
 * has something in it, and where. The player asks for the moment it shows ([renderAt]) each
 * frame; a moment like the last is not drawn again.
 *
 * Everything libass does happens on one thread of its own: the first draw after install scans
 * the headset's fonts (SubtitleFonts) — seconds, once.
 */
class AssRenderer(context: Context) {

    /** The subtitles drawn: [bitmap] goes at ([x], [y]) of a [frameWidth]×[frameHeight] frame. */
    class Frame(val bitmap: Bitmap, val x: Int, val y: Int, val frameWidth: Int, val frameHeight: Int)

    private val appContext = context.applicationContext
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-ass").apply { isDaemon = true } }

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    /** Which script this is: a draw made for one before it is not shown. */
    @Volatile private var script = 0
    private val drawing = AtomicBoolean(false)
    private val askedMs = AtomicLong(0)
    @Volatile private var size = Size(0, 0, 0, 0)
    @Volatile private var fontScale = 1.0

    // The thread's own.
    private var handle = 0L
    private var loaded = false
    private var sizeSet: Size? = null
    private var scaleSet = 1.0
    private var force = false
    private val ring = arrayOfNulls<Bitmap>(RING)
    private var nextBitmap = 0

    private data class Size(val width: Int, val height: Int, val videoWidth: Int, val videoHeight: Int)

    /** [utf8], a whole .ass file, becomes what is drawn. */
    fun load(utf8: ByteArray, label: String) {
        val id = ++script
        _frame.value = null
        unfollow()
        thread.execute {
            if (!ensureHandle()) return@execute
            val started = android.os.SystemClock.elapsedRealtime()
            loaded = AssNative.loadScript(handle, utf8)
            force = true
            android.util.Log.i(TAG, "${if (loaded) "loaded" else "could not read"} $label (${utf8.size / 1024} KB) " +
                "in ${android.os.SystemClock.elapsedRealtime() - started} ms, script $id")
        }
    }

    /**
     * An MKV's ASS stream ([AssStreams.Stream]) becomes what is drawn: its header, the events read
     * so far, and each one read from now on — the file is read ahead of what plays.
     */
    fun follow(stream: AssStreams.Stream, label: String) {
        val id = ++script
        _frame.value = null
        unfollow()
        val listener: (AssStreams.Event) -> Unit = { e -> thread.execute { if (id == script && loaded) AssNative.addEvent(handle, e.chunk, e.startMs, e.durationMs) } }
        thread.execute {
            if (!ensureHandle()) return@execute
            loaded = AssNative.newTrack(handle, stream.header)
            // Registered here, on the thread: an event read meanwhile is in the list or comes after it.
            stream.listeners += listener
            val had = stream.events.toList()
            had.forEach { AssNative.addEvent(handle, it.chunk, it.startMs, it.durationMs) }
            force = true
            android.util.Log.i(TAG, "following $label: ${had.size} events so far, script $id")
        }
        following = stream to listener
    }

    private var following: Pair<AssStreams.Stream, (AssStreams.Event) -> Unit>? = null

    private fun unfollow() {
        following?.let { (stream, listener) -> stream.listeners -= listener }
        following = null
    }

    /**
     * The fonts a video file carries ([fonts]: name and bytes — an MKV's attachments), for the ASS
     * set in them: replacing the last file's. Until they come, PICO Sans stands in.
     */
    fun setFonts(fonts: List<Pair<String, ByteArray>>) {
        thread.execute {
            if (!ensureHandle()) return@execute
            AssNative.clearFonts(handle)
            fonts.forEach { (name, data) -> AssNative.addFont(handle, name, data) }
            AssNative.refreshFonts(handle)
            force = true
            if (fonts.isNotEmpty()) android.util.Log.i(TAG, "${fonts.size} fonts of the file: ${fonts.joinToString { it.first }}")
        }
    }

    /** Nothing is drawn until the next [load]. */
    fun clear() {
        script++
        _frame.value = null
        unfollow()
        thread.execute {
            if (handle != 0L && loaded) AssNative.loadScript(handle, ByteArray(0))
            loaded = false
        }
    }

    /** The frame the subtitles are drawn for, in pixels, and the video's own size (for its pixels' shape). */
    fun setSize(width: Int, height: Int, videoWidth: Int = 0, videoHeight: Int = 0) {
        size = Size(width, height, videoWidth, videoHeight)
    }

    /** 字幕大小: 1 = the script's own sizes. */
    fun setFontScale(scale: Float) {
        fontScale = scale.toDouble()
    }

    /** Draw the subtitles of [timeMs] — skipped while the last is still being drawn: the next frame asks again. */
    fun renderAt(timeMs: Long) {
        askedMs.set(timeMs)
        if (!drawing.compareAndSet(false, true)) return
        val id = script
        thread.execute {
            try {
                draw(id)
            } catch (e: RuntimeException) {
                android.util.Log.w(TAG, "draw failed", e)
            } finally {
                drawing.set(false)
            }
        }
    }

    fun release() {
        script++
        _frame.value = null
        unfollow()
        thread.execute {
            if (handle != 0L) AssNative.free(handle)
            handle = 0L
            loaded = false
        }
        thread.shutdown()
    }

    private fun ensureHandle(): Boolean {
        if (handle != 0L) return true
        if (!AssNative.available) return false
        val started = android.os.SystemClock.elapsedRealtime()
        handle = AssNative.create(SubtitleFonts.conf(appContext).absolutePath, "sans-serif")
        android.util.Log.i(TAG, "libass ready in ${android.os.SystemClock.elapsedRealtime() - started} ms (fonts)")
        return handle != 0L
    }

    private fun draw(id: Int) {
        if (handle == 0L || !loaded || id != script) return
        val s = size
        if (s.width <= 0 || s.height <= 0) return
        if (s != sizeSet) {
            AssNative.setSize(handle, s.width, s.height, s.videoWidth, s.videoHeight)
            sizeSet = s
            force = true
        }
        if (fontScale != scaleSet) {
            AssNative.setFontScale(handle, fontScale)
            scaleSet = fontScale
            force = true
        }
        val box = IntArray(4)
        val started = android.os.SystemClock.elapsedRealtime()
        val status = AssNative.render(handle, askedMs.get(), force, box)
        force = false
        when (status) {
            NOTHING_NEW -> return
            NOTHING_TO_SHOW -> {
                if (id == script) _frame.value = null
                return
            }
        }
        val bitmap = bitmapFor(box[2], box[3], s.width)
        if (!AssNative.copy(handle, bitmap)) return
        val took = android.os.SystemClock.elapsedRealtime() - started
        if (took > SLOW_MS) android.util.Log.i(TAG, "slow draw: $took ms for ${box[2]}×${box[3]}")
        if (id == script) _frame.value = Frame(bitmap, box[0], box[1], s.width, s.height)
    }

    /**
     * A bitmap of [w]×[h] from the ring — three, so the one drawn into is never the one shown nor
     * the one before it — made as wide as the frame, so that it is shrunk to each box, not made anew.
     */
    private fun bitmapFor(w: Int, h: Int, frameWidth: Int): Bitmap {
        val i = nextBitmap
        nextBitmap = (nextBitmap + 1) % RING
        val held = ring[i]
        if (held != null && held.allocationByteCount >= w * h * 4) {
            held.reconfigure(w, h, Bitmap.Config.ARGB_8888)
            return held
        }
        val rows = ((h + ROW_STEP - 1) / ROW_STEP) * ROW_STEP
        val made = Bitmap.createBitmap(maxOf(w, frameWidth), rows, Bitmap.Config.ARGB_8888)
        made.reconfigure(w, h, Bitmap.Config.ARGB_8888)
        ring[i] = made
        return made
    }

    private companion object {
        const val TAG = "AuraAss"
        const val RING = 3
        const val ROW_STEP = 64
        const val SLOW_MS = 40L
        /** AssNative.render's answers. */
        const val NOTHING_NEW = 0
        const val NOTHING_TO_SHOW = 2
    }
}
