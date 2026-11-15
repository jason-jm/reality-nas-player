package com.therealityteam.videoplayer.data.cast

import com.therealityteam.videoplayer.util.PRODUCT_NAME

/**
 * 接收投屏: Aura as a DLNA / UPnP MediaRenderer, the thing a phone's 投屏 button looks for — Bilibili,
 * Tencent Video, iQIYI and Youku all cast this way, and so do NAS apps and BubbleUPnP. A phone finds
 * the headset by SSDP ([Ssdp]), reads [deviceXml], and drives playback through SOAP actions on the
 * three services every controller expects ([CastControl]). The URL it sends is played like any other.
 *
 * The protocol, pure — XML and messages as strings, no sockets — so it is tested here; the app runs
 * the network side. Dependency-free and regex-read, as the DLNA client ([DlnaSource]) is.
 */
object CastRenderer {

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    val SERVICES = listOf(AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER)

    /** The path each service's description and control live under: `/AVTransport/scpd.xml`, `…/control`. */
    fun pathOf(service: String): String = "/" + service.substringAfter(":service:").substringBefore(':')

    /** The service whose control or description [path] is, or null. */
    fun serviceAt(path: String): String? = SERVICES.firstOrNull { path.startsWith(pathOf(it) + "/") }

    /** The root device description, served at `/description.xml`. [udn] is `uuid:…`, kept per install. */
    fun deviceXml(friendlyName: String, udn: String, modelName: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion>")
        append("<device>")
        append("<deviceType>").append(DEVICE_TYPE).append("</deviceType>")
        append("<friendlyName>").append(Soap.escape(friendlyName)).append("</friendlyName>")
        append("<manufacturer>The Reality Team</manufacturer>")
        append("<modelName>").append(Soap.escape(modelName)).append("</modelName>")
        append("<modelDescription>$PRODUCT_NAME</modelDescription>")
        append("<UDN>").append(udn).append("</UDN>")
        append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>")
        append("<serviceList>")
        for (s in SERVICES) {
            val p = pathOf(s)
            append("<service>")
            append("<serviceType>").append(s).append("</serviceType>")
            append("<serviceId>urn:upnp-org:serviceId:").append(p.removePrefix("/")).append("</serviceId>")
            append("<SCPDURL>").append(p).append("/scpd.xml</SCPDURL>")
            append("<controlURL>").append(p).append("/control</controlURL>")
            append("<eventSubURL>").append(p).append("/event</eventSubURL>")
            append("</service>")
        }
        append("</serviceList>")
        append("</device></root>")
    }

