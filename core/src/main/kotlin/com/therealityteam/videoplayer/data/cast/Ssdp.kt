package com.therealityteam.videoplayer.data.cast

/**
 * How a phone finds the renderer: SSDP on 239.255.255.250:1900. A controller multicasts `M-SEARCH`
 * for what it wants (a MediaRenderer, an AVTransport, everything) and the renderer answers it
 * directly with where its description lives; the renderer also announces itself (`NOTIFY
 * ssdp:alive`) now and then, and takes its leave (`ssdp:byebye`) when it stops.
 */
object Ssdp {

    const val GROUP = "239.255.255.250"
    const val PORT = 1900

    /** How long an announcement holds, seconds; re-announced well inside it. */
    const val MAX_AGE = 1800

    /** The search target of an `M-SEARCH` request, or null when [message] is not one. */
    fun searchTarget(message: String): String? {
        val lines = message.split("\r\n", "\n")
        if (!lines.firstOrNull().orEmpty().startsWith("M-SEARCH", ignoreCase = true)) return null
        if (header(lines, "MAN")?.contains("ssdp:discover", ignoreCase = true) != true) return null
        return header(lines, "ST")
    }

    /** The (NT, USN) pairs a renderer with [udn] stands for — the three it announces and answers as, per service. */
    fun targets(udn: String): List<Pair<String, String>> = buildList {
        add("upnp:rootdevice" to "$udn::upnp:rootdevice")
        add(udn to udn)
        add(CastRenderer.DEVICE_TYPE to "$udn::${CastRenderer.DEVICE_TYPE}")
        CastRenderer.SERVICES.forEach { add(it to "$udn::$it") }
    }

    /** The pairs that answer a search for [st]: all of them for `ssdp:all`, else the one it names. */
    fun answering(st: String, udn: String): List<Pair<String, String>> {
        val all = targets(udn)
        if (st.equals("ssdp:all", ignoreCase = true)) return all
        // A version-1 renderer answers a search for any version of its type ("…:MediaRenderer:2" no).
        return all.filter { it.first.equals(st, ignoreCase = true) }
    }

    /** The unicast answer to a search, for one target. */
    fun searchResponse(st: String, usn: String, location: String, server: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=").append(MAX_AGE).append("\r\n")
        append("EXT:\r\n")
        append("LOCATION: ").append(location).append("\r\n")
        append("SERVER: ").append(server).append("\r\n")
        append("ST: ").append(st).append("\r\n")
        append("USN: ").append(usn).append("\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    /** An announcement — `ssdp:alive` with where it lives, or `ssdp:byebye`. */
    fun notify(nt: String, usn: String, location: String, server: String, alive: Boolean): String = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: ").append(GROUP).append(':').append(PORT).append("\r\n")
        if (alive) {
            append("CACHE-CONTROL: max-age=").append(MAX_AGE).append("\r\n")
            append("LOCATION: ").append(location).append("\r\n")
            append("SERVER: ").append(server).append("\r\n")
        }
        append("NT: ").append(nt).append("\r\n")
        append("NTS: ").append(if (alive) "ssdp:alive" else "ssdp:byebye").append("\r\n")
        append("USN: ").append(usn).append("\r\n")
        append("Content-Length: 0\r\n\r\n")
    }

    private fun header(lines: List<String>, name: String): String? = lines.firstNotNullOfOrNull { line ->
        val i = line.indexOf(':')
        if (i > 0 && line.substring(0, i).trim().equals(name, ignoreCase = true)) line.substring(i + 1).trim() else null
    }
}
