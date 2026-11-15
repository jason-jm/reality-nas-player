package com.therealityteam.videoplayer.data.playback

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** One chapter mark: where it starts and what it is called. */
data class Chapter(val startMs: Long, val title: String)

/** Random access to the bytes of a container, however it is stored. */
interface ByteSource {
    val length: Long

    /** Up to [size] bytes at [offset]; fewer only at the end of the source. */
    fun read(offset: Long, size: Int): ByteArray
}

/** A local file as a [ByteSource]. */
class FileByteSource(private val file: File) : ByteSource {
    override val length: Long get() = file.length()
    override fun read(offset: Long, size: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        if (offset >= raf.length()) return ByteArray(0)
        raf.seek(offset)
        val buf = ByteArray(minOf(size.toLong(), raf.length() - offset).toInt())
        var got = 0
        while (got < buf.size) {
            val n = raf.read(buf, got, buf.size - got)
            if (n < 0) break
            got += n
        }
        if (got == buf.size) buf else buf.copyOf(got)
    }
}

/**
 * Reads chapter marks straight out of a container — the player engine exposes none. Matroska
 * keeps them in an EBML `Chapters` element (reached through `SeekHead`, or met on the way to
 * the first `Cluster`); MP4 in the Nero `chpl` box under `moov/udta`, which is what ffmpeg and
 * most muxers write. Pure byte parsing, so it is unit-tested without a device.
 */
object ChapterReader {

    /** Chapters of a local video, or none — a missing or unreadable file is simply chapterless. */
    fun read(path: String): List<Chapter> {
        val local = when {
            path.startsWith("file://") -> path.removePrefix("file://")
            path.startsWith("/") -> path
            else -> return emptyList()
        }
        val file = File(local)
        if (!file.isFile) return emptyList()
        return runCatching { read(FileByteSource(file)) }.getOrDefault(emptyList())
    }

    /**
     * Chapters of a file the player streams over http — a share through the loopback proxy, a media
     * server's URL — or none; [source] is left with its request count for the caller to log.
     */
    fun readRemote(source: HttpByteSource): List<Chapter> =
        runCatching { read(source) }.getOrDefault(emptyList())

    fun read(source: ByteSource): List<Chapter> {
        val head = source.read(0, 12)
        if (head.size < 8) return emptyList()
        val marks = when {
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                readMatroska(source)
            String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> readMp4(source)
            else -> emptyList()
        }
        return marks.map { it.copy(title = named(it.title)) }
    }

    /**
     * [title], or blank where it names nothing: a muxer's stand-in — `00:07:06.926` (La La Land's
     * sixteen chapters, each called by its own start time, shown twice over in the list), `Chapter
     * 01`, `チャプター1`, `第3章`, a bare number. A blank title reads as 第 N 章 wherever it is shown.
     */
    fun named(title: String): String {
        val t = title.trim()
        return if (STAND_IN.matches(t)) "" else t
    }

    private val STAND_IN = Regex(
        """(?i)\d{1,2}:\d{2}:\d{2}(?:[.,]\d+)?|\d{1,3}|""" +
            """(?:chapter|chapitre|kapitel|capitolo|cap[ií]tulo|hoofdstuk|rozdział|глава|チャプター|章节|章節)\s*\d{1,3}|""" +
            """第\s*\d{1,3}\s*[章节節]""",
    )

    // ---- Matroska ---------------------------------------------------------------------------

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAP_STRING = 0x85L
    private const val ID_CHAP_LANGUAGE = 0x437CL

    internal class Element(val id: Long, val dataOffset: Long, val dataSize: Long) {
        val end: Long get() = dataOffset + dataSize
        val unknownSize: Boolean get() = dataSize < 0
    }

