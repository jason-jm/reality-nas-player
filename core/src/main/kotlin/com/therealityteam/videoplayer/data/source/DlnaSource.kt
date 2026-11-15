package com.therealityteam.videoplayer.data.source

import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * DLNA / UPnP ContentDirectory provider: SSDP discovery → device description → recursive
 * SOAP Browse. Dependency-free. DLNA `res` URLs are plain HTTP, so they play directly.
 * If [MediaSourceConfig.uri] is set it's treated as a device description URL; otherwise
 * SSDP discovery runs.
 */
class DlnaSource : MediaSourceProvider {

    override val type = SourceType.DLNA

    override suspend fun list(config: MediaSourceConfig): List<DiscoveredVideo> = listing(config).videos

    /**
     * A server's items are addressed by their media URLs, not by the containers they were found in,
     * so a container that would not browse cannot be told apart by path: any gap — a server that did
     * not answer, a container that failed or came back full at the page size — makes the whole
     * listing [SourceListing.cut], and the scan removes nothing on it.
     */
    override suspend fun listing(config: MediaSourceConfig): SourceListing =
        withContext(Dispatchers.IO) {
            val locations = if (config.uri.isNotBlank()) listOf(config.uri) else discoverSsdp()
            val out = mutableListOf<DiscoveredVideo>()
            var whole = true
            for (loc in locations.take(MAX_SERVERS)) {
                val control = runCatching { contentDirectoryControlUrl(loc) }.getOrNull()
                if (control == null) {
                    whole = false
                    continue
                }
                if (!browse(control, "0", out, 0)) whole = false
                if (out.size >= MAX_FILES) {
                    whole = false
                    break
                }
            }
            SourceListing(out, cut = !whole)
        }

    private fun discoverSsdp(): List<String> = runCatching {
        val socket = DatagramSocket()
        socket.soTimeout = SSDP_TIMEOUT_MS
        val query = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: urn:schemas-upnp-org:service:ContentDirectory:1\r\n\r\n")
        }.toByteArray()
        val group = InetAddress.getByName("239.255.255.250")
        socket.send(DatagramPacket(query, query.size, group, 1900))

        val locations = LinkedHashSet<String>()
        val buf = ByteArray(2048)
        try {
            while (locations.size < MAX_SERVERS * 2) {
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                val text = String(resp.data, 0, resp.length)
                Regex("LOCATION:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(text)
                    ?.groupValues?.get(1)?.trim()?.let { locations.add(it) }
            }
        } catch (_: SocketTimeoutException) {
            // expected end of discovery window
        } finally {
            socket.close()
        }
        locations.toList()
    }.getOrDefault(emptyList())

