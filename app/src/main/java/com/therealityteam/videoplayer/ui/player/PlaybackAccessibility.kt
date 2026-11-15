package com.therealityteam.videoplayer.ui.player

import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.playback.PlaybackState
import com.therealityteam.videoplayer.data.playback.PlayerEngine

internal fun PlaybackState.spokenStatus(): Int = when (this) {
    PlaybackState.INIT -> R.string.screen_idle
    PlaybackState.PREPARING -> R.string.vid_loading
    PlaybackState.READY -> R.string.screen_ready
    PlaybackState.PLAYING -> R.string.screen_playing
    PlaybackState.PAUSED -> R.string.vid_paused
    PlaybackState.ERROR -> R.string.vid_cannot_play
}

internal fun PlayerEngine.toggleFromScreen(): Boolean = when (state.value) {
    PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.READY -> {
        togglePlayPause()
        true
    }
    else -> false
}
