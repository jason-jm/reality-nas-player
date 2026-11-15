package com.therealityteam.videoplayer.data.cast

/** The source a cast's item is filed under: no library, no progress kept (its URL is the phone's, for now). */
const val CAST_SOURCE = "cast"

/** What a cast drives: the app's player, seen from the renderer. Called off the main thread. */
interface CastPlayer {
    /** A controller's URL to play, with the title it sent, if any. */
    fun load(uri: String, title: String?)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(ms: Long)
    val state: CastState
    val positionMs: Long
    val durationMs: Long
    /** 0–100. */
    var volume: Int
    var muted: Boolean
}

/** The transport states a controller understands. */
enum class CastState(val upnp: String) {
    STOPPED("STOPPED"), PLAYING("PLAYING"), PAUSED("PAUSED_PLAYBACK"), LOADING("TRANSITIONING"), IDLE("NO_MEDIA_PRESENT")
}

/**
 * The renderer's side of each SOAP action: the three services a controller calls — AVTransport (load,
 * play, pause, seek, ask where it is), RenderingControl (volume, mute), ConnectionManager (what it can
 * play) — answered from [player]. Pure, so the exchange a phone has with the headset is tested here.
 */
class CastControl(private val player: CastPlayer) {

    /** The current URL and what came with it, as a controller asks for them back. */
    @Volatile private var uri: String = ""
    @Volatile private var metadata: String = ""

    /** The URL the last SetAVTransportURI gave, for the events subscribers are sent. */
    val currentUri: String get() = uri

    /** The SOAP body answering [call]. */
    fun handle(call: Soap.Call): String = runCatching { answer(call) }
        .getOrElse { Soap.fault(501, "Action Failed") }

    private fun answer(c: Soap.Call): String {
        fun ok(vararg out: Pair<String, String>) = Soap.response(c.service, c.action, out.toList())
        return when (c.service) {
            CastRenderer.AV_TRANSPORT -> when (c.action) {
                "SetAVTransportURI" -> {
                    val u = c.args["CurrentURI"]?.trim().orEmpty()
                    if (!u.startsWith("http://") && !u.startsWith("https://")) return Soap.fault(716, "Resource not found")
                    uri = u
                    metadata = c.args["CurrentURIMetaData"].orEmpty()
                    player.load(u, Soap.didlTitle(metadata))
                    ok()
                }
                "Play" -> { player.play(); ok() }
                "Pause" -> { player.pause(); ok() }
                "Stop" -> { player.stop(); ok() }
                "Seek" -> {
                    val unit = c.args["Unit"].orEmpty()
                    val ms = Soap.parseTime(c.args["Target"])
                    if (ms == null || (unit != "REL_TIME" && unit != "ABS_TIME")) return Soap.fault(710, "Seek mode not supported")
                    player.seekTo(ms)
                    ok()
                }
                "GetTransportInfo" -> ok(
                    "CurrentTransportState" to player.state.upnp,
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )
                "GetPositionInfo" -> {
                    val duration = Soap.formatTime(player.durationMs)
                    val at = Soap.formatTime(player.positionMs)
                    ok(
                        "Track" to (if (uri.isEmpty()) "0" else "1"),
                        "TrackDuration" to duration,
                        "TrackMetaData" to metadata,
                        "TrackURI" to uri,
                        "RelTime" to at,
                        "AbsTime" to at,
                        "RelCount" to "2147483647",
                        "AbsCount" to "2147483647",
                    )
                }
                "GetMediaInfo" -> ok(
                    "NrTracks" to (if (uri.isEmpty()) "0" else "1"),
                    "MediaDuration" to Soap.formatTime(player.durationMs),
                    "CurrentURI" to uri,
                    "CurrentURIMetaData" to metadata,
                    "NextURI" to "",
                    "NextURIMetaData" to "",
                    "PlayMedium" to "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                )
                "GetTransportSettings" -> ok("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
                "GetCurrentTransportActions" -> ok("Actions" to "Play,Pause,Stop,Seek")
                else -> Soap.fault(401, "Invalid Action")
            }
            CastRenderer.RENDERING_CONTROL -> when (c.action) {
                "GetVolume" -> ok("CurrentVolume" to player.volume.toString())
                "SetVolume" -> {
                    val v = c.args["DesiredVolume"]?.trim()?.toIntOrNull() ?: return Soap.fault(402, "Invalid Args")
                    player.volume = v.coerceIn(0, 100)
                    ok()
                }
                "GetMute" -> ok("CurrentMute" to if (player.muted) "1" else "0")
                "SetMute" -> {
                    player.muted = c.args["DesiredMute"]?.trim()?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
                    ok()
                }
                else -> Soap.fault(401, "Invalid Action")
            }
            CastRenderer.CONNECTION_MANAGER -> when (c.action) {
                "GetProtocolInfo" -> ok("Source" to "", "Sink" to SINK_PROTOCOLS)
                "GetCurrentConnectionIDs" -> ok("ConnectionIDs" to "0")
                "GetCurrentConnectionInfo" -> ok(
                    "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "",
                    "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
                )
                else -> Soap.fault(401, "Invalid Action")
            }
            else -> Soap.fault(401, "Invalid Action")
        }
    }

    companion object {
        /** What it plays, as a controller checks before casting: video and audio over HTTP, in any container. */
        val SINK_PROTOCOLS = listOf(
            "http-get:*:video/mp4:*", "http-get:*:video/x-matroska:*", "http-get:*:video/mpeg:*",
            "http-get:*:video/x-flv:*", "http-get:*:video/quicktime:*", "http-get:*:video/webm:*",
            "http-get:*:video/x-msvideo:*", "http-get:*:application/x-mpegURL:*", "http-get:*:application/vnd.apple.mpegurl:*",
            "http-get:*:video/*:*", "http-get:*:audio/*:*",
        ).joinToString(",")
    }
}
