package com.therealityteam.videoplayer.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** An SMB server found on the local network. [name] is null when nothing would name it. */
data class DiscoveredHost(val address: String, val name: String?) {
    /** What the user sees: the NAS's own name, falling back to its address. */
    val label: String get() = name ?: address
}

/**
 * Finds SMB servers on the local network so nobody has to type an address.
 *
 * Discovery is a TCP sweep of port 445. That is the one method that finds every server rather
 * than only the ones that advertise themselves: a NAS may or may not answer mDNS or NetBIOS,
 * but if it serves SMB at all it is listening on 445. mDNS and NetBIOS are therefore used only
 * for what the sweep cannot produce — a human-readable name.
 *
 * WHICH networks get swept matters as much as the sweep itself. The headset's own /24 is the
 * common home case, but it is not the only one: a NAS reached over a router or a tunnel sits on
 * a different /24, and no broadcast-based protocol can see it at all. So the sweep also covers
 * the network of every SMB source already configured — which is what makes finding the SECOND
 * through Nth share on a known NAS completely automatic — plus any network the user points it
 * at explicitly. Those hints are ordinary addresses; each one contributes its /24.
 *
 * The sweep is 254 connects per network with a short timeout, run [CONCURRENCY] at a time, so a
 * network resolves in a couple of seconds. Results are streamed as they land rather than
 * collected, because the first NAS usually answers long before the last dead address times out.
 */
class SmbDiscovery {

    /**
     * Emits each host as it answers, and completes when every planned network has been probed.
     *
     * [hints] are addresses (a NAS, a configured source's host) whose /24 should be swept in
     * addition to the headset's own networks.
     */
    fun discover(hints: List<String> = emptyList()): Flow<DiscoveredHost> = channelFlow {
        // Returning from the channelFlow block is what closes the channel and completes the
        // flow. (An awaitClose here instead would suspend until the COLLECTOR cancelled, so the
        // scan would never report itself finished and the UI would spin forever.)
        plannedTargets(hints).map { address ->
            launch(sweepDispatcher) {
                if (probeSmbPort(address)) {
                    send(DiscoveredHost(address, nameOf(address)))
                }
            }
        }.joinAll()
    }

    /**
     * Bounds the sweep's width. Dispatchers.IO alone would cap it at its own 64 threads, which
     * is a limit set for disk work rather than for several hundred sockets that are almost
     * always idle.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sweepDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)

    /**
     * Every address [discover] will probe, given [hints] — hinted networks first.
     *
     * The order is not cosmetic. A hinted network is one the user named or already has a source
     * on, so it is where the NAS most likely is, and probing it first both shows results sooner
     * and keeps the search off the far end of a long queue of dead addresses.
     */
    fun plannedTargets(hints: List<String>): List<String> {
        val hinted = hints.flatMap { sweepTargets(it, DEFAULT_HINT_PREFIX, excludeSelf = false) + it }
        val own = localIpv4().flatMap { (address, prefix) -> sweepTargets(address, prefix) }
        return (hinted + own).distinct()
    }

    /** The networks [discover] will sweep, as "192.168.1.*" — for telling the user what is happening. */
    fun plannedNetworks(hints: List<String>): List<String> =
        plannedTargets(hints).mapNotNull { it.substringBeforeLast('.', "").takeIf(String::isNotBlank) }
            .distinct()
            .map { "$it.*" }

