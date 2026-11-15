package com.therealityteam.videoplayer

import androidx.media3.common.C
import com.therealityteam.videoplayer.data.playback.PatientLoadErrorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the player asks again after a read failed: not about a server the loopback could not reach, while opening. */
class PatientLoadErrorPolicyTest {

    private fun delay(status: Int?, errorCount: Int = 1, patient: Boolean = false, hopeless: Boolean = false) =
        PatientLoadErrorPolicy.retryDelayMs(hopeless, status, errorCount, patient)

    @Test fun `opening, a server that cannot be reached is not asked again, other failures are, briefly`() {
        assertEquals(C.TIME_UNSET, delay(504))
        assertEquals(1000L, delay(502))
        assertEquals(1000L, delay(null))
        assertEquals(C.TIME_UNSET, delay(502, errorCount = 4))
        assertEquals(C.TIME_UNSET, delay(404))
    }

    @Test fun `playing, the network is waited out, unreachable or not`() {
        assertTrue(delay(504, patient = true) > 0)
        assertEquals(5000L, delay(502, errorCount = 6, patient = true))
        assertEquals(C.TIME_UNSET, delay(416, patient = true))
        assertEquals(C.TIME_UNSET, delay(null, patient = true, hopeless = true))
    }
}
