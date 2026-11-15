package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.disc.DiscTitle
import com.therealityteam.videoplayer.data.disc.PsClock
import com.therealityteam.videoplayer.data.disc.TsClock
import com.therealityteam.videoplayer.data.disc.Udf
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** What opening a disc image (原盘 .iso) for playing came to. */
sealed interface DiscOpen {
    /** Its film, served at [url] as one stream (.m2ts for a Blu-ray, .vob for a DVD). */
    data class Playing(val url: String, val title: DiscTitle) : DiscOpen {
        /** (A log line: a disc of 59 title sets listed every run of the image, past what logcat keeps of a line.) */
        override fun toString() = "Playing(${title.summary})"
    }

    /** Its film is encrypted (AACS / CSS): nothing in it can be read as it is. */
    data class Encrypted(val kind: DiscTitle.Kind) : DiscOpen

    /** No film in it — software, a data disc — or no disc file system at all. */
    data object NoFilm : DiscOpen

    /** No film, but videos: a data disc of them (a tutorial's), by their paths in it, to play one after another. */
    data class Videos(val paths: List<String>) : DiscOpen

    /** The image could not be read (the share went away). */
    data object Unreadable : DiscOpen
}

/** A file on the headset (or the computer), read where it lies. */
internal class FileSource(private val file: File) : LocalStreamServer.Source {
    @Volatile private var raf: RandomAccessFile? = null

    private fun open(): RandomAccessFile = raf ?: synchronized(this) { raf ?: RandomAccessFile(file, "r").also { raf = it } }

    override fun size(): Long = file.length()

    override fun copyRange(start: Long, length: Long, out: OutputStream) {
        val buf = ByteArray(256 * 1024)
        var at = start
        var left = length
        try {
            while (left > 0) {
                val n = synchronized(this) {
                    val f = open()
                    f.seek(at)
                    f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                }
                if (n <= 0) break
                out.write(buf, 0, n)
                at += n
                left -= n
            }
        } catch (_: Exception) {
            // the reader went away (a seek): stop
        }
    }

    @Synchronized
    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }
}

/** A file a server hands out by Range requests (a WebDAV share), with the headers it wants (its login). */
internal class HttpRangeSource(private val url: String, private val headers: Map<String, String>) : LocalStreamServer.Source {
    @Volatile private var length = -1L

    override fun size(): Long {
        if (length >= 0) return length
        val conn = open(0, 0)
        try {
            length = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: conn.contentLengthLong.takeIf { conn.responseCode == 200 } ?: -1L
        } finally {
            conn.disconnect()
        }
        return length
    }

    override fun copyRange(start: Long, length: Long, out: OutputStream) {
        if (length <= 0) return
        val conn = open(start, start + length - 1)
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        } catch (_: Exception) {
            // the reader went away, or the server: stop
        } finally {
            conn.disconnect()
        }
    }

    private fun open(from: Long, to: Long): HttpURLConnection = (URL(url.replace(" ", "%20")).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 20_000
        setRequestProperty("Range", "bytes=$from-$to")
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    override fun close() {}
}

/**
 * A [LocalStreamServer.Source] as a [ByteSource], for reading a disc image's file system: the reads are
 * a sector or a few at a time, scattered — each would be a request to a share — so they go by 64 KB
 * blocks, the last [MAX_BLOCKS] kept.
 */
internal class SourceBytes(private val source: LocalStreamServer.Source) : ByteSource {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }

    override val length: Long by lazy { source.size() }

    override fun read(offset: Long, size: Int): ByteArray {
        val end = minOf(offset + size, length)
        if (offset < 0 || end <= offset) return ByteArray(0)
        val out = ByteArray((end - offset).toInt())
        var at = offset
        while (at < end) {
            val index = at / BLOCK
            val block = block(index)
            val inBlock = (at - index * BLOCK).toInt()
            if (inBlock >= block.size) break
            val n = minOf(block.size - inBlock, (end - at).toInt())
            System.arraycopy(block, inBlock, out, (at - offset).toInt(), n)
            at += n
        }
        return if (at == end) out else out.copyOf((at - offset).toInt())
    }

    @Synchronized
    private fun block(index: Long): ByteArray = blocks.getOrPut(index) {
        val from = index * BLOCK
        val n = minOf(BLOCK.toLong(), length - from)
        java.io.ByteArrayOutputStream(n.toInt()).also { source.copyRange(from, n, it) }.toByteArray()
    }

    private companion object {
        const val BLOCK = 64 * 1024
        const val MAX_BLOCKS = 64
    }
}

