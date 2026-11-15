package com.therealityteam.videoplayer.data.subtitle

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The player engine reads subtitle files as UTF-8 and nothing else, so a GBK or Big5 `.srt`
 * — still the common case for Chinese subtitles — comes out as mojibake. Before such a file
 * is handed to the engine it is decoded with the charset [SubtitleParser.detectCharset]
 * finds and written back out as UTF-8 in the cache; UTF-8 files (with or without a BOM) go
 * through untouched.
 */
object SubtitleTranscoder {

    /** Files above this are left alone — a subtitle that large is not a subtitle. */
    const val MAX_BYTES = 8L * 1024 * 1024

    /**
     * Which way of telling the charset made a cached copy (2: by everyday writing, not GBK for all; 3: fifteen code
     * pages, so a Russian or Korean file a 2 read as GBK is converted again).
     */
    private const val READING = 3

    /** The charset to convert from, or null when the bytes are already UTF-8. */
    fun charsetToConvert(bytes: ByteArray): Charset? {
        val detected = SubtitleParser.detectCharset(bytes)
        return if (detected == StandardCharsets.UTF_8) null else detected
    }

    /** UTF-8 bytes of [bytes] read as [from], without a byte-order mark. */
    fun toUtf8(bytes: ByteArray, from: Charset): ByteArray =
        String(bytes, from).removePrefix("﻿").toByteArray(StandardCharsets.UTF_8)

    /**
     * The path to hand the engine for [file]: the file itself when it is UTF-8 (or too big
     * to bother with), else a UTF-8 copy under [cacheDir], reused while the original's size
     * and modification time stand.
     */
    fun utf8Path(file: File, cacheDir: File): File {
        if (!file.isFile || file.length() > MAX_BYTES) return file
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return file
        val from = charsetToConvert(bytes) ?: return file
        com.therealityteam.videoplayer.util.Log.i("AuraSubs", "${file.name} read as ${from.name()}, given as UTF-8")
        // The reading's version in the key: a copy made by an older guess (Big5 read as GBK) is not reused.
        val key = Integer.toHexString(("$READING:" + file.absolutePath + ":" + file.length() + ":" + file.lastModified()).hashCode())
        val copy = File(File(cacheDir, "subtitles"), "$key.${file.extension.ifEmpty { "srt" }}")
        if (!copy.isFile) {
            copy.parentFile?.mkdirs()
            runCatching { copy.writeBytes(toUtf8(bytes, from)) }.onFailure { return file }
        }
        return copy
    }
}
