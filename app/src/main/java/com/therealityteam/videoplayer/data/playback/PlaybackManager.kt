package com.therealityteam.videoplayer.data.playback

import com.pico.spatial.core.ecs.video.CypressMediaPlayer
import com.pico.spatial.core.ecs.video.CypressMediaPlayerCallback
import com.pico.spatial.core.ecs.video.CypressMediaPlayerErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps the SDK's built-in [CypressMediaPlayer] and exposes observable playback state.
 * The same player drives both the planar panel (Shared Space) and the immersive sphere
 * (Full Space) — see VideoEntityAssembler.
 */
class PlaybackManager : PlayerEngine {

    val player = CypressMediaPlayer()

    private val _state = MutableStateFlow(PlaybackState.INIT)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _hasFirstFrame = MutableStateFlow(false)
    override val hasFirstFrame: StateFlow<Boolean> = _hasFirstFrame.asStateFlow()

    // CypressMediaPlayer exposes no track or subtitle APIs — these stay empty.
    override val audioTracks: StateFlow<List<MediaTrack>> = MutableStateFlow(emptyList())
    override val subtitleTracks: StateFlow<List<MediaTrack>> = MutableStateFlow(emptyList())
    override val activeCues: StateFlow<List<SubtitleCue>> = MutableStateFlow(emptyList())
    override val videoUnsupportedLabel: StateFlow<String?> = MutableStateFlow(null)
    override val supportsFd: Boolean = true

    private var pendingSeekMs = 0L
    private var registered = false

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    override val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    /**
     * The system's player says nothing of a file it cannot decode (a 4K HEVC Main10 remux: "amc: no suitable codec",
     * its DTS 4.0 "not 1,2 channel") — the window stood black under 播放 for good, though it had told the picture's
     * size (its first-frame sign). A film not moved on from where it opened [NO_PICTURE_MS] later, and not paused by
     * the viewer, is taken for that and said ([PlaybackFailure.UNSUPPORTED]): Media3 (设置 › 播放 › 引擎) plays such files.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var openedAtMs = 0L
    private val noPicture = Runnable {
        val at = runCatching { player.getCurrentPosition() }.getOrDefault(0L)
        android.util.Log.i("AuraPlay", "the system's player ${NO_PICTURE_MS / 1000} s in: ${_state.value}, at ${at}ms of ${_duration.value}ms (opened at ${openedAtMs}ms)")
        if (_state.value == PlaybackState.ERROR || _state.value == PlaybackState.PAUSED) return@Runnable
        if (at > openedAtMs + 1_000L) return@Runnable
        android.util.Log.i("AuraPlay", "the system's player has not moved from ${openedAtMs}ms in ${NO_PICTURE_MS / 1000} s: UNSUPPORTED")
        runCatching { player.pause() }
        _failure.value = PlaybackFailure.UNSUPPORTED
        _state.value = PlaybackState.ERROR
    }

    private val callback = object : CypressMediaPlayerCallback {
        override fun onPrepared() {
            _duration.value = player.getDuration()
            if (pendingSeekMs > 0L) {
                player.seekTo(pendingSeekMs)
                pendingSeekMs = 0L
            }
            player.play()
            _state.value = PlaybackState.READY
        }

        override fun onStarted() {
            _hasFirstFrame.value = true
            _state.value = PlaybackState.PLAYING
        }

        override fun onCompleted() {
            _state.value = PlaybackState.READY
        }

        override fun onSeekToCompleted() {
            _position.value = player.getCurrentPosition()
        }

        override fun onPaused() {
            _state.value = PlaybackState.PAUSED
        }

        override fun onStopped() {
            _state.value = PlaybackState.READY
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            // First decoded frame is imminent once the size is known.
            _hasFirstFrame.value = true
        }

        override fun onError(error: CypressMediaPlayerErrorCode) {
            main.removeCallbacks(noPicture)
            android.util.Log.i("AuraPlay", "the system's player failed: $error")
            _failure.value = PlaybackFailure.UNSUPPORTED
            _state.value = PlaybackState.ERROR
        }
    }

    override fun setup(path: String, startPositionMs: Long, sidecars: List<SidecarSubtitle>) {
        prepareCommon(startPositionMs)
        player.setDataSource(path)
        player.prepareAsync()
    }

    /** Play from a (seekable) file descriptor — used by the SMB→fd bridge prototype. */
    override fun setupFd(fd: Int, length: Long, startPositionMs: Long) {
        prepareCommon(startPositionMs)
        player.setDataSource(fd, 0L, length)
        player.prepareAsync()
    }

    private fun prepareCommon(startPositionMs: Long) {
        _state.value = PlaybackState.PREPARING
        _hasFirstFrame.value = false
        _failure.value = null
        openedAtMs = startPositionMs
        main.removeCallbacks(noPicture)
        main.postDelayed(noPicture, NO_PICTURE_MS)
        _duration.value = 0L
        _position.value = startPositionMs
        pendingSeekMs = startPositionMs
        if (registered) {
            // Switching files: the player must be back in the uninitialized state or a new
            // setDataSource is ignored and the previous video keeps playing (official sequence
            // is reset → setDataSource → prepare → play). The callback survives reset().
            player.reset()
        } else {
            player.registerCypressMediaPlayerCallback(callback)
            registered = true
        }
    }

    override fun togglePlayPause() {
        if (player.isPlaying()) player.pause() else player.resume()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }
    override fun seekTo(ms: Long) {
        player.seekTo(ms)
        _position.value = ms
    }
    override fun setVolume(v: Float) { player.setVolume(v.coerceIn(0f, 1f)) }
    override fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    override fun setLoop(loop: Boolean) { player.setLoop(loop) }

    /** Poll the current position (called on a UI ticker). */
    override fun refreshPosition() {
        if (_state.value == PlaybackState.PLAYING || _state.value == PlaybackState.PAUSED) {
            _position.value = player.getCurrentPosition()
        }
    }

    override fun release() {
        main.removeCallbacks(noPicture)
        runCatching {
            if (registered) player.unregisterCypressMediaPlayerCallback()
            player.close()
        }
        registered = false
        _state.value = PlaybackState.INIT
    }
}

/** How long the system's player may show no picture of a file before it is taken not to play it. */
private const val NO_PICTURE_MS = 12_000L
