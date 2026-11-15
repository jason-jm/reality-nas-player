package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.source.SmbDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pieces of LAN discovery that can be judged without a LAN: which addresses the sweep
 * will probe, and what a host's NBSTAT reply says its name is. Everything else in
 * [SmbDiscovery] is sockets, and only a real network can answer for those.
 */
class SmbDiscoveryTest {

    // ---- which addresses get probed -------------------------------------------------------

    @Test
    fun aSlashTwentyFourSweepsEveryHostButNotItself() {
        val targets = SmbDiscovery.sweepTargets("192.168.1.50", 24)
        assertEquals(253, targets.size) // 254 usable, minus the headset itself
        assertTrue(targets.contains("192.168.1.1"))
        assertTrue(targets.contains("192.168.1.254"))
        assertFalse("the headset must not probe itself", targets.contains("192.168.1.50"))
        assertFalse("network address is not a host", targets.contains("192.168.1.0"))
        assertFalse("broadcast address is not a host", targets.contains("192.168.1.255"))
    }

    @Test
    fun aWiderNetworkIsNarrowedToTheSlashTwentyFourTheHeadsetIsOn() {
        // A /16 is 65,000 probes. A home NAS is on the headset's own /24, so that is the range.
        val targets = SmbDiscovery.sweepTargets("10.0.5.7", 16)
        assertEquals(253, targets.size)
        assertTrue(targets.contains("10.0.5.1"))
        assertTrue(targets.contains("10.0.5.254"))
        assertFalse("must not leave the headset's own /24", targets.contains("10.0.6.1"))
    }

    @Test
    fun aNarrowerNetworkSweepsOnlyItsOwnBlock() {
        // .200 on a /25 lives in the upper half: 128..255, so hosts are 129..254.
        val targets = SmbDiscovery.sweepTargets("192.168.1.200", 25)
        assertEquals(125, targets.size) // 129..254 is 126 addresses, minus the headset
        assertTrue(targets.contains("192.168.1.129"))
        assertTrue(targets.contains("192.168.1.254"))
        assertFalse("the lower half is a different network", targets.contains("192.168.1.100"))
        assertFalse(targets.contains("192.168.1.128"))
    }

    @Test
    fun aHintedNetworkKeepsTheHintedAddressItself() {
        // Self-exclusion exists so the headset does not probe itself. Reused for a HINT it did
        // the opposite of what was wanted: pointing the scan at a NAS dropped that NAS from the
        // sweep, so the one address the user actually named was the only one never probed.
        val targets = SmbDiscovery.sweepTargets("192.168.1.126", 24, excludeSelf = false)
        assertTrue("the hinted address is the likeliest server of all", targets.contains("192.168.1.126"))
        assertEquals(254, targets.size)
    }

    @Test
    fun aPointToPointOrSingleHostNetworkHasNothingToSweep() {
        assertTrue(SmbDiscovery.sweepTargets("192.168.1.50", 32).isEmpty())
        assertTrue(SmbDiscovery.sweepTargets("192.168.1.50", 31).isEmpty())
    }

    @Test
    fun rubbishAddressesSweepNothingRatherThanThrowing() {
        assertTrue(SmbDiscovery.sweepTargets("not-an-address", 24).isEmpty())
        assertTrue(SmbDiscovery.sweepTargets("192.168.1", 24).isEmpty())
        assertTrue(SmbDiscovery.sweepTargets("192.168.1.999", 24).isEmpty())
        assertTrue(SmbDiscovery.sweepTargets("192.168.1.50", 33).isEmpty())
    }

    // ---- proving a host really speaks SMB -------------------------------------------------

