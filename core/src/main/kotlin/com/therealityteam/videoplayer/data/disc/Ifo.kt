package com.therealityteam.videoplayer.data.disc

/**
 * A DVD title set's information file (VIDEO_TS/VTS_nn_0.IFO): the cells its title VOBs play — where each
 * lies (sectors of VTS_nn_1.VOB, VTS_nn_2.VOB… read as one), how long it plays, which VOB it is part of,
 * whether its clock starts over — and its chapters (part-of-title entries, each the first cell of a
 * program). Only what playing the title set's VOBs end to end needs: no menus, commands or angles.
 */
object Ifo {

    /** A cell: its sectors [first, last] in the title VOBs, how long it plays, its VOB, and whether the clock starts over at it. */
    data class Cell(val first: Long, val last: Long, val ms: Long, val vob: Int, val clockRestarts: Boolean)

    /** The title set: its cells in the order they lie in the VOBs (each once), and the first sectors of its chapters. */
    data class TitleSet(val cells: List<Cell>, val chapterSectors: List<Long>) {
        val durationMs: Long get() = cells.sumOf { it.ms }

        /** Where each cell starts in the title set's time, in milliseconds. */
        val startsMs: List<Long> get() = cells.runningFold(0L) { t, c -> t + c.ms }.dropLast(1)

        /** Where its chapters start, in milliseconds, in order (a second apart at least); empty for one or none. */
        val chaptersMs: List<Long>
            get() {
                val starts = startsMs
                val at = chapterSectors.mapNotNull { s -> cells.indexOfFirst { it.first == s }.takeIf { it >= 0 }?.let { starts[it] } }.sorted()
                val out = ArrayList<Long>()
                for (t in at) if (out.isEmpty() || t - out.last() >= 1_000) out += t
                if (out.isNotEmpty() && out.first() < 1_000) out[0] = 0
                return if (out.size < 2) emptyList() else out
            }
    }

    private const val SECTOR = 2048

    /** The title set [b] describes, or null when it is no VTS IFO (or holds no cells). */
    fun parse(b: ByteArray): TitleSet? {
        if (b.size < 0x100 || String(b, 0, 12, Charsets.US_ASCII) != "DVDVIDEO-VTS") return null
        val pgci = u32(b, 0xCC) * SECTOR
        if (pgci <= 0 || pgci + 8 > b.size) return null
        val count = u16(b, pgci.toInt())
        val cells = HashMap<Long, Cell>()
        val chapters = ArrayList<Long>()
        // Each program chain: its cells (where, how long, which VOB) and its programs' first cells.
        val programs = HashMap<Int, List<Long>>() // PGC number → each program's first sector
        for (n in 0 until count) {
            val entry = (pgci + 8 + n * 8).toInt()
            if (entry + 8 > b.size) break
            val pgc = (pgci + u32(b, entry + 4)).toInt()
            if (pgc + 0xEC > b.size) continue
            val programCount = b[pgc + 2].toInt() and 0xFF
            val cellCount = b[pgc + 3].toInt() and 0xFF
            val map = pgc + u16(b, pgc + 0xE6)
            val playback = pgc + u16(b, pgc + 0xE8)
            val position = pgc + u16(b, pgc + 0xEA)
            if (playback + cellCount * 24 > b.size || position + cellCount * 4 > b.size || map + programCount > b.size) continue
            val firsts = (0 until cellCount).map { i ->
                val at = playback + i * 24
                val first = u32(b, at + 8)
                val cell = Cell(
                    first = first,
                    last = u32(b, at + 20),
                    ms = time(b, at + 4),
                    vob = u16(b, position + i * 4),
                    clockRestarts = b[at].toInt() and 0x02 != 0,
                )
                cells.putIfAbsent(first, cell)
                first
            }
            programs[n + 1] = (0 until programCount).mapNotNull { p -> firsts.getOrNull((b[map + p].toInt() and 0xFF) - 1) }
        }
        // The chapters: each title's parts, as (program chain, program).
        val ptt = u32(b, 0xC8) * SECTOR
        if (ptt > 0 && ptt + 8 <= b.size) {
            val titles = u16(b, ptt.toInt())
            val end = minOf(ptt + u32(b, ptt.toInt() + 4) + 1, b.size.toLong()).toInt()
            val offsets = (0 until titles).map { ptt + u32(b, (ptt + 8 + it * 4).toInt().coerceAtMost(b.size - 4)) }
            offsets.forEachIndexed { i, from ->
                val to = offsets.getOrNull(i + 1) ?: end.toLong()
                var at = from.toInt()
                while (at + 4 <= to && at + 4 <= b.size) {
                    programs[u16(b, at)]?.getOrNull(u16(b, at + 2) - 1)?.let { chapters += it }
                    at += 4
                }
            }
        }
        if (cells.isEmpty()) return null
        return TitleSet(cells.values.sortedBy { it.first }, chapters.distinct())
    }

    /** A playback time: hours, minutes, seconds in BCD, then frames in BCD with the rate in the top two bits (01 25, 11 29.97). */
    private fun time(b: ByteArray, at: Int): Long {
        fun bcd(v: Int) = (v shr 4) * 10 + (v and 0x0F)
        val h = bcd(b[at].toInt() and 0xFF)
        val m = bcd(b[at + 1].toInt() and 0xFF)
        val s = bcd(b[at + 2].toInt() and 0xFF)
        val f = bcd(b[at + 3].toInt() and 0x3F)
        val fps = if ((b[at + 3].toInt() shr 6) and 3 == 1) 25.0 else 30000.0 / 1001
        return ((h * 3600L + m * 60L + s) * 1000) + (f * 1000 / fps).toLong()
    }

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}
