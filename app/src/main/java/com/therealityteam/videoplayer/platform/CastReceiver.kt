package com.therealityteam.videoplayer.platform

import com.therealityteam.videoplayer.util.PRODUCT_AGENT
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.therealityteam.videoplayer.data.cast.CastControl
import com.therealityteam.videoplayer.data.cast.CastPlayer
import com.therealityteam.videoplayer.data.cast.CastRenderer
import com.therealityteam.videoplayer.data.cast.Gena
import com.therealityteam.videoplayer.data.cast.Soap
import com.therealityteam.videoplayer.data.cast.Ssdp
import java.io.BufferedInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 接收投屏 on the network: the HTTP side a controller reads the renderer's description from and
 * sends its SOAP to, and the SSDP side it is found by (see [CastRenderer]). Runs while the setting is
 * on and the app is up; [stop] says goodbye on the network and lets go of the ports.
 *
 * Only ever reachable from the local network — the phone casting is on the same Wi-Fi — and it plays
 * only http(s) URLs it is handed; it serves nothing of the library.
 */
class CastReceiver(private val context: Context, private val player: CastPlayer) {

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val workers = Executors.newFixedThreadPool(4)
    private val control = CastControl(player)

    /** The address and port the description is served from, once started (`http://192.168.1.50:49152`). */
    @Volatile var base: String? = null
        private set

