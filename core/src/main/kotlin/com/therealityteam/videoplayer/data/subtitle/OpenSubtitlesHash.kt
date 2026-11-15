package com.therealityteam.videoplayer.data.subtitle

import java.io.File
import java.io.RandomAccessFile

/**
 * OpenSubtitles file-hash algorithm: 64-bit checksum from file size + first/last 64 KB.
 * Used to fetch the exact matching subtitle for a video ("online subtitle by hash").
 */
object OpenSubtitlesHash {

    private const val CHUNK = 64 * 1024

    /** Pure core (testable): size + summed little-endian longs of head & tail. */
    fun hash(sizeBytes: Long, head: ByteArray, tail: ByteArray): String {
        var hash = sizeBytes + sumLongs(head) + sumLongs(tail)
        return String.format("%016x", hash)
    }

    fun ofFile(file: File): String? = runCatching {
        val size = file.length()
        if (size < CHUNK) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(CHUNK)
            raf.seek(0)
            raf.readFully(head)
            val tail = ByteArray(CHUNK)
            raf.seek(size - CHUNK)
            raf.readFully(tail)
            hash(size, head, tail)
        }
    }.getOrNull()

    private fun sumLongs(bytes: ByteArray): Long {
        var sum = 0L
        val n = (bytes.size / 8) * 8
        var i = 0
        while (i < n) {
            var v = 0L
            for (j in 0 until 8) v = v or ((bytes[i + j].toLong() and 0xFF) shl (8 * j))
            sum += v
            i += 8
        }
        return sum
    }
}
