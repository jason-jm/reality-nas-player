package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.playback.PlaybackFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A share that cannot be reached is not a broken file, nor a format the headset lacks. */
class PlaybackFailureTest {
    @Test fun `media3 error codes map to what the viewer can do about them`() {
        assertEquals(PlaybackFailure.UNREACHABLE, PlaybackFailure.of(2001, onNetwork = true)) // connection failed
        assertEquals(PlaybackFailure.UNREACHABLE, PlaybackFailure.of(2004, onNetwork = true)) // the proxy's bad status
        assertEquals(PlaybackFailure.MISSING, PlaybackFailure.of(2005, onNetwork = false))    // file not found
        assertEquals(PlaybackFailure.DAMAGED, PlaybackFailure.of(2008, onNetwork = false))    // read past its end: cut short
        assertEquals(PlaybackFailure.DAMAGED, PlaybackFailure.of(2008, onNetwork = true))
        assertEquals(PlaybackFailure.DAMAGED, PlaybackFailure.of(3001, onNetwork = true))     // container malformed
        assertEquals(PlaybackFailure.UNSUPPORTED, PlaybackFailure.of(4005, onNetwork = false)) // format unsupported
        assertEquals(PlaybackFailure.OTHER, PlaybackFailure.of(1000, onNetwork = false))
    }

    @Test fun `a format no reader reads is unsupported, not damaged, nor a share out of reach`() {
        assertEquals(PlaybackFailure.UNSUPPORTED, PlaybackFailure.of(3003, onNetwork = true)) // RMVB, WMV: no extractor
        assertEquals(PlaybackFailure.UNSUPPORTED, PlaybackFailure.of(2000, onNetwork = true, readerCrashed = true)) // FLV reader threw
        assertEquals(PlaybackFailure.UNREACHABLE, PlaybackFailure.of(2000, onNetwork = true))
        assertEquals("RMVB", PlaybackFailure.legacyFormat("影视帝国(bbs.cnxp.com).华氏911.CD1.rmvb"))
        assertEquals("WMV", PlaybackFailure.legacyFormat("Casino Lights 99 A.wmv"))
        assertEquals(null, PlaybackFailure.legacyFormat("The.Mandalorian.S01E02.mkv"))
        assertEquals("XviD/DivX", PlaybackFailure.legacyCodec("video/mp4v-es"))
        assertEquals(null, PlaybackFailure.legacyCodec("video/avc"))
    }

    @Test fun `only what might come back is offered a retry`() {
        assertTrue(PlaybackFailure.UNREACHABLE.retryable)
        assertTrue(PlaybackFailure.OTHER.retryable)
        assertFalse(PlaybackFailure.DAMAGED.retryable)
        assertFalse(PlaybackFailure.UNSUPPORTED.retryable)
        assertFalse(PlaybackFailure.MISSING.retryable)
        assertFalse(PlaybackFailure.TOO_LARGE.retryable)
    }
}
