package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.CodecProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecProfileTest {
    @Test fun `profiles by the names people know`() {
        assertEquals("High 10", CodecProfile.name("avc1.6E0032"))
        assertEquals("10-bit", CodecProfile.depth("avc1.6E0032"))
        assertEquals("High", CodecProfile.name("avc1.640028"))
        assertNull(CodecProfile.depth("avc1.640028"))
        assertEquals("Main 10", CodecProfile.name("hvc1.2.4.L150.B0"))
        assertEquals("10-bit", CodecProfile.depth("hev1.2.4.L153.90"))
        assertEquals("Main", CodecProfile.name("hvc1.1.6.L120.90"))
        assertNull(CodecProfile.name("vp09.00.10.08"))
        assertNull(CodecProfile.name(null))
    }
}
