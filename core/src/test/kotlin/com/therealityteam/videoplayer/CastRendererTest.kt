package com.therealityteam.videoplayer

import com.therealityteam.videoplayer.data.cast.CastControl
import com.therealityteam.videoplayer.data.cast.CastPlayer
import com.therealityteam.videoplayer.data.cast.CastRenderer
import com.therealityteam.videoplayer.data.cast.CastState
import com.therealityteam.videoplayer.data.cast.Gena
import com.therealityteam.videoplayer.data.cast.Soap
import com.therealityteam.videoplayer.data.cast.Ssdp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接收投屏: the exchange a phone has with the headset, as the phone sends it. */
class CastRendererTest {

    private class FakePlayer : CastPlayer {
        val calls = mutableListOf<String>()
        override fun load(uri: String, title: String?) { calls += "load $uri | $title" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override var state = CastState.IDLE
        override var positionMs = 0L
        override var durationMs = 0L
        override var volume = 50
        override var muted = false
    }

    private val udn = "uuid:5c7e1d2a-0000-4000-8000-000000000001"

    private fun envelope(service: String, action: String, args: String) =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""

    @Test fun `a phone's cast loads the URL with the title it sent`() {
        val player = FakePlayer()
        val control = CastControl(player)
        // As Bilibili sends it: the metadata is escaped DIDL-Lite, the URL carries & in its query.
        val meta = "&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot;&gt;" +
            "&lt;item&gt;&lt;dc:title&gt;【4K】风景 &amp;amp; 城市&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
        val body = envelope(
            CastRenderer.AV_TRANSPORT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>https://cdn.example.com/v.mp4?a=1&amp;b=2</CurrentURI><CurrentURIMetaData>$meta</CurrentURIMetaData>",
        )
        val call = Soap.parse("\"${CastRenderer.AV_TRANSPORT}#SetAVTransportURI\"", body)!!
        assertEquals("https://cdn.example.com/v.mp4?a=1&b=2", call.args["CurrentURI"])
        val reply = control.handle(call)
        assertTrue(reply, reply.contains("SetAVTransportURIResponse"))
        assertEquals("load https://cdn.example.com/v.mp4?a=1&b=2 | 【4K】风景 & 城市", player.calls.single())

        control.handle(Soap.parse("${CastRenderer.AV_TRANSPORT}#Play", envelope(CastRenderer.AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>"))!!)
        control.handle(Soap.parse("${CastRenderer.AV_TRANSPORT}#Seek", envelope(CastRenderer.AV_TRANSPORT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:30.500</Target>"))!!)
        assertEquals(listOf("play", "seek 90500"), player.calls.drop(1))
    }

    @Test fun `where it is, as the phone polls it`() {
        val player = FakePlayer().apply { state = CastState.PLAYING; positionMs = 3_725_000; durationMs = 7_200_000 }
        val control = CastControl(player)
        val info = control.handle(Soap.Call(CastRenderer.AV_TRANSPORT, "GetTransportInfo", mapOf("InstanceID" to "0")))
        assertTrue(info, info.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"))
        val pos = control.handle(Soap.Call(CastRenderer.AV_TRANSPORT, "GetPositionInfo", mapOf("InstanceID" to "0")))
        assertTrue(pos, pos.contains("<RelTime>1:02:05</RelTime>") && pos.contains("<TrackDuration>2:00:00</TrackDuration>"))
        val vol = control.handle(Soap.Call(CastRenderer.RENDERING_CONTROL, "SetVolume", mapOf("DesiredVolume" to "130")))
        assertTrue(vol.contains("SetVolumeResponse"))
        assertEquals(100, player.volume)
    }

    @Test fun `what it cannot do is a UPnP fault, not a crash`() {
        val control = CastControl(FakePlayer())
        assertTrue(control.handle(Soap.Call(CastRenderer.AV_TRANSPORT, "Record", emptyMap())).contains("<errorCode>401</errorCode>"))
        assertTrue(control.handle(Soap.Call(CastRenderer.AV_TRANSPORT, "SetAVTransportURI", mapOf("CurrentURI" to "file:///etc/passwd"))).contains("<errorCode>716</errorCode>"))
        assertTrue(control.handle(Soap.Call(CastRenderer.AV_TRANSPORT, "Seek", mapOf("Unit" to "TRACK_NR", "Target" to "2"))).contains("<errorCode>710</errorCode>"))
    }

    @Test fun `an action named only in the body is read too`() {
        val call = Soap.parse(null, envelope(CastRenderer.AV_TRANSPORT, "Pause", "<InstanceID>0</InstanceID>"))!!
        assertEquals(CastRenderer.AV_TRANSPORT, call.service)
        assertEquals("Pause", call.action)
    }

    @Test fun `times as DLNA writes them`() {
        assertEquals(90_500L, Soap.parseTime("0:01:30.5"))
        assertEquals(3_725_000L, Soap.parseTime("01:02:05"))
        assertNull(Soap.parseTime("90"))
        assertEquals("1:02:05", Soap.formatTime(3_725_999))
    }

    @Test fun `the description names every service where it lives`() {
        val xml = CastRenderer.deviceXml("Reality NAS Player · PICO", udn, "PICO 4 Ultra")
        assertTrue(xml.contains("<deviceType>${CastRenderer.DEVICE_TYPE}</deviceType>"))
        assertTrue(xml.contains("<controlURL>/AVTransport/control</controlURL>"))
        assertTrue(xml.contains("<SCPDURL>/RenderingControl/scpd.xml</SCPDURL>"))
        assertEquals(CastRenderer.AV_TRANSPORT, CastRenderer.serviceAt("/AVTransport/control"))
        assertNull(CastRenderer.serviceAt("/description.xml"))
        val scpd = CastRenderer.scpdXml(CastRenderer.AV_TRANSPORT)!!
        assertTrue(scpd.contains("<name>SetAVTransportURI</name>") && scpd.contains("<name>AVTransportURI</name>"))
    }

    @Test fun `a search is answered for what it asks, and all of it for ssdp all`() {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        val st = Ssdp.searchTarget(search)!!
        assertEquals(listOf("$udn::${CastRenderer.DEVICE_TYPE}"), Ssdp.answering(st, udn).map { it.second })
        assertEquals(6, Ssdp.answering("ssdp:all", udn).size)
        assertTrue(Ssdp.answering("urn:schemas-upnp-org:service:ContentDirectory:1", udn).isEmpty())
        assertNull(Ssdp.searchTarget("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n"))
        val reply = Ssdp.searchResponse(st, "$udn::$st", "http://192.168.1.50:49152/description.xml", "Android/14 UPnP/1.0 RealityNASPlayer/1.0")
        assertTrue(reply.startsWith("HTTP/1.1 200 OK\r\n") && reply.contains("LOCATION: http://192.168.1.50:49152/description.xml\r\n"))
    }

    @Test fun `a subscriber is told the state as LastChange, escaped inside the property set`() {
        assertEquals(listOf("http://192.168.1.20:9999/evt", "http://192.168.1.20:9999/evt2"), Gena.callbacks("<http://192.168.1.20:9999/evt><http://192.168.1.20:9999/evt2>"))
        assertEquals(1800, Gena.timeoutSeconds("Second-infinite"))
        assertEquals(300, Gena.timeoutSeconds("Second-300"))
        assertEquals(60, Gena.timeoutSeconds("Second-5"))
        val body = Gena.transportEvent(Gena.Transport(CastState.PAUSED, "http://cdn/v.mp4?a=1&b=2", 90_000))
        assertTrue(body, body.contains("<LastChange>&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;"))
        assertTrue(body, body.contains("&lt;TransportState val=&quot;PAUSED_PLAYBACK&quot;/&gt;"))
        // The URL's & escaped twice: once as an attribute in the Event, once as the Event in the XML.
        assertTrue(body, body.contains("v.mp4?a=1&amp;amp;b=2"))
        assertTrue(body, body.contains("CurrentTrackDuration val=&quot;0:01:30&quot;"))
        val rc = Gena.renderingEvent(Gena.Rendering(35, true))
        assertTrue(rc, rc.contains("&lt;Volume channel=&quot;Master&quot; val=&quot;35&quot;/&gt;") && rc.contains("Mute channel=&quot;Master&quot; val=&quot;1&quot;"))
        val head = Gena.notifyHeaders("192.168.1.20:9999", "/evt", "uuid:abc", 0, 321)
        assertTrue(head.startsWith("NOTIFY /evt HTTP/1.1\r\n") && head.contains("SEQ: 0\r\n") && head.contains("NTS: upnp:propchange\r\n"))
    }
}