    /**
     * True when this address actually serves SMB — which is not the same as having port 445
     * open, and the difference is not academic. A transparent proxy or VPN TUN interface (the
     * kind a desktop proxy tool installs, and the kind an emulator sits behind) completes the
     * TCP handshake for EVERY address it is asked about, before it has any idea whether the
     * destination exists. Probing by connect alone reported all 254 addresses of a subnet as
     * SMB servers, including ones with nothing on them.
     *
     * So the probe makes the host prove it: send an SMB2 NEGOTIATE and require the reply to
     * start with the SMB protocol marker. A real server answers even when it goes on to refuse
     * the dialect; a proxied dead address sends nothing and times out.
     */
    private fun probeSmbPort(address: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, SMB_PORT), PROBE_TIMEOUT_MS)
            socket.soTimeout = NEGOTIATE_TIMEOUT_MS
            socket.getOutputStream().apply { write(negotiateRequest()); flush() }
            val head = ByteArray(8)
            var read = 0
            while (read < head.size) {
                val n = socket.getInputStream().read(head, read, head.size - read)
                if (n < 0) return@runCatching false
                read += n
            }
            speaksSmb(head)
        }
    }.getOrDefault(false)

    /**
     * A friendly name for a host: its NetBIOS workstation name if it answers on UDP 137,
     * otherwise a reverse-DNS name if the router publishes one. Null when only the address
     * is known — better to show the address than to invent a name for it.
     */
    private suspend fun nameOf(address: String): String? = withContext(Dispatchers.IO) {
        netbiosName(address) ?: reverseDns(address)
    }

    private fun reverseDns(address: String): String? = runCatching {
        val host = InetAddress.getByName(address).canonicalHostName
        // getCanonicalHostName echoes the address back when there is no PTR record.
        host.takeIf { it != address }?.substringBefore('.')?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Ask a host to name itself (NBSTAT over UDP 137). Windows and Samba both answer. */
    private fun netbiosName(address: String): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = NETBIOS_TIMEOUT_MS
            val query = netbiosQuery()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(address), NETBIOS_PORT))
            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            parseNetbiosName(response.data, response.length)
        }
    }.getOrNull()

    /** The headset's own IPv4 addresses, each with the prefix length of its network. */
    private fun localIpv4(): List<Pair<String, Int>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic -> nic.interfaceAddresses }
            .mapNotNull { ia ->
                val addr = ia.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    addr.hostAddress?.let { it to ia.networkPrefixLength.toInt() }
                } else {
                    null
                }
            }
    }.getOrDefault(emptyList())

    companion object {
        private const val SMB_PORT = 445
        private const val NETBIOS_PORT = 137
        /**
         * Reaching a real server takes far longer than being told a fake one exists. Measured
         * against a NAS two hops away: the genuine connect took 1026ms, while a nonexistent
         * neighbour on the same subnet "connected" in 12ms because a proxy answered for it. So
         * the connect budget has to cover a real round trip — at 800ms the actual NAS was the
         * one address the scan missed — while the negotiate budget only has to outlast a server
         * that is thinking (the same NAS replied 12ms after connecting).
         */
        private const val PROBE_TIMEOUT_MS = 2500
        private const val NEGOTIATE_TIMEOUT_MS = 1200
        private const val NETBIOS_TIMEOUT_MS = 400

        /** A hint is one address; the network worth sweeping around it is its /24. */
        private const val DEFAULT_HINT_PREFIX = 24

        /**
         * How many probes are in flight at once. Each one spends nearly all its time waiting on
         * a socket rather than working, so this is set by how long the scan should take, not by
         * the core count: a two-network sweep is ~500 addresses, and the slowest of them costs
         * [PROBE_TIMEOUT_MS] + [NEGOTIATE_TIMEOUT_MS]. At 96 the headset's two-network sweep (its own
         * /24 and the NAS's, a router away) took 14 s, six waves of dead addresses' timeouts; at this
         * width it is two.
         */
        private const val CONCURRENCY = 256

        /**
         * The addresses to probe for a host sitting at [localIp] on a /[prefixLength] network,
         * excluding the network address, the broadcast address, and the headset itself.
         *
         * Anything wider than a /24 is narrowed to the /24 the headset is on: a /16 would be
         * 65,000 probes, and a home NAS is on the same /24 as the headset in every setup this
         * targets.
         */
        fun sweepTargets(
            address: String,
            prefixLength: Int,
            excludeSelf: Boolean = true,
        ): List<String> {
            val octets = address.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
            if (prefixLength !in 0..32) return emptyList()
            // A /31 or /32 has no usable host range to sweep.
            if (prefixLength >= 31) return emptyList()

            val hostBits = (32 - prefixLength).coerceAtMost(8)
            val blockSize = 1 shl hostBits
            val base = octets[3] and (256 - blockSize)
            val network = "${octets[0]}.${octets[1]}.${octets[2]}"
            return ((base + 1)..(base + blockSize - 2))
                .map { "$network.$it" }
                .filter { !excludeSelf || it != address }
        }

        /**
         * A minimal SMB2 NEGOTIATE, the smallest thing that makes a host identify itself as an
         * SMB server. Offering only the two SMB 2.x dialects is deliberate: the reply is proof
         * of protocol regardless of whether the server accepts them, and a server that speaks
         * only SMB1 still answers with the SMB1 marker, which [speaksSmb] also accepts.
         *
         * Layout: a 4-byte direct-TCP header carrying the length, a 64-byte SMB2 header, then
         * the 40-byte negotiate body. All multi-byte fields are little-endian.
         */
        fun negotiateRequest(): ByteArray {
            val payload = ByteArray(SMB2_HEADER_SIZE + NEGOTIATE_BODY_SIZE)

            // --- SMB2 header ---
            SMB2_MAGIC.copyInto(payload, 0)
            payload.putShortLe(4, SMB2_HEADER_SIZE)   // StructureSize
            payload.putShortLe(12, 0)                 // Command = NEGOTIATE
            payload.putShortLe(14, 1)                 // CreditRequest
            // Status, flags, ids and signature all stay zero for an unauthenticated negotiate.

            // --- NEGOTIATE body ---
            val body = SMB2_HEADER_SIZE
            payload.putShortLe(body, 36)              // StructureSize (fixed by the spec)
            payload.putShortLe(body + 2, 2)           // DialectCount
            payload.putShortLe(body + 4, 1)           // SecurityMode = signing enabled
            payload.putShortLe(body + 36, 0x0202)     // SMB 2.0.2
            payload.putShortLe(body + 38, 0x0210)     // SMB 2.1

            // --- direct-TCP framing ---
            val packet = ByteArray(4 + payload.size)
            packet[1] = ((payload.size shr 16) and 0xFF).toByte()
            packet[2] = ((payload.size shr 8) and 0xFF).toByte()
            packet[3] = (payload.size and 0xFF).toByte()
            payload.copyInto(packet, 4)
            return packet
        }

        /**
         * Whether the first bytes off the wire are an SMB reply: the 4-byte framing header
         * followed by the protocol marker, `\xFESMB` for SMB2/3 or `\xFFSMB` for SMB1.
         */
        fun speaksSmb(head: ByteArray): Boolean {
            if (head.size < 8) return false
            val isSmb2 = head[4] == SMB2_MAGIC[0]
            val isSmb1 = head[4] == 0xFF.toByte()
            return (isSmb2 || isSmb1) &&
                head[5] == 'S'.code.toByte() &&
                head[6] == 'M'.code.toByte() &&
                head[7] == 'B'.code.toByte()
        }

        private fun ByteArray.putShortLe(index: Int, value: Int) {
            this[index] = (value and 0xFF).toByte()
            this[index + 1] = ((value shr 8) and 0xFF).toByte()
        }

        private val SMB2_MAGIC =
            byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())

        private const val SMB2_HEADER_SIZE = 64
        private const val NEGOTIATE_BODY_SIZE = 40

        /**
         * An NBSTAT query for the wildcard name "*", which asks a host for its own name table.
         *
         * NetBIOS first-level encoding turns each byte into two printable characters (high
         * nibble + 'A', low nibble + 'A'), so the 16-byte name becomes 32 bytes on the wire.
         */
        fun netbiosQuery(): ByteArray {
            val out = ArrayList<Byte>(50)
            out.addAll(listOf(0x00, 0x00).map(Int::toByte))       // transaction id
            out.addAll(listOf(0x00, 0x00).map(Int::toByte))       // flags: standard query
            out.addAll(listOf(0x00, 0x01).map(Int::toByte))       // 1 question
            out.addAll(List(6) { 0x00.toByte() })                 // no answer/authority/additional
            out.add(0x20)                                         // encoded name length (32)
            val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
            for (b in name) {
                val v = b.toInt() and 0xFF
                out.add((('A'.code) + (v shr 4)).toByte())
                out.add((('A'.code) + (v and 0x0F)).toByte())
            }
            out.add(0x00)                                         // end of name
            out.addAll(listOf(0x00, 0x21).map(Int::toByte))       // type NBSTAT
            out.addAll(listOf(0x00, 0x01).map(Int::toByte))       // class IN
            return out.toByteArray()
        }

        /**
         * Pull the machine name out of an NBSTAT reply.
         *
         * The name table starts after the header, the echoed 34-byte encoded name, and the
         * type/class/ttl/rdlength fields. Each entry is a 15-byte name, a one-byte suffix
         * saying what the name is for, and two flag bytes. The machine's own name is the
         * unique (not group) entry with suffix 0x00.
         */
        fun parseNetbiosName(response: ByteArray, length: Int): String? {
            val countIndex = NETBIOS_NAME_TABLE_OFFSET
            if (length <= countIndex) return null
            val count = response[countIndex].toInt() and 0xFF
            for (i in 0 until count) {
                val start = countIndex + 1 + i * NETBIOS_ENTRY_SIZE
                if (start + NETBIOS_ENTRY_SIZE > length) return null
                val suffix = response[start + 15].toInt() and 0xFF
                val flags = ((response[start + 16].toInt() and 0xFF) shl 8) or
                    (response[start + 17].toInt() and 0xFF)
                val isGroup = (flags and 0x8000) != 0
                if (suffix == 0x00 && !isGroup) {
                    val name = String(response, start, 15, Charsets.US_ASCII).trim()
                    if (name.isNotEmpty()) return name
                }
            }
            return null
        }

        /** 12-byte header + 34-byte encoded name + type(2) + class(2) + ttl(4) + rdlength(2). */
        private const val NETBIOS_NAME_TABLE_OFFSET = 56

        /** 15-byte name + 1-byte suffix + 2 flag bytes. */
        private const val NETBIOS_ENTRY_SIZE = 18
    }
}
