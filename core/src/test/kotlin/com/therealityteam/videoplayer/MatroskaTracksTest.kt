package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.ByteSource
import com.therealityteam.videoplayer.data.playback.MatroskaTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The sound tracks an MKV declares, and which of them Media3's reader would drop. */
class MatroskaTracksTest {

    private fun el(id: Long, body: ByteArray): ByteArray {
        val idBytes = generateSequence(id) { it ushr 8 }.takeWhile { it > 0 }.map { (it and 0xFF).toByte() }.toList().reversed().toByteArray()
        val size = body.size.toLong()
        val sizeBytes = byteArrayOf(0x08, 0, 0, 0, 0, 0, 0, 0).also { b ->
            b[0] = 0x01
            for (i in 1..7) b[i] = ((size ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        return idBytes + sizeBytes + body
    }

    private fun uint(v: Long) = byteArrayOf(v.toByte())

    private fun mkv(vararg tracks: Pair<Long, String>): ByteSource {
        val entries = ByteArrayOutputStream().apply {
            tracks.forEach { (type, codec) -> write(el(0xAE, el(0x83, uint(type)) + el(0x86, codec.toByteArray()))) }
        }.toByteArray()
        val bytes = el(0x1A45DFA3, ByteArray(0)) + el(0x18538067, el(0x1654AE6B, entries))
        return object : ByteSource {
            override val length = bytes.size.toLong()
            override fun read(offset: Long, size: Int): ByteArray =
                if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + size))
        }
    }

    @Test fun `the sound tracks are read by codec, the picture's left out`() {
        val src = mkv(1L to "V_MPEG4/ISO/AVC", 2L to "A_AAC/MPEG4/LC/SBR", 2L to "A_FLAC", 17L to "S_TEXT/ASS")
        assertEquals(listOf("A_AAC/MPEG4/LC/SBR", "A_FLAC"), MatroskaTracks.audioCodecs(src))
    }

    @Test fun `the old AAC ids are no ids Media3 reads`() {
        assertFalse(MatroskaTracks.media3Reads("A_AAC/MPEG4/LC/SBR"))
        assertFalse(MatroskaTracks.media3Reads("A_AAC/MPEG2/LC"))
        assertTrue(MatroskaTracks.media3Reads("A_AAC"))
        assertTrue(MatroskaTracks.media3Reads("A_FLAC"))
    }

    @Test fun `the tracks Media3 would drop are the sound and subtitles it has no name for`() {
        val src = mkv(1L to "V_MPEG4/ISO/AVC", 2L to "A_AAC", 17L to "S_ASS", 17L to "S_TEXT/UTF8", 2L to "A_AAC/MPEG2/LC")
        assertEquals(listOf("S_ASS", "A_AAC/MPEG2/LC"), MatroskaTracks.droppedByMedia3(src))
        assertEquals(listOf("S_ASS", "S_TEXT/UTF8"), MatroskaTracks.subtitleCodecs(src))
        assertEquals(emptyList<String>(), MatroskaTracks.droppedByMedia3(mkv(2L to "A_FLAC", 17L to "S_TEXT/ASS")))
    }

    @Test fun `only the kind a file came up without is asked about`() {
        val src = mkv(2L to "A_AAC", 2L to "A_ALAC", 17L to "S_ASS")
        assertEquals(listOf("S_ASS"), MatroskaTracks.droppedByMedia3(src, sound = false, text = true))
        assertEquals(listOf("A_ALAC"), MatroskaTracks.droppedByMedia3(src, sound = true, text = false))
    }

    @Test fun `a file whose first sound is dropped has lost its film's sound`() {
        assertTrue(MatroskaTracks.mainSoundDropped(mkv(2L to "A_AAC/MPEG4/LC/SBR", 2L to "A_VORBIS")))
        assertFalse(MatroskaTracks.mainSoundDropped(mkv(2L to "A_VORBIS", 2L to "A_ALAC")))
        assertFalse(MatroskaTracks.mainSoundDropped(mkv(1L to "V_MPEG4/ISO/AVC")))
    }
}
