package com.therealityteam.videoplayer.data.disc

import com.therealityteam.videoplayer.data.playback.ByteSource

/**
 * The ISO 9660 file system of a disc image with no UDF — a data CD or DVD burnt the older way, a tutorial
 * disc's .mov files — read for what [DiscTitle] reads of a disc: its folders, where a file's bytes lie, a
 * small file's contents. Joliet's names (UTF-16, long and in any script) when the disc has them, else the
 * primary ones ("INTRO.MOV;1" → "INTRO.MOV"). Not: Rock Ridge, multi-extent files (past 4 GB), interleaving.
 */
class Iso9660 private constructor(
    private val src: ByteSource,
    private val root: Record,
    private val joliet: Boolean,
) : DiscFiles {

    /** A directory record: its name, whether a folder, where its bytes start and how many there are. */
    data class Record(override val name: String, override val isDirectory: Boolean, val offset: Long, val length: Long) : DiscFiles.Entry

    override fun list(path: String): List<Record>? {
        var dir = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            dir = records(dir)?.firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return records(dir)
    }

    override fun file(path: String): Pair<List<Udf.Extent>, Long>? {
        val name = path.substringAfterLast('/')
        val r = list(path.substringBeforeLast('/', ""))?.firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) } ?: return null
        return listOf(Udf.Extent(r.offset, r.length)) to r.length
    }

    override fun read(path: String, max: Int): ByteArray? {
        val (extents, size) = file(path) ?: return null
        return src.read(extents.first().offset, minOf(size, max.toLong()).toInt())
    }

    private fun records(dir: Record): List<Record>? {
        if (!dir.isDirectory || dir.length > MAX_DIRECTORY) return null
        val data = src.read(dir.offset, dir.length.toInt())
        val out = ArrayList<Record>()
        var at = 0
        while (at < data.size) {
            val len = data[at].toInt() and 0xFF
            // A record never crosses a sector: the rest of this one is padding.
            if (len == 0) {
                at = (at / SECTOR + 1) * SECTOR
                continue
            }
            if (at + len > data.size || len < 34) break
            record(data, at, len)?.let(out::add)
            at += len
        }
        return out
    }

    private fun record(b: ByteArray, at: Int, len: Int): Record? {
        val nameLen = b[at + 32].toInt() and 0xFF
        if (33 + nameLen > len) return null
        // "\u0000" and "\u0001" name the folder itself and its parent.
        if (nameLen == 1 && (b[at + 33].toInt() == 0 || b[at + 33].toInt() == 1)) return null
        val raw = if (joliet) String(b, at + 33, nameLen and 0xFE, Charsets.UTF_16BE) else String(b, at + 33, nameLen, Charsets.ISO_8859_1)
        val name = raw.substringBefore(';').trimEnd('.').ifEmpty { return null }
        val directory = (b[at + 25].toInt() and 0x02) != 0
        return Record(name, directory, le32(b, at + 2) * SECTOR, le32(b, at + 10))
    }

    companion object {
        private const val SECTOR = 2048
        private const val MAX_DIRECTORY = 4L shl 20

        /** The ISO 9660 file system of the image [src] reads (Joliet's when it has one), or null when it has none. */
        fun open(src: ByteSource): Iso9660? {
            var primary: Record? = null
            var joliet: Record? = null
            for (sector in 16 until 64) {
                val d = src.read(sector.toLong() * SECTOR, SECTOR)
                if (d.size < SECTOR || String(d, 1, 5, Charsets.US_ASCII) != "CD001") break
                when (d[0].toInt() and 0xFF) {
                    1 -> primary = rootOf(d)
                    // A supplementary descriptor of UCS-2 level 1-3 (escapes %/@ %/C %/E) is Joliet's.
                    2 -> if (d[88].toInt() == 0x25 && d[89].toInt() == 0x2F && (d[90].toInt() == 0x40 || d[90].toInt() == 0x43 || d[90].toInt() == 0x45)) joliet = rootOf(d)
                    255 -> break
                }
            }
            val root = joliet ?: primary ?: return null
            return Iso9660(src, root, joliet = joliet != null)
        }

        private fun rootOf(d: ByteArray): Record = Record("", true, le32(d, 156 + 2) * SECTOR, le32(d, 156 + 10))

        private fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}
