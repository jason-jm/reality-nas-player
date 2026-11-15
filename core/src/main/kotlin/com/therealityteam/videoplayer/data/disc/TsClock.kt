package com.therealityteam.videoplayer.data.disc

/**
 * A Blu-ray clip's clock moved on, in place: the program clock references and the PES presentation and
 * decoding times of its 192-byte packets (a 4-byte arrival stamp, then a 188-byte transport packet) — so
 * a film's clips, each recorded on its own clock, play as one timeline. Bytes are changed, never added or
 * taken away: a range of the film is still the same range.
 */
object TsClock {

    const val PACKET = 192

    private const val WRAP = 1L shl 33

    /** Moves the clock of every whole packet in [b] (from its start, on 192-byte boundaries) on by [shift] (90 kHz). */
    fun shift(b: ByteArray, shift: Long) {
        if (shift == 0L) return
        var p = 0
        while (p + PACKET <= b.size) {
            packet(b, p + 4, shift)
            p += PACKET
        }
    }

    /** The 188-byte transport packet at [at]. */
    private fun packet(b: ByteArray, at: Int, shift: Long) {
        if (b[at].toInt() and 0xFF != 0x47) return
        val unitStart = b[at + 1].toInt() and 0x40 != 0
        val control = (b[at + 3].toInt() shr 4) and 3
        var payload = at + 4
        if (control and 2 != 0) {
            val length = b[at + 4].toInt() and 0xFF
            if (length > 0 && length <= 183) {
                val flags = b[at + 5].toInt() and 0xFF
                var field = at + 6
                if (flags and 0x10 != 0) { pcr(b, field, shift); field += 6 } // PCR
                if (flags and 0x08 != 0) pcr(b, field, shift) // OPCR
            }
            payload = at + 5 + length
        }
        if (!unitStart || control and 1 == 0 || payload + 9 > at + 188) return
        // A PES packet's start: 00 00 01, its stream, its length, then (for these streams) its optional header.
        if (b[payload].toInt() != 0 || b[payload + 1].toInt() != 0 || b[payload + 2].toInt() != 1) return
        val stream = b[payload + 3].toInt() and 0xFF
        if (stream == 0xBC || stream == 0xBE || stream == 0xBF || stream == 0xF0 || stream == 0xF1 || stream == 0xFF || stream == 0xF2 || stream == 0xF8) return
        if (b[payload + 6].toInt() and 0xC0 != 0x80) return
        val times = (b[payload + 7].toInt() shr 6) and 3
        if (times and 2 != 0 && payload + 14 <= at + 188) timestamp(b, payload + 9, shift)
        if (times == 3 && payload + 19 <= at + 188) timestamp(b, payload + 14, shift)
    }

    /** A 33-bit time in a PES header's five bytes (with its prefix and marker bits kept), moved on. */
    private fun timestamp(b: ByteArray, at: Int, shift: Long) {
        val t = ((b[at].toLong() shr 1) and 0x07 shl 30) or
            ((b[at + 1].toLong() and 0xFF) shl 22) or ((b[at + 2].toLong() and 0xFE) shl 14) or
            ((b[at + 3].toLong() and 0xFF) shl 7) or ((b[at + 4].toLong() and 0xFE) shr 1)
        val v = Math.floorMod(t + shift, WRAP)
        b[at] = ((b[at].toInt() and 0xF1) or (((v shr 30) and 0x07).toInt() shl 1)).toByte()
        b[at + 1] = (v shr 22).toByte()
        b[at + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[at + 3] = (v shr 7).toByte()
        b[at + 4] = (((v and 0x7F).toInt() shl 1) or 1).toByte()
    }

    /** A program clock reference's 33-bit base (90 kHz) moved on; its 27 MHz extension kept. */
    private fun pcr(b: ByteArray, at: Int, shift: Long) {
        val base = ((b[at].toLong() and 0xFF) shl 25) or ((b[at + 1].toLong() and 0xFF) shl 17) or
            ((b[at + 2].toLong() and 0xFF) shl 9) or ((b[at + 3].toLong() and 0xFF) shl 1) or ((b[at + 4].toLong() and 0x80) shr 7)
        val v = Math.floorMod(base + shift, WRAP)
        b[at] = (v shr 25).toByte()
        b[at + 1] = (v shr 17).toByte()
        b[at + 2] = (v shr 9).toByte()
        b[at + 3] = (v shr 1).toByte()
        b[at + 4] = ((b[at + 4].toInt() and 0x7F) or (((v and 1).toInt()) shl 7)).toByte()
    }

    /** The PES presentation times of [b]'s whole packets, in order (for checks): what a player reads. */
    fun presentationTimes(b: ByteArray): List<Long> = buildList {
        var p = 0
        while (p + PACKET <= b.size) {
            val at = p + 4
            p += PACKET
            if (b[at].toInt() and 0xFF != 0x47 || b[at + 1].toInt() and 0x40 == 0) continue
            val control = (b[at + 3].toInt() shr 4) and 3
            if (control and 1 == 0) continue
            val payload = if (control and 2 != 0) at + 5 + (b[at + 4].toInt() and 0xFF) else at + 4
            if (payload + 14 > at + 188 || b[payload].toInt() != 0 || b[payload + 1].toInt() != 0 || b[payload + 2].toInt() != 1) continue
            if ((b[payload + 7].toInt() shr 6) and 2 == 0) continue
            val t = payload + 9
            add(
                ((b[t].toLong() shr 1) and 0x07 shl 30) or ((b[t + 1].toLong() and 0xFF) shl 22) or
                    ((b[t + 2].toLong() and 0xFE) shl 14) or ((b[t + 3].toLong() and 0xFF) shl 7) or ((b[t + 4].toLong() and 0xFE) shr 1),
            )
        }
    }
}

/**
 * A DVD's clock moved on, in place, as [TsClock] moves a Blu-ray clip's: its 2048-byte packs' system
 * clock references and their PES packets' presentation and decoding times (MPEG-2 program stream).
 */
object PsClock {

