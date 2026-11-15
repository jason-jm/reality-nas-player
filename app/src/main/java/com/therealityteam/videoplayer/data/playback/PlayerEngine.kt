package com.therealityteam.videoplayer.data.playback

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared "aspect not known" flow for engines that don't report video size. */
private val UNKNOWN_VIDEO_ASPECT: StateFlow<Float> = MutableStateFlow(0f)

/**
 * One subtitle line to draw right now. Text subtitles carry [text] (already stripped of
 * engine markup); image subtitles (PGS/VobSub) carry [bitmap].
 */
data class SubtitleCue(
    val text: CharSequence? = null,
    val bitmap: Bitmap? = null,
    /** Which lane the cue asked for (ASS `\an7`–`\an9` is the top one). */
    val placement: CuePlacement = CuePlacement.BOTTOM,
    /** Horizontal alignment: −1 left, 0 centre, 1 right. */
    val hAlign: Int = 0,
    /** The cue's own text size as a fraction of the picture height, when the file states one. */
    val sizeFraction: Float? = null,
    /** Present when a bitmap is positioned relative to the source video frame. */
    val bitmapPlacement: BitmapSubtitlePlacement? = null,
) {
    /** Display adjustments apply after mapping the authored image to the flat film. */
    fun pictureBounds(
        frame: FrameRect, faceWidth: Float, faceHeight: Float, scale: Float,
        raisePx: Float, preview: Boolean, coveredBottom: Float = 0f,
    ): FrameRect? {
        val image = bitmap ?: return null
        val rect = bitmapPlacement?.bounds(frame, faceWidth, faceHeight, image.width.toFloat() / image.height, scale)
            ?: return null
        val left = if (scale != 1f) rect.left.coerceIn(0f, (faceWidth - rect.width).coerceAtLeast(0f)) else rect.left
        var top = when {
            placement != CuePlacement.BOTTOM -> rect.top
            preview -> faceHeight * 0.05f
            else -> rect.top - raisePx
        }
        if (coveredBottom > 0f || scale != 1f || top != rect.top) {
            top = top.coerceIn(0f, (faceHeight - coveredBottom - rect.height).coerceAtLeast(0f))
        }
        return rect.copy(left = left, top = top)
    }
}

/**
 * What the playback UI talks to, regardless of which engine renders. Two implementations:
 * [PlaybackManager] wraps the SDK's CypressMediaPlayer (VideoPlayerComponent path);
 * [ExoPlaybackManager] wraps Media3 ExoPlayer (VideoComponent + SurfaceRenderTexture path,
 * the official third-party-player architecture) and is the only one that fills the track
 * and cue flows.
 */
interface PlayerEngine {
    val state: StateFlow<PlaybackState>
    val duration: StateFlow<Long>
    val position: StateFlow<Long>
    val hasFirstFrame: StateFlow<Boolean>

    /** Selectable streams of the current file. Engines without track APIs keep these empty. */
    val audioTracks: StateFlow<List<MediaTrack>>
    val subtitleTracks: StateFlow<List<MediaTrack>>

    /** Subtitle lines to draw right now (empty when subtitles are off or between lines). */
    val activeCues: StateFlow<List<SubtitleCue>>

    /**
     * Non-null when the file's video track cannot be decoded on this device (e.g. 4K HEVC
     * Main 10 with no capable decoder) — the label describes the format so the UI can say
     * why the screen is black while audio and subtitles keep playing.
     */
    val videoUnsupportedLabel: StateFlow<String?>
    /** Why the last file failed (state ERROR), when the engine can tell; null otherwise. */
    val failure: StateFlow<PlaybackFailure?> get() = NO_FAILURE

    /** The old format the last failure was over, named as people know it (XviD/DivX), when the engine can tell. */
    val failedFormat: String? get() = null

    /** The picture the last failure was over, when it was past this headset (PlaybackFailure.TOO_LARGE). */
    val oversize: Oversize? get() = null

    /**
     * The last time the film waited on its data: how fast the file came in against how fast the film
     * plays it. Null when it has not waited, or the engine cannot tell.
     */
    val dataRate: StateFlow<DataRate?> get() = NO_DATA_RATE

    /**
     * The picture, named ("4K60 H.264 4:2:2 10-bit"), when software decoding cannot keep up with it —
     * most of its frames lost even made lighter — so the viewer is told why it stutters. Null otherwise.
     */
    val decodeBehind: StateFlow<String?> get() = NOT_BEHIND