    /** A service's description: its actions and their arguments, as controllers read them before calling. */
    fun scpdXml(service: String): String? {
        val actions = ACTIONS[service] ?: return null
        val variables = actions.flatMap { it.args.asList() }.map { it.variable }.distinct()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
            append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
            for (a in actions) {
                append("<action><name>").append(a.name).append("</name><argumentList>")
                for (arg in a.args) {
                    append("<argument><name>").append(arg.name).append("</name><direction>")
                        .append(if (arg.out) "out" else "in").append("</direction><relatedStateVariable>")
                        .append(arg.variable).append("</relatedStateVariable></argument>")
                }
                append("</argumentList></action>")
            }
            append("</actionList><serviceStateTable>")
            for (v in variables) {
                val type = if (v in NUMERIC) "ui4" else "string"
                append("<stateVariable sendEvents=\"").append(if (v == "LastChange") "yes" else "no")
                    .append("\"><name>").append(v).append("</name><dataType>").append(type).append("</dataType></stateVariable>")
            }
            append("</serviceStateTable></scpd>")
        }
    }

    private class Arg(val name: String, val variable: String, val out: Boolean = false)
    private class Action(val name: String, vararg val args: Arg)

    private fun inst() = Arg("InstanceID", "A_ARG_TYPE_InstanceID")

    private val ACTIONS: Map<String, List<Action>> = mapOf(
        AV_TRANSPORT to listOf(
            Action("SetAVTransportURI", inst(), Arg("CurrentURI", "AVTransportURI"), Arg("CurrentURIMetaData", "AVTransportURIMetaData")),
            Action("Play", inst(), Arg("Speed", "TransportPlaySpeed")),
            Action("Pause", inst()),
            Action("Stop", inst()),
            Action("Seek", inst(), Arg("Unit", "A_ARG_TYPE_SeekMode"), Arg("Target", "A_ARG_TYPE_SeekTarget")),
            Action(
                "GetTransportInfo", inst(),
                Arg("CurrentTransportState", "TransportState", out = true),
                Arg("CurrentTransportStatus", "TransportStatus", out = true),
                Arg("CurrentSpeed", "TransportPlaySpeed", out = true),
            ),
            Action(
                "GetPositionInfo", inst(),
                Arg("Track", "CurrentTrack", out = true),
                Arg("TrackDuration", "CurrentTrackDuration", out = true),
                Arg("TrackMetaData", "CurrentTrackMetaData", out = true),
                Arg("TrackURI", "CurrentTrackURI", out = true),
                Arg("RelTime", "RelativeTimePosition", out = true),
                Arg("AbsTime", "AbsoluteTimePosition", out = true),
                Arg("RelCount", "RelativeCounterPosition", out = true),
                Arg("AbsCount", "AbsoluteCounterPosition", out = true),
            ),
            Action(
                "GetMediaInfo", inst(),
                Arg("NrTracks", "NumberOfTracks", out = true),
                Arg("MediaDuration", "CurrentMediaDuration", out = true),
                Arg("CurrentURI", "AVTransportURI", out = true),
                Arg("CurrentURIMetaData", "AVTransportURIMetaData", out = true),
                Arg("NextURI", "NextAVTransportURI", out = true),
                Arg("NextURIMetaData", "NextAVTransportURIMetaData", out = true),
                Arg("PlayMedium", "PlaybackStorageMedium", out = true),
                Arg("RecordMedium", "RecordStorageMedium", out = true),
                Arg("WriteStatus", "RecordMediumWriteStatus", out = true),
            ),
            Action(
                "GetTransportSettings", inst(),
                Arg("PlayMode", "CurrentPlayMode", out = true),
                Arg("RecQualityMode", "CurrentRecordQualityMode", out = true),
            ),
            Action("GetCurrentTransportActions", inst(), Arg("Actions", "CurrentTransportActions", out = true)),
        ),
        RENDERING_CONTROL to listOf(
            Action("GetVolume", inst(), Arg("Channel", "A_ARG_TYPE_Channel"), Arg("CurrentVolume", "Volume", out = true)),
            Action("SetVolume", inst(), Arg("Channel", "A_ARG_TYPE_Channel"), Arg("DesiredVolume", "Volume")),
            Action("GetMute", inst(), Arg("Channel", "A_ARG_TYPE_Channel"), Arg("CurrentMute", "Mute", out = true)),
            Action("SetMute", inst(), Arg("Channel", "A_ARG_TYPE_Channel"), Arg("DesiredMute", "Mute")),
        ),
        CONNECTION_MANAGER to listOf(
            Action("GetProtocolInfo", Arg("Source", "SourceProtocolInfo", out = true), Arg("Sink", "SinkProtocolInfo", out = true)),
            Action("GetCurrentConnectionIDs", Arg("ConnectionIDs", "CurrentConnectionIDs", out = true)),
            Action(
                "GetCurrentConnectionInfo", Arg("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                Arg("RcsID", "A_ARG_TYPE_RcsID", out = true),
                Arg("AVTransportID", "A_ARG_TYPE_AVTransportID", out = true),
                Arg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo", out = true),
                Arg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager", out = true),
                Arg("PeerConnectionID", "A_ARG_TYPE_ConnectionID", out = true),
                Arg("Direction", "A_ARG_TYPE_Direction", out = true),
                Arg("Status", "A_ARG_TYPE_ConnectionStatus", out = true),
            ),
        ),
    )

    private val NUMERIC = setOf("Volume", "NumberOfTracks", "CurrentTrack", "A_ARG_TYPE_InstanceID", "RelativeCounterPosition", "AbsoluteCounterPosition")
}
