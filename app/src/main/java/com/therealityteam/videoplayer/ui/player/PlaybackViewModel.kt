package com.therealityteam.videoplayer.ui.player

import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.STAGE_ID
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import com.therealityteam.videoplayer.player.Ambience
import com.therealityteam.videoplayer.player.LetterboxProbe
import com.therealityteam.videoplayer.data.subtitle.SubtitleTranscoder
import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.ControllerModel
import com.therealityteam.videoplayer.data.playback.Resume
import com.therealityteam.videoplayer.data.playback.indexAt
import com.therealityteam.videoplayer.data.playback.ChapterReader
import com.therealityteam.videoplayer.data.playback.ChapterStillPlan
import com.therealityteam.videoplayer.data.playback.Chapter
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import android.content.Context
import android.view.Surface
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SortAsUIElementComponent
import com.pico.spatial.core.ecs.resource.SurfaceRenderTexture
import com.pico.spatial.core.ecs.video.VideoDimensionMode
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.AspectFit
import com.therealityteam.videoplayer.data.playback.AspectMode
import com.therealityteam.videoplayer.data.playback.FrameRect
import com.therealityteam.videoplayer.data.playback.ExoPlaybackManager
import com.therealityteam.videoplayer.data.playback.SwitchingEngine
import com.therealityteam.videoplayer.data.playback.PlaybackState
import com.therealityteam.videoplayer.data.playback.LocalStreamServer
import com.therealityteam.videoplayer.data.playback.PlaybackManager
import com.therealityteam.videoplayer.data.playback.PlayerEngine
import com.therealityteam.videoplayer.data.playback.PlaylistMode
import com.therealityteam.videoplayer.data.playback.PlaylistNav
import com.therealityteam.videoplayer.data.playback.SleepMode
import com.therealityteam.videoplayer.data.playback.SmbFdBridge
import com.therealityteam.videoplayer.data.playback.TrackMemory
import com.therealityteam.videoplayer.data.repository.MediaRepository
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.data.repository.SourceRepository
import com.therealityteam.videoplayer.data.source.SmbSource
import com.therealityteam.videoplayer.data.subtitle.SubtitleEngine
import com.therealityteam.videoplayer.data.subtitle.SubtitleLanguage
import com.therealityteam.videoplayer.data.playback.TrackPreference
import com.therealityteam.videoplayer.player.Environment
import com.therealityteam.videoplayer.player.EnvironmentScene
import com.therealityteam.videoplayer.player.Environments
import com.therealityteam.videoplayer.player.ViewingSeat
import com.therealityteam.videoplayer.player.ScreenCaptions
import com.therealityteam.videoplayer.player.CinemaReflection
import com.therealityteam.videoplayer.player.ScreenPose
import com.therealityteam.videoplayer.player.VideoEntityAssembler
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import com.therealityteam.videoplayer.data.repository.SkipStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Session-scoped playback state holder. Owns the single [PlayerEngine], the planar
 * video panel + immersive sphere entities, and the current playback queue (prev/next).
 * Persists resume progress back to the library.
 */
