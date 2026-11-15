package com.therealityteam.videoplayer.data.disc

/**
 * A Blu-ray movie playlist (BDMV/PLAYLIST/xxxxx.mpls): the clips it plays, in order, each from its IN
 * to its OUT time (45 kHz), and its chapters (entry marks). Only what finding the film needs — no
 * sub-paths or stream tables.
 */
object Mpls {

    /** One play item: [clip] is the BDMV/STREAM file's name without .m2ts ("00800"). */
    data class Item(val clip: String, val inTime: Long, val outTime: Long, val multiAngle: Boolean)

    /** A chapter's start: in play item [item], at its clip's time [time] (45 kHz). */
    data class Mark(val item: Int, val time: Long)

    data class Playlist(val items: List<Item>, val marks: List<Mark> = emptyList()) {
        /** How long the playlist plays, in milliseconds. */
        val durationMs: Long get() = items.sumOf { (it.outTime - it.inTime).coerceAtLeast(0) } / 45

        /** How long it plays with each part (a clip from an in to an out point) counted once: a loop's one pass. */
        val onceMs: Long get() = items.distinctBy { Triple(it.clip, it.inTime, it.outTime) }.sumOf { (it.outTime - it.inTime).coerceAtLeast(0) } / 45

        /**
         * Where its chapters start in the film, in milliseconds — each mark's time in its item, after the
         * items before it — in order; a mark less than a second after the last is the same chapter's.
         * Empty for one chapter or none.
         */
        val chaptersMs: List<Long>
            get() {
                val starts = LongArray(items.size + 1)
                items.forEachIndexed { i, it -> starts[i + 1] = starts[i] + (it.outTime - it.inTime).coerceAtLeast(0) }
                val at = marks.filter { it.item in items.indices }
                    .map { m -> (starts[m.item] + (m.time - items[m.item].inTime).coerceIn(0, starts[m.item + 1] - starts[m.item])) / 45 }
                    .sorted()
                val out = ArrayList<Long>()
                // (A mark in the last second — many discs end on one — starts nothing.)
                val end = starts.last() / 45
                for (t in at) if (t < end - 1_000 && (out.isEmpty() || t - out.last() >= 1_000)) out += t
                if (out.isNotEmpty() && out.first() < 1_000) out[0] = 0
                return if (out.size < 2) emptyList() else out
            }
    }

    /** The playlist in [b], or null when it is not one. */
    fun parse(b: ByteArray): Playlist? {
        if (b.size < 40 || String(b, 0, 4, Charsets.US_ASCII) != "MPLS") return null
        val start = u32(b, 8).toInt()
        if (start < 40 || start + 10 > b.size) return null
        val count = u16(b, start + 6)
        val items = ArrayList<Item>(count)
        var at = start + 10
        repeat(count) {
            if (at + 2 > b.size) return null
            val length = u16(b, at)
            val body = at + 2
            if (length < 20 || body + length > b.size) return null
            val clip = String(b, body, 5, Charsets.US_ASCII)
            val flags = u16(b, body + 9)
            items += Item(clip, u32(b, body + 12), u32(b, body + 16), multiAngle = flags and 0x10 != 0)
            at = body + length
        }
        return Playlist(items, marks(b))
    }

    /** The playlist's entry marks — its chapters — from its PlayListMark table; none when it has none. */
    private fun marks(b: ByteArray): List<Mark> {
        val start = u32(b, 12).toInt()
        if (start < 40 || start + 6 > b.size) return emptyList()
        val count = u16(b, start + 4)
        return (0 until count).mapNotNull { i ->
            val at = start + 6 + i * 14
            if (at + 14 > b.size) return@mapNotNull null
            // (type 1: an entry mark — a chapter; type 2 marks a link point, not a chapter.)
            if (b[at + 1].toInt() != 1) return@mapNotNull null
            Mark(u16(b, at + 2), u32(b, at + 4))
        }
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}
