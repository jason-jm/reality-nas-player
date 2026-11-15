package com.therealityteam.videoplayer.data.cast

/** The SOAP a UPnP controller speaks: an action and its arguments in, a response or a fault out. */
object Soap {

    /** One call: which service, which action, its arguments (unescaped). */
    data class Call(val service: String, val action: String, val args: Map<String, String>)

    /**
     * The call in [body], named by its `SOAPACTION` header (`"urn:…:AVTransport:1#Play"`, quotes or
     * not) — or, when a controller leaves the header out, by the element inside `<s:Body>`.
     */
    fun parse(soapAction: String?, body: String): Call? {
        val header = soapAction?.trim()?.trim('"')?.takeIf { '#' in it }
        val (service, action) = if (header != null) {
            header.substringBefore('#') to header.substringAfter('#')
        } else {
            val m = BODY_ACTION.find(body) ?: return null
            m.groupValues[2] to m.groupValues[1]
        }
        if (action.isBlank()) return null
        val inner = Regex("""<(?:\w+:)?${Regex.escape(action)}\b[^>]*>(.*)</(?:\w+:)?${Regex.escape(action)}>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1).orEmpty()
        val args = LinkedHashMap<String, String>()
        ARG.findAll(inner).forEach { args[it.groupValues[1]] = unescape(it.groupValues[2]) }
        // An argument sent empty as <Name/>.
        EMPTY_ARG.findAll(inner).forEach { args.putIfAbsent(it.groupValues[1], "") }
        return Call(service, action, args)
    }

    /** The response to [action] of [service] carrying [out], in the order given. */
    fun response(service: String, action: String, out: List<Pair<String, String>>): String = buildString {
        append(ENVELOPE_OPEN)
        append("<u:").append(action).append("Response xmlns:u=\"").append(service).append("\">")
        for ((k, v) in out) append('<').append(k).append('>').append(escape(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("Response>")
        append(ENVELOPE_CLOSE)
    }

    /** A UPnP error: 401 an action this renderer has not, 402 an argument it cannot use, 501 a failure. */
    fun fault(code: Int, description: String): String = buildString {
        append(ENVELOPE_OPEN)
        append("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>")
        append("<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>").append(code)
        append("</errorCode><errorDescription>").append(escape(description)).append("</errorDescription></UPnPError>")
        append("</detail></s:Fault>")
        append(ENVELOPE_CLOSE)
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        .replace("&amp;", "&")

    /**
     * The title a controller put in the DIDL-Lite it sent with the URL (`CurrentURIMetaData`), if any:
     * Bilibili sends the video's name there, which is what the player then shows.
     */
    fun didlTitle(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        val didl = if ("&lt;" in metadata) unescape(metadata) else metadata
        return Regex("""<dc:title>(.*?)</dc:title>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(didl)?.groupValues?.get(1)?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `H+:MM:SS[.fff]` as DLNA writes times, in ms; null when it is not one. */
    fun parseTime(s: String?): Long? {
        val m = TIME.matchEntire(s?.trim() ?: return null) ?: return null
        val (h, min, sec) = m.destructured
        val frac = m.groupValues[4].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
        return ((h.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000 + frac
    }

    /** [ms] as `H:MM:SS`, the form controllers read positions in. */
    fun formatTime(ms: Long): String {
        val t = (ms.coerceAtLeast(0) / 1000)
        return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    private const val ENVELOPE_OPEN = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
    private const val ENVELOPE_CLOSE = "</s:Body></s:Envelope>"

    private val BODY_ACTION = Regex("""<(?:\w+:)?Body[^>]*>\s*<(?:\w+:)?(\w+)\b[^>]*xmlns(?::\w+)?="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
    private val ARG = Regex("""<(\w+)(?:\s[^>]*)?>([^<]*)</\1>""")
    private val EMPTY_ARG = Regex("""<(\w+)(?:\s[^>]*)?/>""")
    private val TIME = Regex("""(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d+))?""")
}