    /**
     * The file's server stopped answering mid-film (a NAS gone to sleep, its share dropped while the network
     * stays up) and the film waits it out, asking again: true until it plays on. False for a film that only loads.
     */
    val sourceDown: StateFlow<Boolean> get() = NOT_DOWN

    /**
     * The playing video's display aspect ratio (width÷height, pixel ratio applied), or 0 until
     * a frame's size is known. Drives the panel's 画面比例 fit; engines without a video-size
     * callback leave it 0 (the panel then fills the window).
     */
    val videoAspect: StateFlow<Float> get() = UNKNOWN_VIDEO_ASPECT

    /**
     * Whether the subtitles are drawn into the picture itself (mpv's PGS and VobSub) rather than over
     * it by the player — so that cutting the frame's black bars would cut them too (去黑边).
     */
    val subtitlesInPicture: StateFlow<Boolean> get() = NOT_IN_PICTURE

    /** A snapshot of the current file's streams for the info panel; null when unavailable. */
    fun mediaInfo(): MediaInfo? = null

    /** The URI the engine is currently playing (file path or proxy URL), for frame capture. */
    fun currentUri(): String? = null

    /**
     * The frame on screen, written to [into] as a PNG by the engine itself — true when it did. An
     * engine that cannot leaves the snapshot to MediaMetadataRetriever re-decoding [currentUri].
     */
    suspend fun captureFrame(into: java.io.File): Boolean = false

    /** The decoded picture's size in pixels (width to height), or null when the engine cannot tell. */
    fun videoPixels(): Pair<Int, Int>? = null

    fun setup(path: String, startPositionMs: Long = 0L, sidecars: List<SidecarSubtitle> = emptyList())

    /** Play from a seekable file descriptor (SMB fd bridge). Not every engine supports it. */
    fun setupFd(fd: Int, length: Long, startPositionMs: Long = 0L)
    val supportsFd: Boolean

    fun selectAudioTrack(id: String) {}

    /** Select a subtitle track by id, or null to turn subtitles off. */
    fun selectSubtitleTrack(id: String?) {}

    fun togglePlayPause()
    fun play()
    fun pause()
    fun seekTo(ms: Long)

    /**
     * 拖动预览: the seek bar is being dragged. An engine that can show frames on the way does so
     * from here until [endScrub] — silently, the clock held; one that cannot waits for the release.
     */
    fun beginScrub() {}
    /** A point the drag passes; an engine without a preview ignores it. */
    fun scrubTo(ms: Long) {}
    /** The drag let go at [ms]: playback carries on from there as it was, playing or paused. */
    fun endScrub(ms: Long) { seekTo(ms) }

    fun setVolume(v: Float)

    /**
     * 音量增强: scale the decoded audio by [factor] (1.0 = off, up to 2.0). Unlike [setVolume]
     * this goes above unity. Engines without a PCM hook ignore it.
     */
    fun setAudioBoost(factor: Float) {}
    /** 夜间模式: compress the loudness (and lift a 5.1 mix's centre); engines without a PCM stage ignore it. */
    fun setNightMode(on: Boolean) {}

    /**
     * 音画同步: shift the sound against the picture by [ms] — positive = audio later, negative =
     * audio earlier (±2 s). Takes effect on the next audio buffer. Engines without a PCM hook
     * ignore it.
     */
    fun setAudioDelay(ms: Long) {}

    /**
     * What the system's "now playing" (and a headset's display) shows for the next [setup]:
     * a title, the line under it (show · S01E02, or a film's year), and artwork. Engines
     * without a media session ignore it.
     */
    fun setNowPlaying(title: String?, subtitle: String?, artworkPath: String?) {}

    /**
     * Whether the OS may spatialize our output (PICO 空间音频). Off = play the mix as authored.
     * Applied per item; engines without audio attributes ignore it.
     */
    fun setSpatialAudio(enabled: Boolean) {}

    /**
     * The playing file's sound field (全景声: its SA3D / Opus family 2), heard turned with the head and the
     * picture; null for ordinary sound. Media3 decodes it (AmbisonicDecoder), mpv by its filter graph.
     */
    fun setAmbisonic(audio: AmbisonicAudio?) {}