    /** Reads the element header at [offset]: id, then size; null past the end or on garbage. */
    internal fun element(source: ByteSource, offset: Long): Element? {
        val buf = source.read(offset, 12)
        if (buf.isEmpty()) return null
        val idLen = vintLength(buf[0]) ?: return null
        if (buf.size < idLen + 1) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (buf[i].toLong() and 0xFF)
        val sizeLen = vintLength(buf[idLen]) ?: return null
        if (buf.size < idLen + sizeLen) return null
        var size = (buf[idLen].toLong() and 0xFF) and ((1L shl (8 - sizeLen)) - 1)
        var allOnes = size == (1L shl (8 - sizeLen)) - 1
        for (i in 1 until sizeLen) {
            val b = buf[idLen + i].toLong() and 0xFF
            size = (size shl 8) or b
            if (b != 0xFFL) allOnes = false
        }
        return Element(id, offset + idLen + sizeLen, if (allOnes) -1 else size)
    }

    private fun vintLength(first: Byte): Int? {
        val b = first.toInt() and 0xFF
        if (b == 0) return null
        var len = 1
        var mask = 0x80
        while (b and mask == 0) { len++; mask = mask shr 1 }
        return len
    }

    private fun readMatroska(source: ByteSource): List<Chapter> =
        topLevel(source, ID_CHAPTERS)?.let { parseChapters(source, it) } ?: emptyList()

    /**
     * A Matroska file's top-level element [wantedId] (Chapters, Attachments…), or null. Walks the
     * segment's children: a SeekHead may point at one kept past the clusters; otherwise it comes
     * before the first Cluster, where the walk stops.
     */
    internal fun topLevel(source: ByteSource, wantedId: Long): Element? {
        val segment = segment(source) ?: return null
        val segmentEnd = if (segment.unknownSize) source.length else minOf(segment.end, source.length)
        var offset = segment.dataOffset
        var foundAt: Long? = null
        while (offset < segmentEnd) {
            val el = element(source, offset) ?: break
            when (el.id) {
                ID_SEEK_HEAD -> seekHeadTarget(source, el, wantedId)?.let { foundAt = segment.dataOffset + it }
                wantedId -> return el
                ID_CLUSTER -> break
            }
            if (el.unknownSize) break
            offset = el.end
        }
        val at = foundAt ?: return null
        val el = element(source, at) ?: return null
        return if (el.id == wantedId) el else null
    }

    internal fun segment(source: ByteSource): Element? {
        val ebml = element(source, 0) ?: return null
        if (ebml.id != 0x1A45DFA3L || ebml.unknownSize) return null
        return element(source, ebml.end)?.takeIf { it.id == ID_SEGMENT }
    }

    /** The position a SeekHead records for [wantedId], relative to the segment's data start. */
    private fun seekHeadTarget(source: ByteSource, seekHead: Element, wantedId: Long): Long? {
        var offset = seekHead.dataOffset
        while (offset < seekHead.end) {
            val seek = element(source, offset) ?: return null
            if (seek.id == ID_SEEK) {
                var id = 0L
                var position = -1L
                var inner = seek.dataOffset
                while (inner < seek.end) {
                    val f = element(source, inner) ?: break
                    when (f.id) {
                        ID_SEEK_ID -> id = readUInt(source, f)
                        ID_SEEK_POSITION -> position = readUInt(source, f)
                    }
                    inner = f.end
                }
                if (id == wantedId && position >= 0) return position
            }
            offset = seek.end
        }
        return null
    }

