package com.therealityteam.videoplayer.data.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvEvent
import `is`.xyz.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * libmpv as a [PlayerEngine], for the files Media3 cannot read: RMVB and RM, WMV and ASF, FLV's old
 * codecs, XviD/DivX AVIs and their AC-3 (see docs/legacy-formats.md). FFmpeg's demuxers and
 * software decoders — the hardware's only for H.264/HEVC/VP9/AV1, through MediaCodec — rendering
 * into the same Surface the Media3 engine renders into ([attachSurface]).
 *
 * Only [SwitchingEngine] makes one, and only for such a file: the native libraries are loaded on
 * the first, never before. libmpv lives once per process (MPVLib is a singleton) — created then,
 * kept until the process ends; [release] stops the file and lets the Surface go.
 *
 * Subtitles are not drawn by mpv: their text comes out as [activeCues], and the player draws it
 * as it draws Media3's, in the reader's own size and colour — all but a disc's picture subtitles
 * (a Blu-ray's PGS, a DVD's VobSub), which have no text, and ASS: a fansub's typesetting — a sign
 * set over the sign it translates, an opening's karaoke, the release's own fonts — which libass
 * draws into the picture as its author set it, scaled by the reader's 字幕大小.
 *
 * What ExoPlayer does for itself and mpv does not is done here the same way: it takes the audio
 * focus as it plays — pausing for a call or another app's sound, and playing on when a short one
 * is over — and pauses when the headphones go.
 */
class MpvPlaybackManager(context: Context) : PlayerEngine, MPVLib.EventObserver, MPVLib.LogObserver {