    val udn: String by lazy {
        val f = File(context.filesDir, "aura/cast-udn")
        f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.startsWith("uuid:") }
            ?: ("uuid:" + UUID.randomUUID()).also { f.parentFile?.mkdirs(); f.writeText(it) }
    }

    fun start(friendlyName: String) {
        if (running) return
        running = true
        thread(name = "aura-cast-http", isDaemon = true) { serveHttp(friendlyName) }
        thread(name = "aura-cast-ssdp", isDaemon = true) { serveSsdp() }
        thread(name = "aura-cast-events", isDaemon = true) { runCatching { watchState() } }
    }

    fun stop() {
        if (!running) return
        running = false
        val socket = ssdp
        val location = base?.let { "$it/description.xml" }
        if (socket != null && location != null) {
            runCatching { announce(socket, location, alive = false) }
        }
        runCatching { server?.close() }
        runCatching { socket?.close() }
        runCatching { lock?.release() }
        subscribers.clear()
        server = null
        ssdp = null
        base = null
        Log.i(TAG, "stopped")
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────────────────────

    private fun serveHttp(friendlyName: String) {
        val socket = PORTS.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: ServerSocket(0)
        server = socket
        val ip = localAddress()?.hostAddress
        base = ip?.let { "http://$it:${socket.localPort}" }
        Log.i(TAG, "listening on ${base ?: "port ${socket.localPort} (no Wi-Fi address)"} as $udn")
        val description = CastRenderer.deviceXml(friendlyName, udn, android.os.Build.MODEL)
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            workers.execute { runCatching { client.use { answer(it, description) } } }
        }
    }

    private fun answer(client: Socket, description: String) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/").substringBefore('?') }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val out = client.getOutputStream()
        fun reply(status: String, body: String = "", type: String = "text/xml; charset=\"utf-8\"", extra: List<String> = emptyList()) {
            val bytes = body.toByteArray()
            val text = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                if (bytes.isNotEmpty()) append("Content-Type: ").append(type).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Server: ").append(SERVER).append("\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            out.write(text.toByteArray())
            out.write(bytes)
            out.flush()
        }
        when {
            method == "GET" && (path == "/description.xml" || path == "/") -> reply("200 OK", description)
            method == "GET" && path.endsWith("/scpd.xml") ->
                CastRenderer.serviceAt(path)?.let(CastRenderer::scpdXml)?.let { reply("200 OK", it) } ?: reply("404 Not Found")
            method == "POST" && path.endsWith("/control") -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = String(readBody(input, length), Charsets.UTF_8)
                val call = Soap.parse(headers["soapaction"], body)
                if (call == null) {
                    reply("500 Internal Server Error", Soap.fault(401, "Invalid Action"))
                } else {
                    Log.i(TAG, "${call.action} from ${client.inetAddress.hostAddress}")
                    val result = control.handle(call)
                    reply(if ("<s:Fault>" in result) "500 Internal Server Error" else "200 OK", result)
                }
            }
            // Eventing (Gena): a controller subscribes to a service and is told its state — now and
            // whenever it moves. A renewal names its SID; a new subscription its CALLBACK.
            method == "SUBSCRIBE" && path.endsWith("/event") -> {
                val service = CastRenderer.serviceAt(path)
                val seconds = Gena.timeoutSeconds(headers["timeout"])
                val renewing = headers["sid"]?.let { sid -> subscribers.firstOrNull { it.sid == sid } }
                when {
                    service == null -> reply("404 Not Found")
                    renewing != null -> {
                        renewing.expiresAt = System.currentTimeMillis() + seconds * 1000L
                        reply("200 OK", extra = listOf("SID: ${renewing.sid}", "TIMEOUT: Second-$seconds"))
                    }
                    else -> {
                        val urls = Gena.callbacks(headers["callback"])
                        if (urls.isEmpty()) return reply("412 Precondition Failed")
                        val sub = Subscriber("uuid:" + UUID.randomUUID(), service, urls, System.currentTimeMillis() + seconds * 1000L)
                        subscribers += sub
                        reply("200 OK", extra = listOf("SID: ${sub.sid}", "TIMEOUT: Second-$seconds"))
                        Log.i(TAG, "subscribed to ${service.substringAfter(":service:")} by ${client.inetAddress.hostAddress}")
                        // The first event follows the answer, as the spec has it.
                        workers.execute { tell(sub, eventFor(service)) }
                    }
                }
            }
            method == "UNSUBSCRIBE" -> {
                headers["sid"]?.let { sid -> subscribers.removeAll { it.sid == sid } }
                reply("200 OK")
            }
            else -> reply("404 Not Found")
        }
    }

    // ── Eventing ────────────────────────────────────────────────────────────────────────────────

    private class Subscriber(val sid: String, val service: String, val callbacks: List<String>, @Volatile var expiresAt: Long) {
        var seq = 0L
    }

    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Subscriber>()

    private fun transportNow() = Gena.Transport(player.state, control.currentUri, player.durationMs)
    private fun renderingNow() = Gena.Rendering(player.volume, player.muted)

    /** A new subscriber's first event — the state the watcher then counts as told, so it is not sent twice. */
    private fun eventFor(service: String): String? = when (service) {
        CastRenderer.AV_TRANSPORT -> Gena.transportEvent(transportNow().also { lastTransport = it })
        CastRenderer.RENDERING_CONTROL -> Gena.renderingEvent(renderingNow().also { lastRendering = it })
        else -> null
    }

    /** Watches what subscribers are told and tells them when it moves; drops the expired. */
    @Volatile private var lastTransport: Gena.Transport? = null
    @Volatile private var lastRendering: Gena.Rendering? = null

    private fun watchState() {
        while (running) {
            Thread.sleep(WATCH_EVERY_MS)
            val now = System.currentTimeMillis()
            subscribers.removeAll { it.expiresAt < now }
            if (subscribers.isEmpty()) continue
            val t = transportNow()
            if (t != lastTransport) {
                lastTransport = t
                val body = Gena.transportEvent(t)
                subscribers.filter { it.service == CastRenderer.AV_TRANSPORT }.forEach { sub -> workers.execute { tell(sub, body) } }
            }
            val r = renderingNow()
            if (r != lastRendering) {
                lastRendering = r
                val body = Gena.renderingEvent(r)
                subscribers.filter { it.service == CastRenderer.RENDERING_CONTROL }.forEach { sub -> workers.execute { tell(sub, body) } }
            }
        }
    }

    /** One NOTIFY to a subscriber, at the first of its callbacks that takes it. */
    private fun tell(sub: Subscriber, body: String?) {
        body ?: return
        val bytes = body.toByteArray()
        val seq = synchronized(sub) { sub.seq++ }
        for (url in sub.callbacks) {
            val ok = runCatching {
                val u = java.net.URL(url)
                Socket().use { s ->
                    s.connect(InetSocketAddress(u.host, if (u.port > 0) u.port else 80), NOTIFY_TIMEOUT_MS)
                    s.soTimeout = NOTIFY_TIMEOUT_MS
                    val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
                    val head = Gena.notifyHeaders("${u.host}:${if (u.port > 0) u.port else 80}", path, sub.sid, seq, bytes.size)
                    s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                    s.getInputStream().bufferedReader().readLine()?.contains(" 200") == true
                }
            }.getOrDefault(false)
            if (ok) return
        }
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = StringBuilder()
        var last4 = 0
        while (buf.length < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString().trimEnd()
        }
        return null
    }

    private fun readBody(input: BufferedInputStream, length: Int): ByteArray {
        val n = length.coerceIn(0, MAX_BODY)
        val data = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(data, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) data else data.copyOf(got)
    }

    // ── SSDP ────────────────────────────────────────────────────────────────────────────────────

    private fun serveSsdp() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        lock = wifi?.createMulticastLock("aura-cast")?.apply { setReferenceCounted(false); acquire() }
        val socket = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORT))
                val group = InetSocketAddress(InetAddress.getByName(Ssdp.GROUP), Ssdp.PORT)
                val nif = localInterface()
                if (nif != null) joinGroup(group, nif) else joinGroup(InetAddress.getByName(Ssdp.GROUP))
                soTimeout = ANNOUNCE_EVERY_MS.toInt()
                timeToLive = 4
            }
        }.onFailure { Log.w(TAG, "no SSDP: ${it.message}") }.getOrNull() ?: return
        ssdp = socket
        // The HTTP side has to know its address before anyone is told where it lives.
        while (running && base == null) Thread.sleep(50)
        var lastAnnounce = 0L
        val buf = ByteArray(2048)
        while (running) {
            val location = base?.let { "$it/description.xml" } ?: break
            if (System.currentTimeMillis() - lastAnnounce >= ANNOUNCE_EVERY_MS) {
                runCatching { announce(socket, location, alive = true) }
                lastAnnounce = System.currentTimeMillis()
            }
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val st = Ssdp.searchTarget(String(packet.data, 0, packet.length)) ?: continue
            val answers = Ssdp.answering(st, udn)
            if (answers.isEmpty()) continue
            Log.i(TAG, "search for $st from ${packet.address.hostAddress}")
            for ((nt, usn) in answers) {
                val bytes = Ssdp.searchResponse(if (st.equals("ssdp:all", true)) nt else st, usn, location, SERVER).toByteArray()
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port)) }
            }
        }
    }

    private fun announce(socket: MulticastSocket, location: String, alive: Boolean) {
        val group = InetAddress.getByName(Ssdp.GROUP)
        repeat(2) {
            for ((nt, usn) in Ssdp.targets(udn)) {
                val bytes = Ssdp.notify(nt, usn, location, SERVER, alive).toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, group, Ssdp.PORT))
            }
        }
    }

    /** The Wi-Fi (or Ethernet) interface and its IPv4 address: where a phone on the same network reaches us. */
    private fun localInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address } }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .firstOrNull()
    }.getOrNull()

    private fun localAddress(): InetAddress? = localInterface()?.inetAddresses?.toList()?.firstOrNull { it is Inet4Address }

    companion object {
        private const val TAG = "AuraCast"
        private val SERVER = "Android/${android.os.Build.VERSION.RELEASE} UPnP/1.0 $PRODUCT_AGENT"
        private val PORTS = listOf(49152, 49153, 49154)
        private const val ANNOUNCE_EVERY_MS = 10 * 60_000L
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 256 * 1024
        private const val WATCH_EVERY_MS = 1_000L
        private const val NOTIFY_TIMEOUT_MS = 3_000
    }
}