/**
 * A disc's film as one file: its streams' runs of the image, in order — zeros for a run not recorded —
 * each Blu-ray clip after the first with its clock moved on ([DiscTitle.Part.shift], [TsClock]).
 */
internal class DiscStream(
    private val image: LocalStreamServer.Source,
    parts: List<DiscTitle.Part>,
    private val kind: DiscTitle.Kind = DiscTitle.Kind.BLURAY,
) : LocalStreamServer.Source {
    /** What its clock is moved on in: a Blu-ray's 192-byte packets, a DVD's 2048-byte packs. */
    private val unit = if (kind == DiscTitle.Kind.DVD) PsClock.PACK.toLong() else TsClock.PACKET.toLong()

    private class Run(val extents: List<Udf.Extent>, val length: Long, val shift: Long)

    private val runs = parts.map { p -> DiscTitle.trimmed(p.extents, p.size).let { e -> Run(e, e.sumOf { it.length }, p.shift) } }
    private val total = runs.sumOf { it.length }

    override fun size(): Long = total

    override fun copyRange(start: Long, length: Long, out: OutputStream) {
        var at = start
        var left = minOf(length, total - start)
        var base = 0L
        for (r in runs) {
            if (left <= 0) break
            if (at < base + r.length) {
                val inRun = at - base
                val n = minOf(r.length - inRun, left)
                val whole = if (r.shift == 0L) copy(r.extents, inRun, n, out) else copyShifted(r, inRun, n, out)
                if (!whole) return // the image's source stopped short: so does this
                at += n
                left -= n
            }
            base += r.length
        }
    }

    /** [n] bytes of a run from [from] in it, as they are on the disc. False when the image gave fewer. */
    private fun copy(extents: List<Udf.Extent>, from: Long, n: Long, out: OutputStream): Boolean {
        var at = from
        var left = n
        var base = 0L
        for (e in extents) {
            if (left <= 0) break
            if (at < base + e.length) {
                val inExtent = at - base
                val k = minOf(e.length - inExtent, left)
                if (e.offset < 0) {
                    val zeros = ByteArray(64 * 1024)
                    var z = k
                    while (z > 0) { val m = minOf(z, zeros.size.toLong()).toInt(); out.write(zeros, 0, m); z -= m }
                } else {
                    val counting = CountingStream(out)
                    image.copyRange(e.offset + inExtent, k, counting)
                    if (counting.count < k) return false
                }
                at += k
                left -= k
            }
            base += e.length
        }
        return true
    }

    /**
     * [n] bytes of a clip from [from], its clock moved on: read by whole packets (a range starts and ends
     * where the reader likes), each packet's timestamps changed in place, the asked-for bytes written.
     */
    private fun copyShifted(r: Run, from: Long, n: Long, out: OutputStream): Boolean {
        val end = from + n
        var chunkStart = from - from % unit
        while (chunkStart < end) {
            val chunkEnd = minOf(r.length, chunkStart + 1024 * unit, end + (unit - end % unit) % unit)
            val buf = java.io.ByteArrayOutputStream((chunkEnd - chunkStart).toInt())
            val whole = copy(r.extents, chunkStart, chunkEnd - chunkStart, buf)
            val bytes = buf.toByteArray()
            if (kind == DiscTitle.Kind.DVD) PsClock.shift(bytes, r.shift) else TsClock.shift(bytes, r.shift)
            val skip = (maxOf(from, chunkStart) - chunkStart).toInt()
            val upTo = minOf(bytes.size.toLong(), end - chunkStart).toInt()
            if (upTo > skip) out.write(bytes, skip, upTo - skip)
            if (!whole) return false
            chunkStart = chunkEnd
        }
        return true
    }

    override fun close() = image.close()

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