class PlaybackViewModel(
    private val repo: MediaRepository,
    private val sourceRepo: SourceRepository,
    private val streamServer: LocalStreamServer,
    private val settingsRepo: SettingsRepository,
    private val fdBridge: SmbFdBridge,
    context: Context,
    /** 同步, when the user has it on: other devices' positions in, this headset's out. */
    private val sync: com.therealityteam.videoplayer.data.sync.HeadsetSync? = null,
    /** 书签: moments marked in a file, to come back to (更多 → 书签, and dots on the seek bar). */
    private val bookmarkStore: com.therealityteam.videoplayer.data.repository.BookmarkStore? = null,
    /** 跳过片头片尾: a show's opening and closing, set once, skipped on every episode after. */
    private val skipStore: SkipStore? = null,
    /** 整季沿用: the sound and subtitles picked in one episode, found again in the next. */
    private val trackPicks: com.therealityteam.videoplayer.data.repository.TrackPickStore? = null,
    /** What the databases tell of each episode — the names the bar, the queue and 接下来 show. */
    private val scrape: com.therealityteam.videoplayer.data.scrape.ScrapeEngine? = null,
    /** 去黑边: the black bars found in each file's frame, so the panel takes the picture's shape at once. */
    private val cropStore: com.therealityteam.videoplayer.data.repository.CropStore? = null,
    /** 下载到本机: a file with a whole copy on the headset plays from the copy, the NAS not needed. */
    private val downloads: com.therealityteam.videoplayer.data.download.DownloadStore? = null,
    /** Told while a film plays over the network, so a download running beside it steps aside. */
    private val onStreaming: ((Boolean) -> Unit)? = null,
) {

    /**
     * The engine is chosen once per session from settings: ExoPlayer (tracks, subtitles,
     * broad formats — the default) or the SDK's CypressMediaPlayer as a fallback switch.
     * Each engine pairs with its own render component, so the entity assembly below branches.
     */
    private val appContext = context.applicationContext

    // Media3 for every file it can read, mpv for the rest (SwitchingEngine, docs/legacy-formats.md).
    val manager: PlayerEngine =
        if (settingsRepo.get().useExoEngine) SwitchingEngine(context, ExoPlaybackManager(context)) else PlaybackManager()

    /**
     * The headset taken off, or its power button pressed: the film pauses where it was. It played on in
     * the dark — at about 0.6× speed, the spatial runtime idling, the sound from the speakers to nobody —
     * and was minutes further on, unseen, when the headset went back on. A countdown to the next episode
     * is called off with it. Not taken up again on waking: one press goes on from the same frame.
     *
     * Told by the display going dark, not by SCREEN_OFF: Android 14 holds that broadcast back from an app
     * it no longer counts as active — on the headset, the moment it sleeps — until it wakes again.
     */
    private val sleepWatch = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            if (power.isInteractive) return
            dropNextUp()
            if (manager.state.value == PlaybackState.PLAYING) {
                manager.pause()
                android.util.Log.i("AuraLife", "the headset slept: paused at ${manager.position.value / 1000} s")
            }
        }
    }

    init {
        (appContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.registerDisplayListener(sleepWatch, android.os.Handler(android.os.Looper.getMainLooper()))
        (manager as? SwitchingEngine)?.let { m ->
            m.onCompleted = { onItemCompleted() }
            // A headset's / controller's skip keys act on the queue, which lives here.
            m.exo.onSkipNext = { playNext() }
            m.exo.onSkipPrevious = { playPrev() }
            m.exo.hasNextItem = { hasNext() }
            m.exo.hasPreviousItem = { hasPrev() }
        }
    }

    // Source preparation (SMB directory listings, fd opens) is network IO, so starting an item
    // runs as a cancellable job on this scope: a quick prev/next tap cancels the slower one.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var startJob: Job? = null

    init {
        // The position is saved as it goes, not only when the player is closed the polite way: a
        // headset taken off and left, or the process reclaimed, used to lose the whole sitting —
        // and 续看 then had nothing to offer. Every PROGRESS_SAVE_MS while playing, and on pause.
        scope.launch {
            var ticks = 0
            while (true) {
                kotlinx.coroutines.delay(PROGRESS_SAVE_MS)
                if (manager.state.value == PlaybackState.PLAYING) {
                    saveProgress()
                    // 继续观看's picture of where it stopped: soon after it starts, then once a minute.
                    if (ticks++ % RESUME_FRAME_TICKS == 0) captureResumeFrame()
                    // A long sitting reaches the other devices every ten minutes, not only at its end.
                    sync?.nudge(minGapMs = 10 * 60_000)
                }
            }
        }
        // A film playing from the share or a server shares its Wi-Fi with any download under way; the
        // download is slowed for as long as it plays, so the film never waits for a copy of another.
        if (onStreaming != null) scope.launch {
            kotlinx.coroutines.flow.combine(manager.state, _current) { state, item ->
                state == PlaybackState.PLAYING && item != null && item.path.contains("://") && !item.path.startsWith("file://") &&
                    downloads?.localCopy(item.id) == null
            }.distinctUntilChanged().collect { onStreaming.invoke(it) }
        }
        scope.launch {
            manager.state.collect {
                if (it == PlaybackState.PAUSED) {
                    saveProgress()
                    captureResumeFrame()
                    sync?.nudge(minGapMs = 15_000)
                    // Paused — by hand, by the sleep timer, by the headset coming off — while the next
                    // item counts down over the credits: nobody wants it started under a paused film.
                    // (A file's real end reads READY, not PAUSED, so its own countdown is untouched.)
                    dropNextUp()
                }
            }
        }
    }

    private var panel: Entity? = null
    private var sphere: Entity? = null
    // 在环境的屏幕上播放: the film on a place's own screen, on the stage (see routePicture), and the dark
    // face behind it of a screen the model does not have.
    private var screen: Entity? = null
    private var screenBacking: Entity? = null
    private val screenReflection = CinemaReflection()

    // ExoPlayer path: each shape owns a SurfaceRenderTexture (strong refs, per SDK docs);
    // the player's output surface is pointed at whichever entity is currently visible.
    private var panelTexture: SurfaceRenderTexture? = null
    private var sphereTexture: SurfaceRenderTexture? = null
    private var screenTexture: SurfaceRenderTexture? = null
    private var panelSurface: Surface? = null
    private var sphereSurface: Surface? = null
    private var screenSurface: Surface? = null

    // The shape each panel's mesh was last made for. MeshResource.createVideoPanel waits on the
    // engine (0.5–1 s a call on the emulator while a window opens), and the panel was asked for four
    // times as a film started — twice for each of two sizes; a mesh is made again only for a shape
    // it does not have.
    private var panelShape: PanelShape? = null
    private var screenShape: PanelShape? = null
    private var preparedMesh: Pair<PanelShape, MeshResource>? = null

    // Physical size of the planar panel, in meters. Set by the UI from the window's own Dp
    // (via PhysicalLengthConverter) so the video mesh fills the window plane exactly and the
    // window's glass base panel never shows beside it. Falls back to a sane 16:9 default.
    // The window plane the video lives on (meters). The actual mesh (panelW/panelH) is fitted
    // inside it per the aspect mode, so a non-16:9 film letterboxes instead of stretching.
    private var windowW = 1.30f
    private var windowH = 0.73f
    private var panelW = 1.30f
    private var panelH = 0.73f

    /**
     * The window plane as the player window last measured it, kept across launches: what the first
     * film's mesh is made for as it starts ([startSource]), before the window is up to say. Null
     * until a window has measured itself once.
     */
    private val lastWindow = appContext.getSharedPreferences("aura_player_window", Context.MODE_PRIVATE)
    private fun lastWindowSize(): Pair<Float, Float>? =
        lastWindow.getFloat("w", 0f).takeIf { it > 0f }?.let { w -> lastWindow.getFloat("h", 0f).takeIf { it > 0f }?.let { w to it } }

    /** The window plane [preparedMesh] was made for. */
    private var preparedWindow: Pair<Float, Float>? = null

    /**
     * Each file's picture shape as last played, by item id: the panel of a film played before is
     * made in its own shape as it starts, before the decoder has said — a second sooner, and with
     * no remaking once it does. A few hundred, the oldest let go.
     */
    private val aspects = appContext.getSharedPreferences("aura_player_aspects", Context.MODE_PRIVATE)

    /**
     * The picture's shape: the engine's once known, until then the shape this file had last time (0 for
     * none) — one eye's, for a frame that packs two full-size pictures (AspectFit.eyeAspect).
     */
    private fun aspectNow(): Float {
        val frame = manager.videoAspect.value.takeIf { it > 0f } ?: _current.value?.id?.let { aspects.getFloat(it, 0f) } ?: 0f
        val stereo = _current.value?.video?.stereo?.takeIf { _current.value?.video?.projection == ProjectionMode.FLAT }
        val eye = com.therealityteam.videoplayer.data.playback.AspectFit.eyeAspect(stereo, frame)
        // A right-eye-first frame is shown as its left eye's half (leftEyeCrop), which 自动 takes the shape of
        // from the frame's: given here so that the half comes out at the eye's own shape.
        val half = leftEyeCrop() ?: return eye
        return eye * (1f - half.top - half.bottom) / (1f - half.left - half.right)
    }

    /**
     * A flat film in stereo whose right eye's picture comes first (_RL, cross-eyed side by side; _BT): the
     * window's material gives the left eye the left (or top) half and cannot be told otherwise, which would
     * turn its depth inside out. It is shown flat instead — its left eye's half, as a panorama of the kind is.
     */
    private fun leftEyeCrop(): com.therealityteam.videoplayer.data.playback.FrameCrop? {
        val v = _current.value?.video ?: return null
        if (v.projection != ProjectionMode.FLAT || !v.rightFirst) return null
        return when (v.stereo) {
            StereoMode.SBS -> com.therealityteam.videoplayer.data.playback.FrameCrop(0.5f, 0f, 0f, 0f)
            StereoMode.OU -> com.therealityteam.videoplayer.data.playback.FrameCrop(0f, 0.5f, 0f, 0f)
            else -> null
        }
    }

    /** How the window's material splits the frame between the eyes: one picture for a right-eye-first one (leftEyeCrop). */
    private fun panelStereo(): StereoMode? = if (leftEyeCrop() != null) StereoMode.MONO else _current.value?.video?.stereo

    private fun rememberAspect(id: String, aspect: Float) {
        if (aspect <= 0f || aspects.getFloat(id, 0f) == aspect) return
        val e = aspects.edit()
        val all = aspects.all
        if (all.size >= ASPECTS_MAX) all.keys.take(all.size - ASPECTS_MAX + 1).forEach(e::remove)
        e.putFloat(id, aspect).apply()
    }

    // 画面比例 (Movist's aspect control). AUTO preserves the source aspect — the right default,
    // and identical to the old full-window behaviour for the common 16:9 case.
    private val _aspectMode = MutableStateFlow(AspectMode.AUTO)
    val aspectMode: StateFlow<AspectMode> = _aspectMode

    // 循环 / 随机: how the queue auto-advances when an item ends.
    private val _playlistMode = MutableStateFlow(PlaylistMode.SEQUENTIAL)
    val playlistMode: StateFlow<PlaylistMode> = _playlistMode
    fun setPlaylistMode(mode: PlaylistMode) { _playlistMode.value = mode }

    // 音量增强 (session-level, like speed): PCM gain above unity for quiet dialogue.
    private val _audioBoost = MutableStateFlow(1.0f)
    val audioBoost: StateFlow<Float> = _audioBoost
    fun setAudioBoost(factor: Float) {
        val f = factor.coerceIn(1.0f, 2.0f)
        _audioBoost.value = f
        manager.setAudioBoost(f)
    }

    // 夜间模式 (kept across films and sessions: a viewer at night stays at night): loud scenes
    // down, quiet lines up, a 5.1 mix's centre lifted — see Loudness.
    private val _nightMode = MutableStateFlow(settingsRepo.get().nightMode)
    val nightMode: StateFlow<Boolean> = _nightMode
    fun setNightMode(on: Boolean) {
        settingsRepo.update(settingsRepo.get().copy(nightMode = on))
    }

    init {
        // Followed rather than read once: 设置 → 播放 turns it on and off too, perhaps mid-film.
        scope.launch {
            settingsRepo.settings.map { it.nightMode }.distinctUntilChanged().collect { on ->
                _nightMode.value = on
                manager.setNightMode(on)
            }
        }
    }

    // 音画同步 (session-level, like speed): a signed offset the engine bakes into the PCM stream.
    private val _audioDelayMs = MutableStateFlow(0L)
    val audioDelayMs: StateFlow<Long> = _audioDelayMs
    fun setAudioDelay(ms: Long) {
        val v = ms.coerceIn(-PlayerAdjust.AUDIO_DELAY_LIMIT_MS, PlayerAdjust.AUDIO_DELAY_LIMIT_MS)
        _audioDelayMs.value = v
        manager.setAudioDelay(v)
        android.util.Log.i("AuraAV", "音画同步: sound ${if (v >= 0) "+" else ""}$v ms")
    }
    /** Nudge by [steps] tenths of a second; 0 resets — the same contract as the subtitle-delay control. */
    fun adjustAudioDelay(steps: Int) = setAudioDelay(
        if (steps == 0) 0L else PlayerAdjust.stepOffset(_audioDelayMs.value, steps, PlayerAdjust.AUDIO_DELAY_LIMIT_MS),
    )

    // 睡眠定时: a countdown that pauses playback (or stops at end of the current item).
    private val _sleepMode = MutableStateFlow(SleepMode.OFF)
    val sleepMode: StateFlow<SleepMode> = _sleepMode
    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs
    private var sleepJob: Job? = null

    /** Arm/disarm the sleep timer. Timed modes count wall-clock and pause at zero. */
    fun setSleepMode(mode: SleepMode) {
        sleepJob?.cancel()
        sleepJob = null
        _sleepMode.value = mode
        _sleepRemainingMs.value = null
        android.util.Log.i("AuraSleep", "睡眠定时: $mode")
        if (!mode.isTimed) return                    // OFF or 播放完当前 → no countdown
        sleepJob = scope.launch {
            var remaining = mode.minutes * 60_000L
            _sleepRemainingMs.value = remaining
            while (remaining > 0) {
                kotlinx.coroutines.delay(1_000)
                remaining -= 1_000
                _sleepRemainingMs.value = remaining
            }
            manager.pause()
            // A countdown to the next item outlives a pause on an ENDED film (the state never reads
            // PAUSED there), and would start the next episode for somebody who has gone to sleep.
            dropNextUp()
            _sleepMode.value = SleepMode.OFF
            _sleepRemainingMs.value = null
        }
    }

    private val _waitingLong = MutableStateFlow(false)
    /**
     * The film has waited on its data longer than [LOADING_SAY_AFTER_MS] — a seek's moment of it is
     * no news, and 加载中 waits for this. Timed here, not in the window: the window draws seldom while
     * a film plays (the system slows its frames), and a wait timed by its frames ended a second late,
     * so 加载中 came up just after the film had already gone on.
     */
    val waitingLong: StateFlow<Boolean> = _waitingLong

    private val _slowLink = MutableStateFlow<com.therealityteam.videoplayer.data.playback.DataRate?>(null)
    /**
     * This film has stopped for its data again and again, and each time it came slower than it plays
     * (the user's camera footage: 13-16 MB/s from the NAS, 25 played): no wait will fix it — 加载中
     * says so, and that a copy on the headset plays it smoothly.
     */
    val slowLink: StateFlow<com.therealityteam.videoplayer.data.playback.DataRate?> = _slowLink
    private var shortWaits = 0
    private var shortWaitsFor: String? = null

    init {
        scope.launch {
            manager.state.collectLatest { s ->
                _waitingLong.value = false
                if (s != PlaybackState.PREPARING) return@collectLatest
                kotlinx.coroutines.delay(LOADING_SAY_AFTER_MS)
                _waitingLong.value = true
                if (manager.hasFirstFrame.value) {
                    android.util.Log.i("AuraPerf", "加载中 shown: waiting on data over $LOADING_SAY_AFTER_MS ms")
                    val id = _current.value?.id
                    if (id != shortWaitsFor) { shortWaitsFor = id; shortWaits = 0; _slowLink.value = null }
                    // Every wait counts; the rate is the last one measured (Media3 knows it as a wait ends).
                    val rate = manager.dataRate.value
                    if (++shortWaits >= SLOW_LINK_AFTER && rate?.short == true && _slowLink.value == null) {
                        _slowLink.value = rate
                        android.util.Log.i("AuraPerf", "slow link told: in %.1f MB/s, plays %.1f MB/s".format(java.util.Locale.US, rate.inBytesPerSec / 1e6, rate.playBytesPerSec / 1e6))
                    }
                }
            }
        }
        // Re-fit the panel whenever the source's aspect becomes known (or changes on a new item) —
        // its mesh made off the main thread, as the window's first is: the aspect arrives a second
        // into a film, while the engine is still busy with it.
        scope.launch {
            manager.videoAspect.collect {
                _current.value?.id?.let { id -> rememberAspect(id, it) }
                // A cubemap's faces lie by its frame's shape (3×2, or a 6×1 / 1×6 strip): known now.
                val item = _current.value
                val p = _pano.value
                if (item != null && p != null && p.projection == ProjectionMode.CUBEMAP && p.frameWidth == 0 && it > 0f && kotlin.math.abs(p.frameAspect - it) > 0.01f) {
                    _pano.value = panoOf(item)
                    _pano.value?.let { layout -> prepareSphere(layout) }
                    if (sphere != null && _immersive.value) buildSphere()
                }
                if (panel == null) return@collect
                standInForShape()
                preparePanel(windowW, windowH)
                ensurePanel()
            }
        }
        // …and when the bars in its frame are found (去黑边), or subtitles go into or out of them, the same way.
        scope.launch {
            combine(_frameCrop, manager.subtitlesInPicture, manager.subtitleTracks) { crop, embedded, tracks ->
                crop to (embedded || tracks.any { it.selected && it.bitmapSubtitle })
            }.distinctUntilChanged().collect {
                if (panel == null) return@collect
                preparePanel(windowW, windowH)
                ensurePanel()
            }
        }
    }

    // Remembered tracks: once a file's tracks are known, re-select what it was last watched
    // with. One guard per kind, reset per item, so a manual pick isn't undone and a replay of
    // the same file is re-applied — and per engine: a file Media3 hands to mpv as it opens (Hi10P)
    // has its tracks again under mpv's ids, and the picks made on Media3's are to be made again.
    private var audioAppliedFor: String? = null
    private var subtitleAppliedFor: String? = null
    private fun tracksKey(item: MediaItem): String = item.id + "@" + ((manager as? SwitchingEngine)?.engineName ?: "")

    init {
        scope.launch {
            manager.audioTracks.collect { tracks ->
                val item = _current.value ?: return@collect
                if (audioAppliedFor == tracksKey(item)) return@collect
                val saved = repo.get(item.id)?.audioTrackId ?: item.audioTrackId
                // The file's own pick first; a file with none takes the one made earlier in its show.
                val id = TrackMemory.audioToApply(saved, tracks)
                    ?: showPicks(item)?.audio?.let { pick ->
                        com.therealityteam.videoplayer.data.playback.TrackPicks.find(pick, tracks, item.fileName)
                            ?.also { android.util.Log.i("AuraTracks", "show's sound in ${item.fileName}: ${tracks.first { t -> t.id == it }.name}") }
                    }
                    // …and a file of a show never picked for takes the reader's 首选音轨.
                    ?: com.therealityteam.videoplayer.data.playback.TrackPreference.audio(settingsRepo.get().audioChoice, tracks)
                        ?.also { android.util.Log.i("AuraTracks", "preferred sound in ${item.fileName}: ${tracks.first { t -> t.id == it }.name}") }
                    // …and 默认 a film's own language over a dub the file opens on (a MULTi release's French).
                    ?: com.therealityteam.videoplayer.data.playback.TrackPreference
                        .takeIf { settingsRepo.get().audioChoice == com.therealityteam.videoplayer.data.playback.AudioChoice.DEFAULT }
                        ?.originalSound(tracks, item.originalTitle)
                        ?.also { android.util.Log.i("AuraTracks", "original sound in ${item.fileName}: ${tracks.first { t -> t.id == it }.name}") }
                id?.let {
                    manager.selectAudioTrack(it)
                    audioAppliedFor = tracksKey(item)
                }
            }
        }
        scope.launch {
            manager.subtitleTracks.collect { tracks ->
                val item = _current.value ?: return@collect
                // 还有 N 个图形字幕 was chosen: in mpv now, one of that file's pictures — in the reader's
                // language when it has it — is the pick, and is kept as this file's.
                pictureWanted?.let { label ->
                    val pictures = tracks.filter { it.external && it.name == label }
                    if (pictures.isEmpty()) return@let
                    pictureWanted = null
                    val r = com.therealityteam.videoplayer.data.playback.TrackPreference.subtitle(settingsRepo.get().subtitleChoice, pictures, videoFileName = item.fileName)
                    selectSubtitleTrack((r as? TrackMemory.Subtitle.Select)?.id ?: pictures.first().id)
                    return@collect
                }
                if (subtitleAppliedFor == tracksKey(item)) return@collect
                val saved = repo.get(item.id)?.subtitleTrackId ?: item.subtitleTrackId
                when (val r = TrackMemory.subtitleToApply(saved, tracks) ?: showSubtitle(item, tracks) ?: preferredSubtitle(item, tracks)) {
                    TrackMemory.Subtitle.Off -> { manager.selectSubtitleTrack(null); subtitleAppliedFor = tracksKey(item) }
                    is TrackMemory.Subtitle.Select -> { manager.selectSubtitleTrack(r.id); subtitleAppliedFor = tracksKey(item) }
                    null -> Unit
                }
            }
        }
    }

    /**
     * The picks this file's show carries, or null for a film: a folder of loose films is no show, and
     * the sound picked for one of them is no choice for the next.
     */
    private fun showPicks(item: MediaItem): com.therealityteam.videoplayer.data.repository.TrackPickStore.ShowTracks? =
        showKeyOf(item)?.let { trackPicks?.of(it) }

    private fun showKeyOf(item: MediaItem): String? {
        val episodic = item.episode != null || com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(item).cat.let {
            it == com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat.EPISODE || it == com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat.OVA
        }
        return if (episodic) SkipStore.keyOf(item) else null
    }

    /** The reader's 首选字幕 for a file nothing was picked for: off, a track in that language, or null for no word. */
    private fun preferredSubtitle(item: MediaItem, tracks: List<com.therealityteam.videoplayer.data.playback.MediaTrack>): TrackMemory.Subtitle? =
        com.therealityteam.videoplayer.data.playback.TrackPreference.subtitle(
            settingsRepo.get().subtitleChoice, tracks, videoFileName = item.fileName, sound = manager.audioTracks.value,
        )?.also { r ->
            val what = (r as? TrackMemory.Subtitle.Select)?.let { s -> tracks.first { it.id == s.id }.name } ?: "off"
            android.util.Log.i("AuraTracks", "preferred subtitles in ${item.fileName}: $what")
        }

    /** The subtitles this file's show had — off, or the track that is its pick again; null for no word. */
    private fun showSubtitle(item: MediaItem, tracks: List<com.therealityteam.videoplayer.data.playback.MediaTrack>): TrackMemory.Subtitle? {
        val show = showPicks(item) ?: return null
        val pick = show.subtitle   // a local: another module's property cannot be smart-cast
        return when {
            show.subtitlesOff -> if (tracks.isNotEmpty()) TrackMemory.Subtitle.Off.also {
                android.util.Log.i("AuraTracks", "show's subtitles off in ${item.fileName}")
            } else null
            pick != null -> com.therealityteam.videoplayer.data.playback.TrackPicks.find(pick, tracks, item.fileName)?.let { id ->
                android.util.Log.i("AuraTracks", "show's subtitles in ${item.fileName}: ${tracks.first { it.id == id }.name}")
                TrackMemory.Subtitle.Select(id)
            }
            else -> null
        }
    }

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue
    private var playlist: List<MediaItem>
        get() = _queue.value
        set(value) { _queue.value = value }

    // Each queued episode as the database tells it, by item id: read as the queue is set, from the
    // caches the library's pages read too — and, for a run numbered by its names alone, as its file
    // calls it (EpisodeNaming.instalmentNames) where no database told it.
    private val _told = MutableStateFlow<Map<String, com.therealityteam.videoplayer.data.scrape.EpisodeInfo>>(emptyMap())
    val told: StateFlow<Map<String, com.therealityteam.videoplayer.data.scrape.EpisodeInfo>> = _told

    // Whether the queue is such a run — 激流中国's `.01.喉舌与职责` — so that the bar and the queue
    // read 第 1 集 where the scan read no number, as the series' page does (MediaGroup.numberedRun).
    private val _numberedRun = MutableStateFlow(false)
    val numberedRun: StateFlow<Boolean> = _numberedRun

    init {
        val engine = scrape
        scope.launch {
            _queue.collectLatest { queue ->
                // A series only, as the detail page asks (MediaGroup.numberedRun): The Fellowship of the
                // Ring's Part.1 and Part.2 are a film's halves, not 第 1 集 and 第 2 集.
                val run = queue.none { it.kind == com.therealityteam.videoplayer.data.model.MediaKind.MOVIE } &&
                    com.therealityteam.videoplayer.ui.library.EpisodeNaming.numberedByNames(queue)
                _numberedRun.value = run
                val own = if (!run) emptyMap() else com.therealityteam.videoplayer.ui.library.EpisodeNaming.instalmentNames(queue)
                    .mapValues { com.therealityteam.videoplayer.data.scrape.EpisodeInfo(it.value, null, null) }
                _told.value = own
                if (engine != null) {
                    _told.value = own + withContext(Dispatchers.IO) { runCatching { engine.episodeGuide(queue) }.getOrDefault(emptyMap()) }
                }
            }
        }
    }

    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current

    /** The playing file's 书签, in playback order. Declared below [_current], which it reads. */
    val bookmarks: StateFlow<List<com.therealityteam.videoplayer.data.repository.BookmarkStore.Bookmark>> =
        combine(_current, bookmarkStore?.all ?: MutableStateFlow(emptyMap())) { item, all -> item?.let { all[it.id] }.orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Mark where the picture is now; false when there is no file, or this moment is marked already. */
    fun addBookmark(): Boolean {
        val item = _current.value ?: return false
        touchControls()
        return bookmarkStore?.add(item.id, manager.position.value) ?: false
    }

    /** Automation (`aura.bookmarks`): mark [positionMs] in the playing file. */
    fun addBookmarkAt(positionMs: Long) {
        val item = _current.value ?: return
        val added = bookmarkStore?.add(item.id, positionMs) ?: false
        android.util.Log.i("AuraBookmarks", "书签 at ${positionMs / 1000} s in ${item.fileName}: ${if (added) "added" else "not added"}")
    }

    fun removeBookmark(positionMs: Long) {
        val item = _current.value ?: return
        bookmarkStore?.remove(item.id, positionMs)
        touchControls()
    }

    fun seekToBookmark(positionMs: Long) {
        manager.seekTo(positionMs)
        touchControls()
    }

    /** The playing show's 跳过片头片尾 marks (see [SkipStore]); empty when none are set. */
    val skipMarks: StateFlow<SkipStore.Marks> =
        combine(_current, skipStore?.all ?: MutableStateFlow(emptyMap())) { item, all ->
            item?.let { all[SkipStore.keyOf(it)] } ?: SkipStore.Marks()
        }.stateIn(scope, SharingStarted.Eagerly, SkipStore.Marks())

    /** Where this file started after skipping its opening — the overlay says so for a few seconds. */
    private val _introSkippedTo = MutableStateFlow<Long?>(null)
    val introSkippedTo: StateFlow<Long?> = _introSkippedTo
    fun dismissIntroSkipped() { _introSkippedTo.value = null }

    /** Where 看片头 goes back to: the file's start for the show's marked opening, its chapter's start for a named one. */
    private var introBackTo = 0L

    /** 看片头 — back to the opening just skipped. */
    fun watchIntro() {
        _resumedFromMs.value = null
        _introSkippedTo.value = null
        manager.seekTo(introBackTo)
        touchControls()
    }

    /** 片头到此为止: the opening of this show ends where the picture is now. */
    fun markIntroEnd() {
        val item = _current.value ?: return
        skipStore?.setIntroEnd(SkipStore.keyOf(item), manager.position.value)
        touchControls()
    }

    /** 片尾从这里开始: the closing runs from here to the end, in this and every episode. */
    fun markOutroStart() {
        val item = _current.value ?: return
        val duration = manager.duration.value
        if (duration <= 0L) return
        skipStore?.setOutroLength(SkipStore.keyOf(item), duration - manager.position.value)
        outroHandledFor = item.id // marked here, on purpose: this episode plays on
        touchControls()
    }

    fun clearSkipMarks() {
        val item = _current.value ?: return
        skipStore?.clear(SkipStore.keyOf(item))
        touchControls()
    }

    /** Automation (`aura.skip intro_ms,outro_ms`): set the playing show's marks outright. */
    fun setSkipMarks(introEndMs: Long?, outroLengthMs: Long?) {
        val item = _current.value ?: return
        val key = SkipStore.keyOf(item)
        skipStore?.setIntroEnd(key, introEndMs)
        skipStore?.setOutroLength(key, outroLengthMs)
    }

    /** The file whose closing has been reached (or marked) once, so it is not acted on twice. */
    private var outroHandledFor: String? = null

    /** The file that has been seen playing before its closing — only a file that crosses into it counts. */
    private var outroArmedFor: String? = null

    /**
     * The closing reached: the episode is done as far as the viewer is concerned — counted watched,
     * and the next one announced with its countdown (whose 取消 stays for the credits).
     *
     * Only on crossing into the closing, never on landing in it: right after a file starts the player
     * still reports the last file's position for a moment, and a file resumed inside its own credits
     * was left there on purpose. Either used to bring on the next episode the instant this one began.
     */
    private fun checkOutro(positionMs: Long) {
        val item = _current.value ?: return
        val at = skipStore?.of(SkipStore.keyOf(item))?.outroAt(manager.duration.value) ?: return
        if (_nextUp.value != null) {
            // Sought back out of the closing while its countdown runs: they are watching again, and
            // crossing into it later announces the next one afresh.
            if (outroHandledFor == item.id && positionMs in 1 until at - OUTRO_SEEK_BACK_MS) {
                dropNextUp()
                outroHandledFor = null
                outroArmedFor = item.id
            }
            return
        }
        if (outroHandledFor == item.id) return
        if (positionMs < at) {
            if (positionMs > 0L) outroArmedFor = item.id
            return
        }
        if (outroArmedFor != item.id || manager.state.value != PlaybackState.PLAYING) return
        outroHandledFor = item.id
        android.util.Log.i("AuraSkip", "credits from ${at}ms reached at ${positionMs}ms in ${item.fileName}")
        onItemCompleted()
    }

    init {
        scope.launch { manager.position.collect { checkOutro(it) } }
    }

    private val _immersive = MutableStateFlow(false)
    val immersive: StateFlow<Boolean> = _immersive

    private val _cinemaDim = MutableStateFlow(false)
    val cinemaDim: StateFlow<Boolean> = _cinemaDim

    // 虚拟环境: a model drawn around the window on the Full-Space stage — the living room, a
    // bathhouse, an island. The open stage shows whatever this holds and swaps when it changes;
    // the picture stays on the panel throughout.
    private val _environment = MutableStateFlow<Environment?>(null)
    val environment: StateFlow<Environment?> = _environment
    val environmentScene = EnvironmentScene()

    // 观影灯光: in an environment the room dims while the film plays on its own, and comes back up
    // when it pauses or the controls are called — a cinema's lights. Kept across films, like 夜间模式.
    private val _dimEnvironment = MutableStateFlow(settingsRepo.get().dimEnvironment)
    val dimEnvironment: StateFlow<Boolean> = _dimEnvironment
    fun setDimEnvironment(on: Boolean) {
        _dimEnvironment.value = on
        settingsRepo.update(settingsRepo.get().copy(dimEnvironment = on))
    }
    private val _viewingLightPercent = MutableStateFlow(settingsRepo.get().viewingLightPercent)
    val viewingLightPercent: StateFlow<Int> = _viewingLightPercent
    fun setViewingLightPercent(percent: Int) {
        val value = percent.coerceIn(0, 100)
        if (_viewingLightPercent.value == value) return
        _viewingLightPercent.value = value
        settingsRepo.update(settingsRepo.get().copy(viewingLightPercent = value))
    }
    // 灯光: a layered place's own room lights (a cinema's house lights), switched by hand; off, they stay off.
    // On, 观影灯光 above still takes them down while the film plays. Kept across films.
    private val _roomLights = MutableStateFlow(true)
    val roomLights: StateFlow<Boolean> = _roomLights
    fun setRoomLights(on: Boolean) {
        _roomLights.value = on
    }
    private val _barShown = MutableStateFlow(true)
    /** Whether the bar is up, from the window that shows it. */
    fun setBarShown(shown: Boolean) {
        if (_barShown.value != shown) _barShown.value = shown
    }
    val environmentDimmed: StateFlow<Boolean> =
        combine(_environment, manager.state, _barShown, _dimEnvironment) { env, playback, bar, on ->
            on && env != null && playback == PlaybackState.PLAYING && !bar
        }.stateIn(scope, SharingStarted.Eagerly, false)

    // 在环境的屏幕上播放: where the place has a screen, the film plays on it,
    // and its subtitles with it ([screenCaptions]); the window keeps the bar. Kept across films.
    private val _screenPlay = MutableStateFlow(settingsRepo.get().screenPlay)
    val screenPlay: StateFlow<Boolean> = _screenPlay
    fun setScreenPlay(on: Boolean) {
        if (_screenPlay.value == on) return
        _screenPlay.value = on
        settingsRepo.update(settingsRepo.get().copy(screenPlay = on))
        routePicture()
    }

    private val _screenGlow = MutableStateFlow(settingsRepo.get().screenGlow)
    val screenGlow: StateFlow<Boolean> = _screenGlow
    fun setScreenGlow(on: Boolean) {
        if (_screenGlow.value == on) return
        _screenGlow.value = on
        settingsRepo.update(settingsRepo.get().copy(screenGlow = on))
        screen?.let { screenReflection.show(it, on && it.enabled && screenPose()?.backing == true) }
    }

    // The place the stage has up now, its model loaded. The film goes onto a place's screen only once
    // the place is there to be seen — not onto a TV in a room still loading — and while another is read
    // to take its place, the one on show keeps the film: it moves once, with the place, not into the
    // window and out again.
    private val _placeShown = MutableStateFlow<Environment?>(null)
    val placeShown: StateFlow<Environment?> = _placeShown

    suspend fun showEnvironment(env: Environment?, reveal: Boolean = true) {
        val seat = env?.savedSeat(settingsRepo.get().environmentSeats[env.id]) ?: ViewingSeat.CENTER
        environmentScene.show(env, reveal, seat)
    }

    /** The picture and its captions move on their existing surface with the room. */
    fun setViewingSeat(env: Environment, seat: ViewingSeat) {
        if (env != _environment.value || env != _placeShown.value || _entering.value != null) return
        if (!environmentScene.moveToSeat(env, seat)) return
        if (screenPose() != null) ensureScreen()
        val settings = settingsRepo.get()
        val seats = if (seat == ViewingSeat.CENTER) settings.environmentSeats - env.id
            else settings.environmentSeats + (env.id to seat.name)
        settingsRepo.update(settings.copy(environmentSeats = seats))
    }

    /** Only the current request may route the film or report a room-loading failure. */
    fun onEnvironmentShown(requested: Environment?) {
        if (requested != _environment.value) return
        val env = environmentScene.current
        // A place that could not be read leaves the one before it up: the choice goes back to that —
        // or, with none before it, the stage stands empty, and goes.
        if (requested != null && env != requested) {
            _placeFailed.value = requested
            _environment.value = env
            if (env == null) _closeStage.value = true
        }
        _placeShown.value = env
        if (env != null && Environments.byId(env.id) != null && settingsRepo.get().lastEnvironment != env.id) {
            settingsRepo.update(settingsRepo.get().copy(lastEnvironment = env.id))
        }
        routePicture()
    }

    /** Whether the film is on the place's screen now, not in the window. */
    val onScreen: StateFlow<Boolean> =
        combine(_placeShown, _screenPlay) { shown, on -> on && shown?.screen != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    // 动态氛围光: in a place that takes the film's light (its screen's light baked in zones), the frame on the screen is
    // looked at ten times a second — a 48-pixel-wide copy — and each zone's light eased toward what it shows, thirty
    // times a second. Off the screen (the film in its window) the screen is dark and so is its light; paused, it holds.
    // ambienceGain: a debug factor on top (-e aura.env_amb <gain>|off).
    val ambienceGain = MutableStateFlow(1f)
    val ambienceOn = MutableStateFlow(true)
    /** How often the frame is looked at, ms (debug: -e aura.env_amb hz:<n>). */
    val ambienceSampleMs = MutableStateFlow(AMBIENCE_SAMPLE_MS)

    init {
        scope.launch {
            combine(environmentScene.ambienceVisible, _placeShown, _screenPlay, screenGlow) { visible, place, onWall, glow ->
                place?.takeIf { visible && onWall && glow && it.screen != null }
            }.distinctUntilChanged().collectLatest { place ->
                if (place == null) {
                    environmentScene.setAmbience(null)
                    return@collectLatest
                }
                if (manager is PlaybackManager) {
                    // The SDK player owns its texture. It supplies the live bezel reflection, while
                    // the baked walls keep their neutral film light without a readable Surface.
                    environmentScene.setAmbience(FloatArray(Ambience.ZONES * 3) { Ambience.STILL })
                    return@collectLatest
                }
                val target = FloatArray(Ambience.ZONES * 3)
                val shown = target.copyOf()
                // the place came up with its film's light put out: set it once, whatever moves after
                var stale = true
                var sampledAt = 0L
                var sampledPosition: Long? = null
                var lastAt = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val state = manager.state.value
                    val position = manager.position.value
                    val needsFrame = state == PlaybackState.PLAYING ||
                        (state == PlaybackState.PAUSED && position != sampledPosition)
                    when {
                        // off (debug): the zones at the still film's light, as a still bake would have them
                        !ambienceOn.value -> {
                            target.fill(Ambience.STILL)
                            sampledPosition = null
                        }
                        needsFrame && now - sampledAt >= ambienceSampleMs.value -> {
                            sampledAt = now
                            if (state == PlaybackState.PAUSED) {
                                // The seek changes position before the decoder presents its paused frame.
                                kotlinx.coroutines.delay(AMBIENCE_PAUSED_SETTLE_MS)
                                if (manager.state.value != state || manager.position.value != position) continue
                            }
                            val surface = pictureSurface()
                            val aspect = manager.videoAspect.value
                            val screenAspect = place.screen?.aspect ?: 0f
                            if (surface != null && aspect > 0f && screenAspect > 0f) {
                                val w = AMBIENCE_WIDTH
                                val h = (w / aspect).toInt().coerceIn(4, AMBIENCE_WIDTH * 2)
                                LetterboxProbe.copy(surface, w, h)?.let { px ->
                                    Ambience.zones(px, w, h, aspect, screenAspect, ambienceGain.value).copyInto(target)
                                    sampledPosition = position
                                    if (android.util.Log.isLoggable(AMBIENCE_LOG_TAG, android.util.Log.DEBUG)) {
                                        android.util.Log.d(AMBIENCE_LOG_TAG, target.joinToString(" ") { "%.2f".format(java.util.Locale.ROOT, it) })
                                    }
                                }
                            }
                        }
                    }
                    val ease = 1f - kotlin.math.exp(-(now - lastAt) / AMBIENCE_EASE_MS)
                    lastAt = now
                    var moved = 0f
                    for (i in shown.indices) {
                        val d = (target[i] - shown[i]) * ease
                        shown[i] += d; moved += kotlin.math.abs(d)
                    }
                    if (moved > 0.0005f || stale) environmentScene.setAmbience(shown)
                    stale = false
                    kotlinx.coroutines.delay(AMBIENCE_FRAME_MS)
                }
            }
        }
    }

    // A place read before its stage opens: the window says 正在进入, its tile in the picker spins.
    private val _entering = MutableStateFlow<Environment?>(null)
    val entering: StateFlow<Environment?> = _entering
    private var enterSeq = 0

    // A place that could not be read, for the window to say so for a moment.
    private val _placeFailed = MutableStateFlow<Environment?>(null)
    val placeFailed: StateFlow<Environment?> = _placeFailed
    fun clearPlaceFailed() {
        _placeFailed.value = null
    }

    /**
     * Reads [env] before a stage opens on it, the film playing on where it is meanwhile: a Full stage
     * opened first stood black around the window for the seconds the model takes (7 s for the living
     * room on the emulator). True once it is in — or once it is the stage's to read: a model still
     * reading after [PRELOAD_LIMIT_MS] is handed to the stage rather than waited on for ever. False if
     * it could not be read (the window says so) or a later choice called it off.
     */
    suspend fun prepareEnvironment(env: Environment): Boolean {
        val mine = ++enterSeq
        _placeFailed.value = null
        _entering.value = env
        val read = withTimeoutOrNull(PRELOAD_LIMIT_MS) { showEnvironment(env, reveal = false) } != null
        if (mine != enterSeq) return false
        _entering.value = null
        if (!read) {
            android.util.Log.i("AuraEnv", "${env.id} still reading after ${PRELOAD_LIMIT_MS / 1000} s: continuing on stage")
            return true
        }
        if (environmentScene.current == env) return true
        _placeFailed.value = env
        return false
    }

    /** The place being read is not wanted after all: the window chosen, another space, the player closed. */
    fun cancelEntering() {
        enterSeq++
        if (_entering.value != null) environmentScene.discardPendingRead()
        _entering.value = null
    }

    /** Keep the film where it is if the requested room is still loading. */
    fun cancelRoomChange(request: Environment) {
        if (_entering.value == request) {
            cancelEntering()
        } else if (_environment.value == request && _placeShown.value != request) {
            environmentScene.discardPendingRead()
            val previous = _placeShown.value
            _environment.value = previous
            if (previous == null) _closeStage.value = true
        }
    }

    /**
     * The place being read now, for the picker's spinner and the window's 正在进入: one read before
     * its stage opens, one read on its stage, or one taking another's place, until it is up.
     */
    val placeLoading: StateFlow<Environment?> =
        combine(_entering, _environment, _placeShown) { entering, env, shown -> entering ?: env?.takeIf { it != shown } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // The window closes a stage left empty after a failed or canceled first room.
    private val _closeStage = MutableStateFlow(false)
    val closeStage: StateFlow<Boolean> = _closeStage
    fun stageClosed() {
        _closeStage.value = false
    }

    /** The subtitles drawn on the place's screen while the film is there. */
    val screenCaptions = ScreenCaptions(scope)

    /**
     * 沉浸空间's subtitles: the window's lines, read where the film is — on a face three metres ahead of
     * the viewer, its lines along its foot (some 15° below the eyes), turned to the head lazily
     * (PanoFollow, CaptionFollow). In the window's plane they stayed behind when the head turned, and
     * small: a window's share of a sphere.
     */
    val sphereCaptions = ScreenCaptions(scope)
    val captionAnchor: Entity by lazy {
        Entity().apply {
            setName("aura_pano_captions")
            addChild(sphereCaptions.entity)
            sphereCaptions.fit(PANO_CAPTION_WIDTH, PANO_CAPTION_HEIGHT, distance = PANO_CAPTION_DISTANCE)
        }
    }

    /** Where the place's screen stands on the stage, while the film is to play on it. */
    private fun screenPose(): ScreenPose? =
        _placeShown.value?.takeIf { _screenPlay.value && !_immersive.value }?.screenPose(environmentScene.viewingSeat.value)

    // For a thumbnail of an environment (-e aura.env_capture): the picture and the bar step aside.
    private val _capture = MutableStateFlow(false)
    val capture: StateFlow<Boolean> = _capture

    // Stored values are put back on their step grid as they load: an older build summed float steps
    // and left 0.79999995 behind, which a card read as "79%".
    private val _subtitleScale = MutableStateFlow(
        PlayerAdjust.snapFraction(
            settingsRepo.get().subtitleScale,
            PlayerAdjust.SCALE_STEP_PCT, PlayerAdjust.SCALE_MIN_PCT, PlayerAdjust.SCALE_MAX_PCT,
        ),
    )
    val subtitleScale: StateFlow<Float> = _subtitleScale

    init {
        // The same size for the subtitles an engine draws into the picture itself (mpv's ASS).
        scope.launch { _subtitleScale.collect { manager.setSubtitleScale(it) } }
    }

    // Subtitle vertical position, as a fraction of panel height raised off the bottom (0–0.35).
    private val _subtitleRaise = MutableStateFlow(
        PlayerAdjust.snapFraction(
            settingsRepo.get().subtitleRaise,
            PlayerAdjust.RAISE_STEP_PCT, PlayerAdjust.RAISE_MIN_PCT, PlayerAdjust.RAISE_MAX_PCT,
        ),
    )
    val subtitleRaise: StateFlow<Float> = _subtitleRaise

    // 字幕样式: bare text (default), a translucent box, or outlined text — persisted like size and position.
    private val _subtitleStyle = MutableStateFlow(settingsRepo.get().subtitleStyle)
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle
    fun setSubtitleStyle(style: SubtitleStyle) {
        _subtitleStyle.value = style
        settingsRepo.update(settingsRepo.get().copy(subtitleStyle = style))
    }

    // The rest of the subtitle look: outline thickness, typeface, colour, and whether an ASS
    // file's own colours are honoured. All persisted like the style.
    private val _subtitleOutline = MutableStateFlow(settingsRepo.get().subtitleOutline)
    val subtitleOutline: StateFlow<Int> = _subtitleOutline
    fun adjustSubtitleOutline(delta: Int) = setSubtitleOutline(_subtitleOutline.value + delta)

    fun setSubtitleOutline(width: Int) {
        val v = width.coerceIn(PlayerAdjust.OUTLINE_MIN, PlayerAdjust.OUTLINE_MAX)
        _subtitleOutline.value = v
        settingsRepo.update(settingsRepo.get().copy(subtitleOutline = v))
    }

    private val _subtitleFont = MutableStateFlow(settingsRepo.get().subtitleFont)
    val subtitleFont: StateFlow<SubtitleFont> = _subtitleFont
    fun setSubtitleFont(font: SubtitleFont) {
        _subtitleFont.value = font
        settingsRepo.update(settingsRepo.get().copy(subtitleFont = font))
    }

    private val _subtitleColor = MutableStateFlow(settingsRepo.get().subtitleColor)
    val subtitleColor: StateFlow<SubtitleColor> = _subtitleColor
    fun setSubtitleColor(color: SubtitleColor) {
        _subtitleColor.value = color
        settingsRepo.update(settingsRepo.get().copy(subtitleColor = color))
    }

    private val _subtitleKeepStyles = MutableStateFlow(settingsRepo.get().subtitleKeepStyles)
    val subtitleKeepStyles: StateFlow<Boolean> = _subtitleKeepStyles
    // ASS as typeset (libass, here or in mpv) or as plain lines in the reader's look — persisted.
    /** The bar's time after the position — total, time left, or the clock it ends at — cycled by a tap on it. */
    private val _barTime = MutableStateFlow(settingsRepo.get().barTime)
    val barTime: StateFlow<String> = _barTime
    fun cycleBarTime() {
        val next = when (_barTime.value) { "total" -> "left"; "left" -> "ends"; else -> "total" }
        _barTime.value = next
        settingsRepo.update(settingsRepo.get().copy(barTime = next))
    }

    private val _subtitleTypeset = MutableStateFlow(settingsRepo.get().subtitleTypeset)
    val subtitleTypeset: StateFlow<Boolean> = _subtitleTypeset
    fun setSubtitleTypeset(on: Boolean) {
        _subtitleTypeset.value = on
        manager.setTypesetSubtitles(on)
        settingsRepo.update(settingsRepo.get().copy(subtitleTypeset = on))
    }

    /** Whether the subtitles chosen now are ASS — the only ones 按字幕组排版 changes. */
    val subtitleIsTypeset: StateFlow<Boolean> = manager.subtitleTracks
        .map { tracks -> tracks.any { it.selected && it.typeset } }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun setSubtitleKeepStyles(keep: Boolean) {
        _subtitleKeepStyles.value = keep
        settingsRepo.update(settingsRepo.get().copy(subtitleKeepStyles = keep))
    }

    // Chapters, read from the container itself (the engine exposes none) off the main thread
    // when an item starts; a file without marks simply has an empty list.
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters
    private var chapterJob: kotlinx.coroutines.Job? = null

    // 章节 pictures, by chapter start: taken when the chapter list is first shown, from the chapter
    // playing outwards, and kept in the cache for the next time. [chapterStillsNone]: the file gave
    // none (a picture neither the platform's decoders nor the app's FFmpeg reads) — the list keeps
    // its plain numbers.
    private val stills = com.therealityteam.videoplayer.data.playback.ChapterStills(java.io.File(appContext.cacheDir, "chapter_stills"))
    private val _chapterStills = MutableStateFlow<Map<Long, android.graphics.Bitmap>>(emptyMap())
    val chapterStills: StateFlow<Map<Long, android.graphics.Bitmap>> = _chapterStills
    private val _chapterStillsNone = MutableStateFlow(false)
    val chapterStillsNone: StateFlow<Boolean> = _chapterStillsNone
    private var stillsJob: kotlinx.coroutines.Job? = null
    /** The item and chapter list [stillsJob] pictures (a share's marks arrive after the file's start). */
    private var stillsFor: Pair<String, List<Chapter>>? = null

    /** The chapter list is on screen: picture its chapters, once per item and list of marks. */
    fun wantChapterStills() {
        val item = _current.value ?: return
        val chapters = _chapters.value.takeIf { it.size > 1 } ?: return
        if (stillsFor == item.id to chapters) return
        stillsFor = item.id to chapters
        stillsJob?.cancel()
        stillsJob = scope.launch {
            // One still being made as the film changes must not land on the next film's list.
            val onStill: (Long, android.graphics.Bitmap) -> Unit = { ms, bmp ->
                if (_current.value?.id == item.id) _chapterStills.update { it + (ms to bmp) }
            }
            // Those kept from before come at once, from the cache.
            val missing = stills.kept(item.id, chapters, chapters.indexAt(manager.position.value), onStill)
            if (missing == null) { _chapterStillsNone.value = true; return@launch }
            if (missing.isEmpty()) return@launch
            // The rest not while the film is still opening — its reads come first: a list opened at
            // once waits for the picture, and a moment more for the engine's buffer to fill.
            manager.hasFirstFrame.first { it }
            kotlinx.coroutines.delay(STILLS_AFTER_PICTURE_MS)
            // Read here, on the main thread the engine lives on. The order again from where the film
            // is now: a list opened at the start was read before the film went to its resume point.
            val playing = manager.currentUri()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val durationMs = manager.duration.value
            val todo = missing.toHashSet().let { left ->
                ChapterStillPlan.order(chapters.size, chapters.indexAt(manager.position.value)).filter { it in left }
            }
            // A share's file is read under a registration of its own, so closing it never touches
            // the stream the film plays through.
            val key = "stills:${item.path}"
            var registered = false
            try {
                val where = withContext(Dispatchers.IO) {
                    localCopy(item)?.path ?: when {
                        item.path.startsWith("/") -> item.path
                        item.path.startsWith("file://") -> item.path.removePrefix("file://")
                        com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(item.path) -> sourceFor(item).let { src ->
                            streamServer.register(item.path, src?.username, src?.password, key)?.also { registered = true }
                        }
                        else -> playing
                    }
                } ?: return@launch
                val any = stills.make(item.id, where, chapters, todo, durationMs, onStill)
                if (!any && _current.value?.id == item.id) _chapterStillsNone.value = true
            } finally {
                if (registered) streamServer.unregister(key)
            }
        }
    }

    private fun forgetChapterStills() {
        stillsJob?.cancel()
        stillsJob = null
        stillsFor = null
        _chapterStills.value = emptyMap()
        _chapterStillsNone.value = false
    }


    fun seekToChapter(index: Int) {
        _chapters.value.getOrNull(index)?.let { manager.seekTo(it.startMs) }
        touchControls()
    }

    /** Back to this chapter's start, or — within its first three seconds — to the one before. */
    fun previousChapter() {
        val list = _chapters.value
        val pos = manager.position.value
        val i = list.indexAt(pos)
        if (i < 0) return
        seekToChapter(if (i > 0 && pos - list[i].startMs < 3_000) i - 1 else i)
    }

    fun nextChapter() {
        val list = _chapters.value
        val i = list.indexAt(manager.position.value)
        if (i + 1 < list.size) seekToChapter(i + 1)
    }

    // 拖动预览: while the seek bar is dragged the picture follows it — the engine shows the frame at
    // each point the drag passes (no sound, the clock held) — and the release lands on the last one.
    // Points are handed on at most every SCRUB_STEP_MS; a point that arrives meanwhile replaces the
    // one waiting, so the picture chases the hand rather than queueing behind it.
    private var scrubTarget: Long? = null
    private var scrubJob: kotlinx.coroutines.Job? = null

    fun scrubTo(ms: Long) {
        touchControls()
        scrubTarget = ms
        if (scrubJob?.isActive == true) return
        scrubJob = scope.launch {
            manager.beginScrub()
            while (true) {
                val target = scrubTarget ?: break
                scrubTarget = null
                manager.scrubTo(target)
                kotlinx.coroutines.delay(SCRUB_STEP_MS)
            }
        }
    }

    fun endScrub(ms: Long) {
        scrubJob?.cancel()
        scrubJob = null
        scrubTarget = null
        manager.endScrub(ms)
        touchControls()
    }

    // The controller's playback language (see ControllerModel): a step back or forward, a
    // notch of system volume, and double speed while the trigger is held.
    fun seekBy(deltaMs: Long) {
        val duration = manager.duration.value
        val target = (manager.position.value + deltaMs).coerceAtLeast(0L)
        manager.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        touchControls()
    }

    /** 逐帧: paused, one picture on or back (the 播放 panel's ◂▏ ▕▸). */
    fun stepFrame(forward: Boolean) {
        manager.stepFrame(forward)
        touchControls()
    }

    fun adjustVolume(up: Boolean) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        audio.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI,
        )
        touchControls()
    }

    private var speedBeforeBoost: Float? = null

    /**
     * True while a held trigger (or a held press on the picture) plays at double speed. The 倍速
     * tiles keep the chosen speed lit, so the picture says "2×" itself while this holds.
     */
    private val _speedHeld = MutableStateFlow(false)
    val speedHeld: StateFlow<Boolean> = _speedHeld

    /** Double speed while the trigger is held; the chosen speed returns on release. */
    fun holdBoost(on: Boolean) {
        if (on) {
            if (speedBeforeBoost != null) return
            speedBeforeBoost = _speed.value
            manager.setSpeed(2.0f)
            _speedHeld.value = true
        } else {
            val back = speedBeforeBoost ?: return
            speedBeforeBoost = null
            manager.setSpeed(back)
            _speedHeld.value = false
        }
    }

    /** Applies what the controller asked for. */
    fun onControllerIntent(intent: ControllerModel.Intent) = when (intent) {
        ControllerModel.Intent.SEEK_BACK -> seekBy(-SEEK_STEP_MS)
        ControllerModel.Intent.SEEK_FORWARD -> seekBy(SEEK_STEP_MS)
        ControllerModel.Intent.VOLUME_UP -> adjustVolume(up = true)
        ControllerModel.Intent.VOLUME_DOWN -> adjustVolume(up = false)
        ControllerModel.Intent.BOOST_ON -> holdBoost(true)
        ControllerModel.Intent.BOOST_OFF -> holdBoost(false)
    }

    // Control bar activity: the bar fades after a quiet spell while playing; any pointer
    // event on the window or the bar wakes it, and an open menu holds it. Touches are
    // throttled so a hover stream doesn't restart the timer ninety times a second.
    private val _controlsTouchedAt = MutableStateFlow(0L)
    val controlsTouchedAt: StateFlow<Long> = _controlsTouchedAt
    private val _controlsHold = MutableStateFlow(0)
    val controlsHold: StateFlow<Int> = _controlsHold
    fun touchControls() {
        userActed()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - _controlsTouchedAt.value > 250) _controlsTouchedAt.value = now
    }

    /** Bring the bar up now, whatever was pressed a moment ago — a click, not a hover storm. */
    fun showControls() {
        userActed()
        _controlsTouchedAt.value = android.os.SystemClock.elapsedRealtime()
    }

    // The bar is HELD while a pointer is on it — the ray resting anywhere on the bar, not only on
    // a button — and DISMISSED by a click on the picture while it is up. Neither is a timestamp
    // the auto-hide can be restarted from; they are states the timer has to respect.
    private val _controlsHovered = MutableStateFlow(false)
    val controlsHovered: StateFlow<Boolean> = _controlsHovered
    fun setControlsHovered(hovered: Boolean) {
        if (_controlsHovered.value != hovered) _controlsHovered.value = hovered
    }
    private val _controlsDismissedAt = MutableStateFlow(0L)
    val controlsDismissedAt: StateFlow<Long> = _controlsDismissedAt
    fun dismissControls() {
        _controlsDismissedAt.value = android.os.SystemClock.elapsedRealtime()
    }
    fun holdControls(open: Boolean) {
        _controlsHold.value = (_controlsHold.value + if (open) 1 else -1).coerceAtLeast(0)
    }

    /**
     * The 字幕 menus open: their panels sit where the subtitles are, so while one is up the
     * subtitles show at the top of the picture, where a track chosen or a style changed can be
     * seen as it happens — and with the settings open a quiet stretch keeps a line up to judge
     * by. Counted, like [holdControls]: one menu's close can land after the next one's open.
     */
    data class SubtitleMenus(val tracks: Int = 0, val settings: Int = 0) {
        val preview: Boolean get() = tracks > 0 || settings > 0
        val keepLine: Boolean get() = settings > 0
    }
    private val _subtitleMenus = MutableStateFlow(SubtitleMenus())
    val subtitleMenus: StateFlow<SubtitleMenus> = _subtitleMenus
    fun holdSubtitleMenu(settings: Boolean, open: Boolean) {
        val step = if (open) 1 else -1
        val now = _subtitleMenus.value
        _subtitleMenus.value =
            if (settings) now.copy(settings = (now.settings + step).coerceAtLeast(0))
            else now.copy(tracks = (now.tracks + step).coerceAtLeast(0))
    }

    // A–B repeat: one cycle button drops A, then B, then clears; the position ticker loops.
    private val _repeatA = MutableStateFlow<Long?>(null)
    private val _repeatB = MutableStateFlow<Long?>(null)
    /** The marks themselves, so the panel can say where the loop runs. */
    val repeatA: StateFlow<Long?> = _repeatA
    val repeatB: StateFlow<Long?> = _repeatB
    val repeatState: StateFlow<com.therealityteam.videoplayer.data.playback.AbRepeat.State> =
        MutableStateFlow(com.therealityteam.videoplayer.data.playback.AbRepeat.State.IDLE)

    fun cycleAbRepeat() {
        val (a, b) = com.therealityteam.videoplayer.data.playback.AbRepeat.cycle(
            _repeatA.value, _repeatB.value, manager.position.value,
        )
        _repeatA.value = a
        _repeatB.value = b
        (repeatState as MutableStateFlow).value =
            com.therealityteam.videoplayer.data.playback.AbRepeat.state(a, b)
    }

    /** Called on the UI's position ticker; seeks back to A once B is reached. */
    fun enforceAbRepeat() {
        com.therealityteam.videoplayer.data.playback.AbRepeat
            .enforce(_repeatA.value, _repeatB.value, manager.position.value)
            ?.let { manager.seekTo(it) }
    }

    // Snapshot (Movist's ⌘S). The frame is decoded again at the playing position, which over a
    // network share can take seconds, so the UI is told it has started as well as how it ended.
    enum class SnapshotStatus { WORKING, SAVED, FAILED }

    /** The latest snapshot event; [serial] makes a second identical outcome an event too. */
    data class SnapshotNotice(val status: SnapshotStatus, val serial: Int)

    private val _snapshotNotice = MutableStateFlow<SnapshotNotice?>(null)
    val snapshotNotice: StateFlow<SnapshotNotice?> = _snapshotNotice

    /** A capture is under way: 截图 waits for it instead of starting a second one. */
    private val _snapshotting = MutableStateFlow(false)
    val snapshotting: StateFlow<Boolean> = _snapshotting
    private var snapshotSerial = 0

    private fun announceSnapshot(status: SnapshotStatus) {
        _snapshotNotice.value = SnapshotNotice(status, ++snapshotSerial)
    }

    private var snapshotShownSerial = 0

    /**
     * True the first time [notice] is asked about. The view model outlives the player window, so
     * without this a reopened player would announce the last snapshot all over again.
     */
    fun claimSnapshotNotice(notice: SnapshotNotice): Boolean {
        if (notice.serial <= snapshotShownSerial) return false
        snapshotShownSerial = notice.serial
        return true
    }

    fun snapshot() {
        if (_snapshotting.value) return
        val uri = manager.currentUri()
        val item = _current.value
        if (uri == null || item == null) {
            // Nothing to decode a frame from — say so rather than doing nothing.
            announceSnapshot(SnapshotStatus.FAILED)
            return
        }
        _snapshotting.value = true
        announceSnapshot(SnapshotStatus.WORKING)
        scope.launch {
            val title = item.title.ifBlank { item.fileName }
            val name = try {
                // The engine's own frame where it can give one (mpv: what the retriever cannot read).
                val png = java.io.File(appContext.cacheDir, "snapshot.png")
                // …else the picture as it is shown, copied off its Surface at the film's own size: the system's
                // MediaMetadataRetriever gave a black frame on the headset (B3110, OS 6.2) for every file tried —
                // a 4K HEVC Main10 remux and a 1080p H.264 alike — though it gave one on the emulator.
                val surface = if (_immersive.value && sphereReady) sphereSurface else pictureSurface()
                val size = manager.videoPixels()
                if (manager.captureFrame(png) ||
                    (surface != null && size != null &&
                        com.therealityteam.videoplayer.data.playback.ResumeFrame.png(surface, size.first, size.second, png))
                ) {
                    com.therealityteam.videoplayer.data.playback.SnapshotSaver.save(appContext, png, title)
                } else {
                    com.therealityteam.videoplayer.data.playback.SnapshotSaver.capture(
                        appContext, uri, manager.position.value, title,
                    )
                }
            } finally {
                _snapshotting.value = false
            }
            announceSnapshot(if (name != null) SnapshotStatus.SAVED else SnapshotStatus.FAILED)
        }
    }

    // Session-level playback speed, the way desktop players keep it: switching episodes
    // keeps the chosen pace (re-applied in startItem for both engines).
    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    fun setSpeed(value: Float) {
        _speed.value = value
        manager.setSpeed(value)
    }

    // Subtitle delay is shared across files in the playback session.
    private val _subtitleDelayMs = MutableStateFlow(0L)
    val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs

    val supportsSubtitleDelay: Boolean get() = manager is SwitchingEngine

    private var subtitleDelayJob: Job? = null

    /**
     * Nudge the subtitle delay by [steps] tenths of a second (0 resets). The card shows the new
     * value at once. Media3 reparses once after the last adjustment. mpv can retime the active track live.
     */
    fun adjustSubtitleDelay(steps: Int) {
        val engine = manager as? SwitchingEngine ?: return
        val v = if (steps == 0) 0L
        else PlayerAdjust.stepOffset(_subtitleDelayMs.value, steps, PlayerAdjust.SUBTITLE_DELAY_LIMIT_MS)
        if (v == _subtitleDelayMs.value) return
        _subtitleDelayMs.value = v
        subtitleDelayJob?.cancel()
        if (engine.engineName == "mpv") {
            // mpv moves its subtitles as they come: no restart, and nothing to wait for.
            subtitleDelayJob = null
            engine.applySubtitleDelay(v * 1000L)
            return
        }
        subtitleDelayJob = scope.launch {
            kotlinx.coroutines.delay(SUBTITLE_DELAY_SETTLE_MS)
            subtitleDelayJob = null
            engine.applySubtitleDelay(v * 1000L)
        }
    }

    /** Step the subtitle text size (persisted): ±10% within 60%–200%. */
    fun adjustSubtitleScale(steps: Int) = setSubtitleScale(
        PlayerAdjust.stepFraction(
            _subtitleScale.value, steps,
            PlayerAdjust.SCALE_STEP_PCT, PlayerAdjust.SCALE_MIN_PCT, PlayerAdjust.SCALE_MAX_PCT,
        ),
    )

    fun setSubtitleScale(scale: Float) {
        val v = scale.coerceIn(PlayerAdjust.SCALE_MIN_PCT / 100f, PlayerAdjust.SCALE_MAX_PCT / 100f)
        _subtitleScale.value = v
        settingsRepo.update(settingsRepo.get().copy(subtitleScale = v))
    }

    /** Raise or lower subtitles (persisted): ±5% of panel height, 0 (bottom) to 35% up. */
    fun adjustSubtitleRaise(steps: Int) = setSubtitleRaise(
        PlayerAdjust.stepFraction(
            _subtitleRaise.value, steps,
            PlayerAdjust.RAISE_STEP_PCT, PlayerAdjust.RAISE_MIN_PCT, PlayerAdjust.RAISE_MAX_PCT,
        ),
    )

    fun setSubtitleRaise(raise: Float) {
        val v = raise.coerceIn(PlayerAdjust.RAISE_MIN_PCT / 100f, PlayerAdjust.RAISE_MAX_PCT / 100f)
        _subtitleRaise.value = v
        settingsRepo.update(settingsRepo.get().copy(subtitleRaise = v))
    }

    /**
     * Makes the mesh the window's panel will need, off the main thread, before the window asks for
     * the panel ([ensurePanel] then takes it). MeshResource.createVideoPanel waits on the engine —
     * on the emulator, whose frames are slow, 0.5–1 s a call as the player window opened — and on
     * the main thread the window froze as it appeared, the system logging it as unresponsive. A
     * resource may be made off the main thread; only its component may not.
     */
    suspend fun preparePanel(
        widthMeters: Float,
        heightMeters: Float,
        /**
         * The window's first ask: a mesh made for this window in another shape will do — it is
         * shown stretched to the film's own while that one is made ([ensurePanel]).
         */
        anyShape: Boolean = false,
    ) = meshMaking.withLock {
        // One at a time: the film's shape and its bars can both ask as it starts, and a second
        // asker waits for the first's mesh — the one it wants, as a rule — rather than make another.
        val crop = panelCrop()
        val (w, h) = AspectFit.panelSize(_aspectMode.value, aspectNow(), widthMeters, heightMeters, crop)
        val shape = PanelShape(w, h, stereoToDimension(panelStereo()), crop)
        if (manager !is SwitchingEngine || shape == panelShape || preparedMesh?.first == shape) return@withLock
        if (anyShape && panelShape == null && preparedWindow == (widthMeters to heightMeters) &&
            preparedMesh?.first?.let { it.crop == crop && it.dimension == shape.dimension } == true
        ) return@withLock
        val mesh = withContext(Dispatchers.Default) { VideoEntityAssembler.panelMesh(w, h, crop = crop) }
        preparedMesh = shape to mesh
        preparedWindow = widthMeters to heightMeters
    }

    private val meshMaking = kotlinx.coroutines.sync.Mutex()

    /**
     * The film's shape has just been told, and its mesh takes most of a second to make: until then
     * the panel up — made for the shape assumed before (16:9, or the last file's) — is stretched to
     * the film's own, so the picture is shown in its shape at once, not squeezed for that second.
     * Its rounded corners are a little out of round meanwhile; the made mesh then takes over.
     */
    private fun standInForShape() {
        val entity = panel ?: return
        val made = panelShape ?: return
        val crop = panelCrop()
        val (w, h) = AspectFit.panelSize(_aspectMode.value, aspectNow(), windowW, windowH, crop)
        // A change of bars or of 3D lays the picture differently on the mesh: no stretch stands in for that.
        if ((w == made.width && h == made.height) || made.crop != crop ||
            made.dimension != stereoToDimension(panelStereo())
        ) return
        stretch(entity, w / made.width, h / made.height)
        _frameRect.value = frameRectOf(w, h, crop, windowW, windowH)
        android.util.Log.i("AuraPerf", "panel stretched ${made.width}x${made.height} → ${w}x$h while its mesh is made")
    }

    /** The panel entity's scale: 1, 1 for its mesh as made. */
    private fun stretch(entity: Entity, x: Float, y: Float) {
        val t = entity.components[TransformComponent::class.java] ?: TransformComponent().also { entity.components.set(it) }
        t.setScaleVector(Vector3(x, y, 1f))
    }

    /**
     * The panel's surface, made and given to the engine as the first file starts rather than once the
     * window has made the panel: the decoder had nothing to draw into until the window was up and its
     * mesh made (1.6 s after setup on the emulator), and the first frame came 0.85 s after that. Now
     * it is decoded while the window opens, and is there as the panel appears. The texture is the
     * one [ensurePanel] puts on the panel.
     */
    private suspend fun giveSurfaceEarly() = withContext(Dispatchers.Main.immediate) {
        if (manager !is SwitchingEngine || panelSurface != null) return@withContext
        val texture = panelTexture ?: SurfaceRenderTexture().apply { toGlobal() }.also { panelTexture = it }
        panelSurface = texture.acquireSurface()
        if (!_immersive.value && screenPose() == null) manager.setVideoSurface(panelSurface)
    }

    /**
     * Lazily create + bind the planar video panel entity (reused across plays). The caller
     * passes the panel's physical size in meters — sized to the window so the video fills it.
     */
    fun ensurePanel(widthMeters: Float = windowW, heightMeters: Float = windowH): Entity {
        if (widthMeters != windowW || heightMeters != windowH || lastWindowSize() == null) {
            lastWindow.edit().putFloat("w", widthMeters).putFloat("h", heightMeters).apply()
        }
        windowW = widthMeters
        windowH = heightMeters
        // Fit the mesh inside the window plane per the current 画面比例; letterbox bars are the
        // window's own base panel showing through above/below (or beside) the picture.
        val crop = panelCrop()
        val (pw, ph) = AspectFit.panelSize(_aspectMode.value, aspectNow(), windowW, windowH, crop)
        panelW = pw
        panelH = ph
        _frameRect.value = frameRectOf(pw, ph, crop, windowW, windowH)
        val dimension = stereoToDimension(panelStereo())
        val entity = panel ?: Entity().also { panel = it }
        val pano = _pano.value
        when {
            // 全景 in the window: a view into it, the window's whole size, looked round by dragging.
            pano != null && manager is SwitchingEngine -> {
                panelW = windowW
                panelH = windowH
                _frameRect.value = frameRectOf(windowW, windowH, com.therealityteam.videoplayer.data.playback.FrameCrop.NONE, windowW, windowH)
                val texture = panelTexture ?: SurfaceRenderTexture().apply { toGlobal() }.also { panelTexture = it }
                if (panelSurface == null) panelSurface = texture.acquireSurface()
                if (!_immersive.value && screenPose() == null) manager.setVideoSurface(panelSurface)
                // The flat panel's shape is gone from it: made again when the film is flat once more.
                panelShape = null
                preparedMesh = null
                showPortal(entity, pano)
            }
            manager is SwitchingEngine -> {
                clearPortal(entity)
                val texture = panelTexture ?: SurfaceRenderTexture().apply { toGlobal() }
                    .also { panelTexture = it }
                var shape = PanelShape(panelW, panelH, dimension, crop)
                // The window's first panel, its mesh made off the main thread for a shape the film's
                // own overtook meanwhile (the decoder has the surface from the start, and finds out
                // while the mesh is made): shown as it was made, the picture already in it, and fitted
                // again off the main thread — a mesh made here held the main thread for most of a second.
                val overtaken = preparedMesh?.first?.takeIf {
                    panelShape == null && it != shape && it.dimension == dimension && it.crop == crop &&
                        preparedWindow == (windowW to windowH)
                }
                // …stretched to the shape it should have meanwhile, so the picture is never shown squeezed.
                var standIn: PanelShape? = null
                if (overtaken != null) {
                    standIn = shape
                    shape = overtaken
                    panelW = overtaken.width
                    panelH = overtaken.height
                    scope.launch { preparePanel(windowW, windowH); ensurePanel() }
                }
                if (shape != panelShape) {
                    val prepared = preparedMesh?.takeIf { it.first == shape }?.second
                    preparedMesh = null
                    VideoEntityAssembler.assembleSurfacePanel(entity, texture, panelW, panelH, dimension, prepared = prepared, crop = crop)
                    if (panelShape == null) android.util.Log.i("AuraPerf", "panel made (${if (prepared != null) "prepared" else "here"}), surface ${if (panelSurface == null) "new" else "given before"}")
                    panelShape = shape
                    stretch(entity, 1f, 1f)
                }
                standIn?.let { stretch(entity, it.width / shape.width, it.height / shape.height) }
                if (panelSurface == null) panelSurface = texture.acquireSurface()
                if (!_immersive.value && screenPose() == null) manager.setVideoSurface(panelSurface)
            }
            manager is PlaybackManager ->
                VideoEntityAssembler.assemblePanel(entity, manager.player, panelW, panelH, dimension)
        }
        // A 3D entity and the window's 2D content are sorted by two different systems, and the
        // depths never line up on their own: the picture could sit in front of the bar for
        // rendering AND for the ray, so a click on the bar's buttons went to the video and a click
        // on the picture went nowhere. This is the SDK's own answer to that (see the video FAQ on
        // controls obscured by the panel): put the entity in the SAME sort as the 2D UI, a little
        // behind it. The ray then reaches the window's content — the bar, and the layer over the
        // picture that toggles it — as an ordinary press. No collider: a collider made the entity
        // the thing the ray stopped at, and the bar's buttons died behind it.
        if (!entity.components.has(SortAsUIElementComponent::class.java)) {
            entity.components.set(SortAsUIElementComponent(distanceBias = -0.1f))
        }
        // The place's screen shows the same film: fitted again whenever the panel is.
        if (screenPose() != null) ensureScreen()
        // Made (or fitted) while the film is in 沉浸空间: the panel stays hidden behind the sphere's picture.
        if (_immersive.value && sphereReady) entity.enabled = false
        if (pictureHeld) entity.enabled = false
        return entity
    }

    /**
     * The window's picture kept back while a window opens, until its bar has settled in front of it ([holdWindowPicture]):
     * the bar is a window of its own, attached to the player's after it, and the window's depth settles again once it
     * is — so for a moment the picture stood in front of the bar, and the bar came forward through it (B3110, 2026-10-23).
     */
    private var pictureHeld = false

    fun holdWindowPicture(held: Boolean) {
        if (pictureHeld == held) return
        pictureHeld = held
        // The panel only, as routePicture() would show it — not the engine's surface (mpv takes one again from scratch).
        val inWindow = (_immersive.value && !sphereReady) || (!_immersive.value && screenPose() == null)
        panel?.enabled = inWindow && !_capture.value && !held
    }

    /**
     * The place's screen, made on first use and hung on the stage by the immersive scene: a panel of
     * the film fitted inside the screen's face (画面比例 as in the window), at the screen's spot,
     * turned to face where it faces. Square-cornered, as a TV's glass is. Hidden until [routePicture]
     * puts the film on it.
     */
    fun ensureScreen(): Entity {
        val entity = screen ?: Entity().also {
            it.setName("aura_screen")
            it.enabled = false
            it.addChild(screenCaptions.entity)
            it.addChild(screenCaptions.assEntity)
            screen = it
        }
        val pose = screenPose() ?: return entity
        val crop = panelCrop()
        val (w, h) = AspectFit.panelSize(_aspectMode.value, aspectNow(), pose.width, pose.height, crop)
        _screenFrameRect.value = frameRectOf(w, h, crop, pose.width, pose.height)
        val dimension = stereoToDimension(panelStereo())
        val shape = PanelShape(w, h, dimension, crop)
        when (manager) {
            is SwitchingEngine -> {
                val texture = screenTexture ?: SurfaceRenderTexture().apply { toGlobal() }
                    .also { screenTexture = it }
                if (shape != screenShape) {
                    VideoEntityAssembler.assembleSurfacePanel(entity, texture, w, h, dimension, cornerRadius = 0f, crop = crop)
                    screenShape = shape
                    screenReflection.sourceChanged()
                }
                if (screenSurface == null) screenSurface = texture.acquireSurface()
            }
            is PlaybackManager -> if (shape != screenShape) {
                VideoEntityAssembler.assemblePanel(entity, manager.player, w, h, dimension)
                screenShape = shape
                screenReflection.sourceChanged()
            }
        }
        val t = entity.components[TransformComponent::class.java]
            ?: TransformComponent().also { entity.components.set(it) }
        t.setPosition(Vector3(pose.x, pose.y, pose.z))
        t.setEulerAngles(EulerAngles(0f, pose.yaw, 0f))
        fitBacking(entity, pose)
        screenReflection.show(entity, entity.enabled && pose.backing && _screenGlow.value)
        screenCaptions.fit(pose.width, pose.height, distance = kotlin.math.hypot(pose.x, pose.z))
        return entity
    }

    /**
     * The dark face of a screen hung in the air ([ScreenPose.backing]): the whole face, letterbox and
     * all, with a border of [SCREEN_BEZEL] of its width round it, a centimetre behind the film.
     * Unlit and near-black, as an unlit screen is; it does not dim with the room, being dark already.
     */
    private fun fitBacking(screen: Entity, pose: ScreenPose) {
        if (!pose.backing) {
            screenBacking?.enabled = false
            return
        }
        val backing = screenBacking ?: Entity().also {
            it.setName("aura_screen_backing")
            val face = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(SCREEN_FACE, SCREEN_FACE, SCREEN_FACE, 1f))
                setApplyToneMapping(false)
            }
            it.components.set(ModelComponent(MeshResource.createPlane(1f, 1f), face))
            screen.addChild(it)
            screenBacking = it
        }
        val border = pose.width * SCREEN_BEZEL
        val t = backing.components[TransformComponent::class.java]
            ?: TransformComponent().also { backing.components.set(it) }
        t.setScaleVector(Vector3(pose.width + 2 * border, pose.height + 2 * border, 1f))
        t.setPosition(Vector3(0f, 0f, -SCREEN_BACKING_GAP))
        backing.enabled = true
        screenReflection.fit(screen, pose.width + 2 * border, pose.height + 2 * border, -SCREEN_BACKING_GAP / 2)
    }

    /**
     * Puts the film where it is watched — the sphere in 沉浸空间; the place's screen when it has one
     * and 在环境的屏幕上播放 is on; else the window's panel — showing that one, hiding the others, and
     * pointing the engine's one output surface at it.
     */
    private fun routePicture() {
        val engine = manager as? SwitchingEngine
        val hidden = _capture.value
        when {
            // The sphere still being made (the stage's opening keeps the engine busy a few seconds): the
            // film stays in the window meanwhile, and goes round the viewer once its sphere is in.
            _immersive.value && !sphereReady -> {
                screen?.enabled = false
                sphere?.enabled = false
                panel?.enabled = !hidden && !pictureHeld
                engine?.setVideoSurface(panelSurface)
            }
            _immersive.value -> {
                screen?.enabled = false
                panel?.enabled = false
                sphere?.enabled = true
                engine?.setVideoSurface(sphereSurface ?: panelSurface)
            }
            screenPose() != null -> {
                val entity = ensureScreen()
                entity.enabled = !hidden
                panel?.enabled = false
                sphere?.enabled = false
                engine?.setVideoSurface(screenSurface ?: panelSurface)
            }
            else -> {
                screen?.enabled = false
                screenCaptions.clear()
                screenCaptions.clearAss()
                sphere?.enabled = false
                panel?.enabled = !hidden && !pictureHeld
                engine?.setVideoSurface(panelSurface)
            }
        }
        screen?.let { screenReflection.show(it, it.enabled && screenPose()?.backing == true && _screenGlow.value) }
        aimSound()
    }

    /**
     * 全景声: where the film's sound field lies from the listener. Round the viewer, the stage's system
     * turns it with the head every frame (PanoFollowSystem); a panorama in the window is heard the way
     * the window looks into it; anything else, ahead as filmed.
     */
    private fun aimSound() {
        if (_immersive.value && sphereReady) return
        com.therealityteam.videoplayer.data.playback.SoundField.rotation =
            if (panoInWindow) com.therealityteam.videoplayer.data.playback.SoundField.look(panoLook.yaw, panoLook.pitch)
            else com.therealityteam.videoplayer.data.playback.SoundField.IDENTITY
    }

    // 全景: how the playing file's picture surrounds the viewer — what the viewer chose (显示 › 投影), else
    // what the file says of itself (SphericalMetadata: Google's V1/V2, Apple's vexu, Matroska's
    // Projection), else its name (ProjectionDetector, at the scan). Null for a flat film.
    private val _pano = MutableStateFlow<com.therealityteam.videoplayer.data.playback.PanoLayout?>(null)
    val pano: StateFlow<com.therealityteam.videoplayer.data.playback.PanoLayout?> = _pano
    /** What the playing file says of itself, once read (null before, or when it said nothing). */
    private var panoInfo: com.therealityteam.videoplayer.data.playback.SphericalInfo?
        get() = _panoFile.value
        set(v) { _panoFile.value = v }
    private val _panoFile = MutableStateFlow<com.therealityteam.videoplayer.data.playback.SphericalInfo?>(null)
    /** What the playing file's header says of its picture — a sphere, a spatial video's two views — once read. */
    val panoFile: StateFlow<com.therealityteam.videoplayer.data.playback.SphericalInfo?> = _panoFile
    /** The item whose layout is settled — its file read, or there was nothing to read. */
    private val _panoSettled = MutableStateFlow<String?>(null)
    val panoSettled: StateFlow<String?> = _panoSettled
    /** The playing file's sound field (全景声: SA3D, Opus family 2) — 音轨 marks its track. */
    private val _soundField = MutableStateFlow<com.therealityteam.videoplayer.data.playback.AmbisonicAudio?>(null)
    val soundField: StateFlow<com.therealityteam.videoplayer.data.playback.AmbisonicAudio?> = _soundField
    private fun setSoundField(audio: com.therealityteam.videoplayer.data.playback.AmbisonicAudio?) {
        _soundField.value = audio
        manager.setAmbisonic(audio)
    }

    /** The playing file has a mesh of its own (a mesh projection) — 全景格式 offers it. */
    val fileHasMesh: Boolean get() = panoInfo?.mesh == true

    /** 全景视频自动沉浸 (设置 › 播放). */
    val panoAutoImmersive: Boolean get() = settingsRepo.get().panoAutoImmersive
    private var panoJob: Job? = null

    /** The layout [item] is shown in: the viewer's choice first, then the file's word, then its name. */
    private fun panoOf(item: MediaItem): com.therealityteam.videoplayer.data.playback.PanoLayout? =
        layoutOf(item)?.let { l ->
            // A plain cubemap's faces lie by its frame's shape, which the frame's aspect tells.
            if (l.projection == ProjectionMode.CUBEMAP && l.frameWidth == 0) l.copy(frameAspect = manager.videoAspect.value) else l
        }

    private fun layoutOf(item: MediaItem): com.therealityteam.videoplayer.data.playback.PanoLayout? =
        com.therealityteam.videoplayer.data.playback.PanoLayout.forFilm(item.video, item.fileName, panoInfo)

    /**
     * Reads what [item]'s file says of itself as a 360°/180° picture: its header, from the headset's
     * copy or the file itself, or — for a share or a server — through the URL the engine plays, once set.
     */
    private fun readPano(item: MediaItem, started: Job) {
        panoJob?.cancel()
        panoInfo = null
        setSoundField(null)
        // The remote's rows open for a film, not for every film after it.
        remoteTurning.value = false
        remoteFormat.value = false
        keptLevel(item.id).let { (tilt, roll) -> panoTilt = tilt; panoRoll = roll }
        // A new film is looked at from its front, through the window's view as wide as it was.
        panoLook = com.therealityteam.videoplayer.data.playback.PanoViewport.Look(fov = WINDOW_FOV)
        _pano.value = panoOf(item)
        _panoSettled.value = null
        panoJob = scope.launch {
            val local = localCopy(item)?.path ?: item.path.takeIf { it.startsWith("/") } ?: item.path.takeIf { it.startsWith("file://") }?.removePrefix("file://")
            val info = if (local != null) {
                withContext(Dispatchers.IO) { runCatching { com.therealityteam.videoplayer.data.playback.SphericalMetadata.read(com.therealityteam.videoplayer.data.playback.FileByteSource(java.io.File(local))) }.getOrNull() }
            } else {
                started.join()
                val url = manager.currentUri()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (url == null) { _panoSettled.value = item.id; return@launch }
                withContext(Dispatchers.IO) { runCatching { com.therealityteam.videoplayer.data.playback.SphericalMetadata.read(com.therealityteam.videoplayer.data.playback.HttpByteSource(url)) }.getOrNull() }
            }
            if (_current.value?.id != item.id) return@launch
            if (info != null && (info.isSpherical || info.stereo != null || info.ambisonic != null)) android.util.Log.i("AuraPano", "${item.fileName}: $info")
            panoInfo = info
            // 全景声: a sound-field track is heard turned with the head and the picture.
            setSoundField(info?.ambisonic)
            // The library learns what the file said: its 360°/180° tag, and the layout known next time
            // before the header is read (VideoFlags.fromFile — kept when a scan reads the name again).
            // (The film as it is now: a format chosen while the header was on its way is the viewer's word.)
            val now = _current.value?.takeIf { it.id == item.id } ?: return@launch
            info?.learnedFlags(now.video)?.takeIf { it != now.video }?.let { learned ->
                android.util.Log.i("AuraPano", "${item.fileName}: the library learns $learned")
                withContext(Dispatchers.IO) { repo.learnVideoFlags(item.id, learned) }
                // The window shows it so at once: a flat film the file marks as 3D splits its frame between the eyes
                // (or, the right eye first, shows the left eye's half — leftEyeCrop).
                val before = now.video
                _current.value = _current.value?.takeIf { it.id == item.id }?.copy(video = learned) ?: return@launch
                if ((learned.stereo != before.stereo || learned.rightFirst != before.rightFirst) && panel != null) {
                    standInForShape()
                    preparePanel(windowW, windowH)
                    ensurePanel()
                }
            }
            val was = _pano.value
            _pano.value = panoOf(_current.value?.takeIf { it.id == item.id } ?: return@launch)
            // A panorama: its sphere's mesh begun now, before the stage asks for it.
            _pano.value?.let { prepareSphere(it) }
            _panoSettled.value = item.id
            // The window's picture follows: a view into a panorama, or the flat frame again.
            if (panel != null && was != _pano.value) ensurePanel()
            // Made when it is shown: the stage asks for the sphere as it opens (ensureSphere).
            if (sphere != null && _immersive.value) buildSphere()
        }
    }

    /**
     * The sphere entity, made once and hung on the stage by the immersive scene; its mesh follows [pano].
     * The stage opens for a 虚拟环境 too, where nothing goes round the viewer: the mesh and its picture
     * surface are made only once 沉浸空间 is asked for (here, or by [setImmersive] on an open stage).
     */
    fun ensureSphere(): Entity {
        val entity = sphere ?: Entity().also {
            it.setName("aura_pano")
            it.enabled = false
            sphere = it
        }
        if (_immersive.value) buildSphere()
        return entity
    }

    /** The layout the sphere was last made for, and the mesh being made for the next. */
    private var sphereLayout: com.therealityteam.videoplayer.data.playback.PanoLayout? = null
    private var sphereJob: Job? = null

    /** Whether the sphere is made and its picture can go on it ([buildSphere] done). */
    private val sphereReady: Boolean get() = sphere != null && sphereLayout != null && !sphereBuilding

    /** A sphere for a new layout is being made (from [buildSphere]'s start until its picture can go on). */
    private var sphereBuilding = false

    /**
     * (Re)makes the sphere for the playing file's layout — a flat film taken into 沉浸空间 by hand is
     * shown on the whole sphere, as before. The mesh is made off the main thread, put on there; the
     * picture goes to it once it is ready.
     */
    private fun buildSphere() {
        val entity = sphere ?: return
        val stereo = _current.value?.video?.stereo
        val layout = _pano.value ?: com.therealityteam.videoplayer.data.playback.PanoLayout.of(
            ProjectionMode.EQ360, stereo?.takeIf { it == StereoMode.SBS || it == StereoMode.OU } ?: StereoMode.MONO,
        )
        if (layout == sphereLayout && sphereJob?.isActive != true) { routePicture(); return }
        if (layout == sphereLayout) return
        sphereJob?.cancel()
        sphereBuilding = true
        sphereJob = scope.launch {
            val began = android.os.SystemClock.elapsedRealtime()
            val mesh = sphereMeshFor(layout)
            val t1 = android.os.SystemClock.elapsedRealtime()
            val dimension = stereoToDimension(layout.shownStereo)
            when (manager) {
                is SwitchingEngine -> {
                    val texture = sphereTexture ?: SurfaceRenderTexture().apply { toGlobal() }.also { sphereTexture = it }
                    VideoEntityAssembler.assembleSurfacePano(entity, texture, mesh, dimension)
                    if (sphereSurface == null) sphereSurface = texture.acquireSurface()
                }
                is PlaybackManager -> VideoEntityAssembler.assemblePano(entity, manager.player, mesh, dimension)
            }
            orientSphere(entity, panoInfo.takeIf { _current.value?.video?.userOverridden != true })
            sphereLayout = layout
            sphereBuilding = false
            routePicture()
            android.util.Log.i("AuraPano", "sphere: ${layout.projection} ${layout.stereo} in ${android.os.SystemClock.elapsedRealtime() - began}ms (mesh ${t1 - began})")
            // A film's sphere, first shown: its front where the viewer looks (the head is tracked a
            // moment after the stage opens).
            val id = _current.value?.id
            if (id != null && recenteredFor != id) {
                recenteredFor = id
                for (attempt in 0 until RECENTER_TRIES) {
                    // Turned by hand meanwhile: that is the way it faces.
                    if (panoTurnedFor == id || recenterPano()) break
                    delay(RECENTER_WAIT_MS)
                }
            }
        }
    }

    /**
     * The sphere's mesh for a layout, made ahead: as soon as a film is known to be a panorama its mesh
     * is begun off the main thread (a second on the emulator), so the stage — whose opening keeps the
     * main thread busy — only has to put it on.
     */
    private var spherePrepared: Pair<com.therealityteam.videoplayer.data.playback.PanoLayout, kotlinx.coroutines.Deferred<com.pico.spatial.core.ecs.resource.MeshResource>>? = null

    private suspend fun sphereMeshFor(layout: com.therealityteam.videoplayer.data.playback.PanoLayout): com.pico.spatial.core.ecs.resource.MeshResource {
        prepareSphere(layout)
        return spherePrepared!!.second.await()
    }

    /** Begins [layout]'s sphere mesh off the main thread, unless it is begun already. */
    private fun prepareSphere(layout: com.therealityteam.videoplayer.data.playback.PanoLayout) {
        if (spherePrepared?.first == layout) return
        // Not on Default: a film opened at launch finds it full of the library's grouping for seconds
        // (a sphere waited 6.7 s there); a mesh is tens of milliseconds of work, IO's pool takes it at once.
        spherePrepared = layout to scope.async(Dispatchers.IO) { VideoEntityAssembler.panoMesh(layout) }
    }

    /** The film whose picture was turned to face the viewer as its sphere first showed. */
    private var recenteredFor: String? = null
    /** The film whose picture the viewer turned by hand (左转 / 右转). */
    private var panoTurnedFor: String? = null

    /**
     * The picture's own orientation (the file's pose: yaw about up, then pitch about right, then roll
     * about forward, each counter-clockwise, as Google's spherical metadata has them) and the turn the
     * viewer gave it ([panoYaw]).
     */
    private fun orientSphere(entity: Entity, info: com.therealityteam.videoplayer.data.playback.SphericalInfo?) {
        val rad = { deg: Float -> Math.toRadians(deg.toDouble()).toFloat() }
        val yaw = Quat(Vector3(0f, 1f, 0f), rad((info?.poseYaw ?: 0f) + panoYaw))
        val pitch = Quat(Vector3(1f, 0f, 0f), rad((info?.posePitch ?: 0f) + panoTilt))
        val roll = Quat(Vector3(0f, 0f, -1f), rad((info?.poseRoll ?: 0f) + panoRoll))
        val t = entity.components[TransformComponent::class.java] ?: TransformComponent().also { entity.components.set(it) }
        t.setQuaternion(yaw * pitch * roll)
    }

    /** The turn the viewer gave the picture (回正 sets it), degrees counter-clockwise. */
    private var panoYaw = 0f

    /**
     * 回正: the picture's front where the head looks now — however the viewer sits, the film's own
     * forward is ahead of them. Asked for by hand, and once as a film's sphere first shows.
     */
    fun recenterPano(): Boolean {
        val entity = sphere ?: return false
        val space = entity.getParent() ?: return false
        val yaw = com.therealityteam.videoplayer.ui.immersive.PanoFollow.headYaw(space) ?: return false
        panoYaw = yaw
        orientSphere(entity, panoInfo.takeIf { _current.value?.video?.userOverridden != true })
        android.util.Log.i("AuraPano", "recentred to %.1f°".format(yaw))
        return true
    }

    /** A tilt of the picture, for looking at its poles where the head does not move (the emulator, aura.pano tilt:). */
    private var panoTilt = 0f

    fun tiltPano(degrees: Float) {
        val entity = sphere ?: return
        panoTilt = degrees
        orientSphere(entity, panoInfo.takeIf { _current.value?.video?.userOverridden != true })
    }

    /** 沉浸空间's remote has 画面方向 open (PanoRemote): kept while the stage is, so a look away keeps it. */
    val remoteTurning = MutableStateFlow(false)

    /** The remote's 全景格式, opened above it: in 沉浸空间 a wrong guess at the format is put right where the viewer is. */
    val remoteFormat = MutableStateFlow(false)

    val remoteAtEyeLevel = settingsRepo.settings.map { it.panoRemoteAtEyeLevel }
        .stateIn(scope, SharingStarted.Eagerly, settingsRepo.get().panoRemoteAtEyeLevel)

    fun setRemoteAtEyeLevel(raised: Boolean) {
        val settings = settingsRepo.get()
        if (settings.panoRemoteAtEyeLevel != raised) {
            settingsRepo.update(settings.copy(panoRemoteAtEyeLevel = raised))
        }
    }

    fun toggleRemoteHeight() = setRemoteAtEyeLevel(!settingsRepo.get().panoRemoteAtEyeLevel)

    /** The picture rolled about the line of sight (clockwise positive): a camera held crooked, levelled by hand. */
    private var panoRoll = 0f

    /** Per film, the level the viewer gave its picture ("tilt,roll"): a crooked camera stays put right. */
    private val levels = appContext.getSharedPreferences("aura_pano_level", Context.MODE_PRIVATE)

    /**
     * 上移 / 下移, 左旋 / 右旋: the picture moved [pitch] degrees up and rolled [roll] clockwise about
     * the line of sight — a horizon a crooked camera left low, high or tipped, set level — and kept for
     * this film.
     */
    fun nudgeLevel(pitch: Float, roll: Float) {
        panoTilt = (panoTilt + pitch).coerceIn(-LEVEL_LIMIT, LEVEL_LIMIT)
        panoRoll = (panoRoll + roll).coerceIn(-LEVEL_LIMIT, LEVEL_LIMIT)
        keepLevel()
    }

    /** 放平: the picture as filmed (the file's own pose only). */
    fun resetLevel() {
        panoTilt = 0f
        panoRoll = 0f
        keepLevel()
    }

    private fun keepLevel() {
        val id = _current.value?.id ?: return
        levels.edit().apply { if (panoTilt == 0f && panoRoll == 0f) remove(id) else putString(id, "$panoTilt,$panoRoll") }.apply()
        android.util.Log.i("AuraPano", "level: up %.0f°, rolled %.0f°".format(panoTilt, panoRoll))
        sphere?.let { orientSphere(it, panoInfo.takeIf { _current.value?.video?.userOverridden != true }) }
    }

    /** [id]'s level as the viewer left it, or level. */
    private fun keptLevel(id: String): Pair<Float, Float> =
        levels.getString(id, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (0f to 0f)

    /** Turns the picture round the viewer by [degrees] (counter-clockwise positive): a seat cannot turn round. */
    fun turnPano(degrees: Float) {
        val entity = sphere ?: return
        panoTurnedFor = _current.value?.id
        panoYaw = ((panoYaw + degrees + 540f) % 360f) - 180f
        orientSphere(entity, panoInfo.takeIf { _current.value?.video?.userOverridden != true })
    }

    // 全景 in the window: where the window looks into the panorama (dragged round). Its fov is what the
    // window spans of the sphere seen from a seat before it (the portal shows the picture at its own
    // scale, as a real window would): what a drag across it turns, and how far a 180 may be looked round.
    private var panoLook = com.therealityteam.videoplayer.data.playback.PanoViewport.Look(fov = WINDOW_FOV)

    /** Whether the window shows a panorama's view — what a drag on the picture turns. */
    val panoInWindow: Boolean get() = _pano.value != null && !_immersive.value && panel != null

    /** A drag across the picture of ([dx], [dy]) of its width and height: the view follows the hand, as a held picture would. */
    fun lookPanoBy(dx: Float, dy: Float) {
        val layout = _pano.value ?: return
        val l = panoLook
        val vfov = l.fov * windowH / windowW
        val yaw = ((l.yaw - dx * l.fov + 540f) % 360f) - 180f
        val pitch = (l.pitch + dy * vfov).coerceIn(-85f, 85f)
        // At the picture's edge (a 180, a fisheye) the view stops, along that way only.
        val aspect = windowW / windowH
        val fit = { c: com.therealityteam.videoplayer.data.playback.PanoViewport.Look -> com.therealityteam.videoplayer.data.playback.PanoViewport.fits(layout, c, aspect) }
        panoLook = listOf(l.copy(yaw = yaw, pitch = pitch), l.copy(yaw = yaw), l.copy(pitch = pitch)).firstOrNull(fit) ?: return
        lookInWindow()
    }

    /** The window's view set outright (aura.pano look:), degrees. */
    fun lookPanoTo(yaw: Float, pitch: Float) {
        panoLook = panoLook.copy(yaw = yaw, pitch = pitch.coerceIn(-85f, 85f))
        lookInWindow()
    }

    private fun lookInWindow() {
        aimSound()
        // The window's sphere turned so the look is straight through the window (the look's yaw right,
        // pitch up undone): no mesh made, so a drag follows the hand.
        val sphere = portalSphere ?: return
        val l = panoLook
        val rad = { deg: Float -> Math.toRadians(deg.toDouble()).toFloat() }
        val t = sphere.components[TransformComponent::class.java] ?: TransformComponent().also { sphere.components.set(it) }
        t.setQuaternion(Quat(Vector3(1f, 0f, 0f), rad(-l.pitch)) * Quat(Vector3(0f, 1f, 0f), rad(l.yaw)))
    }

    // 全景 in the window, as through a real window: the panel's face a portal into a world of its own
    // with the film's sphere in it (VideoEntityAssembler.assemblePortal) — seen from where the eyes are,
    // in stereo for a stereo film, and turned by a drag without a mesh made (a view cut and made anew
    // took the SDK half a second or more each time: a drag lagged the hand).
    private var portalWorld: Entity? = null
    private var portalSphere: Entity? = null
    private var portalFor: Pair<com.therealityteam.videoplayer.data.playback.PanoLayout, Pair<Float, Float>>? = null
    private var portalJob: Job? = null

    private fun showPortal(entity: Entity, layout: com.therealityteam.videoplayer.data.playback.PanoLayout) {
        val size = windowW to windowH
        if (portalFor == layout to size) { lookInWindow(); return }
        portalJob?.cancel()
        portalJob = scope.launch {
            val began = android.os.SystemClock.elapsedRealtime()
            val mesh = sphereMeshFor(layout)
            val texture = panelTexture ?: return@launch
            if (_pano.value != layout || panel !== entity) return@launch
            val world = portalWorld ?: Entity().apply {
                setName("aura_pano_world")
                components.set(com.pico.spatial.core.ecs.PortalWorldComponent())
            }.also { portalWorld = it }
            val ball = portalSphere ?: Entity().apply { setName("aura_pano_window_sphere") }.also { portalSphere = it; world.addChild(it) }
            VideoEntityAssembler.assembleSurfacePano(ball, texture, mesh, stereoToDimension(layout.shownStereo))
            if (world.getParent() == null) entity.addChild(world)
            VideoEntityAssembler.assemblePortal(entity, world, windowW, windowH)
            portalFor = layout to size
            lookInWindow()
            android.util.Log.i("AuraPano", "window: a portal onto the sphere in ${android.os.SystemClock.elapsedRealtime() - began} ms")
        }
    }

    /** The window's face back to a panel: the film is flat (or gone). */
    private fun clearPortal(entity: Entity) {
        if (portalFor == null && portalJob?.isActive != true) return
        portalJob?.cancel()
        VideoEntityAssembler.clearPortal(entity)
        portalWorld?.let { entity.removeChild(it) }
        portalFor = null
    }

    /** 全景格式 › 文件网格: the viewer's word taken back — the file's own mesh (or its name) decides again. */
    fun clearPanoFormat() {
        val item = _current.value ?: return
        repo.clearVideoFlags(item.id)
        val flags = com.therealityteam.videoplayer.data.playback.ProjectionDetector.detect(item.fileName)
        _current.value = item.copy(video = flags)
        _pano.value = panoOf(_current.value!!)
        if (panel != null) ensurePanel()
        if (sphere != null && _immersive.value) buildSphere()
    }

    /** 全景格式: the viewer's word on how this film's picture lies — projection, and a fisheye's lens. */
    fun setPanoFormat(projection: ProjectionMode, lensFov: Int = 0, stereoAs: StereoMode? = null) {
        val item = _current.value ?: return
        val stereo = stereoAs ?: _pano.value?.stereo ?: item.video.stereo
        val flags = item.video.copy(stereo = stereo, projection = projection, lensFov = lensFov, userOverridden = true)
        repo.setVideoFlags(item.id, flags)
        _current.value = item.copy(video = flags)
        _pano.value = panoOf(_current.value!!)
        if (panel != null) ensurePanel()
        if (sphere != null && _immersive.value) buildSphere()
    }

    private var spaceChange: Job? = null

    /** Keep a user's stage change alive across the two UI surfaces. A newer choice supersedes it. */
    private fun changeSpace(change: suspend () -> Unit) {
        spaceChange?.cancel()
        cancelEntering()
        spaceChange = scope.launch(start = CoroutineStart.UNDISPATCHED) { change() }
    }

    fun returnToWindow(navigator: SpatialNavigator) {
        changeSpace {
            navigator.closeStage()
            setImmersive(false)
            setCinemaDim(false)
            setEnvironment(null)
        }
    }

    fun toggleImmersive(navigator: SpatialNavigator) {
        changeSpace {
            if (_immersive.value) {
                navigator.closeStage()
                setImmersive(false)
            } else {
                if (_cinemaDim.value || _environment.value != null) navigator.closeStage()
                navigator.openStage(STAGE_ID, StageStyle.Full)
                setImmersive(true)
            }
        }
    }

    fun toggleCinema(navigator: SpatialNavigator) {
        changeSpace {
            if (_cinemaDim.value) {
                navigator.closeStage()
                setCinemaDim(false)
            } else {
                if (_immersive.value || _environment.value != null) navigator.closeStage()
                navigator.openStage(STAGE_ID, StageStyle.Progressive)
                setCinemaDim(true)
            }
        }
    }

    fun selectEnvironment(navigator: SpatialNavigator, next: Environment?) {
        changeSpace {
            when {
                next == null -> {
                    val stageOpen = _environment.value != null
                    setEnvironment(null)
                    if (stageOpen) navigator.closeStage()
                }
                _environment.value != null -> setEnvironment(next)
                prepareEnvironment(next) -> {
                    if (_immersive.value || _cinemaDim.value) navigator.closeStage()
                    setEnvironment(next)
                    navigator.openStage(STAGE_ID, StageStyle.Full)
                }
            }
        }
    }

    fun setImmersive(value: Boolean) {
        _immersive.value = value
        if (value) {
            _cinemaDim.value = false
            cancelEntering()
            leaveEnvironment()
            // A stage opened for a place has its sphere hung but not made: made now, the film goes
            // round the viewer once it is (routePicture keeps it in the window until then).
            if (sphere != null) buildSphere()
        }
        // The external engine renders into one surface at a time — retarget it.
        routePicture()
    }

    fun setCinemaDim(value: Boolean) {
        _cinemaDim.value = value
        if (value) {
            _immersive.value = false
            cancelEntering()
            leaveEnvironment()
            routePicture()
        }
    }

    /**
     * Enter [env], or leave with null. The picture goes to the place's screen, or stays on the panel
     * where it has none; the sphere's space, if on, ends. A catalogue environment becomes the one
     * 虚拟环境 opens with next time.
     */
    fun setEnvironment(env: Environment?) {
        if (env == null) {
            cancelEntering()
            leaveEnvironment()
            return
        }
        _placeFailed.value = null
        _closeStage.value = false
        environmentScene.discardPendingRead(keep = env)
        _environment.value = env
        _immersive.value = false
        _cinemaDim.value = false
        routePicture()
    }

    private fun leaveEnvironment() {
        _closeStage.value = false
        if (_environment.value == null) return
        _environment.value = null
        _placeShown.value = null
        environmentScene.clear()
        routePicture()
    }

    /** The environment 虚拟环境 opens with: the one chosen last, else the first on offer. */
    fun lastEnvironment(): Environment =
        Environments.byId(settingsRepo.get().lastEnvironment) ?: Environments.all.first()

    /** Thumbnail capture: hide the picture (the bar hides itself on [capture]). */
    fun setCapture(on: Boolean) {
        _capture.value = on
        routePicture()
    }

    /** 画面比例: re-fit the flat panel to a new aspect mode (no effect while immersive). */
    // 去黑边: the black bars stored in the frame (Letterbox), off the panel in 自动 — a scope film is a
    // scope panel. Found in frames the engine shows, sampled through the first minute and a half,
    // and remembered per file (CropStore): a film looked at once opens in its shape.
    private val _frameCrop = MutableStateFlow(com.therealityteam.videoplayer.data.playback.FrameCrop.NONE)
    val frameCrop: StateFlow<com.therealityteam.videoplayer.data.playback.FrameCrop> = _frameCrop
    private var cropJob: Job? = null
    /** A crop set by hand (`aura.crop`), held until the next file. */
    private var cropForced = false

    /**
     * The crop the panel takes: a right-eye-first frame's left eye's half (leftEyeCrop), whatever the 画面比例;
     * else in 自动, on a flat film — a 3D frame's halves are not bars — and not while mpv draws a Blu-ray's
     * subtitles into the picture: they sit in the bars.
     */
    private fun panelCrop(): com.therealityteam.videoplayer.data.playback.FrameCrop =
        leftEyeCrop() ?: if (_aspectMode.value == AspectMode.AUTO && stereoToDimension(_current.value?.video?.stereo) == VideoDimensionMode.MONO &&
            !manager.subtitlesInPicture.value && manager.subtitleTracks.value.none { it.selected && it.bitmapSubtitle }
        ) {
            _frameCrop.value
        } else {
            com.therealityteam.videoplayer.data.playback.FrameCrop.NONE
        }

    // 全景 by its look: a film with no word of its own (no metadata, nothing in its name) in a panorama's
    // shape has a few of its frames looked at (ProjectionGuess); when they agree, the viewer is asked.
    private val _panoGuess = MutableStateFlow<com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess?>(null)
    val panoGuess: StateFlow<com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess?> = _panoGuess
    private var guessJob: Job? = null
    /** Per film: what its frames looked like ("EQ360/MONO"), or that they looked like nothing (none) — asked once. */
    private val guesses = appContext.getSharedPreferences("aura_pano_guess", Context.MODE_PRIVATE)

    private fun startPanoGuess(item: MediaItem) {
        guessJob?.cancel()
        _panoGuess.value = null
        if (item.video.userOverridden) return
        guessJob = scope.launch {
            // The file's own word first: metadata, or its name.
            _panoSettled.first { it == item.id }
            if (_pano.value != null || _current.value?.id != item.id) return@launch
            val known = guesses.getString(item.id, null)
            if (known == GUESS_NONE) return@launch
            val remembered = known?.split('/')?.takeIf { it.size == 2 }?.let { (p, st) ->
                runCatching { com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess(ProjectionMode.valueOf(p), StereoMode.valueOf(st)) }.getOrNull()
            }
            if (remembered != null) { _panoGuess.value = remembered; return@launch }
            val aspect = manager.videoAspect.first { it > 0f }
            if (GUESS_SHAPES.none { kotlin.math.abs(aspect / it - 1f) < 0.04f }) return@launch
            val width = GUESS_WIDTH
            val height = (width / aspect).toInt().coerceAtLeast(2) / 2 * 2
            val found = mutableListOf<com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess?>()
            var lastAt = -GUESS_EVERY_MS
            var tries = 0
            kotlinx.coroutines.delay(GUESS_FIRST_MS)
            while (found.size < GUESS_FRAMES && tries++ < GUESS_FRAMES * 4) {
                kotlinx.coroutines.delay(1_000)
                val at = manager.position.value
                if (manager.state.value != PlaybackState.PLAYING || at - lastAt < GUESS_EVERY_MS) continue
                val surface = pictureSurface() ?: continue
                val pixels = com.therealityteam.videoplayer.player.LetterboxProbe.copy(surface, width, height) ?: continue
                val frame = withContext(Dispatchers.IO) { com.therealityteam.videoplayer.data.playback.ProjectionGuess.Luma.ofArgb(pixels, width, height) }
                // Debugging (adb shell setprop log.tag.AuraPanoGuess DEBUG): the frames looked at, kept in the cache.
                if (android.util.Log.isLoggable("AuraPanoGuess", android.util.Log.DEBUG)) withContext(Dispatchers.IO) {
                    runCatching {
                        java.io.File(appContext.cacheDir, "pano_guess_${found.size}.png").outputStream().use {
                            android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                // A dark frame (a fade, a night) says nothing either way.
                if (frame.v.average() < 0.06) continue
                found += withContext(Dispatchers.IO) { com.therealityteam.videoplayer.data.playback.ProjectionGuess.guess(frame) }
                lastAt = at
            }
            val verdict = com.therealityteam.videoplayer.data.playback.ProjectionGuess.agree(found)
            android.util.Log.i("AuraPano", "${item.fileName}: looks like ${verdict ?: "a flat film"} (frames: $found)")
            if (found.size < 2) return@launch // too few to say, and not remembered
            guesses.edit().putString(item.id, verdict?.let { "${it.projection}/${it.stereo}" } ?: GUESS_NONE).apply()
            if (_current.value?.id == item.id && _pano.value == null) _panoGuess.value = verdict
        }
    }

    /** 全景播放: the look taken for the viewer's word (as 全景格式 would have it), and round the viewer it goes. */
    fun acceptPanoGuess() {
        val guess = _panoGuess.value ?: return
        _panoGuess.value = null
        setPanoFormat(guess.projection, stereoAs = guess.stereo)
    }

    /** 不是: a flat film after all — not asked again. */
    fun dismissPanoGuess() {
        val item = _current.value ?: return
        _panoGuess.value = null
        guesses.edit().putString(item.id, GUESS_NONE).apply()
    }

    /** The Surface the engine draws into now — the window's panel or the place's screen; none in the sphere. */
    private fun pictureSurface(): Surface? = when {
        _immersive.value -> null
        screenPose() != null -> screenSurface
        else -> panelSurface
    }

    /** [item]'s bars as remembered, else looked for in the frames shown as it plays. */
    private fun startCropProbe(item: MediaItem) {
        cropJob?.cancel()
        cropForced = false
        val known = cropStore?.of(item.id)
        _frameCrop.value = known ?: com.therealityteam.videoplayer.data.playback.FrameCrop.NONE
        if (known != null || stereoToDimension(item.video?.stereo) != VideoDimensionMode.MONO) return
        cropJob = scope.launch {
            val frames = mutableListOf<com.therealityteam.videoplayer.data.playback.Letterbox.Frame>()
            var lastAt = -CROP_EVERY_MS
            var tries = 0
            while (frames.size < CROP_SAMPLES) {
                // A Surface that gives no copies (an engine drawing elsewhere) is not asked forever.
                if (++tries > CROP_SAMPLES * 3) {
                    android.util.Log.i("AuraCrop", "${item.fileName}: no frames to look at (${frames.size} of $CROP_SAMPLES)")
                    return@launch
                }
                kotlinx.coroutines.delay(CROP_EVERY_MS)
                // Moving frames only, well apart: a paused one, or one scene twice, says little of the film.
                val at = manager.position.value
                if (manager.state.value != PlaybackState.PLAYING || at - lastAt < CROP_EVERY_MS / 2) continue
                // Subtitles drawn into the bars would read as picture there.
                if (manager.subtitlesInPicture.value) continue
                val surface = pictureSurface() ?: continue
                com.therealityteam.videoplayer.player.LetterboxProbe.sample(surface)?.let { frames += it; lastAt = at }
            }
            val crop = com.therealityteam.videoplayer.data.playback.Letterbox.detect(frames, manager.videoAspect.value)
            android.util.Log.i("AuraCrop", "${item.fileName}: $crop from ${frames.size} frames")
            cropStore?.put(item.id, crop)
            if (_current.value?.id == item.id && !cropForced) _frameCrop.value = crop
        }
    }

    /** `aura.crop l,t,r,b | off | probe`: set the bars by hand, clear them, or look again now. */
    fun debugCrop(request: String) {
        val item = _current.value ?: return
        when (request) {
            "probe" -> { cropStore?.forget(item.id); startCropProbe(item) }
            "off" -> { cropJob?.cancel(); cropForced = true; _frameCrop.value = com.therealityteam.videoplayer.data.playback.FrameCrop.NONE }
            else -> request.split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 4 }?.let { (l, t, r, b) ->
                cropJob?.cancel()
                cropForced = true
                _frameCrop.value = com.therealityteam.videoplayer.data.playback.FrameCrop(l, t, r, b)
            }
        }
    }

    fun setAspectMode(mode: AspectMode) {
        if (_aspectMode.value == mode) return
        _aspectMode.value = mode
        if (panel != null) ensurePanel()
    }

    private fun stereoToDimension(stereo: StereoMode?): VideoDimensionMode = when (stereo) {
        StereoMode.SBS -> VideoDimensionMode.SIDE_BY_SIDE
        StereoMode.OU -> VideoDimensionMode.TOP_AND_DOWN
        else -> VideoDimensionMode.MONO
    }

    // ---- Queue & playback ----------------------------------------------------------------

    fun open(item: MediaItem, queue: List<MediaItem> = listOf(item), startMs: Long? = null) {
        userActed()
        playlist = queue.ifEmpty { listOf(item) }
        startItem(item, overrideStartMs = startMs)
    }

    private fun startItem(item: MediaItem, overrideStartMs: Long? = null) {
        autoSkippedIntros.clear()
        // A drag still under way belongs to the file it was on (the engine's setup lets go of it too).
        scrubJob?.cancel()
        scrubJob = null
        scrubTarget = null
        nextUpJob?.cancel()
        _nextUp.value = null
        _finished.value = null
        outroHandledFor = null
        outroArmedFor = null
        // A subtitle delay still settling rides along with this start instead of restarting it again.
        if (subtitleDelayJob != null) {
            (manager as? SwitchingEngine)?.subtitleDelayUs = _subtitleDelayMs.value * 1000L
            subtitleDelayJob?.cancel()
            subtitleDelayJob = null
        }
        // A segment loop belongs to the file it was set on — clear it when the item changes.
        if (item.id != _current.value?.id) {
            _repeatA.value = null
            _repeatB.value = null
            (repeatState as MutableStateFlow).value =
                com.therealityteam.videoplayer.data.playback.AbRepeat.State.IDLE
        }
        // Another film: it shows as opening from here on, not as the last one paused (see PlayerEngine.opening).
        if (item.id != _current.value?.id) manager.opening(overrideStartMs ?: 0L)
        _current.value = item
        audioAppliedFor = null
        subtitleAppliedFor = null
        startCropProbe(item)
        startPanoGuess(item)
        // (Re)binds the panel to this item's 3D mode along the active engine's render path. A
        // process's first film has no panel yet: the window makes it at its own size as it opens,
        // and one made here, at a guessed size, was only made again — a wait on the engine each.
        if (panel != null) ensurePanel()
        // Audio routing per the current setting (a change takes effect on the next film).
        manager.setSpatialAudio(settingsRepo.get().spatialAudio)
        // What the system's "now playing" (and a headset's display) shows for this item: the
        // control bar's two lines — the show and 第 1 季 第 2 集：孩子, or a film and its year.
        manager.setNowPlaying(
            title = item.title.ifBlank { item.fileName },
            subtitle = barSubtitle(appContext, item, _told.value[item.id]?.name, _numberedRun.value),
            artworkPath = item.posterPath,
        )
        startJob?.cancel()
        val start = scope.launch {
            startSource(item, overrideStartMs)
            if (_speed.value != 1.0f) manager.setSpeed(_speed.value)
        }
        startJob = start
        chapterJob?.cancel()
        _chapters.value = emptyList()
        forgetChapterStills()
        readPano(item, start)
        chapterJob = scope.launch {
            _chapters.value = withContext(Dispatchers.IO) { ChapterReader.read(localCopy(item)?.path ?: item.path) }
            // A disc's: its playlist's marks, found when it opened (its streams hold none).
            if (_chapters.value.isEmpty() && (com.therealityteam.videoplayer.data.disc.DiscImages.isImage(item.path) ||
                    com.therealityteam.videoplayer.data.disc.DiscImages.ripFolderOf(item.path, sourceFor(item)?.type) != null)
            ) {
                start.join()
                discChapters.takeIf { it.isNotEmpty() && _current.value?.id == item.id }?.let {
                    _chapters.value = it
                    android.util.Log.i("AuraChapters", "${it.size} chapters from the disc's playlist: ${item.fileName}")
                }
                return@launch
            }
            if (_chapters.value.isNotEmpty() || !item.path.contains("://") || item.path.startsWith("file://")) return@launch
            // A file on a share or a server: read through the very URL the engine plays — the share's
            // loopback proxy, the server's signed stream — once it is set up. A few Range requests.
            start.join()
            val url = manager.currentUri()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@launch
            val source = com.therealityteam.videoplayer.data.playback.HttpByteSource(url)
            val found = withContext(Dispatchers.IO) { ChapterReader.readRemote(source) }
            android.util.Log.i("AuraChapters", "${found.size} chapters over http in ${source.requests} requests: ${item.fileName}")
            if (_current.value?.id == item.id) _chapters.value = found
        }
    }

    /**
     * The built-in player streams http(s)/file but not smb://. Bridge SMB one of two ways
     * (A/B-switchable in Settings): a native seekable fd ([SmbFdBridge]) or a loopback HTTP
     * proxy ([LocalStreamServer]). Local/http (DLNA) play directly.
     */
    private var activeProxyUri: String? = null
    /** The chapters of the disc that opened last (its playlist's marks): none for any other file. */
    private var discChapters: List<Chapter> = emptyList()
    private var activeFd: SmbFdBridge.Handle? = null
    private var activeSubtitleUris: List<String> = emptyList()

    private suspend fun startSource(item: MediaItem, overrideStartMs: Long? = null) {
        releaseBridge()
        discChapters = emptyList()
        onSidecars(item, emptyList())
        giveSurfaceEarly()
        // …and the panel's mesh begun, for the window as it last measured itself: making one takes
        // the engine most of a second, and the window would otherwise ask for it only once it is up.
        if (panelShape == null) lastWindowSize()?.let { (w, h) -> scope.launch { preparePanel(w, h) } }
        // Where another device left off, taken in before the resume point is chosen — waiting a
        // second and a half at most, so a slow NAS costs the resume point, never the playback.
        sync?.freshen()
        val start = Resume.decide((repo.get(item.id) ?: item).progress, overrideStartMs)
        // 跳过片头: a file that would start inside the show's opening starts after it — unless the
        // caller asked for a moment of its own (a deep link, a restart from a chosen point).
        val marks = if (overrideStartMs == null) skipStore?.of(SkipStore.keyOf(item)) else null
        val startMs = marks?.startAt(start.positionMs) ?: start.positionMs
        val skippedIntro = startMs != start.positionMs
        if (skippedIntro) android.util.Log.i("AuraSkip", "intro skipped: ${start.positionMs}ms -> ${startMs}ms in ${item.fileName}")
        _introSkippedTo.value = if (skippedIntro) startMs else null
        introBackTo = 0L
        _resumedFromMs.value = if (start.prompt && !skippedIntro) startMs else null
        // 外挂音轨 are found per source below; a file with none (a media server's stream) opens with none.
        manager.setExternalAudio(emptyList())
        val copy = withContext(Dispatchers.IO) { localCopy(item) }
        _playingCopy.value = copy != null
        // What the engine remembers a release by is its folder on the share, whatever URL it is given.
        (manager as? SwitchingEngine)?.libraryPath = item.path
        // A data disc's video (a tutorial's .mov inside its ISO): served from where it lies in the image.
        com.therealityteam.videoplayer.data.disc.DiscImages.innerOf(item.path)?.let { (image, inner) ->
            val src = sourceFor(item).takeIf { !image.startsWith("/") }
            val url = withContext(Dispatchers.IO) { streamServer.registerDiscFile(image, src?.username, src?.password, inner, key = item.path) }
            android.util.Log.i("AuraDisc", "${inner.substringAfterLast('/')} inside ${image.substringAfterLast('/')}: ${if (url != null) "served" else "not read"}")
            if (url == null) {
                manager.fail(com.therealityteam.videoplayer.data.playback.PlaybackFailure.UNREACHABLE)
            } else {
                activeProxyUri = item.path
                manager.setup(url, startMs)
            }
            return
        }
        // 原盘: a disc image's film is read out of it and streamed as one file (DiscTitle).
        if (com.therealityteam.videoplayer.data.disc.DiscImages.isImage(item.path)) {
            playDisc(item, copy?.absolutePath ?: item.path, startMs)
            return
        }
        // A disc ripped as its files (BDMV / VIDEO_TS on the NAS): its playlist's clips as one stream, as an
        // image's film is — not the largest clip alone. One with no playlist plays its file, as before.
        if (copy == null && com.therealityteam.videoplayer.data.disc.DiscImages.ripFolderOf(item.path, sourceFor(item)?.type) != null &&
            playDiscFolder(item, startMs)
        ) return
        if (copy != null) {
            android.util.Log.i("AuraDownload", "playing the copy on the headset: ${item.fileName}")
            playLocal(item, copy.absolutePath, startMs)
            return
        }
        // A share's, an FTP or SFTP server's file: through the loopback proxy (an SMB one may take the fd bridge).
        if (com.therealityteam.videoplayer.data.playback.LocalStreamServer.serves(item.path)) {
            val src = sourceFor(item)
            if (item.path.startsWith("smb://") && settingsRepo.get().useFdBridge && manager.supportsFd) {
                val handle = withContext(Dispatchers.IO) {
                    fdBridge.open(item.path, src?.username, src?.password)
                }
                if (handle != null) {
                    activeFd = handle
                    manager.setupFd(handle.pfd.fd, handle.size, startMs)
                    return
                }
                // fd bridge failed → fall back to the proxy
            }
            // The video's stream first: the folder is listed over its login (LocalStreamServer.siblings),
            // which the video then opens through already logged in.
            streamServer.register(item.path, src?.username, src?.password)?.let { url ->
                smbAudios = emptyList()
                val sidecars = smbSidecars(item, src?.username, src?.password)
                onSidecars(item, sidecars)
                activeProxyUri = item.path
                manager.setExternalAudio(smbAudios)
                manager.setup(url, startMs, sidecars)
                return
            }
        }
        // A media server path is stored token-free (a token inside it would rotate item ids and
        // wipe progress); the source carries the token from the last scan — sign the URL now.
        val src = sourceFor(item)
        // A WebDAV server's file: with its subtitles beside it, and through the loopback with its login
        // when it asks for one (Media3 sends none of its own).
        if (src?.type == com.therealityteam.videoplayer.data.model.SourceType.WEBDAV && item.path.startsWith("http")) {
            playWebDav(item, src.username, src.password, startMs)
            return
        }
        if (src != null && !src.token.isNullOrBlank() && item.path.startsWith("http")) {
            when (src.type) {
                com.therealityteam.videoplayer.data.model.SourceType.JELLYFIN,
                com.therealityteam.videoplayer.data.model.SourceType.EMBY -> {
                    playMediaServer(item, src, startMs)
                    return
                }
                com.therealityteam.videoplayer.data.model.SourceType.PLEX -> {
                    manager.setup(com.therealityteam.videoplayer.data.source.PlexClient.signed(item.path, src.token.orEmpty()), startMs)
                    return
                }
                else -> Unit
            }
        }
        playLocal(item, item.path, startMs)
    }

    /**
     * A WebDAV server's [item]: its folder listed (bounded, as a share's is) for the subtitle files named after
     * it — served through the loopback, which hands their text over in UTF-8 and carries the login — and the
     * video itself straight to the engine, or through the loopback when the server asks for a login.
     */
    private suspend fun playWebDav(item: MediaItem, username: String?, password: String?, startMs: Long) {
        val folder = item.path.substringBeforeLast('/') + "/"
        val listed = withTimeoutOrNull(4_000) {
            try {
                com.therealityteam.videoplayer.data.source.WebDavSource().listFiles(folder, username, password)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
        val names = listed.map { it.first }
        // What lies beside it, kept: a fansub's fonts are looked for there once its .ass is chosen.
        beside = Beside(item.id, folder.trimEnd('/'), names)
        val urls = listed.toMap()
        val sidecars = withLanguages(
            SubtitleEngine.matchSidecars(item.fileName, names, imageFormats = true).mapNotNull { c ->
                val url = urls[c.fileName] ?: return@mapNotNull null
                com.therealityteam.videoplayer.data.playback.SidecarSubtitle(
                    uri = streamServer.registerHttp(url, username, password),
                    mimeType = c.mimeType,
                    label = c.label,
                    language = c.language,
                )
            },
        ) { it.uri }
        if (sidecars.isNotEmpty()) android.util.Log.i("AuraSubs", "beside ${item.fileName} on WebDAV: ${sidecars.joinToString { it.label }}")
        onSidecars(item, sidecars)
        manager.setExternalAudio(emptyList())
        val url = if (username.isNullOrBlank()) item.path else streamServer.registerHttp(item.path, username, password)
        if (url != item.path) activeProxyUri = item.path
        manager.setup(url, startMs, sidecars)
    }

    /**
     * A Jellyfin / Emby server's [item]: its direct-play stream signed with the source's token, and the
     * subtitle files the server keeps beside the video — which that stream, the video file alone, does not
     * carry — served through the loopback, in UTF-8 like a share's, and named by their files as theirs are.
     */
    private suspend fun playMediaServer(item: MediaItem, src: com.therealityteam.videoplayer.data.model.MediaSourceConfig, startMs: Long) {
        val token = src.token.orEmpty()
        val itemId = com.therealityteam.videoplayer.data.source.JellyfinClient.itemIdOf(item.path)
        val user = src.serverUserId
        val external = if (itemId == null || user.isNullOrBlank()) emptyList() else withTimeoutOrNull(SERVER_SUBTITLES_MS) {
            try {
                com.therealityteam.videoplayer.data.source.JellyfinClient()
                    .externalSubtitles(item.path.substringBeforeLast("/Videos/"), user, token, itemId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
        val sidecars = withLanguages(
            external.mapNotNull { sub ->
                val name = sub.fileName ?: "${item.fileName.substringBeforeLast('.')}.${sub.language ?: "sub"}.${sub.format}"
                val mime = SubtitleEngine.mimeOf("subtitle.${sub.format}") ?: return@mapNotNull null
                com.therealityteam.videoplayer.data.playback.SidecarSubtitle(
                    uri = streamServer.registerHttp(sub.url, null, null),
                    mimeType = mime,
                    label = name,
                    // The file's own language tag ("chs", "chs&eng") where it is named after the video, as a
                    // share's sidecar would be read; else the server's language code.
                    language = SubtitleEngine.matchSidecars(item.fileName, listOf(name)).firstOrNull()?.language ?: sub.language,
                )
            },
        ) { it.uri }
        if (sidecars.isNotEmpty()) android.util.Log.i("AuraSubs", "beside ${item.fileName} on its server: ${sidecars.joinToString { it.label }}")
        onSidecars(item, sidecars)
        manager.setExternalAudio(emptyList())
        manager.setup(item.path + "&api_key=" + java.net.URLEncoder.encode(token, "UTF-8"), startMs, sidecars)
    }

    private val _playingCopy = MutableStateFlow(false)
    /** Whether what plays is its copy on the headset (下载到本机) rather than the file on its share. */
    val playingCopy: StateFlow<Boolean> = _playingCopy

    /** The whole copy of [item] on the headset, if it was downloaded. */
    private fun localCopy(item: MediaItem): java.io.File? = downloads?.localCopy(item.id)

    /**
     * A disc image (原盘 .iso) — on a share, a WebDAV server or the headset ([path]): its film, a Blu-ray's
     * longest playlist or a DVD's largest title, streamed straight from the image as one .m2ts / .vob, which
     * mpv plays. An encrypted one, or one with no film, says so.
     */
    private suspend fun playDisc(item: MediaItem, path: String, startMs: Long) {
        val src = sourceFor(item).takeIf { !path.startsWith("/") }
        val started = android.os.SystemClock.elapsedRealtime()
        val open = withContext(Dispatchers.IO) { streamServer.registerDisc(path, src?.username, src?.password) }
        android.util.Log.i("AuraDisc", "${item.fileName}: $open in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        when (open) {
            is com.therealityteam.videoplayer.data.playback.DiscOpen.Playing -> {
                discChapters = open.title.chaptersMs.map { Chapter(it, "") }
                activeProxyUri = path
                manager.setup(open.url, startMs)
            }
            is com.therealityteam.videoplayer.data.playback.DiscOpen.Encrypted ->
                manager.fail(com.therealityteam.videoplayer.data.playback.PlaybackFailure.DISC_ENCRYPTED)
            com.therealityteam.videoplayer.data.playback.DiscOpen.NoFilm ->
                manager.fail(com.therealityteam.videoplayer.data.playback.PlaybackFailure.DISC_NO_FILM)
            // A data disc of videos: they play one after another, as a folder's would — the first now.
            is com.therealityteam.videoplayer.data.playback.DiscOpen.Videos -> {
                val videos = open.paths.map { inner ->
                    val at = com.therealityteam.videoplayer.data.disc.DiscImages.videoPath(item.path, inner)
                    val name = inner.substringAfterLast('/')
                    MediaItem(
                        id = MediaItem.idFor(item.sourceId, at),
                        sourceId = item.sourceId,
                        path = at,
                        fileName = name,
                        title = name.substringBeforeLast('.'),
                        video = com.therealityteam.videoplayer.data.playback.ProjectionDetector.detect(name),
                    )
                }
                android.util.Log.i("AuraDisc", "${item.fileName}: a data disc of ${videos.size} videos — ${videos.take(3).joinToString { it.fileName }}…")
                withContext(Dispatchers.Main) { open(videos.first(), videos, startMs = 0L) }
            }
            com.therealityteam.videoplayer.data.playback.DiscOpen.Unreadable -> manager.fail(
                if (path.startsWith("/")) com.therealityteam.videoplayer.data.playback.PlaybackFailure.MISSING
                else com.therealityteam.videoplayer.data.playback.PlaybackFailure.UNREACHABLE,
            )
        }
    }

    /** A disc folder's film ([LocalStreamServer.registerDiscFolder]) set up; false when it has none to go by. */
    private suspend fun playDiscFolder(item: MediaItem, startMs: Long): Boolean {
        val src = sourceFor(item).takeIf { !item.path.startsWith("/") }
        val started = android.os.SystemClock.elapsedRealtime()
        val open = withContext(Dispatchers.IO) { streamServer.registerDiscFolder(item.path, src?.username, src?.password) }
        android.util.Log.i("AuraDisc", "${item.fileName} (a disc folder): $open in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        return when (open) {
            is com.therealityteam.videoplayer.data.playback.DiscOpen.Playing -> {
                discChapters = open.title.chaptersMs.map { Chapter(it, "") }
                activeProxyUri = item.path
                manager.setup(open.url, startMs)
                true
            }
            is com.therealityteam.videoplayer.data.playback.DiscOpen.Encrypted -> {
                manager.fail(com.therealityteam.videoplayer.data.playback.PlaybackFailure.DISC_ENCRYPTED)
                true
            }
            else -> false
        }
    }

    /** [path] as the engine opens it as it is: a file on the headset, or a URL that needs nothing more. */
    private suspend fun playLocal(item: MediaItem, path: String, startMs: Long) {
        // Sidecars go to the engine as UTF-8: a GBK / Big5 file is copied through the cache first.
        val sidecars = withContext(Dispatchers.IO) {
            // A downloaded film's VobSub too (its .idx and .sub): mpv draws it, Media3 is not handed it.
            SubtitleEngine.discoverSidecars(path, imageFormats = true).map { sc ->
                if (!sc.uri.startsWith("file://") || SubtitleEngine.isPicture(sc.mimeType)) return@map sc
                val original = java.io.File(sc.uri.removePrefix("file://"))
                val utf8 = SubtitleTranscoder.utf8Path(original, appContext.cacheDir)
                if (utf8 == original) sc else sc.copy(uri = "file://${utf8.absolutePath}")
            }
        }.let { found -> withLanguages(found) { it.uri } }
        onSidecars(item, sidecars)
        manager.setExternalAudio(withContext(Dispatchers.IO) { localAudios(path) })
        manager.setup(path, startMs, sidecars)
    }

    /** 外挂音轨 beside a file on the headset: its folder's sound files named after it. */
    private fun localAudios(path: String): List<com.therealityteam.videoplayer.data.playback.SidecarAudio> {
        if (!path.startsWith("/")) return emptyList()
        val video = java.io.File(path)
        val dir = video.parentFile ?: return emptyList()
        val names = runCatching { dir.list() }.getOrNull().orEmpty().toList()
        return com.therealityteam.videoplayer.data.playback.ExternalAudio.match(video.name, names).map {
            com.therealityteam.videoplayer.data.playback.SidecarAudio("file://${java.io.File(dir, it.fileName).absolutePath}", it.label, it.language)
        }.also { if (it.isNotEmpty()) android.util.Log.i("AuraTracks", "外挂音轨 beside ${video.name}: ${it.joinToString { a -> a.label }}") }
    }

    /**
     * Subtitle files sitting next to an SMB video: list the folder, match by stem, and hand
     * each hit to the stream proxy so the engine can fetch it over http. One bounded listing
     * (the folder is already open server-side, so this is cheap next to the stream itself);
     * on any failure the video simply plays without sidecars.
     */
    /** Where a bounded wait on a blocking call runs: past its bound the call is left to finish on its own. */
    private val detachedIo = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** The 外挂音轨 [smbSidecars] found beside the video it listed for, last. */
    private var smbAudios: List<com.therealityteam.videoplayer.data.playback.SidecarAudio> = emptyList()

    private suspend fun smbSidecars(
        item: MediaItem,
        username: String?,
        password: String?,
    ): List<com.therealityteam.videoplayer.data.playback.SidecarSubtitle> {
        // The folder's own address: `smb://nas/share/dir`, `ftp://nas/video/dir` — what lies beside is under it.
        val folderUri = item.path.substringBeforeLast('/')
        val fileName = item.path.substringAfterLast('/')
        if (fileName.isEmpty() || !folderUri.contains("://")) return emptyList()
        // One bounded listing; a slow or failing NAS must not delay the video itself. try/catch
        // rather than runCatching: cancellation (including the timeout's own) must propagate,
        // not be swallowed into "no subtitles". The listing runs apart (detachedIo): a blocking SMB
        // call does not hear a cancellation, and withContext waited it out past the bound — 60 s on a
        // connection the NAS had dropped, the film unopened all that while.
        val names = withTimeoutOrNull(4_000) {
            try {
                detachedIo.async { streamServer.siblings(item.path) }.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } ?: return emptyList()
        // What lies beside it, kept: a fansub's fonts are looked for there once its .ass is chosen.
        beside = Beside(item.id, folderUri, names)
        // …and a release's Subs/ (a fansub's sub/) beside it: listed in the same bound.
        val folder = com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.folderIn(names)
        val inFolder = folder?.let { f ->
            withTimeoutOrNull(3_000) {
                try {
                    detachedIo.async { streamServer.listBeside(item.path, f) }.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.orEmpty()
        val isFeature = com.therealityteam.videoplayer.ui.library.EpisodeNaming.parse(item).cat ==
            com.therealityteam.videoplayer.ui.library.EpisodeNaming.Cat.FEATURE
        val found = (
            // Picture ones too (a release's VobSub): mpv draws them; Media3 is not handed them.
            SubtitleEngine.matchSidecars(fileName, names, imageFormats = true).map { it to it.fileName } +
                folder?.let { f ->
                    com.therealityteam.videoplayer.data.subtitle.SubtitleFolders.match(fileName, inFolder, isFeature, imageFormats = true)
                        .map { it to "$f/${it.fileName}" }
                }.orEmpty()
            )
            // The same file beside the video and in its Subs/ (THORA's ASS, kept in both) is listed once.
            .distinctBy { it.first.fileName }
        if (inFolder.isNotEmpty()) android.util.Log.i("AuraSubs", "$folder/ beside ${item.fileName}: ${found.count { '/' in it.second }} of ${inFolder.size} for it")
        val subUris = mutableListOf<String>()
        // 外挂音轨 from the same listing: each sound file named after the video, streamed as the video is.
        smbAudios = com.therealityteam.videoplayer.data.playback.ExternalAudio.match(fileName, names).mapNotNull { a ->
            val smbUri = "$folderUri/${a.fileName}"
            streamServer.register(smbUri, username, password)?.let { url ->
                subUris += smbUri
                com.therealityteam.videoplayer.data.playback.SidecarAudio(url, a.label, a.language)
            }
        }
        if (smbAudios.isNotEmpty()) android.util.Log.i("AuraTracks", "外挂音轨 beside ${item.fileName}: ${smbAudios.joinToString { it.label }}")
        val onShare = mutableMapOf<String, String>()
        val sidecars = found.mapNotNull { (c, relative) ->
            val smbUri = "$folderUri/$relative"
            streamServer.register(smbUri, username, password)?.let { url ->
                subUris += smbUri
                onShare[url] = smbUri
                com.therealityteam.videoplayer.data.playback.SidecarSubtitle(
                    uri = url, mimeType = c.mimeType, label = c.label, language = c.language,
                )
            }
        }
        activeSubtitleUris = subUris
        return withLanguages(sidecars) { onShare[it.uri] ?: it.uri }
    }

    /** What each subtitle file was found to be written in, by where it lives — read once a session. */
    private val sidecarLanguages = java.util.concurrent.ConcurrentHashMap<String, List<TrackPreference.Lang>>()

    /**
     * Sidecars as the engine and the menus should know them: their languages from their name tags,
     * else — a text file whose name says none, like `1917.…-EVO.ass` beside its film — from its
     * first lines. The engine gets the main language's code (zh-Hans…), so 自动, which follows the
     * reader's language, can pick a Chinese file over an English stream; the menus get the tag
     * ("chs&eng") and name the languages. Read in parallel, each in [SIDECAR_READ_MS] at most: a
     * slow share costs the film's start no more than that, and an unread file stays as named.
     */
    private suspend fun withLanguages(
        sidecars: List<com.therealityteam.videoplayer.data.playback.SidecarSubtitle>,
        place: (com.therealityteam.videoplayer.data.playback.SidecarSubtitle) -> String,
    ): List<com.therealityteam.videoplayer.data.playback.SidecarSubtitle> = coroutineScope {
        sidecars.map { s ->
            async(Dispatchers.IO) {
                var langs = SubtitleLanguage.ofTag(s.language)
                if (langs.isEmpty() && s.mimeType in READABLE_SUBTITLES) {
                    val key = place(s)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val cached = sidecarLanguages[key]
                    langs = cached ?: withTimeoutOrNull(SIDECAR_READ_MS) { readLanguages(s) }
                        ?.also { sidecarLanguages[key] = it }
                        .orEmpty()
                    android.util.Log.i(
                        "AuraTracks",
                        "${s.label}: ${SubtitleLanguage.tag(langs) ?: "no language"} by its lines, " +
                            if (cached != null) "known" else "read in ${android.os.SystemClock.elapsedRealtime() - started} ms",
                    )
                }
                if (langs.isEmpty()) s
                else s.copy(language = SubtitleLanguage.code(langs), tag = SubtitleLanguage.tag(langs))
            }
        }.awaitAll()
    }

    /** The languages of a subtitle file, from its first [SIDECAR_HEAD_BYTES] — local, or through the stream proxy. */
    private fun readLanguages(s: com.therealityteam.videoplayer.data.playback.SidecarSubtitle): List<TrackPreference.Lang>? =
        runCatching {
            val head = if (s.uri.startsWith("file://")) {
                java.io.File(s.uri.removePrefix("file://")).inputStream().use { it.readNBytes(SIDECAR_HEAD_BYTES) }
            } else {
                val c = java.net.URL(s.uri).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 1_000 // the stream proxy, on this device
                c.readTimeout = SIDECAR_READ_MS.toInt()
                c.setRequestProperty("Range", "bytes=0-${SIDECAR_HEAD_BYTES - 1}")
                try {
                    c.inputStream.use { it.readNBytes(SIDECAR_HEAD_BYTES) }
                } finally {
                    c.disconnect()
                }
            }
            SubtitleLanguage.ofHead(head, s.label, complete = head.size < SIDECAR_HEAD_BYTES)
        }.getOrNull()

    /**
     * The source an item belongs to — by id for library items; by URI prefix for ad-hoc
     * ones (a file opened directly on an already-configured share reuses its credentials).
     */
    private fun sourceFor(item: MediaItem) =
        sourceRepo.get(item.sourceId)
            ?: sourceRepo.all()
                .filter { item.path.startsWith(it.uri.trimEnd('/') + "/") || item.path == it.uri }
                .maxByOrNull { it.uri.length }

    private fun releaseBridge() {
        activeProxyUri?.let { streamServer.unregister(it); activeProxyUri = null }
        activeSubtitleUris.forEach { streamServer.unregister(it) }
        activeSubtitleUris = emptyList()
        activeFd?.let { it.close(); activeFd = null }
    }

    private fun currentIndex(): Int = playlist.indexOfFirst { it.id == _current.value?.id }

    fun hasNext(): Boolean = PlaylistNav.hasNext(_playlistMode.value, currentIndex(), playlist.size)
    fun hasPrev(): Boolean = PlaylistNav.hasPrev(_playlistMode.value, currentIndex(), playlist.size)

    /**
     * When the current item ends, the next one (per the 循环/随机 mode) is announced with a
     * five-second countdown rather than started outright: 立即播放 skips the wait, 取消 stays
     * on the ended film, and the countdown itself starts it.
     */
    private fun onItemCompleted() {
        // The file reached its end: record that first, on every path out of here. Nothing else
        // marked it watched — 单曲循环 seeks straight back to 0 and the next save wrote that 0 over
        // the finished watch, a sequential end saved nothing at all, and the 10 s ticker only runs
        // while PLAYING. A series' "play the next episode" reading depends on this flag.
        markCurrentWatched()
        if (_sleepMode.value == SleepMode.END_OF_ITEM) {   // 睡眠定时: stop after this item
            _sleepMode.value = SleepMode.OFF
            // Reached at the credits mark, the film is still playing: that mark is its end as far
            // as the viewer said, so it stops there — it used to run on through the credits and,
            // the timer spent, roll into the next episode at the file's real end.
            if (manager.state.value == PlaybackState.PLAYING) manager.pause()
            return
        }
        val i = currentIndex()
        when (val next = PlaylistNav.onCompleted(_playlistMode.value, i, playlist.size)) {
            -1 -> {                                          // sequential end → stop, and say so
                finishedAtMs = manager.position.value
                _finished.value = _current.value
                android.util.Log.i("AuraEnd", "finished: ${_current.value?.fileName}")
            }
            i -> { manager.seekTo(0); manager.play() }       // 单曲循环 → instant replay
            else -> {
                // Away from the network, the next episode that plays is the next one on the headset:
                // one left on the share would only stop at 网络已断开. None left here — the end.
                val candidate = playlist[next]
                if (_networkDown.value && candidate.path.contains("://") && localCopy(candidate) == null) {
                    val here = playlist.drop(next + 1).firstOrNull { localCopy(it) != null }
                    if (here != null) {
                        android.util.Log.i("AuraNext", "offline: ${candidate.fileName} is not here, ${here.fileName} is")
                        announceNext(here)
                    } else {
                        android.util.Log.i("AuraNext", "offline: nothing after ${_current.value?.fileName} is here")
                        finishedAtMs = manager.position.value
                        _finished.value = _current.value
                    }
                } else {
                    announceNext(candidate)
                }
            }
        }
    }

    /**
     * The item that played to the end of its queue — nothing next, and no timer stopping it there —
     * while its 看完了 card is up. Cleared when another item starts, on 重看, when the card is put
     * away, or when the position is taken back from where it ended.
     */
    private val _finished = MutableStateFlow<MediaItem?>(null)
    val finished: StateFlow<MediaItem?> = _finished
    private var finishedAtMs = 0L

    init {
        scope.launch {
            manager.position.collect { pos ->
                if (_finished.value != null && pos < finishedAtMs - FINISHED_SEEK_BACK_MS) _finished.value = null
            }
        }
    }

    /** 重看 — the ended item again, from its first frame. */
    fun replay() {
        _finished.value = null
        manager.seekTo(0)
        manager.play()
    }

    /** The 看完了 card put away; the film stays where it ended (or plays on through its credits). */
    fun dismissFinished() {
        _finished.value = null
        touchControls()
    }

    /** The item just played through to its end — watched, whatever happens next. */
    private fun markCurrentWatched() {
        val item = _current.value ?: return
        val dur = manager.duration.value
        if (dur > 0) repo.updateProgress(item.id, dur, dur, watched = true)
    }

    /**
     * The item about to play and when it will (elapsed-realtime ms), or null when nothing is queued
     * up; [startsAtMs] is null when 自动播放下一集 is off and the card waits to be asked.
     */
    data class NextUp(val item: MediaItem, val startsAtMs: Long?, val stillWatching: Boolean = false)

    /**
     * How many items in a row the countdown has started by itself, nobody touching anything since.
     * At [STILL_WATCHING_AFTER] the next one waits to be asked (还在看吗？): somebody asleep in the
     * headset was being played the rest of the season, and every episode of it marked watched.
     */
    private var autoAdvances = 0

    /** Somebody is there: a press on the bar or the picture, a seek, a choice to play. */
    private fun userActed() {
        autoAdvances = 0
    }

    private val _nextUp = MutableStateFlow<NextUp?>(null)
    val nextUp: StateFlow<NextUp?> = _nextUp

    /**
     * 跳过片头 / 跳过片尾 by the file's own chapter names ([ChapterSkip]): the opening or the credits
     * playing now, offered as a skip to its end. The show's marked opening ([SkipStore]) is skipped
     * before it is ever reached; this is for the files whose release named theirs.
     */
    val chapterSkip: StateFlow<com.therealityteam.videoplayer.data.playback.ChapterSkip.Offer?> =
        combine(manager.position, _chapters, manager.duration, _nextUp, _finished) { position, chapters, duration, next, done ->
            // Once the item is done — the next one announced, or the 看完了 card up, the credits
            // rolling on behind — there is nothing left to skip.
            if (next != null || done != null) null
            else com.therealityteam.videoplayer.data.playback.ChapterSkip.offerAt(chapters, position, duration)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Skips the chapter on offer: to its end — or, for credits that run to the end of the file, the
     * episode is done, as at the credits mark: counted watched and the next one announced.
     */
    fun skipChapter() {
        val offer = chapterSkip.value ?: return
        userActed()
        val duration = manager.duration.value
        android.util.Log.i("AuraSkip", "chapter ${offer.kind} skipped: ${manager.position.value}ms -> ${offer.endMs}ms")
        if (offer.kind == com.therealityteam.videoplayer.data.playback.ChapterSkip.Kind.OUTRO &&
            duration > 0 && offer.endMs >= duration - CREDITS_TO_END_MS
        ) {
            onItemCompleted()
        } else {
            manager.seekTo(offer.endMs)
        }
    }

    /** Openings 自动跳过片头 has skipped in this sitting of the item, by chapter: one skip each, so a 看片头 sticks. */
    private val autoSkippedIntros = HashSet<Int>()

    init {
        // 自动跳过片头: an opening the chapters name is skipped as playback reaches it — played into, not
        // landed in: a seek or a resume point inside it was somebody's choice — with the 已跳过片头
        // line and its 看片头 to go back.
        scope.launch {
            chapterSkip.collect { offer ->
                if (offer?.kind != com.therealityteam.videoplayer.data.playback.ChapterSkip.Kind.INTRO) return@collect
                if (!settingsRepo.get().autoSkipIntro) return@collect
                val item = _current.value ?: return@collect
                val at = manager.position.value
                if (at - offer.startMs > AUTO_SKIP_REACH_MS || !autoSkippedIntros.add(offer.chapterIndex)) return@collect
                android.util.Log.i("AuraSkip", "chapter intro auto-skipped: ${at}ms -> ${offer.endMs}ms in ${item.fileName}")
                introBackTo = offer.startMs
                manager.seekTo(offer.endMs)
                _introSkippedTo.value = offer.endMs
            }
        }
    }
    private var nextUpJob: kotlinx.coroutines.Job? = null

    private fun announceNext(item: MediaItem) {
        nextUpJob?.cancel()
        val stillWatching = autoAdvances >= STILL_WATCHING_AFTER
        if (!settingsRepo.get().autoPlayNext || stillWatching) {
            android.util.Log.i("AuraNext", "next up, waiting to be asked${if (stillWatching) " (still watching?)" else ""}: ${item.fileName}")
            _nextUp.value = NextUp(item, startsAtMs = null, stillWatching = stillWatching)
            return
        }
        android.util.Log.i("AuraNext", "next up in ${NEXT_UP_DELAY_MS}ms: ${item.fileName}")
        _nextUp.value = NextUp(item, android.os.SystemClock.elapsedRealtime() + NEXT_UP_DELAY_MS)
        nextUpJob = scope.launch {
            kotlinx.coroutines.delay(NEXT_UP_DELAY_MS)
            autoAdvances++
            playNextUp(byUser = false)
        }
    }

    /** 立即播放 / 继续播放 — or, [byUser] false, the countdown reaching zero. */
    fun playNextUp(byUser: Boolean = true) {
        if (byUser) userActed()
        val next = _nextUp.value ?: return
        android.util.Log.i("AuraNext", "playing next: ${next.item.fileName}")
        nextUpJob?.cancel()
        _nextUp.value = null
        saveProgress()
        startItem(next.item)
    }

    /** 取消 — stay on the film that just ended. */
    fun cancelNextUp() {
        dropNextUp()
        touchControls()
    }

    /** The countdown to the next item called off, if one runs. */
    private fun dropNextUp() {
        val next = _nextUp.value ?: return
        android.util.Log.i("AuraNext", "called off: ${next.item.fileName}")
        nextUpJob?.cancel()
        _nextUp.value = null
    }

    /** Where this item resumed from, while the "从 12:34 继续" prompt is worth showing. */
    private val _resumedFromMs = MutableStateFlow<Long?>(null)
    val resumedFromMs: StateFlow<Long?> = _resumedFromMs

    /** 从头看 — the prompt's one action. */
    fun restartFromBeginning() {
        _resumedFromMs.value = null
        manager.seekTo(0L)
        touchControls()
    }

    fun dismissResumePrompt() { _resumedFromMs.value = null }

    /** 重试 — the file that failed, started again where it was (or where it would have resumed). */
    /**
     * A film the network took away (UNREACHABLE: Wi-Fi gone, the share asleep past the waiting —
     * PatientLoadErrorPolicy) starts again where it stopped when a network comes back: 重试 pressed
     * for the viewer. Once per failure; a share still unreachable then says so again.
     */
    private val networkBack = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onLost(network: android.net.Network) {
            _networkDown.value = true
        }

        override fun onAvailable(network: android.net.Network) {
            _networkDown.value = false
            scope.launch {
                // A moment for the routes and the share's login to settle.
                kotlinx.coroutines.delay(NETWORK_SETTLE_MS)
                // mpv waiting out a broken stream sleeps between its reconnects (1, 2, 4… s): a seek to
                // where it is wakes it to read again now, not at its next try.
                if ((manager as? SwitchingEngine)?.mpvStreamBroken == true && manager.state.value != PlaybackState.ERROR &&
                    _current.value != null
                ) {
                    android.util.Log.i("AuraPlay", "network back: mpv reads again at ${manager.positionNow()}ms")
                    manager.seekTo(manager.positionNow())
                    return@launch
                }
                if (manager.state.value == PlaybackState.ERROR &&
                    manager.failure.value == com.therealityteam.videoplayer.data.playback.PlaybackFailure.UNREACHABLE &&
                    _current.value != null
                ) {
                    android.util.Log.i("AuraPlay", "network back: trying again")
                    retry()
                }
            }
        }
    }

    /** Whether the headset has no network now — what a wait says it is waiting for (等待网络). */
    private val _networkDown = MutableStateFlow(false)
    val networkDown: StateFlow<Boolean> = _networkDown

    init {
        runCatching {
            val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            _networkDown.value = cm.activeNetwork == null
            cm.registerDefaultNetworkCallback(networkBack)
        }
        // A server that stopped answering while the network stayed up (a NAS asleep, its share dropped) sends no
        // network-back. mpv slept out its reconnects (1, 2, 4… 32 s) and played on up to a minute after the server
        // was back (53 s, measured); a longer outage left either engine failed (UNREACHABLE) for good, the server
        // long back, until 重试 was pressed. Its file is asked for every few seconds instead — one byte, through the
        // proxy, which asks the server — and on an answer the film opens again where it stopped. Media3 waiting asks
        // again itself (PatientLoadErrorPolicy).
        scope.launch {
            manager.sourceDown.collectLatest { down ->
                if (!down) return@collectLatest
                var lastAt = -1L
                while (true) {
                    kotlinx.coroutines.delay(SERVER_POLL_MS)
                    if (_current.value == null) return@collectLatest
                    val failed = manager.state.value == PlaybackState.ERROR
                    val mpv = (manager as? SwitchingEngine)?.usingMpv?.value == true
                    // Only a film that waits is woken — waiting, failed, or "playing" with its picture standing still
                    // (mpv after a seek with nothing to read); one playing on from what it holds is left alone: a seek
                    // to where it is, every few seconds, would stop it each time (and its server is not asked).
                    val at = manager.positionNow()
                    val standing = at == lastAt
                    lastAt = at
                    val waiting = failed || (mpv && (manager.state.value == PlaybackState.PREPARING || standing))
                    if (!waiting) continue
                    val uri = manager.currentUri() ?: continue
                    if (!withContext(Dispatchers.IO) { answers(uri) }) continue
                    // Opened again where it stands: a seek there did not wake FFmpeg (it slept on to its next reconnect,
                    // 43 s after the server answered, measured); a fresh open shows the picture in under a second.
                    android.util.Log.i("AuraPlay", "the file's server answers again: the film opens again at ${manager.positionNow()}ms${if (failed) " (it had failed)" else ""}")
                    retry()
                    // Asked on until it plays (sourceDown clears and ends this): a server that answered one probe and
                    // went again — still shutting down, or waking — is asked again rather than given up on.
                    kotlinx.coroutines.delay(SERVER_WOKEN_GAP_MS)
                }
            }
        }
    }

    /** Whether the server behind [uri] gives its first byte now (a file on the headset always does). */
    private fun answers(uri: String): Boolean {
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) return true
        return runCatching {
            val c = java.net.URL(uri.replace(" ", "%20")).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = SERVER_PROBE_TIMEOUT_MS
            c.readTimeout = SERVER_PROBE_TIMEOUT_MS
            c.setRequestProperty("Range", "bytes=0-0")
            // The byte itself, not only the status: the proxy says 206 before it asks the server.
            try { c.responseCode in 200..299 && c.inputStream.use { it.read() } != -1 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    fun retry() {
        val item = _current.value ?: return
        val at = manager.position.value.takeIf { it > 0L }
        android.util.Log.i("AuraPlay", "retry ${item.fileName} at ${at ?: "its resume point"}")
        startItem(item, overrideStartMs = at)
    }

    fun playNext() {
        touchControls()
        val next = PlaylistNav.manualNext(_playlistMode.value, currentIndex(), playlist.size)
        if (next >= 0) {
            saveProgress()
            startItem(playlist[next])
        }
    }

    /** Jump straight to a queue entry (the playlist panel's tap). */
    fun playAt(item: MediaItem) {
        if (item.id == _current.value?.id) return
        if (playlist.none { it.id == item.id }) return
        saveProgress()
        startItem(item)
    }

    fun playPrev() {
        touchControls()
        val prev = PlaylistNav.manualPrev(_playlistMode.value, currentIndex(), playlist.size)
        if (prev >= 0) {
            saveProgress()
            startItem(playlist[prev])
        }
    }

    // ---- 第二字幕 -------------------------------------------------------------------------

    /**
     * 第二字幕: a subtitle file beside the video drawn under the engine's own subtitles — two
     * languages at once, where a release ships them as separate files. The engine renders one text
     * track only, so this one is read and timed here ([SubtitleParser]); only files beside the
     * video can be one. The pick follows the queue: the next file's subtitle with the same name
     * tail (".eng.srt") becomes its second subtitle too.
     */
    data class SecondarySubtitle(val label: String, val track: com.therealityteam.videoplayer.data.subtitle.SubtitleTrack)

    private var sidecarsNow: List<com.therealityteam.videoplayer.data.playback.SidecarSubtitle> = emptyList()
    private val _secondary = MutableStateFlow<SecondarySubtitle?>(null)
    val secondary: StateFlow<SecondarySubtitle?> = _secondary
    private var secondaryTail: String? = null
    private var secondaryJob: Job? = null

    // ---- ASS as typeset (AssRenderer) -------------------------------------------------------

    /**
     * A fansub's .ass beside a file Media3 plays, drawn by libass as it was typeset — signs on their
     * signs, an opening's karaoke, a line in layers once — for the window to lay over the picture
     * where Media3's own reader keeps only the words. Null while the subtitles are anything else:
     * mpv draws the ASS it plays itself, and the rest are lines the player sets.
     */
    private val _ass = MutableStateFlow<com.therealityteam.videoplayer.data.playback.AssRenderer?>(null)
    val ass: StateFlow<com.therealityteam.videoplayer.data.playback.AssRenderer?> = _ass
    private var assRenderer: com.therealityteam.videoplayer.data.playback.AssRenderer? = null
    private var assSource: String? = null
    private var assJob: Job? = null

    /** What libass draws: a .ass beside the file, or an ASS stream inside it. */
    private sealed interface AssChoice {
        data class File(val sidecar: com.therealityteam.videoplayer.data.playback.SidecarSubtitle) : AssChoice
        data class Stream(val id: String, val label: String) : AssChoice
    }

    init {
        val inMpv = (manager as? SwitchingEngine)?.usingMpv ?: MutableStateFlow(false)
        manager.setTypesetSubtitles(_subtitleTypeset.value)
        scope.launch {
            combine(manager.subtitleTracks, inMpv, _subtitleTypeset) { tracks, mpv, typeset -> if (mpv || !typeset) null else assChoice(tracks) }
                .distinctUntilChanged()
                .collect { showAss(it) }
        }
        // …and when mpv draws it: the fansub's fonts it names, handed to mpv's own libass.
        scope.launch {
            combine(manager.subtitleTracks, inMpv, _subtitleTypeset) { tracks, mpv, typeset -> if (mpv && typeset) tracks.firstOrNull { it.selected } else null }
                .distinctUntilChanged { a, b -> a?.id == b?.id && a?.external == b?.external }
                .collect { mpvAssFonts(it) }
        }
        scope.launch { _subtitleScale.collect { assRenderer?.setFontScale(it) } }
    }

    /** The chosen subtitles, when they are ASS: a file beside the video, or a stream Media3 reads in it. */
    private fun assChoice(tracks: List<com.therealityteam.videoplayer.data.playback.MediaTrack>): AssChoice? {
        val chosen = tracks.firstOrNull { it.selected } ?: return null
        if (chosen.external) {
            return sidecarsNow.firstOrNull { it.label == chosen.name && it.mimeType == ASS_MIME }?.let { AssChoice.File(it) }
        }
        val id = chosen.streamId?.takeIf { chosen.typeset } ?: return null
        return AssChoice.Stream(id, chosen.name)
    }

    private fun showAss(choice: AssChoice?) {
        assJob?.cancel()
        if (choice == null) {
            assSource = null
            _ass.value = null
            assRenderer?.clear()
            return
        }
        val key = when (choice) {
            is AssChoice.File -> choice.sidecar.uri
            is AssChoice.Stream -> "stream:${_current.value?.id}:${choice.id}"
        }
        if (key == assSource && _ass.value != null) return
        assSource = key
        assJob = scope.launch {
            // libass is libmpv's: loaded off the main thread, as mpv's own libraries are.
            val available = withContext(Dispatchers.IO) { com.therealityteam.videoplayer.data.playback.AssNative.available }
            if (!available) {
                assSource = null
                return@launch
            }
            val renderer = assRenderer ?: com.therealityteam.videoplayer.data.playback.AssRenderer(appContext).also { assRenderer = it }
            renderer.setFontScale(_subtitleScale.value)
            var script: String? = null
            when (choice) {
                is AssChoice.File -> {
                    val bytes = withContext(Dispatchers.IO) { runCatching { readSubtitle(choice.sidecar.uri) }.getOrNull() }
                    if (bytes == null) {
                        assSource = null
                        return@launch
                    }
                    val text = String(bytes, com.therealityteam.videoplayer.data.subtitle.SubtitleParser.detectCharset(bytes)).removePrefix("\uFEFF")
                    script = text
                    renderer.load(text.toByteArray(Charsets.UTF_8), choice.sidecar.label)
                }
                is AssChoice.Stream -> {
                    val stream = (manager as? SwitchingEngine)?.exo?.assStreams?.stream(choice.id)
                    if (stream == null) {
                        android.util.Log.i("AuraAss", "no header read for stream ${choice.id}: its plain lines stay")
                        assSource = null
                        return@launch
                    }
                    script = String(stream.header, Charsets.UTF_8)
                    renderer.follow(stream, choice.label)
                }
            }
            _ass.value = renderer
            loadAssFonts(renderer, script)
        }
    }

    private var assFontsFor: Pair<String, Set<String>>? = null
    private var assFontsJob: Job? = null

    /**
     * The fonts the playing MKV carries — a fansub's own, which its ASS is set in — for libass, read
     * once a file in the background: the attachments' places from the file's head, then each font's
     * bytes in one request. Until they come (and for a file with none) PICO Sans stands in.
     */
    private fun loadAssFonts(renderer: com.therealityteam.videoplayer.data.playback.AssRenderer, script: String?) {
        val item = _current.value ?: return
        val wanted = script?.let { com.therealityteam.videoplayer.data.subtitle.AssFonts.wanted(it) }.orEmpty()
        if (assFontsFor == item.id to wanted) return
        assFontsFor = item.id to wanted
        assFontsJob?.cancel()
        val name = item.fileName.lowercase()
        val url = manager.currentUri()
        assFontsJob = scope.launch {
            // The file's own (a Matroska's attachments) first; then, for the faces still wanting, the fansub's
            // pack beside it — fetched and taken out once a show, a moment after the lines start in PICO Sans.
            val own = if (name.endsWith(".mkv") || name.endsWith(".mks")) {
                withContext(Dispatchers.IO) { runCatching { matroskaFonts(item, url) }.getOrDefault(emptyList()) }
            } else emptyList()
            if (_current.value?.id != item.id) return@launch
            renderer.setFonts(own)
            if (wanted.isEmpty()) return@launch
            val packed = withContext(Dispatchers.IO) {
                runCatching { packFonts(item, wanted) }.onFailure { android.util.Log.w("AuraAss", "fansub fonts not read", it) }.getOrDefault(emptyList())
            }
            if (packed.isNotEmpty() && _current.value?.id == item.id) renderer.setFonts(own + packed)
        }
    }

    private var mpvFontsJob: Job? = null
    private var mpvFontsFor: Pair<String, Set<String>>? = null

    /**
     * mpv draws [chosen] itself: the faces its script names (an .ass beside the video, or a stream's header,
     * as mpv reads it) picked from the fansub's pack, put in a folder of their own, and mpv's libass pointed at it.
     */
    private fun mpvAssFonts(chosen: com.therealityteam.videoplayer.data.playback.MediaTrack?) {
        mpvFontsJob?.cancel()
        val item = _current.value ?: return
        chosen ?: return
        mpvFontsJob = scope.launch(Dispatchers.IO) {
            // (A stream's header is mpv's to tell once it has the track open.)
            if (!chosen.external) kotlinx.coroutines.delay(1_500)
            val script = if (chosen.external) {
                sidecarsNow.firstOrNull { it.label == chosen.name && it.mimeType == ASS_MIME }
                    ?.let { s -> runCatching { readSubtitle(s.uri) }.getOrNull() }
                    ?.let { b -> String(b, com.therealityteam.videoplayer.data.subtitle.SubtitleParser.detectCharset(b)) }
            } else manager.subtitleHeader()
            val wanted = script?.let { com.therealityteam.videoplayer.data.subtitle.AssFonts.wanted(it) }.orEmpty()
            if (wanted.isEmpty() || mpvFontsFor == item.id to wanted) return@launch
            val picked = runCatching { packFontFiles(item, wanted) }.onFailure { android.util.Log.w("AuraAss", "fansub fonts not read", it) }.getOrDefault(emptyList())
            if (picked.isEmpty() || _current.value?.id != item.id) return@launch
            mpvFontsFor = item.id to wanted
            val dir = java.io.File(appContext.cacheDir, "fontsel").apply { deleteRecursively(); mkdirs() }
            picked.forEach { f ->
                val to = java.io.File(dir, f.name)
                runCatching { android.system.Os.link(f.path, to.path) }.getOrElse { f.copyTo(to, overwrite = true) }
            }
            manager.setSubtitleFonts(dir.path)
        }
    }

    /** What lies in a playing file's folder — its address and the names in it, folders ending in "/". */
    private data class Beside(val itemId: String, val folder: String, val names: List<String>)
    @Volatile private var beside: Beside? = null

    /**
     * The fonts of the fansub's pack beside [item] that its script names ([wanted]): a Fonts/ folder, an
     * archive named for fonts (.7z, .zip) or fonts loose in the folder — taken out once into the cache and
     * picked from by the names each font answers to.
     */
    private fun packFonts(item: MediaItem, wanted: Set<String>): List<Pair<String, ByteArray>> =
        packFontFiles(item, wanted).map { it.name to it.readBytes() }

    private fun packFontFiles(item: MediaItem, wanted: Set<String>): List<java.io.File> {
        val near = beside?.takeIf { it.itemId == item.id }
            ?: localBeside(item)?.also { beside = it }
            ?: return emptyList()
        val src = sourceFor(item).takeIf { !item.path.startsWith("/") }
        return fontPacks.pick(near.folder, near.names, wanted, src?.username, src?.password, MAX_FONTS_BYTES)
    }

    private val fontPacks by lazy {
        com.therealityteam.videoplayer.data.subtitle.FontPackFetcher(
            streamServer, java.io.File(appContext.cacheDir, "fontpacks"), java.io.File(appContext.cacheDir, "fontpack-parts"),
        )
    }

    /** The folder of a file on the headset, listed as a share's is. */
    private fun localBeside(item: MediaItem): Beside? {
        val dir = java.io.File((localCopy(item)?.path ?: item.path).removePrefix("file://")).parentFile?.takeIf { it.isDirectory } ?: return null
        return Beside(item.id, dir.path, dir.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }.orEmpty())
    }

    private fun matroskaFonts(item: MediaItem, url: String?): List<Pair<String, ByteArray>> {
        val started = android.os.SystemClock.elapsedRealtime()
        val local = (localCopy(item)?.path ?: item.path).takeIf { it.startsWith("/") || it.startsWith("file://") }?.removePrefix("file://")
        val source: com.therealityteam.videoplayer.data.playback.ByteSource
        val fetch: (com.therealityteam.videoplayer.data.playback.Attachment) -> ByteArray
        if (local != null) {
            val file = com.therealityteam.videoplayer.data.playback.FileByteSource(java.io.File(local))
            source = file
            fetch = { a -> file.read(a.dataOffset, a.size.toInt()) }
        } else {
            val http = com.therealityteam.videoplayer.data.playback.HttpByteSource(url?.takeIf { it.startsWith("http") } ?: return emptyList())
            source = http
            fetch = { a -> http.fetch(a.dataOffset, a.size.toInt()) }
        }
        val attached = com.therealityteam.videoplayer.data.playback.MatroskaAttachments.list(source)
        var total = 0L
        val fonts = mutableListOf<Pair<String, ByteArray>>()
        for (a in attached.filter { it.isFont && it.size in 1..MAX_FONT_BYTES }) {
            if (total + a.size > MAX_FONTS_BYTES) break
            fonts += a.name to fetch(a)
            total += a.size
        }
        android.util.Log.i("AuraAss", "${fonts.size} of ${attached.size} attachments are fonts (${total / 1024} KB), " +
            "read in ${android.os.SystemClock.elapsedRealtime() - started} ms: ${item.fileName}")
        return fonts
    }

    /** Where the video's whole frame lies in the window, as fractions of it: the panel, and past it what 去黑边 cut off. */
    private val _frameRect = MutableStateFlow(FrameRect.FULL)
    val frameRect: StateFlow<FrameRect> = _frameRect

    /** …and on a place's screen, as fractions of its face. */
    private val _screenFrameRect = MutableStateFlow(FrameRect.FULL)
    val screenFrameRect: StateFlow<FrameRect> = _screenFrameRect

    /** Where a [pw]×[ph] picture's whole frame lies in a [cw]×[ch] container it is centred in. */
    private fun frameRectOf(pw: Float, ph: Float, crop: com.therealityteam.videoplayer.data.playback.FrameCrop, cw: Float, ch: Float): FrameRect {
        if (cw <= 0f || ch <= 0f || pw <= 0f || ph <= 0f) return FrameRect.FULL
        val fw = pw / (1f - crop.left - crop.right).coerceAtLeast(0.1f)
        val fh = ph / (1f - crop.top - crop.bottom).coerceAtLeast(0.1f)
        // The picture is centred; the frame reaches past it by what was cut.
        val left = (cw - pw) / 2f - crop.left * fw
        val top = (ch - ph) / 2f - crop.top * fh
        return FrameRect(left / cw, top / ch, fw / cw, fh / ch)
    }

    /** The picture subtitle file (its label) asked for over Media3's menu, until mpv lists it. */
    @Volatile private var pictureWanted: String? = null

    /** 还有 N 个图形字幕: the film goes on in mpv, which draws them, one of them on. */
    fun showPictureSubtitles(): Boolean {
        val engine = manager as? SwitchingEngine ?: return false
        val sidecar = engine.pictureSidecars().firstOrNull() ?: return false
        pictureWanted = sidecar.label
        return engine.playPictureSubtitlesInMpv(sidecar).also { if (!it) pictureWanted = null }
    }

    /** Whether the subtitle file named [label] is pictures (a VobSub, a .sup) — no lines to read. */
    fun isPictureSidecar(label: String): Boolean =
        sidecarsNow.any { it.label == label && SubtitleEngine.isPicture(it.mimeType) }

    /** The second subtitle: the subtitle file of that name, or none. */
    fun selectSecondarySubtitle(label: String?) {
        secondaryJob?.cancel()
        val item = _current.value
        val sidecar = label?.let { l -> sidecarsNow.firstOrNull { it.label == l } }
        if (sidecar == null || item == null) {
            _secondary.value = null
            secondaryTail = null
            return
        }
        secondaryTail = com.therealityteam.videoplayer.data.playback.TrackPicks.tailOf(sidecar.label, item.fileName)
        loadSecondary(sidecar)
    }

    /** A file's subtitle files are known (none yet, at its start): the pick moves to the one with its tail. */
    private fun onSidecars(item: MediaItem, sidecars: List<com.therealityteam.videoplayer.data.playback.SidecarSubtitle>) {
        sidecarsNow = sidecars
        pictureWanted = null
        secondaryJob?.cancel()
        _secondary.value = null
        val tail = secondaryTail ?: return
        sidecars.firstOrNull {
            com.therealityteam.videoplayer.data.playback.TrackPicks.tailOf(it.label, item.fileName).equals(tail, ignoreCase = true)
        }?.let { loadSecondary(it) }
    }

    private fun loadSecondary(sidecar: com.therealityteam.videoplayer.data.playback.SidecarSubtitle) {
        secondaryJob = scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { readSubtitle(sidecar.uri) }.getOrNull() } ?: return@launch
            val track = com.therealityteam.videoplayer.data.subtitle.SubtitleParser.parse(bytes, sidecar.label)
            android.util.Log.i("AuraSubs", "second subtitle ${sidecar.label}: ${track.cues.size} cues")
            if (track.cues.isNotEmpty()) _secondary.value = SecondarySubtitle(sidecar.label, track)
        }
    }

    /** A subtitle file's bytes: from disk, or over the stream proxy for a file on a share. */
    private fun readSubtitle(uri: String): ByteArray =
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            java.net.URL(uri).openStream().use { it.readBytes() }
        } else {
            java.io.File(uri.removePrefix("file://")).readBytes()
        }

    // ---- Remembered tracks --------------------------------------------------------------

    /** Select an audio track and remember it for this file (library items persist it). */
    fun selectAudioTrack(id: String) {
        manager.selectAudioTrack(id)
        val item = _current.value ?: return
        audioAppliedFor = tracksKey(item)
        if (repo.get(item.id) != null) repo.setAudioTrackChoice(item.id, id)
        _current.value = item.copy(audioTrackId = id)
        // …and for the rest of its show.
        showKeyOf(item)?.let { key ->
            manager.audioTracks.value.firstOrNull { it.id == id }?.let {
                trackPicks?.setAudio(key, com.therealityteam.videoplayer.data.playback.TrackPicks.of(it, item.fileName))
            }
        }
    }

    /** Select a subtitle track (null = off) and remember the choice for this file. */
    fun selectSubtitleTrack(id: String?) {
        manager.selectSubtitleTrack(id)
        val item = _current.value ?: return
        android.util.Log.i("AuraTracks", "subtitles chosen by hand: ${id?.let { i -> manager.subtitleTracks.value.firstOrNull { it.id == i }?.name ?: i } ?: "off"}")
        subtitleAppliedFor = tracksKey(item)
        val saved = id ?: TrackMemory.SUBTITLES_OFF
        if (repo.get(item.id) != null) repo.setSubtitleTrackChoice(item.id, saved)
        _current.value = item.copy(subtitleTrackId = saved)
        // …and for the rest of its show: off, or this track described so the next episode finds it.
        showKeyOf(item)?.let { key ->
            if (id == null) {
                trackPicks?.setSubtitle(key, null)
            } else {
                manager.subtitleTracks.value.firstOrNull { it.id == id }?.let {
                    trackPicks?.setSubtitle(key, com.therealityteam.videoplayer.data.playback.TrackPicks.of(it, item.fileName))
                }
            }
        }
    }

    /** Manually correct the 3D / projection mode and re-bind the render entities. */
    fun setVideoMode(stereo: StereoMode, projection: ProjectionMode) {
        val item = _current.value ?: return
        val flags = item.video.copy(stereo = stereo, projection = projection, userOverridden = true)
        repo.setVideoFlags(item.id, flags)
        _current.value = item.copy(video = flags)
        _pano.value = panoOf(_current.value!!)
        if (panel != null) ensurePanel()
        if (sphere != null && _immersive.value) buildSphere()
    }

    /**
     * The picture as it stands, for 继续观看 (see ResumeFrame) — for a file with nothing else to show
     * there. Not with the subtitles drawn into it (mpv's), nor in the sphere, which has no Surface of
     * its own to copy.
     */
    /**
     * Automation (`-e aura.frame_dump <name>`): the frame on show, as decoded, 1920 wide, into the app's
     * own folder on the shared storage (`Android/data/<package>/files/frames/<name>.jpg`), for adb to pull —
     * the headset takes no screenshot of a spatial panel. In a sphere, its whole frame.
     */
    fun dumpFrame(name: String) {
        val surface = (if (_immersive.value && sphereReady) sphereSurface else pictureSurface()) ?: return
        val dir = appContext.getExternalFilesDir(null) ?: return
        val dest = java.io.File(dir, "frames/${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.jpg")
        scope.launch {
            val ok = com.therealityteam.videoplayer.data.playback.ResumeFrame.dump(surface, manager.videoAspect.value, 1920, dest)
            android.util.Log.i("AuraFrame", if (ok) "frame at ${manager.position.value} ms dumped to $dest" else "no frame to dump")
        }
    }

    private fun captureResumeFrame() {
        val item = _current.value ?: return
        if (!com.therealityteam.videoplayer.data.playback.ResumeFrame.wanted(item)) return
        // A panorama is kept as the window's view into it (left eye); a flat 3D frame's halves are not a picture.
        val pano = _pano.value
        if (pano == null && stereoToDimension(item.video?.stereo) != VideoDimensionMode.MONO) return
        if (manager.subtitlesInPicture.value) return
        // Round the viewer, the picture is the sphere's own Surface, seen where the head looks; in the window, its view.
        // (Only once the sphere is up: until then the film still plays in the window, and the sphere's
        // Surface may hold the last film's frame.)
        val immersed = _immersive.value && pano != null && sphereReady
        if (_immersive.value && pano != null && !immersed) return
        val surface = (if (immersed) sphereSurface else pictureSurface()) ?: return
        val dest = com.therealityteam.videoplayer.data.playback.ResumeFrame.file(appContext.filesDir, item.id)
        if (dest.isFile && com.therealityteam.videoplayer.data.playback.ResumeFrame.settled(manager.position.value, manager.duration.value)) return
        val look = if (immersed) headLook() else panoLook.copy(fov = RESUME_VIEW_FOV)
        scope.launch {
            val kept = if (pano != null) {
                com.therealityteam.videoplayer.data.playback.ResumeFrame.captureView(
                    surface, manager.videoAspect.value, pano, look, dest, sourceWidth = panoInfo?.frameWidth ?: 0,
                )
            } else {
                com.therealityteam.videoplayer.data.playback.ResumeFrame.capture(surface, manager.videoAspect.value, _frameCrop.value, dest)
            }
            if (kept) {
                com.therealityteam.videoplayer.ui.library.forgetArtwork(dest.absolutePath)
            }
        }
    }

    /**
     * Where the head looks in the film (its picture's own frame), from the turn PanoFollowSystem works out
     * each frame for the sound field: the listener's ahead, taken back into the film.
     */
    private fun headLook(): com.therealityteam.videoplayer.data.playback.PanoViewport.Look {
        val m = com.therealityteam.videoplayer.data.playback.SoundField.rotation
        val x = -m[6].toDouble()
        val y = -m[7].toDouble()
        val z = -m[8].toDouble()
        val yaw = Math.toDegrees(kotlin.math.atan2(x, -z)).toFloat()
        val pitch = Math.toDegrees(kotlin.math.asin(y.coerceIn(-1.0, 1.0))).toFloat()
        return com.therealityteam.videoplayer.data.playback.PanoViewport.Look(yaw, pitch, RESUME_VIEW_FOV)
    }

    fun saveProgress() {
        val item = _current.value ?: return
        // A cast's URL is the phone's, often signed for minutes: a position for it is nothing to come back to.
        if (item.sourceId == com.therealityteam.videoplayer.data.cast.CAST_SOURCE) return
        val dur = manager.duration.value
        if (dur > 0) repo.updateProgress(item.id, manager.position.value, dur)
    }

    fun close() {
        spaceChange?.cancel()
        // A held double speed ends with the player, whatever became of the hand that held it.
        holdBoost(false)
        startJob?.cancel()
        scrubJob?.cancel()
        nextUpJob?.cancel()
        // A delay still waiting to be applied applies now, without the restart, for the next film.
        if (subtitleDelayJob?.isActive == true) {
            (manager as? SwitchingEngine)?.subtitleDelayUs = _subtitleDelayMs.value * 1000L
        }
        subtitleDelayJob?.cancel()
        subtitleDelayJob = null
        _nextUp.value = null
        _finished.value = null
        _resumedFromMs.value = null
        sleepJob?.cancel()
        sleepJob = null
        _sleepMode.value = SleepMode.OFF
        _sleepRemainingMs.value = null
        saveProgress()
        sync?.nudge(minGapMs = 15_000)
        manager.pause()
        manager.endSession()
        releaseBridge()
        forgetChapterStills()
        panoJob?.cancel()
        guessJob?.cancel()
        _panoGuess.value = null
        panoInfo = null
        _pano.value = null
        panoYaw = 0f
        panoTilt = 0f
        panoRoll = 0f
        setSoundField(null)
        _immersive.value = false
        _cinemaDim.value = false
        cancelEntering()
        _placeFailed.value = null
        _environment.value = null
        _placeShown.value = null
        environmentScene.release()
        screenReflection.release(screen)
        sphere?.enabled = false
        screen?.enabled = false
        screenCaptions.clear()
        screenCaptions.clearAss()
        sphereCaptions.clear()
        panel?.enabled = true
        _current.value = null
        // Last, with no film current: nothing that reads the position can take the emptied player's 0 for it.
        manager.unload()
    }

}

/** How often a server that stopped answering mid-film is asked again, and how long it has to answer. */
/** 动态氛围光: how often the frame is looked at, how often the light moves, and how fast it follows (ms). */
private const val AMBIENCE_LOG_TAG = "AuraAmbience"
private const val AMBIENCE_SAMPLE_MS = 100L
private const val AMBIENCE_PAUSED_SETTLE_MS = 150L
private const val AMBIENCE_FRAME_MS = 33L
private const val AMBIENCE_EASE_MS = 120f
private const val AMBIENCE_WIDTH = 48

private const val SERVER_POLL_MS = 3_000L
private const val SERVER_PROBE_TIMEOUT_MS = 2_500
/** After waking mpv, how long it has to be playing before the server is asked again. */
private const val SERVER_WOKEN_GAP_MS = 10_000L

/** How far 上移 / 下移 and 左旋 / 右旋 go, either way: a crooked camera, not a turned head. */
private const val LEVEL_LIMIT = 45f
/** A panorama's 继续观看 picture: the window's view, this wide (degrees) — a card's look round, not its peephole. */
private const val RESUME_VIEW_FOV = 100f

/** 全景 by its look: the shapes a panorama comes in (2:1 a sphere or two squares, 1:1 two spheres stacked or one lens, 4:1). */
private val GUESS_SHAPES = listOf(2f, 1f, 4f)
/** Its frames copied this wide (ProjectionGuess's tests read 512-wide frames), this many, this far apart, from a little in. */
private const val GUESS_WIDTH = 512
private const val GUESS_FRAMES = 3
private const val GUESS_EVERY_MS = 6_000L
private const val GUESS_FIRST_MS = 3_000L
private const val GUESS_NONE = "none"

/** About what the window spans of a panorama seen through it from a seat before it, degrees across. */
private const val WINDOW_FOV = 60f

/** 沉浸空间's subtitles stand on a face this wide and tall (metres), this far ahead: some 56° across, like a large screen. */
const val PANO_CAPTION_WIDTH = 3.2f
const val PANO_CAPTION_HEIGHT = 1.8f
const val PANO_CAPTION_DISTANCE = 3f

/** 回正 as a sphere first shows: tried this often, this far apart, until the head is tracked. */
private const val RECENTER_TRIES = 30
private const val RECENTER_WAIT_MS = 100L

/** How long after a film's first picture its chapter pictures may start being read. */
private const val STILLS_AFTER_PICTURE_MS = 1_500L

/** A hung screen's border, as a share of its width, and how dark its face is (linear, unlit). */
private const val SCREEN_BEZEL = 0.012f
private const val SCREEN_FACE = 0.012f

/** How far behind the film a hung screen's face is: enough never to show through it. */
private const val SCREEN_BACKING_GAP = 0.01f

/** How often a playing file's position is written while it plays. */
private const val PROGRESS_SAVE_MS = 10_000L

/** Every how many of those saves 继续观看's picture of the place is taken again (a minute's worth). */
private const val RESUME_FRAME_TICKS = 6

/** How long the next-up card waits before starting the next item. */
private const val NEXT_UP_DELAY_MS = 5_000L

/** Items started by the countdown in a row, untouched, before the next one asks 还在看吗？ */
private const val STILL_WATCHING_AFTER = 3

/**
 * How long a place is read before its stage opens. The slowest reads in 10 s on the emulator (the
 * island, the classroom; the church took 28 s as a USDZ); past this the read is the stage's — a model
 * stuck off a stage is not.
 */
private const val PRELOAD_LIMIT_MS = 45_000L

/** How long a film playing may wait on its data (a seek, a hiccup) before 加载中 says so. */
private const val LOADING_SAY_AFTER_MS = 1_000L
/** Waits on data mid-film, the last measured slower than the film plays, before 加载中 says the network is the reason. */
private const val SLOW_LINK_AFTER = 2

/** How many files' picture shapes the player keeps ([PlaybackViewModel.aspectNow]). */
private const val ASPECTS_MAX = 500

/** A video panel's mesh as made: its size in metres and how the picture is laid on it. */
private data class PanelShape(
    val width: Float,
    val height: Float,
    val dimension: VideoDimensionMode,
    val crop: com.therealityteam.videoplayer.data.playback.FrameCrop,
)

/** 去黑边: frames looked at for bars, and how far apart in playing time. */
private const val CROP_SAMPLES = 8
private const val CROP_EVERY_MS = 10_000L

/** How long a sidecar's first lines may take to read for its language, and how much of it is read. */
private const val SIDECAR_READ_MS = 2_000L

/** How long a media server may take to say which subtitle files lie beside a film before it plays without them. */
private const val SERVER_SUBTITLES_MS = 3_000L
private const val SIDECAR_HEAD_BYTES = 48 * 1024

/** The most of an MKV's fonts read for libass — one font, and all of them: a CJK face whole is ~20 MB. */
private const val MAX_FONT_BYTES = 32L * 1024 * 1024
private const val MAX_FONTS_BYTES = 64L * 1024 * 1024

/** How long after a network comes back a film it took away is tried again. */
private const val NETWORK_SETTLE_MS = 1_500L

/** A .ass / .ssa sidecar's MIME type, as the share's listing names it. */
private const val ASS_MIME = "text/x-ssa"

/** The sidecars whose lines can be read for their language: the text formats the parser knows. */
private val READABLE_SUBTITLES = setOf("application/x-subrip", "text/x-ssa", "text/vtt")

/** Taken back this far from where the queue ended, the 看完了 card goes. */
private const val FINISHED_SEEK_BACK_MS = 5_000L

/** How far before the credits mark a seek has to land to call off their countdown — clear of jitter at the mark. */
private const val OUTRO_SEEK_BACK_MS = 2_000L

/** How long the subtitle-delay card waits after the last tap before restarting the film with it. */
private const val SUBTITLE_DELAY_SETTLE_MS = 700L

/** A thumbstick step — the same 15s the bar's −/+ buttons jump. */
private const val SEEK_STEP_MS = 15_000L

/** 拖动预览: the least time between two of a drag's frames asked of the engine. */
private const val SCRUB_STEP_MS = 60L

/** Credits ending this close to the end of the file run to it: skipping them finishes the episode. */
private const val CREDITS_TO_END_MS = 2_000L

/**
 * 自动跳过片头 acts on an opening first seen this close to its start — reached by playing (positions
 * come every half second) — and leaves alone one landed deeper in by a seek or a resume point.
 */
private const val AUTO_SKIP_REACH_MS = 2_000L