    private val appContext = context.applicationContext
    private val words = Words(appContext)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlaybackState.INIT)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()
    private val _hasFirstFrame = MutableStateFlow(false)
    override val hasFirstFrame: StateFlow<Boolean> = _hasFirstFrame.asStateFlow()
    private val _audioTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val audioTracks: StateFlow<List<MediaTrack>> = _audioTracks.asStateFlow()
    private val _subtitleTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val subtitleTracks: StateFlow<List<MediaTrack>> = _subtitleTracks.asStateFlow()
    private val _activeCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    override val activeCues: StateFlow<List<SubtitleCue>> = _activeCues.asStateFlow()
    private val _videoUnsupported = MutableStateFlow<String?>(null)
    override val videoUnsupportedLabel: StateFlow<String?> = _videoUnsupported.asStateFlow()
    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    override val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()
    private val _videoAspect = MutableStateFlow(0f)
    override val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()
    override val supportsFd: Boolean = false

    /** Invoked on the main thread when the current file plays to its end. */
    var onCompleted: (() -> Unit)? = null

    /** Whether mpv is paused — set as the command goes, before mpv reports it (the media session reads it). */
    @Volatile var paused = false
        private set

    /** The speed mpv plays at. */
    var speed = 1f
        private set

    private var created = false
    private var surface: Surface? = null
    private var uri: String? = null
    private var sidecars: List<SidecarSubtitle> = emptyList()
    /** Whether the file being opened got as far as loading — an end before that is a failure. */
    @Volatile private var loaded = false
    private val _dataRate = MutableStateFlow<DataRate?>(null)
    override val dataRate: StateFlow<DataRate?> = _dataRate
    private val _decodeBehind = MutableStateFlow<String?>(null)
    override val decodeBehind: StateFlow<String?> = _decodeBehind

    private val _sourceDown = MutableStateFlow(false)
    override val sourceDown: StateFlow<Boolean> = _sourceDown
    /** An end of file we asked for (a new file, a stop) is no failure. */
    @Volatile private var endExpected = false
    private var volume = 1f
    private var boost = 1f
    /** The last error mpv logged while opening, for the log line a failure leaves. */
    @Volatile private var lastError: String? = null

    /**
     * Where every call into libmpv is made, one after another — never the main thread. libmpv answers
     * a call only when its core is free, and the core can be waiting on the picture's output, which
     * can be waiting on the main thread: the media session asking for the position as a Blu-ray opened
     * froze the app (ANR). The main thread goes by what mpv reports instead ([position], [paused]).
     */
    private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "aura-mpv").apply { isDaemon = true } }

    /** [block] on mpv's thread, after every call made before it. */
    private fun call(block: () -> Unit) {
        calls.execute {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "mpv call failed", e)
            }
        }
    }

    /** libmpv, made on first use: the libraries load then (on mpv's thread) and not before. */
    private fun ensureCreated() {
        if (created) return
        created = true
        call {
            MPVLib.create(appContext)
            val cache = appContext.cacheDir.absolutePath
            // libass's fonts (SubtitleFonts): fontconfig reads fonts.conf from mpv's config dir.
            SubtitleFonts.conf(appContext)
            val configDir = SubtitleFonts.dir(appContext)
            mapOf(
                // A config dir of its own holding only fonts.conf, which libass's fontconfig reads.
                "config" to "yes",
                "config-dir" to configDir.absolutePath,
                "vo" to "gpu",
                "gpu-context" to "android",
                "opengl-es" to "yes",
                // The old codecs have no decoder but FFmpeg's; the hardware takes what it knows.
                "hwdec" to HWDEC,
                "hwdec-codecs" to "h264,hevc,vp9,av1",
                // A hardware decoder that fails — or gives nothing back for 3 s, with our FFmpeg
                // (app/natives/mpv-patches) — hands the file to FFmpeg's own at once.
                "hwdec-software-fallback" to "yes",
                // A picture decoded in software that cannot keep up (a camera's 4K 4:2:2 H.264 at 140 Mb/s)
                // drops frames in the decoder too, not only at the output: choppier, but in step with the
                // sound, instead of falling further behind it. A file that keeps up loses nothing.
                "framedrop" to "decoder+vo",
                // …and a picture none takes (Hi10P) is FFmpeg's from the start: see setup.
                "ao" to "audiotrack,opensles",
                // The panel is fitted to the picture's shape (videoAspect): mpv fills its Surface, adding
                // no bars of its own — a DVD's 720×480 16:9 came out letterboxed and then stretched.
                "keepaspect" to "no",
                "keep-open" to "yes",
                "idle" to "yes",
                "force-window" to "no",
                "input-default-bindings" to "no",
                "input-vo-keyboard" to "no",
                // Drawn by the player from sub-text, as Media3's are — but for what mpv draws itself
                // (readTracks): picture subtitles, and ASS as its author set it.
                "sub-visibility" to "no",
                "secondary-sub-visibility" to "no",
                "sub-auto" to "no",
                "sub-scale" to subtitleScale.toString(),
                "volume-max" to "200",
                // The share is on the LAN; a modest read-ahead, capped in bytes like Media3's.
                "cache" to "yes",
                // A Wi-Fi blip: FFmpeg reconnects where it was, waiting up to ~2 minutes (1, 2, 4… 64 s),
                // rather than taking a cut-off stream for the film's end (PatientLoadErrorPolicy's mpv half).
                "stream-lavf-o" to "reconnect=1,reconnect_on_network_error=1,reconnect_delay_max=120",
                // A connection gone quiet (the server or the Wi-Fi stopped sending, not closed) is given up after 10 s and
                // read again from where it was (the reconnect above) — mpv's own 60 s froze a 4K film from a WebDAV or
                // media server that long once its few seconds of cache ran out. Media3's reads wait 8 s the same way.
                "network-timeout" to "10",
                "demuxer-max-bytes" to "48MiB",
                "demuxer-max-back-bytes" to "16MiB",
                // 截图: the frame as decoded, lossless.
                "screenshot-format" to "png",
                "gpu-shader-cache-dir" to cache,
                "icc-cache-dir" to cache,
                "msg-level" to "all=warn",
            ).forEach { (k, v) -> MPVLib.setOptionString(k, v) }
            MPVLib.init()
            MPVLib.addObserver(this)
            MPVLib.addLogObserver(this)
            listOf(
                "time-pos" to MpvFormat.MPV_FORMAT_DOUBLE,
                "duration" to MpvFormat.MPV_FORMAT_DOUBLE,
                "pause" to MpvFormat.MPV_FORMAT_FLAG,
                "paused-for-cache" to MpvFormat.MPV_FORMAT_FLAG,
                "eof-reached" to MpvFormat.MPV_FORMAT_FLAG,
                "video-params/aspect" to MpvFormat.MPV_FORMAT_DOUBLE,
                "video-params/dw" to MpvFormat.MPV_FORMAT_INT64,
                "video-params/dh" to MpvFormat.MPV_FORMAT_INT64,
                "video-params/rotate" to MpvFormat.MPV_FORMAT_INT64,
                "track-list/count" to MpvFormat.MPV_FORMAT_INT64,
                "aid" to MpvFormat.MPV_FORMAT_STRING,
                "sid" to MpvFormat.MPV_FORMAT_STRING,
                "sub-text" to MpvFormat.MPV_FORMAT_STRING,
                "video-params/gamma" to MpvFormat.MPV_FORMAT_STRING,
            ).forEach { (p, f) -> MPVLib.observeProperty(p, f) }
        }
        // What was set before mpv was made (the engine is handed what the viewer chose before its first file).
        if (night) setAf(withField = false)
        if (field != null) startField()
    }

    /** The render target: the Surface of the panel, the place's screen or the sphere, or none. */
    fun attachSurface(target: Surface?) {
        if (target === surface) return
        if (surface != null && created) {
            // mpv lets the Surface go on its own thread, and the next producer (Media3's decoder, a new
            // place's screen) can only connect once it has: wait for the gpu output to be gone (mpv-android
            // leaves this race open, a FIXME in its BaseMPVView) — but not long: an mpv stuck elsewhere
            // must not freeze the app with it.
            val gone = CountDownLatch(1)
            call {
                try {
                    MPVLib.setPropertyString("vo", "null")
                    MPVLib.setOptionString("force-window", "no")
                    val until = SystemClock.uptimeMillis() + DETACH_WAIT_MS
                    while (MPVLib.getPropertyString("current-vo") == "gpu" && SystemClock.uptimeMillis() < until) {
                        Thread.sleep(5)
                    }
                    MPVLib.detachSurface()
                } finally {
                    gone.countDown()
                }
            }
            if (!gone.await(HANDBACK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                android.util.Log.w(TAG, "mpv kept the Surface past ${HANDBACK_WAIT_MS}ms")
            }
        }
        surface = target
        if (target != null) {
            ensureCreated()
            call {
                MPVLib.attachSurface(target)
                MPVLib.setOptionString("force-window", "yes")
                MPVLib.setPropertyString("vo", "gpu")
            }
            sizeSurface()
            pendingLoad?.let { load -> call { MPVLib.command(load) } }
            pendingLoad = null
        }
    }

    @Volatile private var videoW = 0L
    @Volatile private var videoH = 0L

    /** mpv draws at the picture's display size (its pixels' shape applied); the panel scales it. */
    /**
     * The turn the file asks for (a phone's or a camera's upright clip, stored lying down), which mpv draws:
     * its size and shape as shown are the other way round from what it decodes — or the Surface and the
     * panel were made landscape and the upright picture stretched across them.
     */
    @Volatile private var videoRotate = 0L
    @Volatile private var decodedAspect = 0.0
    private val quarterTurn: Boolean get() = videoRotate % 180L == 90L

    private fun showAspect() {
        if (decodedAspect > 0) _videoAspect.value = (if (quarterTurn) 1.0 / decodedAspect else decodedAspect).toFloat()
    }

    private fun sizeSurface() {
        val size = if (quarterTurn) "${videoH}x$videoW" else "${videoW}x$videoH"
        if (surface != null && videoW > 0 && videoH > 0) call { MPVLib.setPropertyString("android-surface-size", size) }
    }

    override fun setup(path: String, startPositionMs: Long, sidecars: List<SidecarSubtitle>) =
        setup(path, startPositionMs, sidecars, software = false)

    /**
     * [setup], its picture decoded by FFmpeg in [software] from the start: an H.264 profile no
     * hardware decoder takes (EngineRoute.softwareOnly) — tried, the headset's draws green.
     */
    fun setup(path: String, startPositionMs: Long, sidecars: List<SidecarSubtitle>, software: Boolean) {
        ensureCreated()
        uri = path
        this.sidecars = sidecars
        audios = nextAudios
        audioChosen = nextChosen
        nextChosen = null
        dubAids = emptyMap()
        loaded = false
        atEnd = false
        tracksComplete = false
        _dataRate.value = null
        _decodeBehind.value = null
        // The file this one replaces ends first — its END_FILE is ours; this file's own ends count
        // from its START_FILE on.
        endExpected = true
        lastError = null
        openedAt = SystemClock.elapsedRealtime()
        openStatus = 0
        openMissing = false
        info = null
        // The last file's sound field is not this one's: its filters go before this sound starts.
        dropField()
        if (field != null) startField()
        _failure.value = null
        _hasFirstFrame.value = false
        _sourceDown.value = false
        _activeCues.value = emptyList()
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        _duration.value = 0L
        _position.value = startPositionMs
        _videoAspect.value = 0f
        decodedAspect = 0.0
        videoRotate = 0L
        _state.value = PlaybackState.PREPARING
        opened++
        paused = false
        if (software != softwareDecoding || hdrCopy) {
            softwareDecoding = software
            hdrCopy = false
            call { MPVLib.setPropertyString("hwdec", if (software) "no" else HWDEC) }
        }
        call {
            MPVLib.setPropertyBoolean("pause", false)
            // The last file's lighter decoding (lightenDecoding) is not this one's.
            MPVLib.setPropertyString("vd-lavc-skiploopfilter", "default")
            MPVLib.setPropertyString("vd-lavc-fast", "no")
            com.therealityteam.videoplayer.platform.DeepLink.mpvSet?.split(';')?.mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 } }?.forEach { (k, v) ->
                val r = MPVLib.setPropertyString(k.trim(), v.trim())
                android.util.Log.i(TAG, "automation set $k=$v ($r)")
            }
        }
        holdFocus()
        // Resumed in software, the film starts at the keyframe before its place: a precise start decodes
        // every frame from there to it first — a Blu-ray's keyframes ~10 s apart are hundreds of frames,
        // seconds on a headset's cores. The first picture puts precise seeks back (PLAYBACK_RESTART).
        val keyframeStart = software && startPositionMs > 0
        keyframeStartPending = keyframeStart
        // A transport stream (a Blu-ray's .m2ts, a disc's stream as served) opened with start= never shows
        // a picture — FFmpeg's TS reader seeking before it has read the stream; resuming Blade Runner's 4K
        // disc at 10:00 sat at 0 fps. Sent there once loaded it came up only sometimes: a seek landing while
        // the hardware decoder was still starting left it giving nothing. So it opens at its head, paused,
        // and is sent to its place once its first picture is decoded — a seek like any in play — the
        // picture shown and the sound started only from there.
        val seekOnLoad = startPositionMs > 0 && TRANSPORT_STREAM.containsMatchIn(path.substringBefore('?'))
        startOnLoadMs = if (seekOnLoad) startPositionMs else 0L
        startSeekUnderWay = false
        // …and decoded into memory from the start: its decoder turned over to copying when the first frame
        // says HDR (video-params/gamma), while that seek is under way, gave no picture after it — copying
        // from the first frame, the seek comes up at its place (Blade Runner 4K at 10:00: playing at 600.2 s).
        if (seekOnLoad && !software) {
            hdrCopy = true
            call { MPVLib.setPropertyString("hwdec", "mediacodec-copy") }
        }
        // The tracks it was last left on, chosen as it opens: switched once it plays, mpv reads back for them.
        val picked = MpvStartTracks.decode(startTracks.getString(MpvStartTracks.key(path), null))
        val start = (
            listOfNotNull(
                if (startPositionMs > 0 && !seekOnLoad) "start=${"%.3f".format(Locale.US, startPositionMs / 1000.0)}" else null,
                // Said with the file, not only before it: the last one stood at its end (keep-open), where the
                // unpause above only played into that end and paused again — the next episode opened paused
                // (Fate Zero 02 → 03 sat on its first picture under the 已暂停 card).
                if (seekOnLoad) "pause=yes" else "pause=no",
                if (keyframeStart) "hr-seek=no" else null,
                // An OGM's XviD stores its B-frames packed two to a packet, and Ogg's timestamps for them run backwards
                // ("Invalid video timestamp"): mpv dropped half the picture of a 640×480 film at 22 % CPU, and Aura took
                // it for software decoding falling behind. Timed by its frame rate instead, it plays 24 of 24.
                if (OGM.containsMatchIn(path.substringBefore('?'))) "correct-pts=no" else null,
            ) + MpvStartTracks.options(picked?.aid, picked?.sid)
            ).joinToString(",")
        val load = arrayOf("loadfile", path, "replace", "-1", start)
        // Opened before the picture has somewhere to go, mpv drops the picture for good ("Missing
        // surface pointer"): the file waits for its Surface, as mpv-android's do.
        if (surface == null) pendingLoad = load else call { MPVLib.command(load) }
        android.util.Log.i(TAG, "loadfile $path from ${startPositionMs}ms${if (software) ", decoded in software" else ""}" +
            if (surface == null) ", once there is a surface" else "")
    }

    /** An Ogg Media file's name (.ogm): the old fansub container of XviD and Vorbis. */
    private val OGM = Regex("""\.ogm$""", RegexOption.IGNORE_CASE)

    /** A transport stream's name: a Blu-ray's .m2ts / .mts, a broadcast's .ts / .m2t. */
    private val TRANSPORT_STREAM = Regex("""\.(m2ts|mts|m2t|ts)$""", RegexOption.IGNORE_CASE)

    /** Where a transport stream opened at its head is sent once its first picture is decoded ([setup]); 0 for none. */
    @Volatile private var startOnLoadMs = 0L
    /** …and sent: the next restart is its place, where the picture is shown and the sound let go. */
    @Volatile private var startSeekUnderWay = false

    /** The file opened at a keyframe ([setup]): precise seeks come back with its first picture. */
    @Volatile private var keyframeStartPending = false

    /** A file set up before there was a Surface to draw it on. */
    private var pendingLoad: Array<String>? = null

    /** Which file this is, for checks made later about it ([checkPicture]). */
    @Volatile private var opened = 0

    /** Whether mpv decodes in software (hwdec=no): asked for ([setup]), or the hardware gave up ([checkPicture]). */
    private var softwareDecoding = false

    override fun setupFd(fd: Int, length: Long, startPositionMs: Long) =
        throw UnsupportedOperationException("mpv plays a URL")

    override fun currentUri(): String? = uri

    override fun opening(startMs: Long) {
        _state.value = PlaybackState.PREPARING
        _duration.value = 0L
        _position.value = startMs
        _hasFirstFrame.value = false
    }

    /**
     * 截图 by mpv itself: MediaMetadataRetriever cannot read what mpv plays (RealVideo, an MPEG-1
     * system stream…). The video frame only, as Media3's snapshot is — no subtitles.
     */
    override suspend fun captureFrame(into: java.io.File): Boolean {
        if (!created || !loaded) return false
        into.delete()
        return kotlinx.coroutines.suspendCancellableCoroutine { done ->
            call {
                MPVLib.command(arrayOf("screenshot-to-file", into.absolutePath, "video"))
                done.resumeWith(Result.success(into.length() > 0))
            }
        }
    }

    override fun selectAudioTrack(id: String) {
        // A dub (ExternalAudio.trackId): its aid now, not kept for the next opening — added after the
        // file loads, it has no aid yet when mpv opens it.
        ExternalAudio.indexOf(id)?.let { index ->
            val aid = dubAids[index] ?: return
            call { MPVLib.setPropertyString("aid", aid.toString()) }
            return
        }
        val n = id.removePrefix(AUDIO_ID).toIntOrNull() ?: return
        call { MPVLib.setPropertyString("aid", n.toString()) }
        rememberTracks(aid = n.toString())
    }

    override fun selectSubtitleTrack(id: String?) {
        val n = id?.removePrefix(SUB_ID)?.toIntOrNull()
        if (n == null) _activeCues.value = emptyList()
        call { MPVLib.setPropertyString("sid", n?.toString() ?: "no") }
        rememberTracks(sid = n?.toString() ?: "no")
    }

    /** The tracks each file was last left on ([MpvStartTracks]): a few hundred files, the oldest go first. */
    private val startTracks = appContext.getSharedPreferences("aura_mpv_tracks", Context.MODE_PRIVATE)

    private fun rememberTracks(aid: String? = null, sid: String? = null) {
        val path = uri ?: return
        val key = MpvStartTracks.key(path)
        runCatching {
            val was = MpvStartTracks.decode(startTracks.getString(key, null))
            val all = startTracks.all
            val e = startTracks.edit()
            if (was == null && all.size >= START_TRACKS_MAX) {
                all.entries.sortedBy { MpvStartTracks.decode(it.value as? String)?.at ?: 0L }
                    .take(all.size - START_TRACKS_MAX + 1).forEach { e.remove(it.key) }
            }
            e.putString(key, MpvStartTracks.encode(aid ?: was?.aid, sid ?: was?.sid, System.currentTimeMillis())).apply()
        }
    }

    override fun togglePlayPause() {
        if (_state.value == PlaybackState.PLAYING) pause() else play()
    }

    override fun play() {
        if (!created) return
        paused = false
        call {
            // At the end, 播放 starts the file over, as Media3 does.
            if (MPVLib.getPropertyBoolean("eof-reached") == true) MPVLib.command(arrayOf("seek", "0", "absolute"))
            MPVLib.setPropertyBoolean("pause", false)
        }
        holdFocus()
    }

    override fun pause() {
        pausedForFocus = false
        hold()
    }

    /** 逐帧: mpv's own frame-step / frame-back-step, which pause and show the picture next to the one shown. */
    override fun stepFrame(forward: Boolean) {
        if (!created) return
        paused = true
        call { MPVLib.command(arrayOf(if (forward) "frame-step" else "frame-back-step")) }
    }

    /** Paused — by the viewer, or ([pausedForFocus]) by a call taking the sound for a moment. */
    private fun hold() {
        if (!created) return
        paused = true
        call { MPVLib.setPropertyBoolean("pause", true) }
    }

    /** When the last seek was asked for, and where to: said once the film plays again (AuraPerf, as Media3's are). */
    @Volatile private var seekAskedAt = 0L
    @Volatile private var seekAskedTo = 0L

    override fun seekTo(ms: Long) {
        if (!created) return
        _position.value = ms
        seekAskedAt = SystemClock.elapsedRealtime()
        seekAskedTo = ms
        call { MPVLib.command(arrayOf("seek", "%.3f".format(Locale.US, ms / 1000.0), "absolute+exact")) }
    }

    // 拖动预览: keyframes on the way — mpv shows them as cheaply as Media3's previews. A drag passes
    // points faster than a busy mpv reaches them: only the latest is gone to.
    private val scrubTarget = AtomicLong(-1)

    override fun beginScrub() {}
    override fun scrubTo(ms: Long) {
        if (!created) return
        if (scrubTarget.getAndSet(ms) < 0) call {
            val to = scrubTarget.getAndSet(-1)
            if (to >= 0) MPVLib.command(arrayOf("seek", "%.3f".format(Locale.US, to / 1000.0), "absolute+keyframes"))
        }
    }
    override fun endScrub(ms: Long) {
        scrubTarget.set(-1)
        seekTo(ms)
    }

    override fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
    }

    override fun setAudioBoost(factor: Float) {
        boost = factor.coerceIn(1f, 2f)
        applyVolume()
    }

    private fun applyVolume() {
        val level = (volume * boost * 100f).toDouble()
        if (created) call { MPVLib.setPropertyDouble("volume", level) }
    }

    override fun setNightMode(on: Boolean) {
        if (night == on) return
        night = on
        if (!created) return
        setAf(withField = fieldRunning != null)
        // A rebuilt graph starts facing ahead: where the head is, sent again at once.
        heardYaw = Double.NaN
    }

    // ---- 全景声: the file's sound field decoded in mpv's af (SoundFieldFilter), turned with the head ----
    // Media3 decodes it with AmbisonicDecoder; mpv runs none of our code on its sound, so here it is the
    // desktop's filter graph, its two turning gains set by af-command from SoundField. Main thread.

    /** The playing file's sound field, as its header says; and the one whose filters are in mpv's af. */
    private var field: AmbisonicAudio? = null
    @Volatile private var fieldRunning: AmbisonicAudio? = null
    private var night = false
    private var heardYaw = Double.NaN
    private var heardAt = 0L

    override fun setAmbisonic(audio: AmbisonicAudio?) {
        if (audio == field) return
        field = audio
        if (created) startField()
    }

    /**
     * The sound field's filters, put in only once the sound plays and its track is seen to be the
     * field (its channel count): a graph built for four channels on another track — a stereo dub, a 5.1
     * mix — takes the whole sound with it, and set before the sound starts, a graph that fails leaves
     * the file silent. Asked again whenever the audio track changes.
     */
    private fun startField() {
        val audio = field
        dropField()
        if (audio == null || SoundFieldFilter.af(audio) == null) return
        val since = SystemClock.elapsedRealtime()
        fun ask() {
            call {
                // The track's own count (the container's), and what its decoder gives — the latter
                // still the last track's for a moment after a switch.
                val track = MPVLib.getPropertyString("current-tracks/audio/demux-channel-count")?.toIntOrNull()
                val decoded = MPVLib.getPropertyString("audio-params/channel-count")?.toIntOrNull()
                val channels = track ?: decoded
                main.post {
                    if (field !== audio || fieldRunning != null) return@post
                    when {
                        channels == audio.channels && decoded == audio.channels -> {
                            fieldRunning = audio
                            setAf(withField = true)
                            android.util.Log.i(TAG, "全景声: ${audio.from} order ${audio.order}, ${audio.channels} channels, decoded to two ears")
                            heardYaw = Double.NaN
                            main.post(turning)
                        }
                        channels != null && channels != audio.channels ->
                            android.util.Log.i(TAG, "全景声: the track playing ($channels channels) is not the sound field")
                        SystemClock.elapsedRealtime() - since < SOUND_FIELD_WAIT_MS -> main.postDelayed({ ask() }, 250)
                    }
                }
            }
        }
        ask()
    }

    private fun dropField() {
        main.removeCallbacks(turning)
        if (fieldRunning == null) return
        fieldRunning = null
        setAf(withField = false)
    }

    /** mpv's audio filters: the sound field's decode ([withField]), then 夜间模式's compressor. */
    private fun setAf(withField: Boolean) {
        val graph = fieldRunning?.takeIf { withField }?.let { SoundFieldFilter.af(it) }
        val af = listOfNotNull(graph, NIGHT_AF.takeIf { night }).joinToString(",")
        call { MPVLib.setPropertyString("af", af) }
    }

    /**
     * The field turned to where the listener faces: SoundField's third row is the listener's back in
     * the film's frame. Sent as the head turns, and once a second besides — a gain sent before the
     * graph was built is passed over.
     */
    private val turning = object : Runnable {
        override fun run() {
            if (fieldRunning == null) return
            val m = SoundField.rotation
            val yaw = kotlin.math.atan2(-m[6].toDouble(), m[8].toDouble())
            val now = SystemClock.elapsedRealtime()
            if (heardYaw.isNaN() || kotlin.math.abs(yaw - heardYaw) > TURN_STEP || now - heardAt > 1_000) {
                heardYaw = yaw
                heardAt = now
                val (onY, onX) = SoundFieldFilter.turn(yaw)
                call {
                    MPVLib.command(arrayOf("af-command", SoundFieldFilter.LABEL, "volume", SoundFieldFilter.f(onY), SoundFieldFilter.ON_Y))
                    MPVLib.command(arrayOf("af-command", SoundFieldFilter.LABEL, "volume", SoundFieldFilter.f(onX), SoundFieldFilter.ON_X))
                    // (adb shell setprop log.tag.AuraMpv DEBUG: the turns as sent, and the filters mpv holds.)
                    if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) android.util.Log.d(TAG, "全景声 turned to " +
                        "%.1f° (Y %.3f, X %.3f); af %s".format(Locale.US, Math.toDegrees(yaw), onY, onX, MPVLib.getPropertyString("af")?.take(40)))
                }
            }
            main.postDelayed(this, TURN_TICK_MS)
        }
    }

    override fun setAudioDelay(ms: Long) {
        if (created) call { MPVLib.setPropertyDouble("audio-delay", ms / 1000.0) }
    }

    /** Subtitle delay in µs, positive = later — applied at once: the cues come live from mpv. */
    fun setSubtitleDelay(us: Long) {
        if (created) call { MPVLib.setPropertyDouble("sub-delay", us / 1_000_000.0) }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        if (created) call { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    override fun setLoop(loop: Boolean) {
        if (created) call { MPVLib.setPropertyString("loop-file", if (loop) "inf" else "no") }
    }

    /** Called by the screen as it polls; here only the AuraPerf beacon ([framesBeacon]) — mpv tells its position itself. */
    override fun refreshPosition() = framesBeacon()

    // AuraPerf "frames", as ExoPlaybackManager writes them: every FRAMES_EVERY_MS of playing, the pictures
    // the film's clock passed and those mpv dropped (its output's and its decoder's), read on mpv's thread.
    /** This file is HDR and mpv's decoder was turned to copying its pictures out ([HWDEC] otherwise). */
    @Volatile private var hdrCopy = false

    @Volatile private var framesAt = 0L
    @Volatile private var framesAt0 = -1L
    @Volatile private var framesDropped0 = 0L
    private val framesAsked = AtomicBoolean(false)

    private fun framesBeacon() {
        if (_state.value != PlaybackState.PLAYING || !loaded) { framesAt = 0L; return }
        val now = SystemClock.elapsedRealtime()
        if (framesAt != 0L && now - framesAt < FRAMES_EVERY_MS) return
        if (!framesAsked.compareAndSet(false, true)) return
        call {
            try {
                val frame = MPVLib.getPropertyString("estimated-frame-number")?.toLongOrNull() ?: return@call
                val dropped = (MPVLib.getPropertyString("frame-drop-count")?.toLongOrNull() ?: 0L) +
                    (MPVLib.getPropertyString("decoder-frame-drop-count")?.toLongOrNull() ?: 0L)
                val at = SystemClock.elapsedRealtime()
                val since = framesAt
                if (since != 0L && framesAt0 >= 0 && frame >= framesAt0) {
                    val passed = frame - framesAt0
                    val gone = (dropped - framesDropped0).coerceAtLeast(0)
                    val fps = MPVLib.getPropertyDouble("container-fps")?.takeIf { it > 0 }
                    val hw = MPVLib.getPropertyString("hwdec-current")?.takeIf { it != "no" }
                    android.util.Log.i(
                        "AuraPerf",
                        "frames: ${(passed - gone).coerceAtLeast(0)} shown, $gone dropped in ${at - since} ms " +
                            "(${"%.1f".format(Locale.US, (passed - gone) * 1000f / (at - since))} fps of ${fps?.let { "%.0f".format(Locale.US, it) } ?: "?"}, " +
                            "${MPVLib.getPropertyString("width")}x${MPVLib.getPropertyString("height")}, " +
                            "mpv ${hw ?: "software"} ${MPVLib.getPropertyString("video-codec-name") ?: ""})",
                    )
                    // Made lighter two reports ago (the one between counts the decoder's restart) and still
                    // losing most of them: the viewer is told what is too much.
                    if (lightenedFor == opened) windowsLightened++
                    if (hw == null && lightenedFor == opened && windowsLightened >= 2 && passed > 0 && gone * 2 > passed && _decodeBehind.value == null) {
                        _decodeBehind.value = pictureName(fps)
                        android.util.Log.i(TAG, "software decoding still loses $gone of $passed frames: ${_decodeBehind.value}")
                    }
                    if (hw == null && passed > 0 && gone * 5 > passed) lightenDecoding(gone, passed)
                }
                framesAt = at
                framesAt0 = frame
                framesDropped0 = dropped
            } finally {
                framesAsked.set(false)
            }
        }
    }

    /** "4K60 H.264 4:2:2 10-bit": the picture's size, rate, codec and sampling, as mpv reads them. On mpv's thread. */
    private fun pictureName(fps: Double?): String {
        val w = MPVLib.getPropertyString("width")?.toIntOrNull() ?: 0
        val h = MPVLib.getPropertyString("height")?.toIntOrNull() ?: 0
        val size = when {
            w >= 7680 || h >= 4320 -> "8K"
            w >= 5760 -> "5.7K"
            w >= 3840 || h >= 2160 -> "4K"
            h >= 1440 -> "1440p"
            h >= 1080 -> "1080p"
            else -> "${h}p"
        }
        val codec = when (val c = (MPVLib.getPropertyString("video-codec-name") ?: MPVLib.getPropertyString("video-format"))?.lowercase()) {
            "h264" -> "H.264"
            "hevc" -> "HEVC"
            null -> ""
            else -> c.uppercase()
        }
        val pf = MPVLib.getPropertyString("video-params/pixelformat")?.lowercase().orEmpty()
        val chroma = when {
            pf.startsWith("yuv422") -> "4:2:2"
            pf.startsWith("yuv444") || pf.startsWith("gbr") -> "4:4:4"
            else -> null
        }
        val bits = Regex("""p(\d{2})""").find(pf)?.groupValues?.get(1)?.let { "$it-bit" }
        return listOfNotNull(size + (fps?.let { "%.0f".format(Locale.US, it) } ?: ""), codec.ifEmpty { null }, chroma, bits).joinToString(" ")
    }

    /** The file whose software decoding was made lighter ([lightenDecoding]) — once a file. */
    @Volatile private var lightenedFor = -1
    /** The frame reports since then. */
    @Volatile private var windowsLightened = 0

    /**
     * FFmpeg's decoder is losing more than one frame in five (a camera's 4K60 H.264 4:2:2 at 140 Mb/s, on
     * the headset's CPU): it stops running the deblocking filter, and takes its shortcuts. At such a rate
     * there is next to nothing for the filter to smooth — it is a quarter of an H.264 decode for a
     * picture that looks the same. The video decoder is taken up again with them (the picture waits for
     * the next keyframe); a file that keeps up is decoded as it always was. On mpv's thread.
     */
    private fun lightenDecoding(gone: Long, passed: Long) {
        if (lightenedFor == opened) return
        lightenedFor = opened
        windowsLightened = 0
        val vid = MPVLib.getPropertyString("vid")?.takeIf { it != "no" } ?: return
        android.util.Log.i(TAG, "software decoding loses $gone of $passed frames: no deblocking, FFmpeg's shortcuts")
        MPVLib.setPropertyString("vd-lavc-skiploopfilter", "all")
        MPVLib.setPropertyString("vd-lavc-fast", "yes")
        MPVLib.setPropertyString("vid", "no")
        MPVLib.setPropertyString("vid", vid)
    }

    /** Where mpv last said it was — told at every frame (time-pos), so never asked from here. */
    override fun positionNow(): Long = _position.value

    /** The info panel's figures, read on mpv's thread: the panel asks every second and shows the last read. */
    @Volatile private var info: MediaInfo? = null
    private val infoAsked = AtomicBoolean(false)

    override fun mediaInfo(): MediaInfo? {
        if (!created || !loaded) return null
        if (infoAsked.compareAndSet(false, true)) call {
            try {
                info = readMediaInfo()
            } finally {
                infoAsked.set(false)
            }
        }
        return info
    }

    private fun readMediaInfo(): MediaInfo {
        fun s(p: String) = MPVLib.getPropertyString(p)?.takeIf { it.isNotBlank() }
        fun i(p: String) = MPVLib.getPropertyInt(p)
        val w = i("video-params/w")
        val h = i("video-params/h")
        val videoName = s("video-format")?.let { VIDEO_NAMES[it] ?: it.uppercase(Locale.US) }
        // What makes a picture what it is beyond its codec: Hi10P's profile and depth (why it is decoded
        // in software), an HDR one's transfer.
        val profile = s("current-tracks/video/codec-profile")?.takeUnless { it.equals("unknown", ignoreCase = true) }
        val depth = s("video-params/pixelformat")?.let { Regex("p(\\d{2})").find(it)?.groupValues?.get(1) }?.let { "$it-bit" }
        val hdr = when (s("video-params/gamma")) {
            "pq" -> "HDR10"
            "hlg" -> "HLG"
            else -> null
        }
        val video = listOfNotNull(
            if (w != null && h != null && w > 0) "$w×$h" else null,
            videoName?.let { n -> profile?.let { "$n $it" } ?: n },
            depth,
            hdr,
            MPVLib.getPropertyDouble("container-fps")?.takeIf { it > 0 }?.let { "%.0ffps".format(Locale.US, it) },
        ).joinToString(" · ").ifBlank { null }
        val audioName = s("audio-codec-name")?.let { CODEC_NAMES[it] ?: if (it.startsWith("pcm_")) "PCM" else it.uppercase(Locale.US) }
        val audio = listOfNotNull(
            audioName,
            i("audio-params/channel-count")?.let { ExoPlaybackManager.channelLabel(it, words) },
            i("audio-params/samplerate")?.takeIf { it > 0 }?.let { hz ->
                com.therealityteam.videoplayer.ui.common.kiloHertzText(hz)
            },
        ).joinToString(" · ").ifBlank { null }
        val hw = s("hwdec-current")?.takeIf { it != "no" }
        // The codecs by the names people know, as the picture's and sound's lines name them.
        val decoder = listOfNotNull(
            videoName?.let { (if (hw != null) words.hardware else words.software) + " " + it },
            audioName?.let { words.audio + " " + it },
        ).joinToString(" · ").ifBlank { null }?.let { "$it · mpv" }
        val frames = i("frame-drop-count")?.let { dropped ->
            words.frames(dropped, i("estimated-frame-number") ?: 0)
        }
        return MediaInfo(video = video, audio = audio, decoder = decoder, frames = frames)
    }

    override fun release() {
        pendingLoad = null
        dropFocus()
        if (!created) return
        dropField()
        field = null
        endExpected = true
        call { MPVLib.command(arrayOf("stop")) }
        attachSurface(null)
        uri = null
        _state.value = PlaybackState.INIT
    }

    // ---- the system's sound: audio focus, and headphones going -------------------------------
    // ExoPlayer does both for itself (handleAudioFocus, setHandleAudioBecomingNoisy); so does mpv's
    // engine, the same way. Main thread.

    private val audio: AudioManager = appContext.getSystemService(AudioManager::class.java)
    private var focusHeld = false
    /** Paused by a call or another app's sound for a moment: plays on when it is over. */
    private var pausedForFocus = false

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    audio.abandonAudioFocusRequest(focusRequest)
                    focusHeld = false
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!paused) {
                    hold()
                    pausedForFocus = true
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) play()
                // A notification's sound over ours (…CAN_DUCK): the system lowers ours itself.
            }
            android.util.Log.i(TAG, "audio focus: $change")
        }, main)
        .build()

    private val becomingNoisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var noisyRegistered = false

    /** Playing: the sound is ours, and headphones going pause it. */
    private fun holdFocus() {
        pausedForFocus = false
        if (!focusHeld) focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!noisyRegistered) {
            noisyRegistered = true
            appContext.registerReceiver(becomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun dropFocus() {
        pausedForFocus = false
        if (focusHeld) audio.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
        if (noisyRegistered) {
            noisyRegistered = false
            runCatching { appContext.unregisterReceiver(becomingNoisy) }
        }
    }

    // ---- mpv's events, on its own thread ----------------------------------------------------

    override fun eventProperty(property: String) {
        when (property) {
            "sub-text" -> _activeCues.value = emptyList()
            "duration" -> Unit
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "track-list/count" -> readTracks()
            "video-params/dw" -> { videoW = value; main.post { sizeSurface() } }
            "video-params/dh" -> { videoH = value; main.post { sizeSurface() } }
            "video-params/rotate" -> {
                videoRotate = value
                showAspect()
                main.post { sizeSurface() }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                paused = value
                // keep-open pauses a file that has played through, and that pause can come after the
                // end itself: read as PAUSED, it called off the next episode's countdown the moment it
                // was announced (a pause, the player takes it, is somebody stopping). The end stays READY.
                if (loaded && _state.value != PlaybackState.ERROR && !(value && atEnd)) {
                    _state.value = if (value) PlaybackState.PAUSED else PlaybackState.PLAYING
                }
            }
            "paused-for-cache" -> if (loaded && value) {
                _state.value = PlaybackState.PREPARING
                // What it waits on: how fast the file comes in against how fast the film plays it.
                runCatching {
                    val speed = MPVLib.getPropertyDouble("cache-speed") ?: 0.0
                    val rate = MPVLib.getPropertyDouble("video-bitrate") ?: 0.0
                    val ahead = MPVLib.getPropertyDouble("demuxer-cache-duration") ?: 0.0
                    android.util.Log.i(
                        TAG,
                        "waiting for data: in at %.1f MB/s, the picture plays %.1f MB/s, %.1f s ahead".format(Locale.US, speed / 1e6, rate / 8e6, ahead),
                    )
                    if (speed > 0 && rate > 0) _dataRate.value = DataRate(speed, rate / 8)
                }
            }
                else if (loaded) {
                    _state.value = if (MPVLib.getPropertyBoolean("pause") == true) PlaybackState.PAUSED else PlaybackState.PLAYING
                    // Back only if no broken read came with it: FFmpeg giving up (its reconnects spent) ends the wait
                    // too, the moment it says the stream ended early — the film then fails, its server still away.
                    if (_sourceDown.value && SystemClock.elapsedRealtime() - networkTroubleAt > GAVE_UP_GAP_MS) {
                        _sourceDown.value = false
                        android.util.Log.i(TAG, "the file's server answers again: playing on")
                    }
                }
            "eof-reached" -> if (!value) atEnd = false else if (loaded) {
                atEnd = true
                val at = _position.value
                val length = _duration.value
                val networkTrouble = SystemClock.elapsedRealtime() - networkTroubleAt < NETWORK_TROUBLE_MS
                if (length > 0 && at < length - EARLY_END_MS && networkTrouble) {
                    // The stream stopped, the film did not: the share went away (FFmpeg's reconnecting
                    // gave up). Not played to its end — no next episode; the player's network-back
                    // retry starts it again here.
                    android.util.Log.i(TAG, "ended at ${at}ms of ${length}ms: the stream, not the film")
                    _failure.value = PlaybackFailure.UNREACHABLE
                    _state.value = PlaybackState.ERROR
                } else {
                    _state.value = PlaybackState.READY
                    main.post { onCompleted?.invoke() }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            // An HDR picture (PQ, HLG) goes through memory, where mpv can map its light to the panel's; an
            // SDR one stays on the GPU as the decoder made it (AImageReader, no copy: 8K30 at 30 fps rather
            // than 25 with drops). Told as the file's picture is first described; the next file starts over.
            "video-params/gamma" -> if (value in HDR_GAMMAS && !softwareDecoding && !hdrCopy) {
                hdrCopy = true
                android.util.Log.i(TAG, "HDR ($value): mpv decodes it into memory, to map its light")
                call { MPVLib.setPropertyString("hwdec", "mediacodec-copy") }
            }
            "sub-text" -> {
                CueClock.changedAt = SystemClock.uptimeMillis()
                _activeCues.value = if (value.isBlank() || subtitlesDrawnByMpv) emptyList() else listOf(SubtitleCue(text = value))
            }
            "aid", "sid" -> {
                readTracks()
                if (property == "aid" && value != heardAid) {
                    heardAid = value
                    main.post { if (field != null) startField() }
                }
            }
        }
    }

    /** The audio track mpv last said it plays (its "aid"): the sound field is asked about again when it changes. */
    @Volatile private var heardAid: String? = null

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _position.value = (value * 1000).toLong()
            "duration" -> _duration.value = (value * 1000).toLong()
            "video-params/aspect" -> { decodedAspect = value; showAspect() }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_START_FILE -> endExpected = false
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // A transport stream resumed: its head's picture decoded (paused, not shown), now its place.
                val sendTo = startOnLoadMs
                if (sendTo > 0 && loaded) {
                    startOnLoadMs = 0L
                    startSeekUnderWay = true
                    MPVLib.command(arrayOf("seek", "%.3f".format(Locale.US, sendTo / 1000.0), if (keyframeStartPending) "absolute+keyframes" else "absolute"))
                    android.util.Log.i(TAG, "a transport stream: sent to ${sendTo / 1000} s from its first picture")
                    return
                }
                if (startSeekUnderWay) {
                    startSeekUnderWay = false
                    MPVLib.setPropertyBoolean("pause", false)
                }
                seekAskedAt.takeIf { it > 0 }?.let { at ->
                    seekAskedAt = 0L
                    val landed = MPVLib.getPropertyDouble("time-pos") ?: -1.0
                    android.util.Log.i("AuraPerf", "seek → ${seekAskedTo / 1000}s: playing again in ${SystemClock.elapsedRealtime() - at}ms (mpv, at ${"%.1f".format(Locale.US, landed)} s)")
                }
                if (keyframeStartPending) {
                    keyframeStartPending = false
                    MPVLib.setPropertyString("hr-seek", "default")
                }
                // Playing from where it was opened or sent: the picture is up — per file, unlike
                // vo-configured, which the kept window (force-window) has before any file.
                if (loaded && MPVLib.getPropertyString("vid").let { it != null && it != "no" }) _hasFirstFrame.value = true
            }
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                loaded = true

                val file = opened
                main.postDelayed({ checkPicture(file) }, NO_PICTURE_MS)
                call {
                    try {
                        addSidecars()
                    } finally {
                        tracksComplete = true
                        readTracks()
                    }
                }
                _state.value = if (MPVLib.getPropertyBoolean("pause") == true) PlaybackState.PAUSED else PlaybackState.PLAYING
                android.util.Log.i(TAG, "loaded: ${MPVLib.getPropertyString("file-format")} " +
                    "${MPVLib.getPropertyString("video-codec")} / ${MPVLib.getPropertyString("audio-codec")}")
            }
            MpvEvent.MPV_EVENT_END_FILE -> {
                // keep-open holds a file that played through; an end we did not ask for is a failure.
                if (endExpected) return
                // Not opened: the share's server did not answer (the loopback proxy's 5xx, a broken connection),
                // the file is not there — or FFmpeg, which reads every container there is (the old ones Media3
                // has no reader for are why mpv is here), could not make it out: a cut-off file, an empty one,
                // no video at all. It is not the format; that read 「头显还不支持 RMVB 格式」 for a NAS asleep.
                _failure.value = when {
                    loaded -> PlaybackFailure.DAMAGED
                    openStatus in 500..599 || networkTroubleAt >= openedAt -> PlaybackFailure.UNREACHABLE
                    openStatus == 404 || openStatus == 410 || openMissing -> PlaybackFailure.MISSING
                    else -> PlaybackFailure.DAMAGED
                }
                // …and a server that did not answer is waited for: the player asks it again until it does.
                if (_failure.value == PlaybackFailure.UNREACHABLE) _sourceDown.value = true
                _state.value = PlaybackState.ERROR
                android.util.Log.i(TAG, "failed (${if (loaded) "while playing" else "opening"}): $lastError")
            }
        }
    }

    /** Whether the file has played through and stands at its end (keep-open) — its pause is no one's. */
    @Volatile private var atEnd = false

    /** When FFmpeg last said the share's stream broke — what makes an early end the network's (see eof-reached). */
    @Volatile private var networkTroubleAt = -NETWORK_TROUBLE_MS

    /** When the current file was given to mpv. */
    @Volatile private var openedAt = 0L

    /** What the server said to opening the current file, if it refused (an HTTP status), and whether there was no such file. */
    @Volatile private var openStatus = 0
    @Volatile private var openMissing = false

    /** Whether the stream broke a moment ago — FFmpeg may be sleeping between its reconnects now. */
    val streamBroken: Boolean get() = SystemClock.elapsedRealtime() - networkTroubleAt < STREAM_BROKEN_MS

    override fun logMessage(prefix: String, level: Int, text: String) {
        // Automation asked mpv for more (aura.mpv_set msg-level=…): all of it, to read on the headset.
        if (com.therealityteam.videoplayer.platform.DeepLink.mpvSet?.contains("msg-level") == true) android.util.Log.i("AuraMpvLog", "[$prefix] ${text.trim()}")
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN && NETWORK_WORDS.any { text.contains(it, ignoreCase = true) } &&
            NOT_NETWORK.none { text.contains(it) }
        ) {
            networkTroubleAt = SystemClock.elapsedRealtime()
            // Once the film has played, a broken stream is its server's: the viewer is told it waits on it, as
            // Media3's films say (ExoPlaybackManager.onLoadError), not only 加载中. Not at the film's end: there
            // nothing more is read, and a wait then was taken for the server's — and the film opened again at its end.
            if (loaded && _hasFirstFrame.value && !_sourceDown.value && !atEnd) {
                _sourceDown.value = true
                android.util.Log.i(TAG, "the file's server stopped answering: waiting for it (${text.trim().take(80)})")
            }
        }
        if (!loaded && level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) {
            HTTP_ERROR.find(text)?.let { openStatus = it.groupValues[1].toInt() }
            if (text.contains("No such file or directory")) openMissing = true
        }
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            lastError = "[$prefix] ${text.trim()}"
            android.util.Log.w(TAG, lastError!!)
        }
    }

    /**
     * The picture has not come [NO_PICTURE_MS] after [file] opened: a hardware decoder that takes a
     * stream and gives nothing back (the emulator's, on a Blu-ray's 1080p H.264) holds the whole
     * file up — the sound waits for the picture. FFmpeg's own decoder takes over for this file. A
     * file waiting for the share is given its time first.
     */
    private fun checkPicture(file: Int) {
        if (file != opened || !loaded || _hasFirstFrame.value || _state.value == PlaybackState.ERROR) return
        call {
            if (file != opened) return@call
            if (MPVLib.getPropertyBoolean("paused-for-cache") == true) {
                main.postDelayed({ checkPicture(file) }, NO_PICTURE_MS)
                return@call
            }
            val vid = MPVLib.getPropertyString("vid")
            val hw = MPVLib.getPropertyString("hwdec-current")
            if (vid == null || vid == "no" || hw == null || hw == "no") return@call
            android.util.Log.i(TAG, "no picture from $hw in ${NO_PICTURE_MS}ms: FFmpeg decodes ${MPVLib.getPropertyString("video-codec")}")
            main.post { if (file == opened) softwareDecoding = true }
            MPVLib.setPropertyString("hwdec", "no")
        }
    }

    override fun setSubtitleScale(scale: Float) {
        if (created) call { MPVLib.setPropertyDouble("sub-scale", scale.toDouble()) }
        else subtitleScale = scale
    }

    /** Whether mpv draws ASS itself, as typeset — off, its text comes out as lines like any other. */
    @Volatile private var typeset = true

    override fun setSubtitleFonts(dir: String) {
        if (!created) return
        call {
            MPVLib.setPropertyString("sub-fonts-dir", dir)
            // libass is set up as a track is: the chosen one taken up again, with the fonts.
            val sid = MPVLib.getPropertyString("sid")
            if (sid != null && sid != "no" && sid != "auto") {
                MPVLib.setPropertyString("sid", "no")
                MPVLib.setPropertyString("sid", sid)
            }
            android.util.Log.i(TAG, "subtitle fonts from $dir (track $sid read again)")
        }
    }

    override fun subtitleHeader(): String? =
        if (!created) null else runCatching { MPVLib.getPropertyString("sub-ass-extradata") }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun setTypesetSubtitles(on: Boolean) {
        if (on == typeset) return
        typeset = on
        if (created) call { readTracks() }
    }

    /** 字幕大小 for the subtitles mpv draws, set before mpv was made. */
    @Volatile private var subtitleScale = 1f

    /** The share's subtitle files beside the video, as Media3 is handed them. */
    private fun addSidecars() {
        // A subtitle file beside the video is there to be read: one is shown — as Media3 shows the ones it is
        // handed, each its default — when nothing chose otherwise (the player's own pick comes after, and
        // wins): the reader's language if one is in it, else the first. Not a picture one (VobSub), which
        // Media3 is not handed. mpv's "auto" left them all off — the same file had subtitles in Media3 and none here.
        val texts = sidecars.withIndex().filter { !com.therealityteam.videoplayer.data.subtitle.SubtitleEngine.isPicture(it.value.mimeType) }
        val wanted = ExoPlaybackManager.preferredTextLanguages().map { it.lowercase() }
        val shown = (texts.firstOrNull { (_, s) -> s.language?.lowercase()?.substringBefore('-')?.let { it in wanted } == true } ?: texts.firstOrNull())?.index
        sidecars.forEachIndexed { i, s ->
            MPVLib.command(arrayOf("sub-add", s.uri, if (i == shown) "select" else "auto", s.label, s.language ?: ""))
        }
        // 外挂音轨: offered beside the file's own sound, which stays the one playing ("auto") — unless the film
        // came here for one of them (a dub Media3 does not play), which plays at once.
        audios.forEachIndexed { i, a ->
            MPVLib.command(arrayOf("audio-add", a.uri, if (i == audioChosen) "select" else "auto", a.label, a.language ?: ""))
        }
    }

    /** The 外挂音轨 of the next file [setup] opens ([setExternalAudio]), and of the one open now — with the one to play. */
    @Volatile private var nextAudios: List<SidecarAudio> = emptyList()
    @Volatile private var nextChosen: Int? = null
    @Volatile private var audios: List<SidecarAudio> = emptyList()
    @Volatile private var audioChosen: Int? = null

    /** mpv's track id for each dub ([audios]' index → aid), as its track list says. */
    @Volatile private var dubAids: Map<Int, Int> = emptyMap()

    override fun setExternalAudio(audios: List<SidecarAudio>) = setExternalAudio(audios, null)

    /** [setExternalAudio], with the dub [chosen] (its index) to play rather than the film's own sound. */
    fun setExternalAudio(audios: List<SidecarAudio>, chosen: Int?) {
        nextAudios = audios
        nextChosen = chosen
    }

    /** Whether mpv draws the subtitles itself — a picture subtitle, or ASS, is chosen. */
    @Volatile private var subtitlesDrawnByMpv = false
    private val _subtitlesInPicture = MutableStateFlow(false)
    override val subtitlesInPicture: StateFlow<Boolean> = _subtitlesInPicture.asStateFlow()

    /**
     * Whether the file's tracks are all in — its own, and the share's subtitle files beside it, added
     * once it has loaded: until then none are told, or the player would pick the reader's subtitles
     * from a list without the Chinese .ass that comes a moment later.
     */
    @Volatile private var tracksComplete = false

    /** mpv's track list as the player's tracks: ids "mpv-a:<n>" and "mpv-s:<n>". */
    private fun readTracks() {
        if (!tracksComplete) return
        val n = MPVLib.getPropertyInt("track-list/count") ?: return
        val audio = mutableListOf<MediaTrack>()
        val subs = mutableListOf<MediaTrack>()
        var drawnByMpv = false
        val dubs = mutableMapOf<Int, Int>()
        for (i in 0 until n) {
            fun s(p: String) = MPVLib.getPropertyString("track-list/$i/$p")?.takeIf { it.isNotBlank() }
            val type = s("type") ?: continue
            val id = MPVLib.getPropertyInt("track-list/$i/id") ?: continue
            val lang = s("lang")
            val title = s("title")
            val rawCodec = s("codec")
            val profile = s("codec-profile")
            val codec = rawCodec?.let { c ->
                when {
                    // What the sound is as people know it: a Blu-ray's DTS-HD MA, a TrueHD's Atmos.
                    profile?.contains("Atmos") == true -> "Dolby Atmos"
                    c == "dts" && profile != null && profile.startsWith("DTS-") -> profile
                    else -> CODEC_NAMES[c] ?: if (c.startsWith("pcm_")) "PCM" else c.uppercase(Locale.US)
                }
            }
            val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") == true
            val external = MPVLib.getPropertyBoolean("track-list/$i/external") == true
            when (type) {
                "audio" -> {
                    val channels = MPVLib.getPropertyInt("track-list/$i/demux-channel-count") ?: 0
                    val rate = MPVLib.getPropertyInt("track-list/$i/demux-samplerate")?.takeIf { it > 0 }?.let { hz ->
                        com.therealityteam.videoplayer.ui.common.kiloHertzText(hz)
                    }
                    val name = (if (external) title else TrackTitle.named(title)) ?: ExoPlaybackManager.languageName(lang, words) ?: words.track(id)
                    // A dub added from [audios] (by its title, in order) is known by its place there, as Media3 knows it.
                    val dub = if (external) audios.indices.firstOrNull { it !in dubs && audios[it].label == title } else null
                    if (dub != null) dubs[dub] = id
                    audio += MediaTrack(
                        id = dub?.let(ExternalAudio::trackId) ?: "$AUDIO_ID$id", label = name, language = lang, channels = channels, selected = selected,
                        // As Media3's rows read: (外挂), codec, channels, sample rate.
                        name = name,
                        detail = listOfNotNull(words.external.takeIf { external }, codec, ExoPlaybackManager.channelLabel(channels, words), rate).joinToString(" · "),
                        external = external,
                    )
                }
                "sub" -> {
                    if (selected && (rawCodec in PICTURE_SUBTITLES || (typeset && rawCodec in TYPESET_SUBTITLES))) drawnByMpv = true
                    val name = (if (external) title else TrackTitle.named(title)) ?: ExoPlaybackManager.languageName(lang, words) ?: words.track(id)
                    // A share's file is known by its languages as the app read them (chs&jpn), as Media3's are.
                    val language = if (external) sidecars.firstOrNull { it.label == title }?.tag ?: lang else lang
                    subs += MediaTrack(
                        id = "$SUB_ID$id", label = name, language = language, selected = selected, name = name,
                        detail = listOfNotNull(if (external) words.external else words.embedded, codec).joinToString(" · "),
                        external = external,
                        typeset = rawCodec in TYPESET_SUBTITLES,
                    )
                }
            }
        }
        if (audio.map { it.id to it.selected } != _audioTracks.value.map { it.id to it.selected } || subs.map { it.id to it.selected } != _subtitleTracks.value.map { it.id to it.selected }) {
            android.util.Log.i(TAG, "tracks: sound ${audio.joinToString { "${if (it.selected) "▶" else ""}${it.name} (${it.detail})" }}; " +
                "subtitles ${subs.joinToString { "${if (it.selected) "▶" else ""}${it.name} (${it.detail})" }.ifEmpty { "none" }}")
        }
        dubAids = dubs
        _audioTracks.value = audio
        _subtitleTracks.value = subs
        if (drawnByMpv != subtitlesDrawnByMpv) {
            subtitlesDrawnByMpv = drawnByMpv
            _subtitlesInPicture.value = drawnByMpv
            if (drawnByMpv) _activeCues.value = emptyList()
            MPVLib.setPropertyString("sub-visibility", if (drawnByMpv) "yes" else "no")
        }
    }

    private companion object {
        /**
         * The hardware decoders mpv tries, in order: the decoder's pictures straight to the GPU through an
         * AImageReader (mpv 0.41's hwdec_aimagereader) — on the B3110 an 8K30 HEVC in an MPEG-PS played at
         * 30 fps with none dropped and opened in 0.46 s, against 25.6 fps, 22 dropped in 5 s and 0.86 s copying
         * every picture through memory, which stays the fallback (and HDR's: see "video-params/gamma").
         */
        const val HWDEC = "mediacodec,mediacodec-copy"
        val HDR_GAMMAS = setOf("pq", "hlg")

        /** How often AuraPerf's "frames" line is written while a film plays. */
        const val FRAMES_EVERY_MS = 5_000L

        const val TAG = "AuraMpv"
        /** 夜间模式: the loud parts brought down, the quiet ones up. */
        const val NIGHT_AF = "lavfi=[acompressor=threshold=-21dB:ratio=4:attack=5:release=60:makeup=2]"
        /** How long the sound may take to start before the sound field is given up for this track. */
        const val SOUND_FIELD_WAIT_MS = 12_000L
        /** How often the head is looked at for the sound field, and how far it turns before it is heard, radians. */
        const val TURN_TICK_MS = 50L
        const val TURN_STEP = 0.01
        /** The longest a Surface hand-back waits for mpv's output to let it go. */
        const val DETACH_WAIT_MS = 500L
        /** …and the longest the main thread waits for that to be done. */
        const val HANDBACK_WAIT_MS = 800L
        /** An end this far before the file's length, soon after the stream broke, is the stream's, not the film's. */
        const val EARLY_END_MS = 30_000L
        const val NETWORK_TROUBLE_MS = 180_000L
        const val STREAM_BROKEN_MS = 60_000L
        /** What FFmpeg says when the share's stream breaks. */
        /** FFmpeg's word for a server's refusal: "HTTP error 502 Bad Gateway". */
        val HTTP_ERROR = Regex("""HTTP error (\d{3})""")

        val NETWORK_WORDS = listOf("ends prematurely", "Connection refused", "Connection reset", "timed out", "Network is unreachable", "Will reconnect")
        /**
         * Lines with a network word that are not the network's: the video output waiting on its decoder ("Waiting for
         * frame timed out!") — said at every film's end under keep-open, 0.1 s after eof-reached, which made the end
         * of each mpv film (ISO, RMVB, Hi10P…) a lost server and reopened it at its end 6 s later.
         */
        val NOT_NETWORK = listOf("Waiting for frame")
        /** How long an opened file may show no picture before its decoder is changed. */
        const val NO_PICTURE_MS = 4_000L
        /** A wait that ends within this of a broken read ended because FFmpeg gave up, not because data came. */
        const val GAVE_UP_GAP_MS = 2_000L
        const val AUDIO_ID = "mpv-a:"
        const val SUB_ID = "mpv-s:"
        const val START_TRACKS_MAX = 300

        /** FFmpeg's video codec names as people know them, for the info panel. */
        val VIDEO_NAMES = mapOf(
            "h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1", "vp9" to "VP9", "vp8" to "VP8",
            "mpeg1video" to "MPEG-1", "mpeg2video" to "MPEG-2", "mpeg4" to "MPEG-4", "msmpeg4v3" to "DivX 3",
            "msmpeg4v2" to "MS-MPEG4 v2", "h263" to "H.263", "vc1" to "VC-1", "wmv1" to "WMV7", "wmv2" to "WMV8",
            "wmv3" to "WMV9", "rv10" to "RealVideo 1", "rv20" to "RealVideo 2", "rv30" to "RealVideo 3",
            "rv40" to "RealVideo 4", "flv1" to "Sorenson Spark", "vp6f" to "VP6", "vp6" to "VP6",
            "svq1" to "Sorenson 1", "svq3" to "Sorenson 3", "cinepak" to "Cinepak", "theora" to "Theora",
            "mjpeg" to "Motion JPEG", "prores" to "ProRes",
        )

        /** Subtitles that are pictures, not text: a Blu-ray's, a DVD's, a broadcast's. */
        val PICTURE_SUBTITLES = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")

        /** Subtitles set as their author typeset them — positions, fonts, karaoke: libass draws them. */
        val TYPESET_SUBTITLES = setOf("ass", "ssa")

        /** FFmpeg's codec names as the track menus name them for Media3's tracks (ExoPlaybackManager.codecLabel). */
        val CODEC_NAMES = mapOf(
            "aac" to "AAC", "ac3" to "Dolby AC-3", "eac3" to "Dolby E-AC-3", "truehd" to "Dolby TrueHD",
            "mlp" to "MLP", "dts" to "DTS", "mp3" to "MP3", "mp2" to "MP2", "flac" to "FLAC", "opus" to "Opus",
            "vorbis" to "Vorbis", "cook" to "RealAudio", "sipr" to "RealAudio", "wmav1" to "WMA", "wmav2" to "WMA",
            "wmapro" to "WMA Pro", "alac" to "ALAC",
            "subrip" to "SRT", "ass" to "ASS", "ssa" to "ASS", "webvtt" to "VTT", "mov_text" to "TX3G",
            "hdmv_pgs_subtitle" to "PGS", "dvd_subtitle" to "VobSub", "dvb_subtitle" to "DVB", "xsub" to "XSUB",
        )
    }
}
