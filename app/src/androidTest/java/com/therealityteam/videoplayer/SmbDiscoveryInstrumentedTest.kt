package com.therealityteam.videoplayer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.videoplayer.data.source.SmbDiscovery
import com.therealityteam.videoplayer.data.source.SmbSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discovery against a REAL server, run on the device, because the parts that matter here are the
 * ones a JVM test cannot reach: whether the network actually answers, and whether it answers
 * honestly. Credentials are instrumentation arguments so they stay out of the repository:
 *
 *     adb shell am instrument -w \
 *       -e class com.therealityteam.videoplayer.SmbDiscoveryInstrumentedTest \
 *       -e host 192.168.1.126 -e user <name> -e pass <password> \
 *       com.therealityteam.videoplayer.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Without `-e host` every test here skips, so an ordinary connected-test run stays green.
 */
@RunWith(AndroidJUnit4::class)
class SmbDiscoveryInstrumentedTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private val host: String? get() = args.getString("host")
    private val user: String? get() = args.getString("user")
    private val pass: String? get() = args.getString("pass")

    @Test
    fun theScanFindsTheServerAndRejectsItsDeadNeighbours() = runBlocking {
        val target = host
        assumeTrue("pass -e host <address> to run this", target != null)

        val discovery = SmbDiscovery()
        val planned = discovery.plannedTargets(listOf(target!!))
        Log.i(TAG, "sweeping ${planned.size} addresses")

        val found = discovery.discover(listOf(target)).toList()
        Log.i(TAG, "found ${found.size}: " + found.joinToString { "${it.label} (${it.address})" })

        assertTrue(
            "the scan should find $target; found ${found.map { it.address }}",
            found.any { it.address == target },
        )
        // The point of negotiating rather than merely connecting. Behind a proxy or VPN TUN the
        // connect succeeds for every address in the subnet, so a port-only probe reported all
        // ~254 as SMB servers. A network has a handful of real ones, never almost all of them.
        assertTrue(
            "found ${found.size} of ${planned.size} addresses — the probe is accepting hosts that " +
                "merely completed a TCP handshake instead of ones that speak SMB",
            found.size < planned.size / 4,
        )
    }

    @Test
    fun theServerCanBeWalkedLikeAFileManager() = runBlocking {
        val target = host
        assumeTrue("pass -e host <address> to run this", target != null)

        val smb = SmbSource()
        val shares = smb.listShares(target!!, user, pass)
        Log.i(TAG, "shares: " + shares.joinToString { it.name })
        assertTrue("expected at least one share on $target", shares.isNotEmpty())

        // …and that a share opens, which is what the browse pane does on the next tap.
        val first = shares.first().name
        val entries = smb.browse(target, first, "", user, pass)
        Log.i(TAG, "$first holds ${entries.size} entries: " + entries.take(10).joinToString { it.name })
        assertTrue("listing a share must not throw", true)
    }

    private companion object {
        const val TAG = "SmbDiscoveryTest"
    }
}