    /**
     * The sound tracks in files of their own beside the next file [setup] opens (外挂音轨: a dub's .mka),
     * offered beside its own; empty for none. Set before each [setup] — it is that file's.
     */
    fun setExternalAudio(audios: List<SidecarAudio>) {}

    /** 逐帧: paused, one picture on ([forward]) or back. */
    fun stepFrame(forward: Boolean) {}

    /** What was opening stops, and the player says [failure] — found before any engine opened the file (a disc image's). */
    fun fail(failure: PlaybackFailure) {}

    /**
     * 字幕大小 for the subtitles an engine draws into the picture itself (mpv's ASS, as libass sets
     * it): 1 = the file's own size. The player scales the subtitles it draws on its own.
     */
    fun setSubtitleScale(scale: Float) {}

    /**
     * Whether ASS is drawn as its author typeset it, by an engine that draws it itself (mpv) — off,
     * its text comes out as lines for the player to set in the reader's own look.
     */
    fun setTypesetSubtitles(on: Boolean) {}

    /**
     * For an engine that draws ASS itself (mpv): the fonts in [dir] are the chosen subtitles' too — a
     * fansub's, beside the video. Its subtitles are read again with them.
     */
    fun setSubtitleFonts(dir: String) {}

    /** The chosen ASS stream's header (its styles), for an engine that draws it itself; null for none. Off the main thread. */
    fun subtitleHeader(): String? = null

    fun setSpeed(speed: Float)
    fun setLoop(loop: Boolean)

    /** Poll the current position (called on a UI ticker). */
    fun refreshPosition()
    /** The position this instant, between [refreshPosition]'s half-second ticks (main thread). */
    fun positionNow(): Long = position.value

    fun release()

    /**
     * The player closed: the system's now playing and the media keys let go of the film until the next [setup]. Kept,
     * a headset's or a Bluetooth play key woke a film no window showed, from a stream already let go (404, MISSING).
     */
    fun endSession() {}

    /**
     * The player closed: the film lets go of what it held — decoders, buffers, the picture's memory and the stream
     * from the server — and the engine stays for the next [setup], which is always a fresh one. Kept paused, a closed
     * Hi10P film held some 400 MB of native heap and 100 MB of graphics memory until another film was opened.
     */
    fun unload() {}

    /**
     * Another file is about to be set up: until [setup] has it, what shows is a film opening at [startMs] — not
     * the last film's position, length and pause, which the player showed 「已暂停」 while a slow share was asked
     * for the new one's subtitles.
     */
    fun opening(startMs: Long) {}
}

private val NOT_IN_PICTURE: StateFlow<Boolean> = MutableStateFlow(false)

/** For engines that cannot tell why a file failed. */
private val NO_FAILURE: StateFlow<PlaybackFailure?> = kotlinx.coroutines.flow.MutableStateFlow(null)
private val NO_DATA_RATE: StateFlow<DataRate?> = MutableStateFlow(null)

/**
 * When the engine last handed the window a new subtitle line (uptime ms), so the window can tell how long
 * the line waited for its frame (AuraCue) — on an idle emulator a window drew once a second and lines came
 * in batches up to a second late.
 */
object CueClock {
    @Volatile var changedAt = 0L

    private var last: List<SubtitleCue>? = null
    private val waits = ArrayList<Long>()

    /** The window has drawn [cues] (after a composition that read them); every 20 new lines, their waits said. */
    fun shown(cues: List<SubtitleCue>) {
        if (cues === last) return
        last = cues
        if (cues.isEmpty() || changedAt == 0L) return
        waits += android.os.SystemClock.uptimeMillis() - changedAt
        if (waits.size < 20) return
        val sorted = waits.sorted()
        android.util.Log.i("AuraCue", "20 subtitle lines drawn ${sorted[10]} ms after they came (90 %: ${sorted[18]} ms, worst ${sorted.last()} ms)")
        waits.clear()
    }
}
private val NOT_BEHIND: StateFlow<String?> = MutableStateFlow(null)
private val NOT_DOWN: StateFlow<Boolean> = MutableStateFlow(false)

/** A wait on the film's data: it came in at [inBytesPerSec] while the film plays [playBytesPerSec]. */
data class DataRate(val inBytesPerSec: Double, val playBytesPerSec: Double) {
    /** The file comes slower than it plays: it will keep stopping, however long it is let fill. */
    val short: Boolean get() = inBytesPerSec > 0 && playBytesPerSec > 0 && inBytesPerSec < playBytesPerSec * 0.95
}
