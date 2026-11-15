package com.therealityteam.videoplayer.data.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * mpv as the system's media session sees a player, while mpv plays the file: playing or paused,
 * where in the file and how long it is, and what it is (the now-playing title and artwork) — and
 * the session's commands, a headset's play/pause and a controller's seeks, carried out by mpv. The
 * next/previous keys go on to the player's queue, as they do from ExoPlayer
 * ([ExoPlaybackManager.driveSessionWith]).
 *
 * Main thread only. [SwitchingEngine] says when mpv's state has changed ([refresh]), what file it
 * opened ([opened]) and where the player sent it ([seeked]).
 */
@OptIn(UnstableApi::class)
class MpvSessionPlayer(
    private val mpv: MpvPlaybackManager,
    private val seekBackMs: Long,
    private val seekForwardMs: Long,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var item: MediaItem? = null
    private var uid: Any = Any()
    private var seekedTo: Long? = null

    /** mpv opened [path]: a new item for the session, shown as [metadata]. */
    fun opened(path: String, metadata: MediaMetadata) {
        item = MediaItem.Builder().setUri(path).setMediaMetadata(metadata).build()
        uid = Any()
        invalidateState()
    }

    /** The player sent mpv elsewhere in the file — the seek bar, a chapter: the session's clock jumps too. */
    fun seeked(ms: Long) {
        seekedTo = ms
        invalidateState()
    }

    /** mpv's state changed: the session reads it again. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val b = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(!mpv.paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setSeekBackIncrementMs(seekBackMs)
            .setSeekForwardIncrementMs(seekForwardMs)
            .setPlaybackParameters(PlaybackParameters(mpv.speed))
        val current = item ?: return b.setPlaybackState(Player.STATE_IDLE).build()
        val durationMs = mpv.duration.value
        b.setPlaylist(
            listOf(
                MediaItemData.Builder(uid)
                    .setMediaItem(current)
                    .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                    .setIsSeekable(durationMs > 0)
                    .build(),
            ),
        )
            .setPlaybackState(
                when (mpv.state.value) {
                    PlaybackState.PREPARING -> Player.STATE_BUFFERING
                    PlaybackState.PLAYING, PlaybackState.PAUSED -> Player.STATE_READY
                    // mpv holds a file that played through (keep-open) and reports it so.
                    PlaybackState.READY -> Player.STATE_ENDED
                    PlaybackState.INIT, PlaybackState.ERROR -> Player.STATE_IDLE
                },
            )
            .setContentPositionMs { mpv.positionNow() }
        seekedTo?.let { b.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, it) }
        seekedTo = null
        return b.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) mpv.play() else mpv.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        mpv.seekTo(if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L))
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** What the session may ask of mpv; next/previous are added by the session's own player. */
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        ).build()
    }
}