    private fun contentDirectoryControlUrl(locationUrl: String): String? {
        val xml = Http.get(locationUrl) ?: return null
        val base = runCatching {
            val u = URL(locationUrl)
            val port = if (u.port == -1) "" else ":${u.port}"
            "${u.protocol}://${u.host}$port"
        }.getOrNull() ?: return null

        // Find the <service> whose serviceType is ContentDirectory, take its <controlURL>.
        val service = Regex("<service>(.*?)</service>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("ContentDirectory", ignoreCase = true) } ?: return null
        val control = Regex("<controlURL>(.*?)</controlURL>", RegexOption.IGNORE_CASE)
            .find(service)?.groupValues?.get(1)?.trim() ?: return null
        return when {
            control.startsWith("http") -> control
            control.startsWith("/") -> base + control
            else -> "$base/$control"
        }
    }

    /** Whether everything under [objectId] was read: no container failed, none was cut at a limit. */
    private fun browse(controlUrl: String, objectId: String, out: MutableList<DiscoveredVideo>, depth: Int): Boolean {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return false
        // Every page of its children: a container with more than a page (a folder of 300 episodes)
        // was read to its first 200 and the rest never seen.
        val pages = DlnaPaging.pages(BROWSE_PAGE, MAX_PAGES) { start, count -> soapBrowse(controlUrl, objectId, start, count) }
            ?: return false
        var whole = pages.complete
        for (didl in pages.didl) if (!collect(controlUrl, didl, out, depth)) whole = false
        return whole
    }

    /** One page's containers (walked in turn) and video items; whether every container under it was read whole. */
    private fun collect(controlUrl: String, didl: String, out: MutableList<DiscoveredVideo>, depth: Int): Boolean {
        var whole = true
        // Recurse into containers.
        Regex("<container[^>]*\\bid=\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(didl).forEach {
            if (out.size < MAX_FILES && !browse(controlUrl, unescape(it.groupValues[1]), out, depth + 1)) whole = false
        }
        // Collect items.
        Regex("<item.*?</item>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(didl).forEach { m ->
            val item = m.value
            val res = Regex("<res[^>]*>([^<]+)</res>", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)?.trim()
            val title = Regex("<dc:title>([^<]*)</dc:title>", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)?.trim()
            if (res != null && out.size < MAX_FILES) {
                val url = unescape(res)
                val name = (title?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/'))
                    .let { if (it.substringAfterLast('.', "").isEmpty()) "$it.mp4" else it }
                val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
                val isVideo = ext in VIDEO_EXTENSIONS || item.contains("videoItem", ignoreCase = true)
                if (isVideo) out.add(DiscoveredVideo(path = url, fileName = name))
            }
        }
        return whole
    }

    private fun soapBrowse(controlUrl: String, objectId: String, start: Int, count: Int): DlnaPaging.Page? {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">")
            // An id is the server's own text: an & or a < in it would break the request as written.
            append("<ObjectID>").append(escape(objectId)).append("</ObjectID>")
            append("<BrowseFlag>BrowseDirectChildren</BrowseFlag>")
            append("<Filter>*</Filter><StartingIndex>").append(start).append("</StartingIndex>")
            append("<RequestedCount>").append(count).append("</RequestedCount><SortCriteria></SortCriteria>")
            append("</u:Browse></s:Body></s:Envelope>")
        }
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"",
            )
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            // <Result> contains escaped DIDL-Lite; unescape it.
            val result = Regex("<Result>(.*?)</Result>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(resp)?.groupValues?.get(1) ?: return null
            fun number(tag: String) = Regex("<$tag>\\s*(\\d+)\\s*</$tag>", RegexOption.IGNORE_CASE).find(resp)?.groupValues?.get(1)?.toIntOrNull()
            DlnaPaging.Page(unescape(result), returned = number("NumberReturned"), total = number("TotalMatches"))
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")

    private companion object {
        const val SSDP_TIMEOUT_MS = 3_000
        const val MAX_SERVERS = 4
        const val MAX_DEPTH = 6
        const val MAX_FILES = 2000
        /** Children asked for in one Browse; a container answering with a full page may hold more. */
        const val BROWSE_PAGE = 200
        /** Pages asked of one container at most: a server that never stops saying "more". */
        const val MAX_PAGES = 50
    }
}

/**
 * A ContentDirectory container's children, a page at a time: Browse from StartingIndex 0 by [page],
 * on until a page comes back short, the server's TotalMatches is reached, or [maxPages] (a server
 * that never says "done"). Pure apart from [fetch], so the paging is unit-tested.
 */
internal object DlnaPaging {

    /** One Browse answer: its DIDL-Lite, and the NumberReturned / TotalMatches it gave, if it did. */
    data class Page(val didl: String, val returned: Int?, val total: Int?)

    /** The pages read, and whether they are all there are. */
    data class Pages(val didl: List<String>, val complete: Boolean)

    private val CHILD = Regex("<(container|item)[\\s>]", RegexOption.IGNORE_CASE)

    /** Null when a page failed: what was under the container is not known. */
    fun pages(page: Int, maxPages: Int, fetch: (start: Int, count: Int) -> Page?): Pages? {
        val out = mutableListOf<String>()
        var start = 0
        repeat(maxPages) {
            val p = fetch(start, page) ?: return null
            out += p.didl
            // A server that leaves NumberReturned out is counted by what it sent.
            val n = p.returned ?: CHILD.findAll(p.didl).count()
            start += n
            val total = p.total?.takeIf { it > 0 }
            if (n < page || n == 0 || (total != null && start >= total)) return Pages(out, complete = true)
        }
        return Pages(out, complete = false)
    }
}
