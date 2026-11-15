package com.therealityteam.videoplayer.data.playback

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A file the player reaches over http — a share through the loopback proxy ([LocalStreamServer]),
 * a media server's stream URL — as a [ByteSource], for [ChapterReader]. The reader walks a
 * container a dozen bytes at a time (one element header per read), so the file is fetched by Range
 * requests in [BLOCK]-byte blocks and each block kept: a Matroska file's chapters cost a request or
 * two, not hundreds. At most [maxBlocks] are ever fetched, so a walk that goes astray stops instead of
 * pulling a film over the network. A server that ignores Range can still answer for the first block.
 */
class HttpByteSource(
    private val url: String,
    private val timeoutMs: Int = 4_000,
    private val maxBlocks: Int = 24,
) : ByteSource {

    private val blocks = HashMap<Long, ByteArray>()
    private var total = -1L

    /** How many requests have been made — for tests and the log. */
    var requests = 0
        private set

    override val length: Long
        get() {
            if (total < 0) block(0)
            return total
        }

    override fun read(offset: Long, size: Int): ByteArray {
        if (offset < 0 || size <= 0) return ByteArray(0)
        val end = if (length >= 0) minOf(offset + size, length) else offset + size
        if (end <= offset) return ByteArray(0)
        val out = ByteArray((end - offset).toInt())
        var at = offset
        while (at < end) {
            val index = at / BLOCK
            val b = block(index)
            val inBlock = (at - index * BLOCK).toInt()
            if (inBlock >= b.size) break // the file ended inside this block
            val n = minOf(b.size - inBlock, (end - at).toInt())
            System.arraycopy(b, inBlock, out, (at - offset).toInt(), n)
            at += n
        }
        return if (at == end) out else out.copyOf((at - offset).toInt())
    }

    /**
     * [size] bytes at [offset] in one request, kept nowhere and not counted against [maxBlocks] —
     * an attachment's data, read once (a fansub's font: megabytes, not a walk's dozen bytes).
     */
    fun fetch(offset: Long, size: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Range", "bytes=$offset-${offset + size - 1}")
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP ${conn.responseCode} for a range of $url")
            val bytes = conn.inputStream.use { it.readNBytesCompat(size) }
            if (bytes.size != size) throw IOException("${bytes.size} of $size bytes from $url")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun block(index: Long): ByteArray {
        blocks[index]?.let { return it }
        if (requests >= maxBlocks) throw IOException("read more than $maxBlocks blocks of $url")
        requests++
        val start = index * BLOCK
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Range", "bytes=$start-${start + BLOCK - 1}")
            val bytes = when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // "bytes 0-65535/1234567"
                    conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        ?.let { total = it }
                    conn.inputStream.use { it.readNBytesCompat(BLOCK) }
                }
                HttpURLConnection.HTTP_OK -> {
                    // No Range: the whole file is coming. Its head is all such a server can give.
                    if (conn.contentLengthLong >= 0) total = conn.contentLengthLong
                    if (start != 0L) throw IOException("$url does not answer Range requests")
                    conn.inputStream.use { it.readNBytesCompat(BLOCK) }
                }
                416 -> ByteArray(0) // past the end
                else -> throw IOException("HTTP ${conn.responseCode} from $url")
            }
            if (total < 0 && bytes.size < BLOCK) total = start + bytes.size
            blocks[index] = bytes
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) buf else buf.copyOf(got)
    }

    companion object {
        const val BLOCK = 64 * 1024
    }
}
