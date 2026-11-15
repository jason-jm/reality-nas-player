package com.therealityteam.videoplayer.data.model

import java.io.Reader

/**
 * Hand one object at a time out of a JSON array, without building the array.
 *
 * The library was read as a single 33 MB string and then parsed into one JSONArray of 9.6k
 * JSONObjects before a single [MediaItem] existed: the file as UTF-16, plus the whole object tree,
 * plus the list — hundreds of megabytes of transient heap for a list that ends up a fraction of
 * that, and an OOM on any device whose heap is not enlarged. Splitting the text as it streams keeps
 * the peak at one object, and the mapper stays the one the rest of the code already uses.
 *
 * This is a splitter, not a parser: it tracks strings and escapes only well enough to know which
 * braces are structure, and hands each object's text to `org.json` to actually read.
 *
 * It is written for the shape of the data rather than for brevity. Most of a library is text inside
 * strings — paths, titles, overviews — so string contents run through a tight inner loop that looks
 * for nothing but a quote or a backslash, and an object's text is copied in whole runs rather than a
 * character at a time. A first version that asked every one of 33 million characters the same five
 * questions and appended each one singly ran 37% slower than reading the file whole.
 */
object JsonArrayStream {

    /**
     * Calls [onObject] with the text of each top-level object in the array [reader] holds.
     *
     * Returns whether the array ended where it should. A false means the file was truncated —
     * a half-written library — and the caller must not treat what it got as the whole of it.
     */
    fun forEachObject(reader: Reader, onObject: (String) -> Unit): Boolean {
        val buf = CharArray(1 shl 16)
        val sb = StringBuilder(8192)
        var opened = false
        var closed = false
        var depth = 0
        var inString = false
        // Where the current object's not-yet-copied text starts in this chunk; -1 between objects.
        var start = -1
        // A backslash as the very last character of a chunk escapes the first of the next one.
        var escapeCarry = false
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            var i = if (escapeCarry) 1 else 0
            escapeCarry = false
            if (depth > 0) start = 0
            while (i < n) {
                if (inString) {
                    val c = buf[i]
                    if (c == '\\') {
                        if (i + 1 >= n) { escapeCarry = true; i = n } else i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i++
                    continue
                }
                when (buf[i]) {
                    '"' -> inString = true
                    '{' -> { if (depth == 0) start = i; depth++ }
                    '}' -> {
                        if (--depth == 0) {
                            sb.append(buf, start, i - start + 1)
                            onObject(sb.toString())
                            sb.setLength(0)
                            start = -1
                        }
                    }
                    '[' -> opened = true
                    ']' -> if (depth == 0 && opened) closed = true
                }
                i++
            }
            if (depth > 0 && start >= 0) sb.append(buf, start, n - start)
        }
        return opened && closed && depth == 0
    }
}
