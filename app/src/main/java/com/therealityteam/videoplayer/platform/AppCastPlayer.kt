package com.therealityteam.videoplayer.platform

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.therealityteam.videoplayer.data.cast.CastPlayer
import com.therealityteam.videoplayer.data.cast.CastState
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.playback.PlaybackState
import com.therealityteam.videoplayer.ui.player.PlaybackViewModel
import org.koin.core.context.GlobalContext

/**
 * What a cast drives, in this app: the URL opens in the player the way a link from another app does
 * (DeepLink.playUriRequest, heard by the window), and play / pause / seek go to the engine on the main
 * thread. The volume is the headset's media volume, as a TV's would be.
 */
class AppCastPlayer(context: Context, private val vm: () -> PlaybackViewModel?) : CastPlayer {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The item the last cast opened: what the phone is told about — not whatever the headset plays after. */
    @Volatile private var castId: String? = null
    @Volatile private var stopped = false

    override fun load(uri: String, title: String?) {
        castId = MediaItem.idFor(com.therealityteam.videoplayer.data.cast.CAST_SOURCE, uri)
        stopped = false
        main.post {
            // Already playing — the library's window, which opens a link, is closed while the player's is
            // up: the next cast plays in the player that is there. Else the link opens the player.
            val playing = vm()?.takeIf { it.current.value != null }
            if (playing != null) {
                val name = android.net.Uri.decode(uri.substringBefore('?').substringAfterLast('/'))
                val item = MediaItem(
                    id = MediaItem.idFor(com.therealityteam.videoplayer.data.cast.CAST_SOURCE, uri),
                    sourceId = com.therealityteam.videoplayer.data.cast.CAST_SOURCE,
                    path = uri,
                    fileName = name,
                    title = title ?: name.substringBeforeLast('.'),
                    video = com.therealityteam.videoplayer.data.playback.ProjectionDetector.detect(name),
                )
                playing.open(item, listOf(item))
            } else {
                DeepLink.playUriTitle.value = title
                DeepLink.playUriCast.value = true
                DeepLink.playUriRequest.value = uri
            }
        }
    }

    override fun play() {
        stopped = false
        main.post { vm()?.manager?.play() }
    }

    override fun pause() { main.post { vm()?.manager?.pause() } }

    /** Held where it is, not closed: a controller stops before every new URL it sends. */
    override fun stop() {
        stopped = true
        main.post { vm()?.manager?.pause() }
    }

    override fun seekTo(ms: Long) { main.post { vm()?.manager?.seekTo(ms) } }

    private fun casting(): PlaybackViewModel? = vm()?.takeIf { it.current.value?.id == castId && castId != null }

    override val state: CastState
        get() {
            val v = casting() ?: return if (castId == null) CastState.IDLE else CastState.STOPPED
            if (stopped) return CastState.STOPPED
            return when (v.manager.state.value) {
                PlaybackState.PLAYING -> CastState.PLAYING
                PlaybackState.PAUSED, PlaybackState.READY -> CastState.PAUSED
                PlaybackState.INIT, PlaybackState.PREPARING -> CastState.LOADING
                PlaybackState.ERROR -> CastState.STOPPED
            }
        }

    override val positionMs: Long get() = casting()?.manager?.position?.value ?: 0L
    override val durationMs: Long get() = casting()?.manager?.duration?.value ?: 0L

    override var volume: Int
        get() {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        }
        set(value) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0, 100) * max + 50) / 100, 0)
        }

    override var muted: Boolean
        get() = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        set(value) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (value) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        }

    companion object {
        /** The window's player, once the window has made it. */
        fun sessionVm(): PlaybackViewModel? = runCatching {
            GlobalContext.get().getScopeOrNull(com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID)?.get<PlaybackViewModel>()
        }.getOrNull()
    }
}