    private fun parseChapters(source: ByteSource, chapters: Element): List<Chapter> {
        val out = mutableListOf<Chapter>()
        var offset = chapters.dataOffset
        while (offset < chapters.end) {
            val edition = element(source, offset) ?: break
            if (edition.id == ID_EDITION_ENTRY) {
                var inner = edition.dataOffset
                while (inner < edition.end) {
                    val atom = element(source, inner) ?: break
                    if (atom.id == ID_CHAPTER_ATOM) parseAtom(source, atom)?.let { out += it }
                    inner = atom.end
                }
                // The first edition is the default one; alternate editions are rarely wanted.
                if (out.isNotEmpty()) break
            }
            offset = edition.end
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseAtom(source: ByteSource, atom: Element): Chapter? {
        var startNs = -1L
        var hidden = false
        var title: String? = null
        var titleLang: String? = null
        var offset = atom.dataOffset
        while (offset < atom.end) {
            val f = element(source, offset) ?: break
            when (f.id) {
                ID_CHAPTER_TIME_START -> startNs = readUInt(source, f)
                ID_CHAPTER_FLAG_HIDDEN -> hidden = readUInt(source, f) != 0L
                ID_CHAPTER_DISPLAY -> {
                    var name: String? = null
                    var lang: String? = null
                    var inner = f.dataOffset
                    while (inner < f.end) {
                        val d = element(source, inner) ?: break
                        when (d.id) {
                            ID_CHAP_STRING -> name = readString(source, d, Charsets.UTF_8)
                            ID_CHAP_LANGUAGE -> lang = readString(source, d, Charsets.US_ASCII)
                        }
                        inner = d.end
                    }
                    // Keep the first display, but prefer a Chinese one when the file offers several.
                    if (name != null && (title == null || (lang?.startsWith("chi") == true && titleLang?.startsWith("chi") != true))) {
                        title = name
                        titleLang = lang
                    }
                }
            }
            offset = f.end
        }
        if (hidden || startNs < 0) return null
        return Chapter(startMs = startNs / 1_000_000, title = title?.trim().orEmpty())
    }

    internal fun readUInt(source: ByteSource, el: Element): Long {
        if (el.dataSize <= 0 || el.dataSize > 8) return 0
        val b = source.read(el.dataOffset, el.dataSize.toInt())
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    internal fun readString(source: ByteSource, el: Element, charset: Charset): String {
        if (el.dataSize <= 0 || el.dataSize > 4096) return ""
        return String(source.read(el.dataOffset, el.dataSize.toInt()), charset).trimEnd(' ')
    }

    // ---- MP4 --------------------------------------------------------------------------------

    private fun readMp4(source: ByteSource): List<Chapter> {
        val moov = findBox(source, 0, source.length, "moov") ?: return emptyList()
        val udta = findBox(source, moov.first, moov.second, "udta") ?: return emptyList()
        val chpl = findBox(source, udta.first, udta.second, "chpl") ?: return emptyList()
        return parseChpl(source.read(chpl.first, (chpl.second - chpl.first).toInt()))
    }

    /** (payload start, payload end) of the first [type] box between [from] and [to]. */
    private fun findBox(source: ByteSource, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var offset = from
        while (offset + 8 <= to) {
            val h = source.read(offset, 16)
            if (h.size < 8) return null
            var size = beUInt(h, 0)
            val name = String(h, 4, 4, Charsets.US_ASCII)
            var header = 8L
            if (size == 1L) {
                if (h.size < 16) return null
                size = beLong(h, 8)
                header = 16L
            } else if (size == 0L) {
                size = to - offset
            }
            if (size < header) return null
            if (name == type) return (offset + header) to minOf(offset + size, to)
            offset += size
        }
        return null
    }

    /** Nero chapters: version(1) flags(3) reserved(4) count(1), then [start 100ns (8) · len (1) · title]. */
    private fun parseChpl(b: ByteArray): List<Chapter> {
        if (b.size < 9) return emptyList()
        val version = b[0].toInt() and 0xFF
        var p = if (version == 0) 4 else 8
        if (p >= b.size) return emptyList()
        val count = b[p].toInt() and 0xFF
        p++
        val out = mutableListOf<Chapter>()
        repeat(count) {
            if (p + 9 > b.size) return out.sortedBy { c -> c.startMs }
            val start100ns = beLong(b, p)
            val len = b[p + 8].toInt() and 0xFF
            p += 9
            if (p + len > b.size) return out.sortedBy { c -> c.startMs }
            val title = String(b, p, len, Charsets.UTF_8)
            p += len
            out += Chapter(startMs = start100ns / 10_000, title = title.trim())
        }
        return out.sortedBy { it.startMs }
    }

    private fun beUInt(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun beLong(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}

/** Which chapter a position falls in — the last one starting at or before it. */
fun List<Chapter>.indexAt(positionMs: Long): Int = indexOfLast { it.startMs <= positionMs }
