package com.therealityteam.videoplayer.data.cast

/**
 * UPnP eventing (GENA), the renderer's side. A controller that SUBSCRIBEs to a service names where to
 * be told (`CALLBACK: <http://…>`); the renderer then NOTIFYs it with the service's `LastChange` — at
 * once, and again whenever the state it reports moves. Tencent Video, iQIYI and Youku keep their own
 * play button and position in step this way rather than by asking; without it they cast once and then
 * sat there showing the wrong state.
 */
object Gena {

    /** The callback URLs in a `CALLBACK` header: `<http://a/b><http://c/d>`. */
    fun callbacks(header: String?): List<String> =
        Regex("""<(https?://[^>]+)>""").findAll(header.orEmpty()).map { it.groupValues[1] }.toList()

    /** Seconds asked for in a `TIMEOUT: Second-1800` header, held to what this renderer grants. */
    fun timeoutSeconds(header: String?): Int {
        val asked = header?.substringAfter("Second-", "")?.trim()?.toIntOrNull()
        return (asked ?: MAX_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
    }

    /** What AVTransport reports in its events. */
    data class Transport(val state: CastState, val uri: String, val durationMs: Long)

    /** What RenderingControl reports. */
    data class Rendering(val volume: Int, val muted: Boolean)

    /** The NOTIFY body for AVTransport: a property set whose LastChange is an escaped `<Event>`. */
    fun transportEvent(t: Transport): String = propertySet(
        "urn:schemas-upnp-org:metadata-1-0/AVT/",
        listOf(
            "TransportState" to t.state.upnp,
            "TransportStatus" to "OK",
            "CurrentTrackURI" to t.uri,
            "AVTransportURI" to t.uri,
            "CurrentTrackDuration" to Soap.formatTime(t.durationMs),
            "CurrentMediaDuration" to Soap.formatTime(t.durationMs),
            "NumberOfTracks" to (if (t.uri.isEmpty()) "0" else "1"),
            "CurrentPlayMode" to "NORMAL",
            "CurrentTransportActions" to "Play,Pause,Stop,Seek",
        ),
    )

    /** The NOTIFY body for RenderingControl. */
    fun renderingEvent(r: Rendering): String = propertySet(
        "urn:schemas-upnp-org:metadata-1-0/RCS/",
        listOf("Volume" to r.volume.toString(), "Mute" to if (r.muted) "1" else "0"),
        channel = true,
    )

    /** The headers of a NOTIFY to one subscriber, [seq] counting from 0 for the first. */
    fun notifyHeaders(host: String, path: String, sid: String, seq: Long, length: Int): String = buildString {
        append("NOTIFY ").append(path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
        append("HOST: ").append(host).append("\r\n")
        append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
        append("CONTENT-LENGTH: ").append(length).append("\r\n")
        append("NT: upnp:event\r\n")
        append("NTS: upnp:propchange\r\n")
        append("SID: ").append(sid).append("\r\n")
        append("SEQ: ").append(seq).append("\r\n")
        append("Connection: close\r\n\r\n")
    }

    private fun propertySet(ns: String, values: List<Pair<String, String>>, channel: Boolean = false): String {
        val event = buildString {
            append("<Event xmlns=\"").append(ns).append("\"><InstanceID val=\"0\">")
            for ((k, v) in values) {
                append('<').append(k)
                if (channel) append(" channel=\"Master\"")
                append(" val=\"").append(Soap.escape(v)).append("\"/>")
            }
            append("</InstanceID></Event>")
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>" +
            Soap.escape(event) +
            "</LastChange></e:property></e:propertyset>"
    }

    const val MIN_TIMEOUT = 60
    const val MAX_TIMEOUT = 1800
}