    @Test
    fun theProbeSendsAWellFormedSmb2Negotiate() {
        val p = SmbDiscovery.negotiateRequest()
        assertEquals("framing(4) + header(64) + body(40)", 108, p.size)
        // Direct-TCP framing: a zero byte then a 3-byte big-endian length of what follows.
        assertEquals(0, p[0].toInt())
        assertEquals(104, ((p[1].toInt() and 0xFF) shl 16) or ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF))
        assertEquals(0xFE.toByte(), p[4])
        assertEquals("SMB", String(p, 5, 3, Charsets.US_ASCII))
        assertEquals("SMB2 header is 64 bytes, little-endian", 64, p[8].toInt())
        assertEquals("command NEGOTIATE", 0, p[16].toInt())
        assertEquals("negotiate body StructureSize is 36", 36, p[68].toInt())
        assertEquals("two dialects offered", 2, p[70].toInt())
        // 0x0202 and 0x0210, little-endian.
        assertEquals(0x02, p[104].toInt()); assertEquals(0x02, p[105].toInt())
        assertEquals(0x10, p[106].toInt()); assertEquals(0x02, p[107].toInt())
    }

    @Test
    fun onlyAnSmbReplyCountsAsAnSmbServer() {
        // The reason this check exists: a proxy/VPN TUN completes the TCP handshake for every
        // address it is asked about, so "port 445 accepted the connection" listed all 254
        // addresses of a subnet as servers. Verified against a real NAS at 192.168.1.126, which
        // answers, versus its dead neighbours, which connect and then say nothing.
        assertTrue(SmbDiscovery.speaksSmb(byteArrayOf(0, 0, 0, 100, 0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())))
        assertTrue("an SMB1-only server still identifies itself",
            SmbDiscovery.speaksSmb(byteArrayOf(0, 0, 0, 100, 0xFF.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())))
        assertFalse("an HTTP server on 445 is not an SMB server",
            SmbDiscovery.speaksSmb("HTTP/1.1 ".toByteArray(Charsets.US_ASCII)))
        assertFalse("a silent socket proves nothing", SmbDiscovery.speaksSmb(ByteArray(8)))
        assertFalse("a short read proves nothing", SmbDiscovery.speaksSmb(byteArrayOf(0, 0, 0, 100, 0xFE.toByte())))
    }

    // ---- asking a host to name itself -----------------------------------------------------

    @Test
    fun theQueryAsksForTheWildcardNameTable() {
        val q = SmbDiscovery.netbiosQuery()
        assertEquals("header(12) + len(1) + encoded name(32) + null(1) + type(2) + class(2)", 50, q.size)
        assertEquals("one question", 1, q[5].toInt())
        assertEquals("encoded name is 32 bytes", 0x20, q[12].toInt())
        // First-level encoding splits each byte into two nibbles offset by 'A'. "*" is 0x2A,
        // so it encodes as 'C','K'; the 15 padding NULs each encode as 'A','A'.
        assertEquals('C'.code.toByte(), q[13])
        assertEquals('K'.code.toByte(), q[14])
        assertEquals('A'.code.toByte(), q[15])
        assertEquals('A'.code.toByte(), q[16])
        assertEquals("type NBSTAT", 0x21, q[47].toInt())
        assertEquals("class IN", 0x01, q[49].toInt())
    }

    /** Build a reply carrying [names], each as (name, suffix, isGroup). */
    private fun reply(vararg names: Triple<String, Int, Boolean>): ByteArray {
        val buffer = ByteArray(57 + names.size * 18)
        buffer[56] = names.size.toByte()
        names.forEachIndexed { i, (name, suffix, isGroup) ->
            val start = 57 + i * 18
            val padded = name.padEnd(15).take(15)
            padded.forEachIndexed { j, c -> buffer[start + j] = c.code.toByte() }
            buffer[start + 15] = suffix.toByte()
            buffer[start + 16] = if (isGroup) 0x80.toByte() else 0x00
            buffer[start + 17] = 0x00
        }
        return buffer
    }

    @Test
    fun theMachineNameIsTheUniqueEntryWithSuffixZero() {
        val r = reply(Triple("SYNOLOGY-NAS", 0x00, false))
        assertEquals("SYNOLOGY-NAS", SmbDiscovery.parseNetbiosName(r, r.size))
    }

    @Test
    fun aWorkgroupIsNotTheMachineName() {
        // The workgroup shares suffix 0x00 and is listed first; only the group flag separates
        // them, and reading the first 0x00 entry would name every NAS "WORKGROUP".
        val r = reply(
            Triple("WORKGROUP", 0x00, true),
            Triple("MY-NAS", 0x00, false),
        )
        assertEquals("MY-NAS", SmbDiscovery.parseNetbiosName(r, r.size))
    }

    @Test
    fun aServiceEntryIsNotTheMachineName() {
        // Suffix 0x20 is the file-server service, 0x03 the messenger — neither is the name.
        val r = reply(
            Triple("NAS-BOX", 0x20, false),
            Triple("NAS-BOX", 0x03, false),
            Triple("NAS-BOX", 0x00, false),
        )
        assertEquals("NAS-BOX", SmbDiscovery.parseNetbiosName(r, r.size))
    }

    @Test
    fun aTruncatedOrSilentReplyNamesNothing() {
        val r = reply(Triple("MY-NAS", 0x00, false))
        assertNull("a reply cut short must not read past its end", SmbDiscovery.parseNetbiosName(r, 60))
        assertNull(SmbDiscovery.parseNetbiosName(ByteArray(0), 0))
        assertNull("a host with no 0x00 entry has no name to give", run {
            val only = reply(Triple("NAS", 0x20, false))
            SmbDiscovery.parseNetbiosName(only, only.size)
        })
    }
}
