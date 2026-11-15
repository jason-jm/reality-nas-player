package com.therealityteam.videoplayer.data.playback

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The player's engine: Media3 ([exo]) for every file it can read — nearly all of them, with the
 * hardware decoders and everything the app builds on it — and mpv for the rest (EngineRoute,
 * docs/legacy-formats.md). A container Media3 has no reader for opens in mpv from the start; a file
 * Media3 turns out unable to play — an XviD AVI, AC-3 in an AVI, an MKV's MPEG-2 picture — is handed
 * to mpv where it stopped, and the viewer is shown no failure. So is an H.264 no decoder here takes
 * (anime's Hi10P, EngineRoute.softwareOnly), which mpv then decodes in software from the start.
 *
 * mpv is made, and its libraries loaded, for the first file that needs it and not before: a session
 * of ordinary files is Media3 alone, exactly as it was. What the viewer set — volume, speed, sound —
 * carries across a hand-over. The picture's Surface ([setVideoSurface]) is held here and given to
 * whichever engine plays, taken back from the other first: a Surface has one producer at a time.
 * So is the system's media session — a headset's keys, the now playing — given to mpv's state
 * ([MpvSessionPlayer]) while mpv plays.
 *
 * Main thread only, as both engines are.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingEngine(context: Context, val exo: ExoPlaybackManager) : PlayerEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mpvEngine: MpvPlaybackManager? = null
    /** The media session's view of mpv, made with it. */
    private var mpvSession: MpvSessionPlayer? = null
    private val active = MutableStateFlow<PlayerEngine>(exo)

    /**
     * The folders whose files went to mpv for what they are made of — a sound track Media3 drops, a
     * picture it cannot decode. A folder is one release, encoded one way: its next episode opens in
     * mpv at once instead of a second or two of Media3 first (silent, or black). Not a damaged file:
     * that is the file's own trouble, not its folder's.
     */
    private val mpvFolders = appContext.getSharedPreferences("aura_engine_routes", Context.MODE_PRIVATE)

    /**
     * The library's own path of the file about to be set up (`smb://…/Show/01.mkv`), set by the player
     * before [setup]: what the engine is given is often the share's loopback URL, one per file, whose
     * "folder" is no folder at all.
     */
    var libraryPath: String? = null

    private fun folderOf(path: String) = (libraryPath ?: path).substringBeforeLast('/', "")

    private fun rememberFolder(path: String, why: String) {
        val folder = folderOf(path).takeIf { it.isNotEmpty() } ?: return
        runCatching {
            val all = mpvFolders.all
            val e = mpvFolders.edit()
            // A few hundred releases at most; the oldest go first.
            if (all.size >= MPV_FOLDERS_MAX) all.entries.sortedBy { (it.value as? Long) ?: 0L }.take(all.size - MPV_FOLDERS_MAX + 1).forEach { e.remove(it.key) }
            e.putLong(folder, System.currentTimeMillis()).apply()
        }
        android.util.Log.i(TAG, "its folder opens in mpv from now on: $why")
    }

    /** Whether the file now playing is in mpv — for the info panel, and for the tests. */
    val usingMpv: StateFlow<Boolean> = active.map { it !== exo }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Whether mpv is playing and its stream broke a moment ago (it may be waiting between reconnects). */
    val mpvStreamBroken: Boolean get() = active.value !== exo && mpvEngine?.streamBroken == true

    /** The engine playing this instant ("exo", "mpv") — what a track id belongs to: mpv's are not Media3's. */
    val engineName: String get() = if (active.value === exo) "exo" else "mpv"

    /** Invoked when the current file plays to its end, whichever engine played it. */
    var onCompleted: (() -> Unit)? = null

    private fun <T> follow(initial: T, pick: (PlayerEngine) -> Flow<T>): StateFlow<T> =
        active.flatMapLatest(pick).stateIn(scope, SharingStarted.Eagerly, initial)

    override val state = follow(exo.state.value) { it.state }
    override val duration = follow(0L) { it.duration }
    override val position = follow(0L) { it.position }
    override val hasFirstFrame = follow(false) { it.hasFirstFrame }
    override val audioTracks = follow(emptyList()) { it.audioTracks }
    override val subtitleTracks = follow(emptyList()) { it.subtitleTracks }
    override val activeCues = follow(emptyList()) { it.activeCues }
    override val videoUnsupportedLabel = follow<String?>(null) { it.videoUnsupportedLabel }
    override val failure = follow<PlaybackFailure?>(null) { it.failure }
    override val videoAspect = follow(0f) { it.videoAspect }
    override val dataRate = follow<DataRate?>(null) { it.dataRate }
    override val decodeBehind = follow<String?>(null) { it.decodeBehind }
    override val sourceDown = follow(false) { it.sourceDown }
    override val subtitlesInPicture = follow(false) { it.subtitlesInPicture }
    override val failedFormat: String? get() = active.value.failedFormat
    override val oversize: Oversize? get() = active.value.oversize
    override val supportsFd: Boolean get() = active.value.supportsFd

    // What the viewer set, for an engine taken up in the middle of a session.
    private var surface: Surface? = null
    private var volume = 1f
    private var boost = 1f
    private var night = false
    private var audioDelayMs = 0L
    private var ambisonic: AmbisonicAudio? = null
    private var speed = 1f
    private var loop = false
    private var subtitleScale = 1f
    private var typeset = true

    /** Subtitle delay in µs. Media3 reads this when parsing cues. mpv applies it live. */
    var subtitleDelayUs: Long
        get() = exo.subtitleDelayUs
        set(v) {
            exo.subtitleDelayUs = v
            mpvEngine?.setSubtitleDelay(v)
        }

    fun applySubtitleDelay(delayUs: Long) {
        if (delayUs == subtitleDelayUs) return
        subtitleDelayUs = delayUs
        if (active.value === exo) exo.refreshSubtitleTiming()
    }

    private data class Opened(val path: String, val startMs: Long, val sidecars: List<SidecarSubtitle>)
    /** The file last opened, for a hand-over — and whether mpv was tried on it already. */
    private var opened: Opened? = null
    private var handedOver = false

    /** When the file now opening was handed over — the AuraPerf first-picture line counts from it, whichever engine shows it. */
    private var setupAt = 0L

    init {
        scope.launch {
            hasFirstFrame.collect { up ->
                if (!up || setupAt == 0L) return@collect
                android.util.Log.i("AuraPerf", "first picture ${android.os.SystemClock.elapsedRealtime() - setupAt}ms after setup ($engineName)")
                setupAt = 0L
            }
        }
        exo.onCompleted = { onCompleted?.invoke() }
        exo.onUnplayable = { f ->
            // An old codec no decoder here takes (XviD, MPEG-2, VC-1): the release's other files are made
            // the same way, so they open in mpv at once. A damaged file is its own trouble, not its folder's.
            val codec = exo.failedFormat
            val why = "Media3 failed: $f" + (codec?.let { " ($it)" } ?: "")
            (EngineRoute.handsOver(f) && handOver(why)).also { moved ->
                if (moved && f == PlaybackFailure.UNSUPPORTED && codec != null) opened?.path?.let { rememberFolder(it, why) }
            }
        }
        exo.onPictureless = { mime, codecs ->
            val software = EngineRoute.softwareOnly(codecs)
            val why = if (software) "no decoder here takes $codecs: FFmpeg's, in software" else "no picture from ${mime ?: "a video read as sound only"}"
            (EngineRoute.handsOverPicture(mime, codecs) && handOver(why, exo.positionNow(), software)).also { moved ->
                if (moved && software) opened?.path?.let { rememberFolder(it, why) }
            }
        }
        // A decoder that took the film and gave nothing back (the emulator's HEVC decoder on an MV-HEVC
        // spatial video's second layer): FFmpeg's, in software — the hardware one already failed.
        exo.onDecoderStalled = { mime -> handOver("the decoder for $mime gave no picture", exo.positionNow(), software = true) }
        // A 外挂音轨 Media3 does not play (a raw DTS, an AC-3 it cannot seek in) chosen: the film goes on in mpv with it.
        exo.onDubNeedsMpv = { index -> handOver("外挂音轨 ${externalAudio.getOrNull(index)?.label} is mpv's", exo.positionNow(), dub = index) }
        // Silent, with a sound track in the file Media3 dropped (an old codec id — FLsnow's
        // A_AAC/MPEG4/LC/SBR): the file goes on in mpv, which reads them all, where it had got to.
        exo.onSoundless = { uri, noSound, noText ->
            scope.launch(Dispatchers.IO) {
                val source = when {
                    uri == null -> null
                    uri.startsWith("http") -> HttpByteSource(uri)
                    else -> java.io.File(uri.removePrefix("file://")).takeIf { it.isFile }?.let(::FileByteSource)
                } ?: return@launch
                val mainLost = !noSound && MatroskaTracks.mainSoundDropped(source)
                val dropped = MatroskaTracks.droppedByMedia3(source, sound = noSound || mainLost, text = noText)
                if (dropped.isEmpty()) return@launch
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val why = "no reader for its tracks: ${dropped.joinToString()}"
                    if (handOver(why, exo.positionNow())) opened?.path?.let { rememberFolder(it, why) }
                }
            }
        }
    }

    /** Whether a decoder on this device says it takes H.264 High 10 — none on a headset, so far. */
    private val decodesHigh10: Boolean by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } &&
                    info.getCapabilitiesForType("video/avc").profileLevels.any {
                        it.profile == android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                    }
            }
        }.getOrDefault(false).also { android.util.Log.i(TAG, "H.264 High 10 in hardware: $it") }
    }

    /** libmpv is in this APK (app/natives/build-mpv.sh); without it, Media3 is all there is. */
    private val mpvPackaged: Boolean by lazy {
        mpvPackaged(appContext).also { if (!it) android.util.Log.w(TAG, "libmpv is not packaged: Media3 only") }
    }

    private fun mpv(): MpvPlaybackManager = mpvEngine ?: MpvPlaybackManager(appContext).also { m ->
        m.onCompleted = { onCompleted?.invoke() }
        mpvEngine = m
        val session = MpvSessionPlayer(m, exo.player.seekBackIncrement, exo.player.seekForwardIncrement)
        mpvSession = session
        // Playing or paused, the file's length: the session reads mpv again when they change.
        scope.launch { combine(m.state, m.duration) { st, d -> st to d }.collect { session.refresh() } }
    }

    override fun setup(path: String, startPositionMs: Long, sidecars: List<SidecarSubtitle>) {
        // mpv's files never reach exo.setup: the session the player's close let go is made again here.
        exo.ensureSession()
        setupAt = android.os.SystemClock.elapsedRealtime()
        opened = Opened(path, startPositionMs, sidecars)
        handedOver = false
        if (EngineRoute.mpvFirst(path) && mpvPackaged) {
            handedOver = true
            android.util.Log.i(TAG, "mpv for ${path.substringAfterLast('/')}: Media3 has no reader for it")
            playInMpv(path, startPositionMs, sidecars)
        } else if (mpvPackaged && mpvFolders.contains(folderOf(path))) {
            handedOver = true
            android.util.Log.i(TAG, "mpv for ${path.substringAfterLast('/')}: its folder's files need it")
            playInMpv(path, startPositionMs, sidecars)
        } else if (EngineRoute.namedLegacy(libraryPath ?: path) && mpvPackaged) {
            handedOver = true
            android.util.Log.i(TAG, "mpv for ${path.substringAfterLast('/')}: named XviD / DivX, which no decoder here takes")
            playInMpv(path, startPositionMs, sidecars)
        } else if (EngineRoute.namedSoftwareOnly(path) && !decodesHigh10 && mpvPackaged) {
            // Its name says Hi10P and no decoder here takes that: straight to FFmpeg, rather than after
            // Media3 has opened it and read its tracks to find so (onPictureless) — a second sooner.
            handedOver = true
            android.util.Log.i(TAG, "mpv for ${path.substringAfterLast('/')}: named 10-bit H.264, decoded in software")
            playInMpv(path, startPositionMs, sidecars, software = true)
        } else {
            // A stream Media3 has no source for (DASH, RTSP — a module this build leaves out) is refused
            // as it is set up, by a throw: it goes on in mpv rather than taking the app down with it.
            val refused = runCatching { useExo().setup(path, startPositionMs, sidecars) }.exceptionOrNull()
            if (refused != null) {
                android.util.Log.w(TAG, "Media3 refused ${path.substringAfterLast('/').take(80)}: ${refused.cause?.javaClass?.simpleName ?: refused.javaClass.simpleName}")
                if (!handOver("Media3 has no source for it", startPositionMs)) throw refused
            }
        }
    }

    /**
     * Media3 could not play the file: it goes on in mpv, from [atMs] (where Media3 had got to) — its
     * picture decoded in [software] from the start when no hardware decoder takes it.
     */
    /**
     * The sound tracks of the file now in Media3 that its reader dropped — an End of Evangelion's two
     * AAC tracks beside the Vorbis one it plays — read off the file's own track list; empty in mpv,
     * which reads them all, or for a file that is no MKV.
     */
    suspend fun droppedSoundTracks(): List<String> {
        if (usingMpv.value || !mpvPackaged) return emptyList()
        val uri = exo.currentUri() ?: return emptyList()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val source = when {
                uri.startsWith("http") -> HttpByteSource(uri)
                else -> java.io.File(uri.removePrefix("file://")).takeIf { it.isFile }?.let(::FileByteSource)
            } ?: return@withContext emptyList()
            MatroskaTracks.droppedByMedia3(source, sound = true, text = false)
        }
    }

    /** The file goes on in mpv where it is, for the sound tracks Media3 could not offer ([droppedSoundTracks]). */
    fun playDroppedSoundInMpv(): Boolean = handOver("asked for the sound tracks Media3 dropped", exo.positionNow())

    /**
     * The subtitle files of pictures beside the file now in Media3 — a release's VobSub (.idx + .sub),
     * a Blu-ray's .sup — which Media3 is not handed; empty in mpv, which lists them with the rest.
     */
    fun pictureSidecars(): List<SidecarSubtitle> =
        if (usingMpv.value || !mpvPackaged) emptyList()
        else opened?.sidecars.orEmpty().filter { com.therealityteam.videoplayer.data.subtitle.SubtitleEngine.isPicture(it.mimeType) }

    /**
     * The file goes on in mpv where it is, for [sidecar]: the picture subtitles Media3 could not draw.
     * Its folder is remembered, so the film opens in mpv from now on, the subtitles picked there kept.
     */
    fun playPictureSubtitlesInMpv(sidecar: SidecarSubtitle): Boolean {
        val why = "asked for the picture subtitles ${sidecar.label}"
        return handOver(why, exo.positionNow()).also { moved -> if (moved) opened?.path?.let { rememberFolder(it, why) } }
    }

    private fun handOver(why: String, atMs: Long? = null, software: Boolean = false, dub: Int? = null): Boolean {
        val o = opened ?: return false
        if (handedOver || !mpvPackaged) return false
        handedOver = true
        val from = atMs?.takeIf { it > 0 } ?: o.startMs
        android.util.Log.i(TAG, "mpv for ${o.path.substringAfterLast('/')} from ${from}ms: $why")
        // Out of Media3's own callback before its player is stopped under it.
        scope.launch(Dispatchers.Main) { playInMpv(o.path, from, o.sidecars, software, dub) }
        return true
    }

    /** [path] in mpv from [startMs] — and in the media session, as what is playing. */
    private fun playInMpv(path: String, startMs: Long, sidecars: List<SidecarSubtitle>, software: Boolean = false, dub: Int? = null) {
        val m = mpv()
        mpvSession?.opened(path, exo.nowPlaying)
        useMpv()
        if (dub != null) m.setExternalAudio(externalAudio, dub)
        m.setup(path, startMs, sidecars, software)
    }

    private fun useMpv(): MpvPlaybackManager {
        val m = mpv()
        if (active.value !== m) {
            exo.player.stop()
            exo.setVideoSurface(null)
            m.attachSurface(surface)
            carry(m)
            active.value = m
            exo.driveSessionWith(mpvSession)
        }
        return m
    }

    private fun useExo(): ExoPlaybackManager {
        if (active.value !== exo) {
            mpvEngine?.release()
            exo.setVideoSurface(surface)
            carry(exo)
            active.value = exo
            exo.driveSessionWith(null)
        }
        return exo
    }

    /** What the viewer set, onto an engine taken up mid-session. */
    private fun carry(e: PlayerEngine) {
        e.setVolume(volume)
        e.setAudioBoost(boost)
        e.setNightMode(night)
        e.setAudioDelay(audioDelayMs)
        e.setSpeed(speed)
        e.setLoop(loop)
        e.setSubtitleScale(subtitleScale)
        e.setTypesetSubtitles(typeset)
        if (e is MpvPlaybackManager) {
            e.setSubtitleDelay(exo.subtitleDelayUs)
            e.setAmbisonic(ambisonic)
            e.setExternalAudio(externalAudio)
        }
    }

    /** The render target: the panel's Surface, a place's screen, the sphere — or none. */
    fun setVideoSurface(target: Surface?) {
        surface = target
        when (val e = active.value) {
            exo -> exo.setVideoSurface(target)
            is MpvPlaybackManager -> e.attachSurface(target)
        }
    }

    override fun setupFd(fd: Int, length: Long, startPositionMs: Long) {
        useExo().setupFd(fd, length, startPositionMs)
    }

    override fun mediaInfo(): MediaInfo? = active.value.mediaInfo()
    override fun currentUri(): String? = active.value.currentUri()
    override fun videoPixels(): Pair<Int, Int>? = active.value.videoPixels()
    override suspend fun captureFrame(into: java.io.File): Boolean = active.value.captureFrame(into)
    override fun selectAudioTrack(id: String) = active.value.selectAudioTrack(id)
    override fun stepFrame(forward: Boolean) = active.value.stepFrame(forward)
    override fun fail(failure: PlaybackFailure) {
        opened = null
        useExo().fail(failure)
    }
    override fun selectSubtitleTrack(id: String?) = active.value.selectSubtitleTrack(id)
    override fun togglePlayPause() = active.value.togglePlayPause()
    override fun play() = active.value.play()
    override fun pause() = active.value.pause()
    override fun seekTo(ms: Long) {
        active.value.seekTo(ms)
        if (active.value !== exo) mpvSession?.seeked(ms)
    }
    override fun beginScrub() = active.value.beginScrub()
    override fun scrubTo(ms: Long) = active.value.scrubTo(ms)
    override fun endScrub(ms: Long) {
        active.value.endScrub(ms)
        if (active.value !== exo) mpvSession?.seeked(ms)
    }
    override fun refreshPosition() = active.value.refreshPosition()
    override fun positionNow(): Long = active.value.positionNow()

    override fun setVolume(v: Float) { volume = v; active.value.setVolume(v) }
    override fun setAudioBoost(factor: Float) { boost = factor; active.value.setAudioBoost(factor) }
    override fun setNightMode(on: Boolean) { night = on; active.value.setNightMode(on) }
    override fun setAudioDelay(ms: Long) { audioDelayMs = ms; active.value.setAudioDelay(ms) }
    override fun setSpeed(speed: Float) {
        this.speed = speed
        active.value.setSpeed(speed)
        if (active.value !== exo) mpvSession?.refresh()
    }
    override fun setLoop(loop: Boolean) { this.loop = loop; active.value.setLoop(loop) }
    override fun setSubtitleScale(scale: Float) { subtitleScale = scale; active.value.setSubtitleScale(scale) }
    override fun setTypesetSubtitles(on: Boolean) { typeset = on; active.value.setTypesetSubtitles(on) }
    override fun setSubtitleFonts(dir: String) = active.value.setSubtitleFonts(dir)
    override fun subtitleHeader(): String? = active.value.subtitleHeader()
    // The media session and the system's audio attributes are Media3's (mpv's session shows what
    // Media3 was told is playing).
    override fun setNowPlaying(title: String?, subtitle: String?, artworkPath: String?) = exo.setNowPlaying(title, subtitle, artworkPath)
    override fun setSpatialAudio(enabled: Boolean) = exo.setSpatialAudio(enabled)
    override fun setAmbisonic(audio: AmbisonicAudio?) {
        ambisonic = audio
        exo.setAmbisonic(audio)
        mpvEngine?.setAmbisonic(audio)
    }

    /** The 外挂音轨 of the file now opening: each engine is told before it opens it (a hand-over too). */
    private var externalAudio: List<SidecarAudio> = emptyList()

    override fun setExternalAudio(audios: List<SidecarAudio>) {
        externalAudio = audios
        exo.setExternalAudio(audios)
        mpvEngine?.setExternalAudio(audios)
    }

    override fun endSession() = exo.endSession()

    // Back to Media3 the way a film handed back from mpv goes (mpv stopped, its picture let go), then Media3 empty.
    override fun unload() {
        opened = null
        useExo().unload()
    }
    override fun opening(startMs: Long) = active.value.opening(startMs)

    override fun release() {
        mpvEngine?.release()
        exo.release()
        scope.cancel()
    }

    companion object {
        private const val TAG = "AuraEngine"

        /**
         * Whether libmpv is in this APK — found where System.loadLibrary would look, without loading
         * it: the libraries are read from inside the APK, not extracted to nativeLibraryDir.
         */
        fun mpvPackaged(context: Context): Boolean =
            (context.classLoader as? dalvik.system.BaseDexClassLoader)?.findLibrary("mpv") != null
    }
}

/** How many folders [SwitchingEngine] remembers as mpv's. */
private const val MPV_FOLDERS_MAX = 300
