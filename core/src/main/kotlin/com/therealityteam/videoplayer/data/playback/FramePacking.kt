package com.therealityteam.videoplayer.data.playback

import com.therealityteam.videoplayer.data.model.StereoMode

/**
 * H.264's and H.265's frame packing arrangement SEI (payload type 45): a stereo frame's packing as the
 * encoder wrote it into the stream itself — x264's --frame-packing, 3D camcorders — where the container
 * says nothing. Read from a file's first access unit (SphericalMetadata).
 */
object FramePacking {

    /** Two pictures in one frame: side by side or one above the other, and whether the right eye's is first. */
    data class Packing(val stereo: StereoMode, val rightFirst: Boolean)

    /**
     * The packing announced in [sample] — one access unit as MP4 stores it, each NAL unit prefixed by its
     * length in [lengthSize] bytes — or null when none is (or it is one we do not show: interleaved rows,
     * columns, checkerboards, frames in turn). [hevc] for H.265's NAL headers.
     */
    fun inSample(sample: ByteArray, lengthSize: Int, hevc: Boolean): Packing? {
        if (lengthSize !in 1..4) return null
        var at = 0
        while (at + lengthSize <= sample.size) {
            var size = 0
            for (i in 0 until lengthSize) size = (size shl 8) or (sample[at + i].toInt() and 0xFF)
            at += lengthSize
            if (size <= 0 || at + size > sample.size) return null
            val header = sample[at].toInt() and 0xFF
            val sei = if (hevc) (header shr 1) and 0x3F == HEVC_PREFIX_SEI else header and 0x1F == AVC_SEI
            if (sei) {
                val skip = if (hevc) 2 else 1
                inSei(unescape(sample, at + skip, at + size))?.let { return it }
            }
            at += size
        }
        return null
    }

    /** The SEI messages of one SEI NAL unit's payload, escapes removed: the first frame packing among them. */
    internal fun inSei(rbsp: ByteArray): Packing? {
        var at = 0
        while (at < rbsp.size && rbsp[at].toInt() and 0xFF != TRAILING) {
            var type = 0
            while (at < rbsp.size && rbsp[at].toInt() and 0xFF == 0xFF) { type += 255; at++ }
            if (at >= rbsp.size) return null
            type += rbsp[at++].toInt() and 0xFF
            var size = 0
            while (at < rbsp.size && rbsp[at].toInt() and 0xFF == 0xFF) { size += 255; at++ }
            if (at >= rbsp.size) return null
            size += rbsp[at++].toInt() and 0xFF
            if (at + size > rbsp.size) return null
            if (type == FRAME_PACKING) return arrangement(rbsp.copyOfRange(at, at + size))
            at += size
        }
        return null
    }

    /**
     * frame_packing_arrangement: its id (ue), a cancel flag, then the type (7 bits: 3 side by side,
     * 4 top and bottom), quincunx sampling (1) and the content's interpretation (6: 1 the first picture
     * is the left eye's, 2 the right's).
     */
    private fun arrangement(payload: ByteArray): Packing? {
        val bits = Bits(payload)
        bits.ue() ?: return null
        if (bits.bit() != 0) return null
        val type = bits.bits(7) ?: return null
        bits.bit()
        val interpretation = bits.bits(6) ?: return null
        val stereo = when (type) {
            3 -> StereoMode.SBS
            4 -> StereoMode.OU
            else -> return null
        }
        return Packing(stereo, rightFirst = interpretation == 2)
    }

    /** A NAL unit's bytes [from, to) without its emulation-prevention bytes (00 00 03 → 00 00). */
    private fun unescape(b: ByteArray, from: Int, to: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(to - from)
        var zeros = 0
        for (i in from until to) {
            val v = b[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** MSB-first bits, with Exp-Golomb's unsigned codes. */
    private class Bits(private val b: ByteArray) {
        private var pos = 0
        fun bit(): Int? {
            if (pos >= b.size * 8) return null
            val v = (b[pos / 8].toInt() shr (7 - pos % 8)) and 1
            pos++
            return v
        }
        fun bits(n: Int): Int? {
            var v = 0
            repeat(n) { v = (v shl 1) or (bit() ?: return null) }
            return v
        }
        fun ue(): Int? {
            var zeros = 0
            while (true) {
                val b = bit() ?: return null
                if (b == 1) break
                if (++zeros > 31) return null
            }
            val rest = bits(zeros) ?: return null
            return (1 shl zeros) - 1 + rest
        }
    }

    private const val AVC_SEI = 6
    private const val HEVC_PREFIX_SEI = 39
    private const val FRAME_PACKING = 45
    private const val TRAILING = 0x80
}
