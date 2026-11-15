package com.therealityteam.videoplayer.data.disc

import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import java.io.OutputStream

/** What [DiscTitle] finds a disc's film in: an image's file system ([Udf]), or a disc ripped as its files ([DiscFolder]). */
interface DiscFiles {
    /** A name in a folder, and whether it is a folder itself. */
    interface Entry {
        val name: String
        val isDirectory: Boolean
    }

    /** The entries of the folder [path] ("BDMV/PLAYLIST"), or null when there is none. */
    fun list(path: String): List<Entry>?

    /** The file at [path]: where its bytes lie, in order, and how many there are. Null when there is none. */
    fun file(path: String): Pair<List<Udf.Extent>, Long>?

    /** Up to [max] bytes of the file at [path] — for small files (a playlist, an IFO). */
    fun read(path: String, max: Int = 1 shl 20): ByteArray?

    /** [read] of each of [paths] (a disc's playlists), in their order — one after another, unless reading them side by side is quicker. */
    fun readAll(paths: List<String>, max: Int): List<ByteArray?> = paths.map { read(it, max) }

    /** The folders [paths] about to be listed: listed side by side now where that is quicker, else nothing. */
    fun prefetch(paths: List<String>) {}
}

/**
 * A disc ripped as its files — a folder holding BDMV (or VIDEO_TS), as a NAS keeps a backup — read as an
 * image is: each file the film asks for is laid after the last in one run of bytes, as an image's files
 * lie in it, so [DiscTitle] finds the film the same way and the stream server plays its clips as one
 * stream. [lister] lists a folder relative to the disc's (names, folders flagged, sizes when it gives
 * them); [opener] opens a file relative to it.
 */
internal class DiscFolder(
    private val lister: (relative: String) -> List<Listed>?,
    private val opener: (relative: String) -> LocalStreamServer.Source?,
) : DiscFiles, LocalStreamServer.Source {

    data class Listed(override val name: String, override val isDirectory: Boolean, val size: Long?) : DiscFiles.Entry

    /** A file laid in the run: where it starts, how long it is, what reads it. */
    private class Laid(val start: Long, val size: Long, val source: LocalStreamServer.Source)

    private val listed = HashMap<String, List<Listed>?>()
    private val listings = java.util.concurrent.atomic.AtomicInteger()
    private val smallReads = java.util.concurrent.atomic.AtomicInteger()
    private val listingNanos = java.util.concurrent.atomic.AtomicLong()
    private val readAllNanos = java.util.concurrent.atomic.AtomicLong()

    /** What reading it has cost so far, for the log: "2 listings 120 ms, 131 small reads 600 ms, 6 streams". */
    val stats: String
        get() = "${listings.get()} listings ${listingNanos.get() / 1_000_000} ms, ${smallReads.get()} small reads " +
            "${readAllNanos.get() / 1_000_000} ms, ${synchronized(laid) { laid.size }} streams"
    private val laid = LinkedHashMap<String, Laid>()
    @Volatile private var end = 0L

    override fun list(path: String): List<DiscFiles.Entry>? = listing(path)

    private fun listing(path: String): List<Listed>? {
        val key = path.trim('/').uppercase()
        synchronized(listed) { if (key in listed) return listed[key] }
        // (Listed outside the lock, so two folders can be asked for at once.)
        val t = System.nanoTime()
        val found = runCatching { listings.incrementAndGet(); lister(path.trim('/')) }.getOrNull()
        listingNanos.addAndGet(System.nanoTime() - t)
        return synchronized(listed) { listed.getOrPut(key) { found } }
    }

    /** Each folder listed is a request to the share (~140 ms for a disc's STREAM): the ones about to be listed, together. */
    override fun prefetch(paths: List<String>) {
        if (paths.size < 2) return
        val threads = paths.map { p -> Thread { listing(p) }.apply { start() } }
        threads.forEach { it.join() }
    }

    override fun file(path: String): Pair<List<Udf.Extent>, Long>? {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entry = listing(parent)?.firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) } ?: return null
        val relative = if (parent.isEmpty()) entry.name else "$parent/${entry.name}"
        val at = synchronized(laid) {
            laid.getOrPut(relative.uppercase()) {
                val source = opener(relative) ?: return null
                val size = entry.size ?: runCatching { source.size() }.getOrNull()?.takeIf { it >= 0 } ?: run { source.close(); return null }
                Laid(end, size, source).also { end += size }
            }
        }
        return listOf(Udf.Extent(at.start, at.size)) to at.size
    }

    /**
     * A small file read and let go — a playlist, an IFO: not laid in the run, so a backup's hundreds of
     * playlists do not each keep a handle open on the share for as long as the film plays.
     */
    override fun read(path: String, max: Int): ByteArray? {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entry = listing(parent)?.firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) } ?: return null
        val source = opener(if (parent.isEmpty()) entry.name else "$parent/${entry.name}") ?: return null
        smallReads.incrementAndGet()
        return try {
            val n = minOf(entry.size ?: source.size(), max.toLong())
            java.io.ByteArrayOutputStream(n.toInt()).also { source.copyRange(0, n, it) }.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * On a share each file is three round trips — open, read, close: ~75 ms on the user's NAS — so a
     * backup's playlists are read up to 24 at a time (27 playlists: one wave, not four).
     */
    override fun readAll(paths: List<String>, max: Int): List<ByteArray?> {
        val t = System.nanoTime()
        try {
            return readAllOf(paths, max)
        } finally {
            readAllNanos.addAndGet(System.nanoTime() - t)
        }
    }

    private fun readAllOf(paths: List<String>, max: Int): List<ByteArray?> {
        if (paths.size < 4) return paths.map { read(it, max) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(24, paths.size))
        return try {
            paths.map { p -> pool.submit<ByteArray?> { read(p, max) } }.map { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    override fun size(): Long = end

    /** The run's bytes from [start]: from the files laid there, in order. */
    override fun copyRange(start: Long, length: Long, out: OutputStream) {
        val files = synchronized(laid) { laid.values.toList() }
        var at = start
        var left = minOf(length, end - start)
        for (f in files) {
            if (left <= 0) break
            if (at >= f.start + f.size) continue
            val n = minOf(f.start + f.size - at, left)
            val counting = Counting(out)
            f.source.copyRange(at - f.start, n, counting)
            if (counting.count < n) return // the file's source stopped short: so does this
            at += n
            left -= n
        }
    }

    override fun close() = synchronized(laid) {
        laid.values.forEach { runCatching { it.source.close() } }
        laid.clear()
    }

    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
