package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.playback.ChapterReader.Element
import java.util.NavigableMap
import java.util.TreeMap

/** Locates the PGS display set that was already active when playback jumped into a cluster. */
class MatroskaPgs private constructor(
    private val track: Int,
    private val scaleNs: Long,
    private val points: NavigableMap<Long, Point>,
) {
    data class Display(val startUs: Long, val endUs: Long, val packet: ByteArray)
    private data class Point(val cluster: Long, val relative: Long?, val durationUs: Long?)

    fun displayAt(source: ByteSource, timeUs: Long): Display? {
        val (start, point) = points.floorEntry(timeUs) ?: return null
        val next = points.higherKey(start) ?: Long.MAX_VALUE
        val end = minOf(next, point.durationUs?.let { Math.addExact(start, it) } ?: next)
        if (timeUs >= end) return Display(end, next, byteArrayOf())
        val cluster = ChapterReader.element(source, point.cluster)?.takeIf { it.id == CLUSTER } ?: return null
        val limit = if (cluster.unknownSize) source.length else cluster.end
        val exact = point.relative?.let { Math.addExact(cluster.dataOffset, it) }
        var clusterTicks: Long? = null
        var pos = cluster.dataOffset
        while (pos < limit) {
            val child = ChapterReader.element(source, pos) ?: return null
            if (child.unknownSize || child.end > limit || child.id == CLUSTER) break
            if (child.id == CLUSTER_TIME) {
                clusterTicks = ChapterReader.readUInt(source, child)
                if (exact != null && exact > pos) {
                    pos = exact
                    continue
                }
            }
            if (clusterTicks != null && (child.id == SIMPLE_BLOCK || child.id == BLOCK_GROUP)) {
                val block = if (child.id == SIMPLE_BLOCK) child else children(source, child).firstOrNull { it.id == BLOCK }
                val payload = block?.let { packetOffset(source, it, clusterTicks, start) }
                if (payload != null) {
                    val duration = if (child.id == BLOCK_GROUP) field(source, child, BLOCK_DURATION) else null
                    val stop = duration?.let { minOf(end, Math.addExact(start, toUs(it, scaleNs))) } ?: end
                    if (timeUs >= stop) return Display(stop, next, byteArrayOf())
                    val size = block.end - payload
                    if (size !in 1..MAX_PACKET_BYTES) return null
                    val packet = source.read(payload, size.toInt())
                    return packet.takeIf { it.size.toLong() == size }?.let { Display(start, stop, it) }
                }
            }
            if (pos == exact) break
            pos = child.end
        }
        return null
    }

    private fun packetOffset(source: ByteSource, block: Element, clusterTicks: Long, startUs: Long): Long? {
        val header = source.read(block.dataOffset, minOf(12L, block.dataSize).toInt())
        val first = header.firstOrNull()?.toInt()?.and(255) ?: return null
        val len = Integer.numberOfLeadingZeros(first) - 23 // EBML track-number VINT.
        if (len !in 1..8 || header.size < len + 3) return null
        var number = first.toLong() and (0xffL shr (len + 1))
        for (i in 1 until len) number = (number shl 8) or (header[i].toLong() and 255)
        if (number != track.toLong() || header[len + 2].toInt() and 6 != 0) return null
        val relativeTicks = (header[len].toInt() shl 8) or (header[len + 1].toInt() and 255)
        if (toUs(clusterTicks + relativeTicks, scaleNs) != startUs) return null
        return block.dataOffset + len + 3
    }

    companion object {
        private const val INFO = 0x1549A966L
        private const val TRACKS = 0x1654AE6BL
        private const val CUES = 0x1C53BB6BL
        private const val CLUSTER = 0x1F43B675L
        private const val CLUSTER_TIME = 0xE7L
        private const val SIMPLE_BLOCK = 0xA3L
        private const val BLOCK_GROUP = 0xA0L
        private const val BLOCK = 0xA1L
        private const val BLOCK_DURATION = 0x9BL
        private const val MAX_PACKET_BYTES = 8L * 1024 * 1024

        fun open(source: ByteSource, track: Int): MatroskaPgs? {
            val segment = ChapterReader.segment(source) ?: return null
            val tracks = ChapterReader.topLevel(source, TRACKS) ?: return null
            val entry = children(source, tracks).filter { it.id == 0xAEL }.firstOrNull {
                field(source, it, 0xD7) == track.toLong()
            } ?: return null
            val fields = children(source, entry).toList()
            val codec = fields.firstOrNull { it.id == 0x86L } ?: return null
            if (ChapterReader.readString(source, codec, Charsets.US_ASCII) != "S_HDMV/PGS" ||
                fields.any { it.id == 0x6D80L }) return null // Encrypted/header-stripped blocks need the extractor.
            val info = ChapterReader.topLevel(source, INFO) ?: return null
            val scaleNs = field(source, info, 0x2AD7B1) ?: 1_000_000L
            if (scaleNs <= 0) return null
            val cues = ChapterReader.topLevel(source, CUES) ?: return null
            val points = TreeMap<Long, Point>()
            for (cue in children(source, cues)) {
                if (cue.id != 0xBBL) continue
                var ticks: Long? = null
                var point: Point? = null
                for (child in children(source, cue)) {
                    if (child.id == 0xB3L) ticks = ChapterReader.readUInt(source, child)
                    if (child.id != 0xB7L) continue
                    // Most CueTrackPositions are video keyframes. Avoid reading the rest of those entries.
                    if (field(source, child, 0xF7) != track.toLong()) continue
                    val cluster = field(source, child, 0xF1) ?: continue
                    point = Point(Math.addExact(segment.dataOffset, cluster), field(source, child, 0xF0),
                        field(source, child, 0xB2)?.let { toUs(it, scaleNs) })
                }
                if (ticks != null && point != null) points[toUs(ticks, scaleNs)] = point
            }
            return if (points.isEmpty()) null else MatroskaPgs(track, scaleNs, points)
        }

        private fun toUs(ticks: Long, scaleNs: Long) = Math.multiplyExact(ticks, scaleNs) / 1000

        private fun field(source: ByteSource, parent: Element, id: Long): Long? =
            children(source, parent).firstOrNull { it.id == id }?.let { ChapterReader.readUInt(source, it) }

        private fun children(source: ByteSource, parent: Element) = sequence {
            var at = parent.dataOffset
            while (at < parent.end) {
                val e = ChapterReader.element(source, at) ?: break
                if (e.unknownSize || e.end <= at || e.end > parent.end) break
                yield(e)
                at = e.end
            }
        }
    }
}