    const val PACK = 2048

    private const val WRAP = 1L shl 33

    /** Moves the clock of every whole pack in [b] (from its start, on 2048-byte boundaries) on by [shift] (90 kHz). */
    fun shift(b: ByteArray, shift: Long) {
        if (shift == 0L) return
        var p = 0
        while (p + PACK <= b.size) {
            pack(b, p, p + PACK, shift)
            p += PACK
        }
    }

    private fun startCode(b: ByteArray, at: Int) = b[at].toInt() == 0 && b[at + 1].toInt() == 0 && b[at + 2].toInt() == 1

    private fun pack(b: ByteArray, from: Int, to: Int, shift: Long) {
        if (!startCode(b, from) || b[from + 3].toInt() and 0xFF != 0xBA || b[from + 4].toInt() and 0xC0 != 0x40) return
        scr(b, from + 4, shift)
        var at = from + 14 + (b[from + 13].toInt() and 0x07)
        // Its PES packets (and a system header) one after another, each with its length.
        while (at + 6 <= to && startCode(b, at)) {
            val stream = b[at + 3].toInt() and 0xFF
            val length = ((b[at + 4].toInt() and 0xFF) shl 8) or (b[at + 5].toInt() and 0xFF)
            val timed = stream == 0xBD || stream in 0xC0..0xEF || stream == 0xFD
            if (timed && at + 9 <= to && b[at + 6].toInt() and 0xC0 == 0x80) {
                val times = (b[at + 7].toInt() shr 6) and 3
                if (times and 2 != 0 && at + 14 <= to) timestamp(b, at + 9, shift)
                if (times == 3 && at + 19 <= to) timestamp(b, at + 14, shift)
            }
            at += 6 + length
        }
    }

    /** The pack header's SCR (33-bit base in its six bytes, with its marker bits and 9-bit extension kept), moved on. */
    private fun scr(b: ByteArray, at: Int, shift: Long) {
        val v0 = ((b[at].toLong() shr 3) and 0x07 shl 30) or ((b[at].toLong() and 0x03) shl 28) or
            ((b[at + 1].toLong() and 0xFF) shl 20) or ((b[at + 2].toLong() shr 3) and 0x1F shl 15) or
            ((b[at + 2].toLong() and 0x03) shl 13) or ((b[at + 3].toLong() and 0xFF) shl 5) or ((b[at + 4].toLong() shr 3) and 0x1F)
        val v = Math.floorMod(v0 + shift, WRAP)
        b[at] = ((b[at].toInt() and 0xC4) or (((v shr 30) and 0x07).toInt() shl 3) or ((v shr 28) and 0x03).toInt()).toByte()
        b[at + 1] = (v shr 20).toByte()
        b[at + 2] = ((b[at + 2].toInt() and 0x04) or (((v shr 15) and 0x1F).toInt() shl 3) or ((v shr 13) and 0x03).toInt()).toByte()
        b[at + 3] = (v shr 5).toByte()
        b[at + 4] = ((b[at + 4].toInt() and 0x07) or ((v and 0x1F).toInt() shl 3)).toByte()
    }

    private fun timestamp(b: ByteArray, at: Int, shift: Long) {
        val t = ((b[at].toLong() shr 1) and 0x07 shl 30) or
            ((b[at + 1].toLong() and 0xFF) shl 22) or ((b[at + 2].toLong() and 0xFE) shl 14) or
            ((b[at + 3].toLong() and 0xFF) shl 7) or ((b[at + 4].toLong() and 0xFE) shr 1)
        val v = Math.floorMod(t + shift, WRAP)
        b[at] = ((b[at].toInt() and 0xF1) or (((v shr 30) and 0x07).toInt() shl 1)).toByte()
        b[at + 1] = (v shr 22).toByte()
        b[at + 2] = ((((v shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        b[at + 3] = (v shr 7).toByte()
        b[at + 4] = (((v and 0x7F).toInt() shl 1) or 1).toByte()
    }

    /** The PES presentation times in [b]'s whole packs, in order — what a player reads; [video] only stream 0xE0's. */
    fun presentationTimes(b: ByteArray, video: Boolean = false): List<Long> = buildList {
        var p = 0
        while (p + PACK <= b.size) {
            val from = p
            val to = p + PACK
            p += PACK
            if (!startCode(b, from) || b[from + 3].toInt() and 0xFF != 0xBA || b[from + 4].toInt() and 0xC0 != 0x40) continue
            var at = from + 14 + (b[from + 13].toInt() and 0x07)
            while (at + 6 <= to && startCode(b, at)) {
                val stream = b[at + 3].toInt() and 0xFF
                val length = ((b[at + 4].toInt() and 0xFF) shl 8) or (b[at + 5].toInt() and 0xFF)
                val timed = if (video) stream == 0xE0 else stream == 0xBD || stream in 0xC0..0xEF || stream == 0xFD
                if (timed && at + 14 <= to && b[at + 6].toInt() and 0xC0 == 0x80 && (b[at + 7].toInt() shr 6) and 2 != 0) {
                    val t = at + 9
                    add(
                        ((b[t].toLong() shr 1) and 0x07 shl 30) or ((b[t + 1].toLong() and 0xFF) shl 22) or
                            ((b[t + 2].toLong() and 0xFE) shl 14) or ((b[t + 3].toLong() and 0xFF) shl 7) or ((b[t + 4].toLong() and 0xFE) shr 1),
                    )
                }
                at += 6 + length
            }
        }
    }
}
