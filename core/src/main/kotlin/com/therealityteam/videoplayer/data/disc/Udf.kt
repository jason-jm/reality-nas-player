package com.therealityteam.videoplayer.data.disc

import com.therealityteam.videoplayer.data.playback.ByteSource

/**
 * The UDF file system of a disc image (ECMA-167 as UDF profiles it): a Blu-ray's UDF 2.50, with its
 * metadata partition, and a DVD's UDF 1.02. Read through a [ByteSource] — a local file, or a share's
 * file a few sectors at a time — to find where a file's bytes lie in the image ([extents]), so the
 * film inside is streamed straight from the image without copying it out.
 *
 * Supported: physical (type 1) partitions, sparable ones read as physical (an image has no defects to
 * spare), the metadata partition (and its mirror); short, long and embedded allocation descriptors and
 * their continuation extents. Not: virtual (VAT) partitions — a disc burnt in sessions, not a film's.
 */
class Udf private constructor(
    private val src: ByteSource,
    private val blockSize: Int,
    private val maps: List<PartitionMap?>,
) : DiscFiles {

    /** The root folder's file entry, from the file set descriptor. */
    private var root = LongAd(0, 0, 0)

    /** Where a run of a file's bytes lies in the image: [offset] in bytes, or -1 for a run of zeros. */
    data class Extent(val offset: Long, val length: Long)

    /** A directory entry: its name, whether it is a folder, and where its file entry is. */
    data class Entry(override val name: String, override val isDirectory: Boolean, val icb: LongAd) : DiscFiles.Entry

    /** A long allocation descriptor: an extent of [length] bytes at logical block [block] of partition reference [partition]. */
    data class LongAd(val length: Long, val block: Long, val partition: Int)

    /** What a partition reference resolves through: a range of sectors, or the metadata file's extents. */
    private sealed interface PartitionMap {
        /** [startSector]: the partition's first sector in the image. */
        data class Physical(val number: Int, val startSector: Long) : PartitionMap

        /** UDF 2.50's metadata partition: its blocks are the bytes of the metadata file, in [extents]. */
        data class Metadata(val extents: List<Extent>) : PartitionMap
    }

    /** The entries of the folder [path] ("BDMV/PLAYLIST", "" for the root), or null when there is none. */
    override fun list(path: String): List<Entry>? {
        var dir = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val next = entries(dir)?.firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) } ?: return null
            dir = next.icb
        }
        return entries(dir)
    }

    /** The file at [path]: where its bytes lie, in order, and how many there are. Null when there is none. */
    override fun file(path: String): Pair<List<Extent>, Long>? {
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entry = list(parent)?.firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) } ?: return null
        return fileData(entry.icb)
    }

    /** Up to [max] bytes of the file at [path] — for small files (a playlist, an IFO). */
    override fun read(path: String, max: Int): ByteArray? {
        val (extents, size) = file(path) ?: return null
        return readExtents(extents, minOf(size, max.toLong()).toInt())
    }

    // ---- Files -------------------------------------------------------------------------------

    private fun entries(dir: LongAd): List<Entry>? {
        val (extents, size) = fileData(dir) ?: return null
        val data = readExtents(extents, minOf(size, MAX_DIRECTORY.toLong()).toInt())
        val out = mutableListOf<Entry>()
        var at = 0
        while (at + 38 <= data.size) {
            if (u16(data, at) != TAG_FID) break
            val characteristics = data[at + 18].toInt() and 0xFF
            val nameLength = data[at + 19].toInt() and 0xFF
            val icb = longAd(data, at + 20)
            val implUse = u16(data, at + 36)
            val nameAt = at + 38 + implUse
            val length = 38 + implUse + nameLength
            if (nameAt + nameLength > data.size) break
            val deleted = characteristics and 0x04 != 0
            val parent = characteristics and 0x08 != 0
            if (!deleted && !parent && nameLength > 0) {
                out += Entry(dString(data, nameAt, nameLength), characteristics and 0x02 != 0, icb)
            }
            at += (length + 3) and 3.inv()
        }
        return out
    }

    /** A file entry's data: its extents and its length. */
    private fun fileData(icb: LongAd): Pair<List<Extent>, Long>? {
        val sector = sectorOf(icb.block, icb.partition) ?: return null
        val fe = src.read(sector * SECTOR, blockSize)
        if (fe.size < 176) return null
        val tag = u16(fe, 0)
        val (eaLengthAt, adStart) = when (tag) {
            TAG_FE -> 168 to 176
            TAG_EFE -> 208 to 216
            else -> return null
        }
        val size = u64(fe, 56)
        val eaLength = u32(fe, eaLengthAt).toInt()
        val adLength = u32(fe, eaLengthAt + 4).toInt()
        val adType = u16(fe, 34) and 0x07
        val start = adStart + eaLength
        if (start + adLength > fe.size || eaLength < 0 || adLength < 0) return null
        if (adType == AD_EMBEDDED) {
            // The data is the descriptor area itself: point at it where it lies in the image.
            return listOf(Extent(sector * SECTOR + start, adLength.toLong())) to size
        }
        val extents = mutableListOf<Extent>()
        allocations(fe, start, adLength, adType, icb.partition, extents, depth = 0)
        return extents to size
    }

    /** The extents [adLength] bytes of allocation descriptors at [at] in [block] name — following continuations. */
    private fun allocations(block: ByteArray, at: Int, adLength: Int, adType: Int, partition: Int, out: MutableList<Extent>, depth: Int) {
        val size = if (adType == AD_SHORT) 8 else 16
        var p = at
        while (p + size <= at + adLength) {
            val raw = u32(block, p)
            val length = raw and 0x3FFFFFFF
            val kind = (raw ushr 30).toInt()
            if (length == 0L) break
            val lbn = u32(block, p + 4)
            val part = if (adType == AD_SHORT) partition else u16(block, p + 8)
            when (kind) {
                EXTENT_RECORDED -> sectorOf(lbn, part)?.let { out += Extent(it * SECTOR, length) } ?: return
                EXTENT_NEXT -> {
                    // The descriptors go on in an allocation extent descriptor elsewhere.
                    if (depth > 64) return
                    val sector = sectorOf(lbn, part) ?: return
                    val aed = src.read(sector * SECTOR, blockSize)
                    if (aed.size < 24 || u16(aed, 0) != TAG_AED) return
                    val more = u32(aed, 20).toInt()
                    allocations(aed, 24, minOf(more, aed.size - 24), adType, part, out, depth + 1)
                    return
                }
                // Allocated or not, nothing recorded there: zeros.
                else -> out += Extent(-1, length)
            }
            p += size
        }
    }

    private fun readExtents(extents: List<Extent>, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(max)
        for (e in extents) {
            if (out.size() >= max) break
            val want = minOf(e.length, (max - out.size()).toLong()).toInt()
            if (e.offset < 0) out.write(ByteArray(want)) else out.write(src.read(e.offset, want))
        }
        return out.toByteArray()
    }

    /** The image sector a logical block of partition reference [partition] is at. */
    private fun sectorOf(block: Long, partition: Int): Long? = when (val map = maps.getOrNull(partition)) {
        is PartitionMap.Physical -> map.startSector + block * blockSize / SECTOR
        is PartitionMap.Metadata -> {
            var at = block * blockSize
            var found: Long? = null
            for (e in map.extents) {
                if (at < e.length) {
                    found = if (e.offset < 0) null else (e.offset + at) / SECTOR
                    break
                }
                at -= e.length
            }
            found
        }
        null -> null
    }

    companion object {
        const val SECTOR = 2048

        private const val TAG_PD = 5
        private const val TAG_LVD = 6
        private const val TAG_TD = 8
        private const val TAG_FSD = 256
        private const val TAG_FID = 257
        private const val TAG_AED = 258
        private const val TAG_FE = 261
        private const val TAG_EFE = 266
        private const val AD_SHORT = 0
        private const val AD_EMBEDDED = 3
        private const val EXTENT_RECORDED = 0
        private const val EXTENT_NEXT = 3
        private const val MAX_DIRECTORY = 4 shl 20

        /** The image's UDF file system, or null when it has none (an ISO 9660-only image, not a disc). */
        fun open(src: ByteSource): Udf? = runCatching { read(src) }.getOrNull()

        private fun read(src: ByteSource): Udf? {
            // The anchor at sector 256 points at the volume's descriptors.
            val anchor = src.read(256L * SECTOR, SECTOR)
            if (anchor.size < 32 || u16(anchor, 0) != 2 || !tagOk(anchor)) return null
            val vdsLength = u32(anchor, 16)
            val vdsSector = u32(anchor, 20)
            val partitions = HashMap<Int, Long>()
            var lvd: ByteArray? = null
            for (i in 0 until minOf(vdsLength / SECTOR, 64L)) {
                val d = src.read((vdsSector + i) * SECTOR, SECTOR)
                if (d.size < 16 || !tagOk(d)) continue
                when (u16(d, 0)) {
                    TAG_PD -> partitions[u16(d, 22)] = u32(d, 188)
                    TAG_LVD -> lvd = d
                    TAG_TD -> break
                }
            }
            val lv = lvd ?: return null
            val blockSize = u32(lv, 212).toInt().takeIf { it in 512..65536 } ?: return null
            val fsd = longAd(lv, 248)
            val mapCount = u32(lv, 268).toInt()
            // First the physical maps, then the metadata map that reads through one of them.
            val raw = mutableListOf<ByteArray>()
            var at = 440
            repeat(mapCount) {
                val type = lv[at].toInt() and 0xFF
                val length = lv[at + 1].toInt() and 0xFF
                if (length <= 0 || at + length > lv.size) return null
                raw += lv.copyOfRange(at, at + length)
                if (type !in 1..2) return null
                at += length
            }
            val physical = raw.mapIndexed { i, m ->
                val number = u16(m, if ((m[0].toInt() and 0xFF) == 1) 4 else 38)
                partitions[number]?.let { i to PartitionMap.Physical(number, it) }
            }.filterNotNull().toMap()
            val maps = arrayOfNulls<PartitionMap>(raw.size)
            raw.forEachIndexed { i, m ->
                if ((m[0].toInt() and 0xFF) == 1) {
                    maps[i] = physical[i]
                } else {
                    val id = String(m, 5, 23, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
                    val base = physical[i] ?: return@forEachIndexed
                    maps[i] = when {
                        id.startsWith("*UDF Metadata Partition") -> metadataMap(src, blockSize, base, u32(m, 40), u32(m, 44))
                        id.startsWith("*UDF Sparable Partition") -> base
                        else -> null // a virtual partition: not a film disc's
                    }
                }
            }
            val udf = Udf(src, blockSize, maps.toList())
            val fsdSector = udf.sectorOf(fsd.block, fsd.partition) ?: return null
            val fsdBlock = src.read(fsdSector * SECTOR, SECTOR)
            if (fsdBlock.size < 416 || u16(fsdBlock, 0) != TAG_FSD) return null
            udf.root = longAd(fsdBlock, 400)
            return udf
        }

        /** The metadata partition: the extents of its metadata file (or of the mirror, if the file is unreadable). */
        private fun metadataMap(src: ByteSource, blockSize: Int, base: PartitionMap.Physical, file: Long, mirror: Long): PartitionMap? {
            for (lbn in listOf(file, mirror)) {
                val sector = base.startSector + lbn * blockSize / SECTOR
                val fe = src.read(sector * SECTOR, blockSize)
                if (fe.size < 216) continue
                val tag = u16(fe, 0)
                if (tag != TAG_EFE && tag != TAG_FE) continue
                // The metadata file's descriptors are short_ads in the physical partition beneath it.
                val reader = Udf(src, blockSize, listOf(base))
                val (extents, _) = reader.fileData(LongAd(blockSize.toLong(), lbn, 0)) ?: continue
                if (extents.isNotEmpty()) return PartitionMap.Metadata(extents)
            }
            return null
        }

        private fun tagOk(d: ByteArray): Boolean {
            var sum = 0
            for (i in 0 until 16) if (i != 4) sum += d[i].toInt() and 0xFF
            return (sum and 0xFF) == (d[4].toInt() and 0xFF)
        }

        internal fun longAd(b: ByteArray, at: Int) = LongAd(u32(b, at) and 0x3FFFFFFF, u32(b, at + 4), u16(b, at + 8))

        private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, at: Int): Long =
            (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

        private fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)

        /** A d-string's characters: compression 8 is one byte each, 16 two (big-endian). */
        private fun dString(b: ByteArray, at: Int, length: Int): String {
            if (length < 1) return ""
            return when (b[at].toInt() and 0xFF) {
                16, 255 -> String(b, at + 1, (length - 1) and 1.inv(), Charsets.UTF_16BE)
                else -> String(b, at + 1, length - 1, Charsets.ISO_8859_1)
            }
        }
    }
}
