package com.therealityteam.videoplayer.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.VideoFlags
import com.therealityteam.videoplayer.data.playback.AbRepeat
import com.therealityteam.videoplayer.data.playback.ExternalAudio
import com.therealityteam.videoplayer.data.playback.NowPlayingLabel
import com.therealityteam.videoplayer.data.playback.PanoLayout
import com.therealityteam.videoplayer.data.playback.ProjectionGuess
import com.therealityteam.videoplayer.data.playback.PlaylistMode
import com.therealityteam.videoplayer.data.playback.PlaylistNav
import com.therealityteam.videoplayer.data.playback.Resume
import com.therealityteam.videoplayer.data.playback.SleepMode
import com.therealityteam.videoplayer.data.playback.SoundFieldFilter
import com.therealityteam.videoplayer.data.playback.SphericalInfo
import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import com.therealityteam.videoplayer.data.playback.TrackMemory
import com.therealityteam.videoplayer.desktop.AppGraph
import com.therealityteam.videoplayer.desktop.data.DesktopSettings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.AudioRoute
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.platform.KeepAwake
import com.therealityteam.videoplayer.desktop.platform.NowPlaying
import com.therealityteam.videoplayer.desktop.platform.RemoteCommands
import com.therealityteam.videoplayer.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.Screenshots
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.togglePlayWhenReady

/**
 * Where libmpv and FFmpeg (about 50 MB of libraries) live on this computer. mediamp unpacks them
 * from its jar into a fresh temporary folder on every launch unless told otherwise; this keeps
 * one copy per mediamp version under the app's data folder, unpacked once. A marker file is
 * written only after an unpack completes, so a launch killed half-way unpacks again next time.
 */
object MpvRuntime {
    /** Changes when what is unpacked does: -t1 is the runtime without its duplicate libraries (build.gradle.kts). */
    private const val VERSION = "mediamp-0.5.0-t1"

    /**
     * Which FFmpeg the runtime carries: the build's marker when ours replaced mediamp's
     * (natives/build-ffmpeg.sh), so a runtime unpacked with the other one is never taken for it.
     */
    private val ffmpeg: String? =
        MpvRuntime::class.java.getResourceAsStream("/aura-ffmpeg.txt")?.use { it.readBytes().decodeToString().trim() }?.takeIf { it.isNotEmpty() }

    /** Whether mpv runs on our full FFmpeg — its whole libavfilter — rather than mediamp's cut-down one. */
    val fullFfmpeg: Boolean get() = ffmpeg != null
    @Volatile private var prepared = false

    @Synchronized
    fun prepare() {
        if (prepared) return
        prepared = true
        runCatching {
            val root = java.io.File(Host.dataDir, "runtime")
            val tag = VERSION + (ffmpeg?.let { "-$it" } ?: "") + "-" + System.getProperty("os.name").lowercase().replace(' ', '_') + "-" + System.getProperty("os.arch")
            val dir = java.io.File(root, tag)
            val done = java.io.File(root, "$tag.ok")
            // Earlier versions' copies go.
            root.listFiles()?.filter { it.name != tag && it.name != "$tag.ok" }?.forEach { it.deleteRecursively() }
            if (done.isFile) {
                MPVHandle.setRuntimeLibraryDirectory(dir.path, false)
            } else {
                dir.deleteRecursively()
                dir.mkdirs()
                MPVHandle.setRuntimeLibraryDirectory(dir.path, true)
                done.writeText(System.currentTimeMillis().toString())
            }
        }.onFailure { Log.w("AuraPlayer", "mpv runtime folder unusable, using mediamp's temporary one", it) }
    }
}

/** One audio or subtitle stream in the playing file, as mpv's track-list describes it. */
data class Track(
    val id: String,
    val title: String?,
    val language: String?,
    val codec: String?,
    val channels: Int,
    val sampleRate: Int,
    val external: Boolean,
    val externalFile: String?,
    val selected: Boolean,
    val default: Boolean,
    val forced: Boolean,
    val hearingImpaired: Boolean,
)

data class Chapter(val title: String?, val startMs: Long)

/** How the picture fills the window. */
enum class Aspect(val override: String?) { AUTO(null), FILL(null), STRETCH(null), R16_9("16:9"), R4_3("4:3"), R235("2.35:1") }

/**
 * The player: one mpv instance for the window's life, the queue it is playing through, and
 * everything the headset's player remembers and does — resume, positions saved as you watch,
 * each file's audio and subtitle choice, the subtitle look, A–B loops, the sleep timer, speed,
 * repeat and shuffle, what comes next.
 *
 * Commands run on the UI thread, as mediamp requires; mpv properties may be set from anywhere.
 */
class PlayerController(private val graph: AppGraph, private val scope: CoroutineScope) {

    private var created: MpvMediampPlayer? = null

    /** Made on first use — loading libmpv is not free, and a library browse never needs it. */
    val player: MpvMediampPlayer
        get() = created ?: create().also { created = it; subMargin = SUB_MARGIN; watch(it) }

    private val handle: MPVHandle get() = player.impl as MPVHandle

    private val opener = MediaOpen(graph.sources, graph.streamServer)

    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var index by mutableStateOf(-1)
        private set
    val current: MediaItem? get() = queue.getOrNull(index)

    /** The card's title — what the top bar says above the file. */
    var cardTitle by mutableStateOf("")
        private set

    var mode by mutableStateOf(PlaylistMode.SEQUENTIAL)
    var speed by mutableStateOf(1f)
        private set
    var abA by mutableStateOf<Long?>(null)
        private set
    var abB by mutableStateOf<Long?>(null)
        private set
    var sleep by mutableStateOf(SleepMode.OFF)
        private set
    var sleepEndsAt by mutableStateOf<Long?>(null)
        private set
    var subtitleDelayMs by mutableStateOf(0L)
        private set
    var audioDelayMs by mutableStateOf(0L)
        private set
    /** Volume boost past 100 %, as a percentage (100 = none). */
    var boost by mutableStateOf(100)
        private set

    /** What the playing file's frame holds — the library's flags, shared with the headset. */
    var flags by mutableStateOf(VideoFlags())
        private set
    /** Where the viewer looks, in a spherical film. */
    var view by mutableStateOf(SphereView())
        private set
    val format: PictureFormat get() = PictureFormat.of(flags)
    /**
     * How the playing file's picture surrounds the viewer, null for a flat one — the headset's rule
     * (PanoLayout.forFilm): the viewer's format, else what the file says of itself, else its name.
     */
    var pano by mutableStateOf<PanoLayout?>(null)
        private set
    val spherical: Boolean get() = pano != null
    /** What the playing file's header says of it as a panorama, once read ([readPano]). */
    @Volatile private var panoInfo: SphericalInfo? = null
    private var panoJob: Job? = null
    /**
     * A panorama the picture looks like, when neither the file nor its name said so (ProjectionGuess, as
     * the headset asks it): shown as a question — 全景播放 / 不是 — until answered or the file changes.
     */
    var panoGuess by mutableStateOf<ProjectionGuess.Guess?>(null)
        internal set
    private var guessJob: Job? = null
    private val memory = PanoMemory(File(graph.filesDir, "pano-memory.properties"))

    /**
     * 画面方向: the playing panorama's horizon levelled — degrees its front is lifted, and turned clockwise —
     * for a camera held crooked; kept for the film, as the headset keeps it.
     */
    var level by mutableStateOf(0f to 0f)
        private set

    /** The playing file's sound field, while mpv decodes it (SoundFieldFilter); null for ordinary sound. */
    @Volatile private var soundField: com.therealityteam.videoplayer.data.playback.AmbisonicAudio? = null
    /** The sound field the file's header named, decoded or not — put back when its track is chosen again. */
    @Volatile private var fileField: com.therealityteam.videoplayer.data.playback.AmbisonicAudio? = null
    /** The playing file's frame, pixels (0 until mpv knows): how a cubemap's faces lie, EAC's border. */
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    /** The picture area's width over its height, as the screen last reported it. */
    @Volatile private var viewportAspect = 16.0 / 9.0
    private val viewRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    var aspect by mutableStateOf(Aspect.AUTO)
        private set
    var audioTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var subtitleTracks by mutableStateOf<List<Track>>(emptyList())
        internal set
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        internal set
    /** Set when a file opened part-way through: the resume toast offers "from the start". */
    var resumedFromMs by mutableStateOf<Long?>(null)
    var error by mutableStateOf<String?>(null)
        private set
    /** Why a disc image (原盘 .iso) did not play, when that was it — said in words of its own ([error] is then ""). */
    var discProblem by mutableStateOf<MediaOpen.DiscProblem.Kind?>(null)
        private set
    /** The outputs mpv can play through: its name for each, and what the system calls it. */
    var audioDevices by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    /** Set when the file has sound but the output device could not be opened — the player says so. */
    var audioOutputFailed by mutableStateOf(false)
        private set

    /** Seconds left before the next file starts by itself; null when no countdown is running. */
    var upNextIn by mutableStateOf<Int?>(null)

    /**
     * The video track's codec when its pictures cannot be decoded — sound over black otherwise,
     * with no word why; "" when the codec is not known either. Null while pictures come, or
     * before anyone could tell.
     */
    var noPicture by mutableStateOf<String?>(null)

    /** A word for the picture's message line, from outside the player (the menu bar): text and when. */
    var notice by mutableStateOf<Pair<String, Long>?>(null)
        private set

    fun tell(text: String) { notice = text to System.currentTimeMillis() }

    /** When playback last paused itself because the sound moved to the computer's speakers. */
    var pausedForSpeakers by mutableStateOf<Long?>(null)
        private set

    private var loadJob: Job? = null
    private var sleepJob: Job? = null
    private var countdownJob: Job? = null
    private var tracksAppliedFor: String? = null

    private fun create(): MpvMediampPlayer {
        MpvRuntime.prepare()
        val s = graph.settings.value
        return MpvMediampPlayer(Unit, EmptyCoroutineContext, Dispatchers.Main) { h ->
            // The subtitles beside a file are found and handed over by MediaOpen, for every
            // source alike; mpv's own guess would add them a second time for local files.
            h.option("sub-auto", "no")
            h.option("hwdec", if (s.hardwareDecoding) "auto-safe" else "no")
            h.option("audio-device", s.audioDevice.ifBlank { "auto" })
            h.option("slang", subtitleLanguages())
            h.option("sub-codepage", "auto")
            h.option("screenshot-format", "png")
            // A NAS over Wi-Fi hiccups; a desktop has the memory to ride it out.
            h.option("demuxer-max-bytes", "${256 * 1024 * 1024}")
            h.option("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
            h.option("cache", "yes")
            h.option("keep-open", "yes")
            h.option("sub-font-size", "44")
            h.option("sub-margin-y", "$SUB_MARGIN")
            // Anything else, for whoever needs it: -Daura.mpv.opts="key=value;key=value".
            System.getProperty("aura.mpv.opts")?.split(';')?.filter { '=' in it }?.forEach {
                h.option(it.substringBefore('=').trim(), it.substringAfter('=').trim())
            }
        }
    }

    private fun readerLocale(): Locale = Locale.forLanguageTag(graph.settings.value.uiLanguage.ifBlank { Locale.getDefault().toLanguageTag() })

    /** Subtitle languages mpv should prefer: the app's language in every spelling, then English. */
    private fun subtitleLanguages(): String {
        val tag = graph.settings.value.uiLanguage.ifBlank { Locale.getDefault().toLanguageTag() }
        val locale = Locale.forLanguageTag(tag)
        val zhHant = locale.language == "zh" &&
            (locale.script.equals("Hant", true) || locale.country.uppercase() in setOf("TW", "HK", "MO"))
        val own = when {
            zhHant -> "cht,tc,zh-Hant,zh-TW,zh-HK,big5,chi,zho,zh,chs,sc,zh-Hans"
            locale.language == "zh" -> "chs,sc,zh-Hans,zh-CN,gb,chi,zho,zh,cht,tc,zh-Hant"
            else -> listOfNotNull(locale.language, runCatching { locale.isO3Language }.getOrNull()).joinToString(",")
        }
        return listOf(own, "eng,en").filter { it.isNotBlank() }.joinToString(",")
    }

    /** The system's media controls — media keys, headphones, Control Center — for this player. */
    private val nowPlaying: NowPlaying by lazy {
        NowPlaying.create(
            RemoteCommands(
                play = { if (current != null) player.resume() },
                pause = { if (current != null) player.pause() },
                toggle = { if (current != null) togglePause() },
                next = { next() },
                previous = { prev() },
                seekTo = { seekTo(it) },
            ),
        )
    }

    /**
     * What is playing, told to the system when it changes — a pause, a seek, another file, a new
     * speed — and every few seconds besides; in between the system counts the time itself.
     */
    private fun tellNowPlaying(p: MpvMediampPlayer) {
        scope.launch {
            var lastPos = 0L
            var lastSent = 0L
            var lastKey: Any? = null
            while (isActive) {
                delay(500)
                val item = current
                val st = p.state.value
                if (item == null || st.mediaStatus != MediaStatus.Ready) {
                    if (lastKey != null) { nowPlaying.clear(); lastKey = null }
                    continue
                }
                val pos = p.currentPositionMillis.value
                val key = listOf(item.id, st.isPlaying, speed, cardTitle)
                val now = System.currentTimeMillis()
                val jumped = kotlin.math.abs(pos - lastPos - (if (st.isPlaying) (now - lastSent) * speed else 0f).toLong()) > 2_000
                if (key != lastKey || jumped || now - lastSent > 5_000) {
                    // As the headset shows it: the title; the show and episode, or the year; the poster.
                    val title = item.title.ifBlank { cardTitle.ifBlank { item.fileName } }
                    val subtitle = NowPlayingLabel.subtitle(item.showTitle, item.season, item.episode, item.year)
                    nowPlaying.update(title, subtitle, item.posterPath, durationMs(), pos, st.isPlaying, speed.toDouble())
                    lastKey = key
                    lastSent = now
                    lastPos = pos
                }
            }
        }
    }

    private fun watch(p: MpvMediampPlayer) {
        scope.launch(Dispatchers.IO) { drawViews() }
        tellNowPlaying(p)
        // Tracks and chapters: mediamp tells us the lists changed; the details come from mpv.
        val meta = p.features[MediaMetadata]
        scope.launch {
            val audio = meta?.audioTracks?.candidates ?: return@launch
            val subs = meta.subtitleTracks?.candidates ?: return@launch
            combine(audio, subs) { a, s -> a.size to s.size }.collect { refreshTracks() }
        }
        scope.launch {
            meta?.audioTracks?.selected?.collect { refreshTracks() }
        }
        scope.launch {
            meta?.subtitleTracks?.selected?.collect { refreshTracks() }
        }
        scope.launch {
            meta?.chapters?.collect { list ->
                chapters = list.map { Chapter(com.therealityteam.videoplayer.data.playback.ChapterReader.named(it.name).takeIf { n -> n.isNotBlank() }, it.offsetMillis) }
                    // A disc's film: its playlist's marks (its streams hold none).
                    .ifEmpty { opener.discChaptersMs.map { Chapter(null, it) } }
            }
        }
        // The end of a file: mark it watched, then go on as the repeat mode says.
        scope.launch {
            p.events.filterIsInstance<PlaybackEvent.MediaEnded>().collect { onEnded() }
        }
        // Errors, in words.
        scope.launch {
            p.state.map { it.mediaStatus }.distinctUntilChanged().collect { st ->
                if (st is MediaStatus.Error) {
                    error = st.error.message ?: st.error.toString()
                    Log.w(TAG, "playback error: $error")
                }
            }
        }
        // Positions saved as you watch (every 10 s) and on a pause; the other devices hear of them
        // every ten minutes of watching, and soon after a pause — the moment one gets up and
        // carries on elsewhere.
        scope.launch {
            var lastSave = 0L
            var wasPlaying = false
            while (isActive) {
                delay(500)
                val item = current ?: continue
                val st = p.state.value
                if (st.mediaStatus != MediaStatus.Ready) continue
                val now = System.currentTimeMillis()
                if (st.isPlaying && now - lastSave >= 10_000) {
                    lastSave = now
                    saveProgress(item)
                    graph.sync.nudge(minGapMs = 10 * 60_000)
                } else if (wasPlaying && !st.isPlaying) {
                    saveProgress(item)
                    graph.sync.nudge(minGapMs = 15_000)
                }
                wasPlaying = st.isPlaying
            }
        }
        // A video track and no picture: a codec the bundled decoders do not know plays as sound
        // over black. Once the file has played on for a few seconds with nothing decoded, say
        // so (the headset's "cannot decode the video"). Album art is no video.
        scope.launch {
            var since = 0L
            var watched: String? = null
            while (isActive) {
                delay(1_000)
                val item = current
                val st = p.state.value
                if (item == null || st.mediaStatus != MediaStatus.Ready) { since = 0L; continue }
                if (item.id != watched) { watched = item.id; since = 0L; noPicture = null }
                if (noPicture != null || !st.isPlaying) continue
                val h = p.impl as MPVHandle
                fun str(prop: String) = runCatching { h.getPropertyString(prop) }.getOrNull()?.takeIf { it.isNotBlank() }
                // The file's video track, chosen or not: mpv leaves one it has no decoder for unchosen.
                val n = runCatching { h.getPropertyInt("track-list/count") }.getOrDefault(0)
                val video = (0 until n).firstOrNull { i -> str("track-list/$i/type") == "video" && str("track-list/$i/albumart") != "yes" }
                if (video == null || str("video-params/w") != null) { since = 0L; continue }
                if (since == 0L) since = System.currentTimeMillis()
                if (System.currentTimeMillis() - since >= 4_000) {
                    // "" when not even the codec is known (a FourCC FFmpeg has never heard of).
                    val codec = str("track-list/$video/codec")?.takeIf { it != "unknown" } ?: str("track-list/$video/codec-desc") ?: ""
                    noPicture = codec.uppercase()
                    Log.w(TAG, "no picture from ${item.fileName}: ${codec.ifEmpty { "an unknown codec" }}")
                }
            }
        }
        // Headphones out, AirPods away, a TV's cable pulled: the sound would carry on from the
        // computer's own speakers. Pause instead, as the headset does, and say why.
        scope.launch(Dispatchers.IO) {
            var last: AudioRoute.Route? = null
            while (isActive) {
                delay(1_000)
                if (!p.state.value.isPlaying) { last = null; continue }
                val now = AudioRoute.current() ?: continue
                if (AudioRoute.becameNoisy(last, now)) {
                    Log.i(TAG, "sound moved from $last to $now: pausing")
                    withContext(Dispatchers.Main) { p.pause(); pausedForSpeakers = System.currentTimeMillis() }
                }
                last = now
            }
        }
        // The screen stays on while something plays (KeepAwake), and may sleep again on a pause.
        scope.launch {
            p.state.map { it.mediaStatus == MediaStatus.Ready && it.isPlaying }.distinctUntilChanged().collect { KeepAwake.hold(it) }
        }
        // Settings the player follows live: the subtitle look, hardware decoding.
        scope.launch {
            graph.settings.settings.map { it.subtitleLook() }.distinctUntilChanged().collect { applySubtitleLook(it) }
        }
        scope.launch {
            graph.settings.settings.map { it.volume to it.muted }.distinctUntilChanged().collect { applyVolume() }
        }
    }

    /** The settings the player's controls read and change (volume, the subtitle look…). */
    fun graphSettings() = graph.settings.settings

    fun updateSettings(change: (DesktopSettings) -> DesktopSettings) = graph.settings.update(change)

    /** How far the arrow keys and the ±buttons skip, in seconds. */
    fun seekStep(): Int = graph.settings.value.seekStep

    fun volume(): Float = graph.settings.value.volume

    // ---- Opening -----------------------------------------------------------------------------

    fun open(item: MediaItem, queue: List<MediaItem>, startMs: Long?, title: String?) {
        val q = queue.ifEmpty { listOf(item) }
        val i = q.indexOfFirst { it.id == item.id }
        this.queue = if (i >= 0) q else listOf(item)
        this.index = if (i >= 0) i else 0
        this.cardTitle = title ?: item.showTitle ?: item.title
        load(startMs)
    }

    /** Show [item] in the controls without opening it — for rendering the player offscreen. */
    internal fun previewOnly(item: MediaItem, queue: List<MediaItem>, title: String) {
        this.queue = queue
        this.index = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        this.cardTitle = title
    }

    /** Play queue entry [i] from where it was left. */
    fun jumpTo(i: Int) {
        if (i !in queue.indices || i == index) return
        current?.let { saveProgress(it) }
        index = i
        load(null)
    }

    private fun load(startOverride: Long?) {
        val item = current ?: return
        loadJob?.cancel()
        countdownJob?.cancel()
        upNextIn = null
        error = null
        discProblem = null
        clearAb()
        tracksAppliedFor = null
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        chapters = emptyList()
        loadJob = scope.launch {
            // Where another device left this file, if sync is on — never more than a moment's wait.
            graph.sync.freshen()
            val fresh = graph.media.get(item.id) ?: item
            flags = fresh.video
            view = SphereView()
            panoJob?.cancel()
            guessJob?.cancel()
            panoGuess = null
            panoInfo = null
            level = withContext(Dispatchers.IO) { memory.level(fresh.id) }
            frameW = 0
            frameH = 0
            pano = PanoLayout.forFilm(flags, fresh.fileName, null)
            // The last file's shaders: mpv holds what it read of them, so the files can go.
            withContext(Dispatchers.IO) {
                File(Host.dataDir, "shaders").listFiles { f -> f.name.startsWith("view_") || f.name.startsWith("aura-") }?.forEach { it.delete() }
            }
            val start = Resume.decide(fresh.progress, startOverride)
            resumedFromMs = if (start.prompt) start.positionMs else null
            try {
                val media = opener.open(fresh)
                // (mpv's list may not change from the last file's empty one: no word would come of it.)
                if (opener.discChaptersMs.isNotEmpty()) chapters = opener.discChaptersMs.map { Chapter(null, it) }
                // The last file's sound-field decode would take this one's channels for its own.
                applySoundField(null)
                readPano(fresh, media.uri)
                val p = player
                // mediamp's mpv backend does not load MediaExtraFiles; mpv's own sub-files list
                // does, as external tracks the file's automatic subtitle choice can then pick.
                runCatching {
                    handle.command("change-list", "sub-files", "clr", "")
                    media.extraFiles.subtitles.forEach { sub -> handle.command("change-list", "sub-files", "append", sub.uri) }
                }
                // …and its 外挂音轨 (a dub's .mka beside it): offered as sound tracks, the file's own still first.
                runCatching {
                    handle.command("change-list", "audio-files", "clr", "")
                    opener.audioFiles.forEach { handle.command("change-list", "audio-files", "append", it.uri) }
                    if (opener.audioFiles.isNotEmpty()) Log.i(TAG, "外挂音轨: ${opener.audioFiles.joinToString { it.label }}")
                }
                // The last file's crop and view shader go before this one's first frame; a spherical
                // file is seen through its view from that frame on (the view needs no frame size).
                runCatching {
                    handle.setPropertyString("video-crop", "0x0+0+0")
                    if (spherical) {
                        handle.setPropertyDouble("video-aspect-override", viewportAspect)
                        viewRequests.trySend(Unit)
                    } else {
                        handle.command("change-list", "glsl-shaders", "clr", "")
                    }
                }
                p.setMediaData(media, playWhenReady = true, startPositionMillis = start.positionMs)
                applyPersistentSessionState()
                Log.i(TAG, "playing ${fresh.fileName} from ${start.positionMs}ms")
                // The track list fills in over the first moments (embedded, then the files beside
                // it); choose once it has settled, not on the first partial list.
                delay(350)
                refreshTracks()
                applyTrackMemory()
                subtitleFontsSoon()
                checkAudioOutput()
                applyPicture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaOpen.DiscProblem) {
                discProblem = e.kind
                error = ""
                Log.i(TAG, "${fresh.fileName}: ${e.kind}")
            } catch (e: MediaOpen.DataDisc) {
                // Its videos, as a folder's: the first plays, the others are next.
                val videos = e.paths.map { inner ->
                    val at = com.therealityteam.videoplayer.data.disc.DiscImages.videoPath(fresh.path, inner)
                    val name = inner.substringAfterLast('/')
                    MediaItem(MediaItem.idFor(fresh.sourceId, at), fresh.sourceId, at, name, title = name.substringBeforeLast('.'))
                }
                Log.i(TAG, "${fresh.fileName}: a data disc of ${videos.size} videos")
                open(videos.first(), videos, startMs = 0L, title = fresh.title)
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
                Log.w(TAG, "could not open ${fresh.path}", e)
            }
        }
    }

    /**
     * What [item]'s file says of itself as a panorama: its header, read beside the player (the file, or
     * the address mpv plays — a share through the proxy, a server's stream). The library learns it
     * (SphericalInfo.learnedFlags — its 360°/180° tag, and the layout known before the next read), as
     * the headset's does; the picture is laid out again by it.
     */
    private fun readPano(item: MediaItem, uri: String) {
        panoJob?.cancel()
        panoJob = scope.launch {
            val info = withContext(Dispatchers.IO) { Projection.spherical(uri) }
            if (current?.id != item.id) return@launch
            if (info != null && (info.isSpherical || info.stereo != null || info.ambisonic != null)) Log.i(TAG, "${item.fileName}: $info")
            panoInfo = info
            applySoundField(info?.ambisonic)
            info?.learnedFlags(flags)?.takeIf { it != flags }?.let { learned ->
                Log.i(TAG, "${item.fileName}: the library learns $learned")
                flags = learned
                if (!item.id.startsWith("adhoc:")) withContext(Dispatchers.IO) { graph.media.learnVideoFlags(item.id, learned) }
            }
            refreshPano()
            startPanoGuess(item)
        }
    }

    /**
     * The picture's own look, when the file and its name said nothing (core ProjectionGuess, the headset's):
     * three frames six seconds of playing apart, 512 across, as mpv decodes them (no view drawn on them),
     * two of which must agree. Only frames of a panorama's shapes (2:1, 1:1, 4:1); the answer — or that there is
     * none — remembered for the file.
     */
    private fun startPanoGuess(item: MediaItem) {
        guessJob?.cancel()
        panoGuess = null
        if (pano != null || flags.userOverridden) return
        memory.guess(item.id)?.let { known -> panoGuess = known.guess; return }
        guessJob = scope.launch {
            delay(GUESS_FIRST_MS)
            repeat(20) { if (frameW <= 0) delay(250) }
            if (frameW <= 0 || frameH <= 0) return@launch
            val aspect = frameW.toFloat() / frameH
            if (GUESS_SHAPES.none { kotlin.math.abs(aspect / it - 1f) < 0.04f }) return@launch
            val found = mutableListOf<ProjectionGuess.Guess?>()
            var lastAt = -GUESS_EVERY_MS
            var tries = 0
            val preview = created?.features?.get(FramePreview) ?: return@launch
            val w = GUESS_WIDTH
            val hh = (w.toFloat() / aspect).toInt().coerceAtLeast(2) / 2 * 2
            while (found.size < GUESS_FRAMES && tries++ < GUESS_FRAMES * 6) {
                delay(1_000)
                if (current?.id != item.id || pano != null) return@launch
                val at = player.currentPositionMillis.value
                if (!player.state.value.isPlaying || at - lastAt < GUESS_EVERY_MS) continue
                // The decoded frame (no view drawn on it, no subtitles), small, as the seek bar's pictures are
                // made — not a full-size screenshot, whose encoding would hold the window up.
                val frame = runCatching { preview.getPreviewFrame(at, w, hh) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull() ?: continue
                val luma = withContext(Dispatchers.Default) { ProjectionGuess.Luma.ofArgb(frame.pixels, frame.width, frame.height) }
                // A dark frame (a fade, a night) says nothing either way.
                if (luma.v.average() < 0.06) continue
                found += withContext(Dispatchers.Default) { ProjectionGuess.guess(luma) }
                lastAt = at
            }
            val verdict = ProjectionGuess.agree(found)
            Log.i(TAG, "${item.fileName}: looks like ${verdict ?: "a flat film"} (frames: $found)")
            if (found.size < 2) return@launch
            withContext(Dispatchers.IO) { memory.keepGuess(item.id, verdict) }
            if (current?.id == item.id && pano == null) panoGuess = verdict
        }
    }

    /** 全景播放: the look taken as the viewer's choice of format, as 全景格式 would make it. */
    fun acceptPanoGuess() {
        val guess = panoGuess ?: return
        panoGuess = null
        takeFlags(flags.copy(stereo = guess.stereo, projection = guess.projection, lensFov = 0, rightFirst = false, userOverridden = true))
    }

    /** 不是: a flat film after all — not asked again. */
    fun dismissPanoGuess() {
        val item = current ?: return
        panoGuess = null
        scope.launch(Dispatchers.IO) { memory.keepGuess(item.id, null) }
    }

    /** The playing file's layout again, from its flags and what it said of itself — and its picture laid out by it. */
    private fun refreshPano() {
        val item = current ?: return
        val next = PanoLayout.forFilm(flags, item.fileName, panoInfo)
        if (next != pano) {
            Log.i(TAG, "${item.fileName}: sphere ${next?.let { "${it.projection} ${it.stereo}" + (if (it.eyesSwapped) " right-first" else "") + (if (it.ownMesh != null) " own-mesh" else "") } ?: "none"}")
            pano = next
        }
        turnSoundField()
        applyPicture()
    }

    /**
     * 全景声: [audio]'s sound field decoded to the two ears by mpv's own filters (SoundFieldFilter), turned
     * with the view; null takes the decode off. A graph mpv cannot build (a libavfilter without these
     * filters) leaves the sound as the file has it.
     */
    private fun applySoundField(audio: com.therealityteam.videoplayer.data.playback.AmbisonicAudio?, fromFile: Boolean = true) {
        if (fromFile) fileField = audio
        val h = created?.let { it.impl as MPVHandle } ?: return
        // mediamp's cut-down libavfilter (Windows, for now) may lack these filters, and a graph mpv cannot
        // build takes the whole sound with it: there the field plays as the file has it.
        if (audio != null && !MpvRuntime.fullFfmpeg) {
            Log.i(TAG, "全景声 left as the file has it: mpv's libavfilter here is mediamp's cut-down one")
            return
        }
        val af = audio?.let { System.getProperty("aura.probe.fieldaf") ?: SoundFieldFilter.af(it) }
        if (af == null && soundField == null) return
        // (mediamp's calls say false rather than throw.)
        val ok = runCatching { h.setPropertyString("af", af ?: "") }.getOrDefault(false)
        if (!ok) Log.w(TAG, "mpv would not take the sound field's filters")
        soundField = if (ok && af != null) audio else null
        val field = soundField ?: return
        Log.i(TAG, "全景声: ${field.from} order ${field.order}, ${field.channels} channels, decoded to two ears")
        turnSoundField()
        // A graph that did not build answers no command. Set while the sound plays, it is passed over and
        // the four channels go out as they are; set before, it takes the whole sound with it ("Audio: no
        // audio", and mpv does not try again) — then the track is picked anew, the filters off. Asked once
        // the sound is under way (a stream can take seconds; the graph is built on its first samples), and
        // only of the field's own track: another playing — a stereo dub, a 5.1 mix — is no sound field.
        scope.launch {
            withTimeoutOrNull(SOUND_FIELD_WAIT_MS) {
                while (runCatching { h.getPropertyString("audio-params/channel-count") }.getOrNull().isNullOrBlank()) delay(250)
            }
            delay(500)
            if (soundField !== field) return@launch
            val channels = runCatching { h.getPropertyString("audio-params/channel-count") }.getOrNull()?.toIntOrNull()
            val fits = channels == null || channels == field.channels
            if (fits) {
                if (turnSoundField()) return@launch
                delay(1_000)
                if (soundField !== field || turnSoundField()) return@launch
            }
            Log.w(TAG, if (fits) "全景声: the filters are not running — the track plays as it is" else "全景声: the track playing ($channels channels) is not the sound field")
            soundField = null
            runCatching {
                val silent = h.getPropertyString("audio-out-params/channels").isNullOrBlank()
                val aid = h.getPropertyString("aid")?.takeIf { it != "no" } ?: "auto"
                h.setPropertyString("af", "")
                if (silent) {
                    h.setPropertyString("aid", "no")
                    h.setPropertyString("aid", aid)
                }
            }
        }
    }

    /** How long the sound may take to start before the sound field's filters are judged. */
    private val SOUND_FIELD_WAIT_MS = 12_000L

    /** A panorama's seek-bar pictures are looked into from a frame this wide at least. */
    private val PANO_PREVIEW_SOURCE = 1536

    /** 画面方向's step and its farthest, degrees: the headset's (PANO_LEVEL_STEP, LEVEL_LIMIT). */
    val LEVEL_STEP = 5f
    private val LEVEL_LIMIT = 45f

    // The headset's numbers for looking at a picture (PlaybackViewModel's GUESS_*).
    private val GUESS_SHAPES = listOf(2f, 1f, 4f)
    private val GUESS_WIDTH = 512
    private val GUESS_FRAMES = 3
    private val GUESS_EVERY_MS = 6_000L
    private val GUESS_FIRST_MS = 3_000L

    /**
     * The sound field turned to where the view looks: the film's front while the picture is flat. True
     * when the filters took it.
     */
    private fun turnSoundField(): Boolean {
        if (soundField == null) return false
        val h = created?.let { it.impl as MPVHandle } ?: return false
        // Where the view looks in the film's own frame: the view's turn and the picture's own (the file's pose yaw).
        val (onY, onX) = SoundFieldFilter.turn(if (spherical) view.yaw + Math.toRadians(pose()[0].toDouble()) else 0.0)
        return runCatching {
            h.command("af-command", SoundFieldFilter.LABEL, "volume", SoundFieldFilter.f(onY), SoundFieldFilter.ON_Y) &&
                h.command("af-command", SoundFieldFilter.LABEL, "volume", SoundFieldFilter.f(onX), SoundFieldFilter.ON_X)
        }.getOrDefault(false)
    }

    /**
     * The sphere's turn: the file's own orientation, unless the viewer chose its format (the headset's
     * rule), and the horizon's level on top (its pitch and roll, as the headset adds them).
     */
    private fun pose(): FloatArray {
        val info = panoInfo?.takeIf { !flags.userOverridden }
        return floatArrayOf(info?.poseYaw ?: 0f, (info?.posePitch ?: 0f) + level.first, (info?.poseRoll ?: 0f) + level.second)
    }

    /** 上移/下移 ([tilt]) and 左旋/右旋 ([roll], clockwise), degrees: the horizon levelled, kept for the film. */
    fun nudgeLevel(tilt: Float, roll: Float) {
        val (t, r) = level
        setLevel((t + tilt).coerceIn(-LEVEL_LIMIT, LEVEL_LIMIT), (r + roll).coerceIn(-LEVEL_LIMIT, LEVEL_LIMIT))
    }

    /** 放平: the picture as filmed. */
    fun resetLevel() = setLevel(0f, 0f)

    private fun setLevel(tilt: Float, roll: Float) {
        val item = current ?: return
        if (!spherical) return
        level = tilt to roll
        Log.i(TAG, "level: up %.0f°, turned %.0f°".format(tilt, roll))
        scope.launch(Dispatchers.IO) { memory.keepLevel(item.id, tilt, roll) }
        viewRequests.trySend(Unit)
    }

    /**
     * Session-long choices that outlive one file: speed, boost, audio and subtitle offsets,
     * aspect — the next episode of a release plays with the timing its first one needed, as on
     * the headset.
     */
    private fun applyPersistentSessionState() {
        runCatching {
            handle.setPropertyDouble("speed", speed.toDouble())
            handle.setPropertyDouble("audio-delay", audioDelayMs / 1000.0)
            handle.setPropertyDouble("sub-delay", subtitleDelayMs / 1000.0)
            applyAspect(aspect)
            applyVolume()
            applySubtitleLook(graph.settings.value.subtitleLook())
        }
    }

    /**
     * A file with sound and no output: some USB devices (a Bose USB speaker, found the hard way)
     * report no channel layout, and mpv's CoreAudio output refuses them. Nothing is switched
     * behind the user's back — sound from the laptop while their headset is the default would be
     * worse than silence they can see explained — the player says so and offers the other outputs.
     */
    private suspend fun checkAudioOutput() {
        delay(1_500)
        val h = created?.let { it.impl as MPVHandle } ?: return
        refreshAudioDevices()
        val hasAudio = audioTracks.isNotEmpty()
        val ao = runCatching { h.getPropertyString("current-ao") }.getOrNull()
        audioOutputFailed = hasAudio && ao.isNullOrBlank()
        if (audioOutputFailed) Log.w(TAG, "audio output could not be opened on ${h.getPropertyString("audio-device")}")
    }

    fun refreshAudioDevices() {
        val h = created?.let { it.impl as MPVHandle } ?: return
        val json = runCatching { h.getPropertyString("audio-device-list") }.getOrNull() ?: return
        audioDevices = runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.optString("name") to o.optString("description")
            }
        }.getOrDefault(emptyList())
    }

    /** Play through [name] (mpv's "auto" is the system default), now and from now on. */
    fun chooseAudioDevice(name: String) {
        graph.settings.update { it.copy(audioDevice = name) }
        val h = handle
        runCatching { h.setPropertyString("audio-device", name) }
        scope.launch {
            // An output that failed took the file's sound down with it: reload the output, and if
            // that brings nothing up, re-select the audio track so mpv builds its chain again.
            runCatching { h.command("ao-reload") }
            delay(300)
            if (runCatching { h.getPropertyString("current-ao") }.getOrNull().isNullOrBlank()) {
                val aid = audioTracks.firstOrNull { it.selected }?.id ?: audioTracks.firstOrNull()?.id
                if (aid != null) {
                    runCatching { h.setPropertyString("aid", "no") }
                    delay(100)
                    runCatching { h.setPropertyString("aid", aid) }
                }
            }
            checkAudioOutput()
        }
    }

    // ---- Transport -------------------------------------------------------------------------

    fun togglePause() = player.togglePlayWhenReady()

    fun seekTo(ms: Long) {
        val d = durationMs()
        player.seekTo(if (d > 0) ms.coerceIn(0, d) else ms.coerceAtLeast(0))
    }

    fun skip(deltaMs: Long) = seekTo(player.currentPositionMillis.value + deltaMs)

    /** 逐帧: mpv's frame-step / frame-back-step — paused, the picture next to the one shown. */
    fun stepFrame(forward: Boolean) {
        runCatching { handle.command(if (forward) "frame-step" else "frame-back-step") }
    }

    fun durationMs(): Long = player.mediaProperties.value?.durationMillis ?: 0L

    val hasNext: Boolean get() = PlaylistNav.hasNext(mode, index, queue.size)
    val hasPrev: Boolean get() = PlaylistNav.hasPrev(mode, index, queue.size)

    fun next() {
        val n = PlaylistNav.manualNext(mode, index, queue.size)
        if (n >= 0) jumpTo(n)
    }

    fun prev() {
        // Past the first few seconds, "previous" means the start of this one.
        if (player.currentPositionMillis.value > 5_000 || !hasPrev) {
            seekTo(0)
            return
        }
        val n = PlaylistNav.manualPrev(mode, index, queue.size)
        if (n >= 0) jumpTo(n)
    }

    private fun onEnded() {
        val item = current ?: return
        val d = durationMs()
        if (d > 0 && !item.id.startsWith("adhoc:")) graph.media.updateProgress(item.id, d, d, watched = true)
        if (sleep == SleepMode.END_OF_ITEM) {
            changeSleep(SleepMode.OFF)
            return
        }
        if (!graph.settings.value.autoPlayNext && mode != PlaylistMode.REPEAT_ONE) return
        val n = PlaylistNav.onCompleted(mode, index, queue.size)
        if (n < 0) return
        if (n == index) {
            seekTo(0)
            player.play()
            return
        }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (s in 5 downTo 1) {
                upNextIn = s
                delay(1_000)
            }
            upNextIn = null
            index = n
            load(null)
        }
    }

    /** Start what the countdown was waiting for, now. */
    fun playUpNextNow() {
        val n = PlaylistNav.onCompleted(mode, index, queue.size)
        countdownJob?.cancel()
        upNextIn = null
        if (n >= 0 && n != index) {
            index = n
            load(null)
        }
    }

    fun cancelUpNext() {
        countdownJob?.cancel()
        upNextIn = null
    }

    fun upNext(): MediaItem? {
        val n = PlaylistNav.onCompleted(mode, index, queue.size)
        return if (n >= 0 && n != index) queue.getOrNull(n) else null
    }

    // ---- Progress -------------------------------------------------------------------------

    fun saveProgress(item: MediaItem? = current) {
        item ?: return
        if (item.id.startsWith("adhoc:")) return
        val p = created ?: return
        val d = p.mediaProperties.value?.durationMillis ?: return
        if (d <= 0) return
        val pos = p.currentPositionMillis.value
        if (pos <= 0) return
        graph.media.updateProgress(item.id, pos, d)
    }

    /** Leave the player: save where we were, stop, let go of the proxy. */
    fun stop() {
        current?.let { saveProgress(it) }
        loadJob?.cancel()
        countdownJob?.cancel()
        upNextIn = null
        created?.stopPlayback()
        opener.release()
        queue = emptyList()
        index = -1
        changeSleep(SleepMode.OFF)
        clearAb()
        // A subtitle offset belongs to a release: it goes on through the queue, not to the next title.
        subtitleDelayMs = 0
    }

    fun dispose() {
        stop()
        KeepAwake.hold(false)
        created?.close()
        created = null
    }

    // ---- Tracks ---------------------------------------------------------------------------

    private fun refreshTracks() {
        val h = created?.let { it.impl as MPVHandle } ?: return
        val audio = mutableListOf<Track>()
        val subs = mutableListOf<Track>()
        runCatching {
            val n = h.getPropertyInt("track-list/count")
            for (i in 0 until n) {
                val type = h.getPropertyString("track-list/$i/type") ?: continue
                val t = Track(
                    id = h.getPropertyInt("track-list/$i/id").toString(),
                    title = h.getPropertyString("track-list/$i/title"),
                    language = h.getPropertyString("track-list/$i/lang"),
                    codec = h.getPropertyString("track-list/$i/codec"),
                    channels = runCatching { h.getPropertyInt("track-list/$i/demux-channel-count") }.getOrDefault(0),
                    sampleRate = runCatching { h.getPropertyInt("track-list/$i/demux-samplerate") }.getOrDefault(0),
                    external = h.getPropertyBoolean("track-list/$i/external"),
                    externalFile = h.getPropertyString("track-list/$i/external-filename"),
                    selected = h.getPropertyBoolean("track-list/$i/selected"),
                    default = h.getPropertyBoolean("track-list/$i/default"),
                    forced = h.getPropertyBoolean("track-list/$i/forced"),
                    hearingImpaired = runCatching { h.getPropertyBoolean("track-list/$i/hearing-impaired") }.getOrDefault(false),
                )
                when (type) {
                    // A dub beside the film is named as the headset names it — its tag (国语), in its
                    // language — not by the file (or the stream server's URL for it) mpv opened.
                    "audio" -> audio += opener.audioFiles.firstOrNull { it.uri == t.externalFile }
                        ?.let { dub -> t.copy(title = t.title?.takeUnless { ExternalAudio.isAudio(it) || it == dub.uri.substringAfterLast('/') } ?: dub.label, language = t.language ?: dub.language, externalFile = null) }
                        ?: t
                    "sub" -> subs += t
                }
            }
        }
        audioTracks = audio
        subtitleTracks = subs
    }

    // ---- A fansub's fonts ------------------------------------------------------------------

    private val fontPacks by lazy {
        com.therealityteam.videoplayer.data.subtitle.FontPackFetcher(
            graph.streamServer, File(Host.dataDir, "aura/cache/fontpacks"), File(Host.dataDir, "aura/cache/fontpack-parts"),
        )
    }
    private var fontsFor: Pair<String, String>? = null
    private var fontsJob: Job? = null

    /**
     * The chosen subtitles are ASS: the faces their script names (the file beside the video, or the
     * track's header as mpv read it) picked from the fansub's pack beside it — or a folder up, for a
     * special in SPs/ — and mpv's libass pointed at them, the track taken up again.
     */
    private fun subtitleFontsSoon() {
        val item = current ?: return
        fontsJob?.cancel()
        fontsJob = scope.launch {
            delay(300) // the choice has reached mpv's track list
            refreshTracks()
            val sub = subtitleTracks.firstOrNull { it.selected } ?: return@launch
            if (fontsFor == item.id to sub.id) return@launch
            val beside = opener.beside ?: return@launch
            val h = handle
            val picked = withContext(Dispatchers.IO) {
                val script = if (sub.external) {
                    sub.externalFile?.let { f ->
                        runCatching {
                            val bytes = if (f.startsWith("http")) java.net.URL(f).openStream().use { it.readBytes() } else File(f.removePrefix("file://")).readBytes()
                            String(bytes, com.therealityteam.videoplayer.data.subtitle.SubtitleParser.detectCharset(bytes))
                        }.getOrNull()
                    }
                } else runCatching { h.getPropertyString("sub-ass-extradata") }.getOrNull()
                val wanted = script?.let { com.therealityteam.videoplayer.data.subtitle.AssFonts.wanted(it) }.orEmpty()
                if (wanted.isEmpty()) return@withContext emptyList()
                val (user, pass) = opener.loginOf(item)
                runCatching { fontPacks.pick(beside.first, beside.second, wanted, user, pass, 64L * 1024 * 1024) }
                    .onFailure { Log.w(TAG, "fansub fonts not read", it) }.getOrDefault(emptyList())
            }
            if (picked.isEmpty() || current?.id != item.id) return@launch
            fontsFor = item.id to sub.id
            val dir = withContext(Dispatchers.IO) {
                File(Host.dataDir, "aura/cache/fontsel").apply {
                    deleteRecursively()
                    mkdirs()
                    picked.forEach { f ->
                        val to = File(this, f.name)
                        runCatching { java.nio.file.Files.createLink(to.toPath(), f.toPath()) }.getOrElse { f.copyTo(to, overwrite = true) }
                    }
                }
            }
            runCatching {
                h.setPropertyString("sub-fonts-dir", dir.absolutePath)
                val sid = h.getPropertyString("sid")
                if (sid != null && sid != "no") {
                    h.setPropertyString("sid", "no")
                    h.setPropertyString("sid", sid)
                }
                Log.i(TAG, "subtitle fonts: ${picked.joinToString { it.name }} (track $sid read again)")
            }
        }
    }

    /** Each file's own audio and subtitle choice, re-applied once when its tracks appear. */
    private fun applyTrackMemory() {
        val item = current ?: return
        if (tracksAppliedFor == item.id) return
        tracksAppliedFor = item.id
        val saved = graph.media.get(item.id) ?: item
        val asMedia = { list: List<Track> -> list.map { com.therealityteam.videoplayer.data.playback.MediaTrack(it.id, it.title ?: "") } }
        TrackMemory.audioToApply(saved.audioTrackId, asMedia(audioTracks))?.let { selectAudio(it, remember = false) }
            // mpv plays a 外挂音轨 it was given (audio-files) over the file's own: nothing chosen, the film's
            // own sound — its default track, else its first — as the headset keeps it.
            ?: audioTracks.takeIf { list -> list.any { it.selected && it.external } }?.filter { !it.external }
                ?.let { own -> own.firstOrNull { it.default } ?: own.firstOrNull() }
                ?.let { selectAudio(it.id, remember = false) }
            // …and a Korean or Japanese film its own language over the dub the file opens on (a MULTi
            // release's French), as the headset's 默认 does.
            ?: com.therealityteam.videoplayer.data.playback.TrackPreference.originalSound(
                audioTracks.filter { !it.external }.map {
                    com.therealityteam.videoplayer.data.playback.MediaTrack(it.id, it.title ?: "", language = it.language, selected = it.selected, name = it.title ?: it.language ?: "")
                },
                item.originalTitle,
            )?.let { selectAudio(it, remember = false) }
        when (val s = TrackMemory.subtitleToApply(saved.subtitleTrackId, asMedia(subtitleTracks))) {
            TrackMemory.Subtitle.Off -> selectSubtitle(null, remember = false)
            is TrackMemory.Subtitle.Select -> selectSubtitle(s.id, remember = false)
            // Nothing chosen for this file yet: the reader's language, read from the tracks' names too.
            null -> {
                val candidates = subtitleTracks.map { t ->
                    com.therealityteam.videoplayer.data.subtitle.SubtitlePick.Candidate(
                        id = t.id, language = t.language, title = t.title,
                        fileName = t.externalFile?.substringAfterLast('/')?.substringAfterLast('\\'),
                        codec = t.codec, forced = t.forced, hearingImpaired = t.hearingImpaired, default = t.default,
                    )
                }
                com.therealityteam.videoplayer.data.subtitle.SubtitlePick.best(candidates, readerLocale(), item.fileName)
                    ?.let { best -> if (subtitleTracks.firstOrNull { it.id == best.id }?.selected != true) selectSubtitle(best.id, remember = false) }
            }
        }
    }

    fun selectAudio(id: String, remember: Boolean = true) {
        // 全景声 belongs to its own track: off before another is taken, on again once its own plays.
        val field = fileField
        if (field != null) applySoundField(null, fromFile = false)
        runCatching { handle.setPropertyString("aid", id) }
        if (field != null) scope.launch {
            delay(400)
            val channels = runCatching { handle.getPropertyString("audio-params/channel-count") }.getOrNull()?.toIntOrNull()
            if (channels == field.channels && fileField === field) applySoundField(field)
        }
        if (remember) current?.takeIf { !it.id.startsWith("adhoc:") }?.let { graph.media.setAudioTrackChoice(it.id, id) }
        refreshTracksSoon()
    }

    /** mpv updates track-list a moment after a selection; read it then, so the checks are right. */
    private fun refreshTracksSoon() {
        refreshTracks()
        scope.launch {
            delay(200)
            refreshTracks()
        }
    }

    /** [id] null turns subtitles off (and remembers that too). */
    fun selectSubtitle(id: String?, remember: Boolean = true) {
        runCatching { handle.setPropertyString("sid", id ?: "no") }
        subtitleFontsSoon()
        if (remember) current?.takeIf { !it.id.startsWith("adhoc:") }?.let {
            graph.media.setSubtitleTrackChoice(it.id, id ?: TrackMemory.SUBTITLES_OFF)
        }
        refreshTracksSoon()
    }

    /** Load a subtitle file the user picked (or dropped) for what is playing. */
    fun addSubtitleFile(file: File) {
        runCatching { handle.command("sub-add", file.absolutePath, "select") }
        refreshTracksSoon()
    }

    // ---- Look and sound -------------------------------------------------------------------

    private data class SubtitleLook(
        val scale: Float, val raise: Float, val style: SubtitleStyle, val outline: Int,
        val font: SubtitleFont, val color: SubtitleColor, val keep: Boolean,
    )

    private fun DesktopSettings.subtitleLook() =
        SubtitleLook(subtitleScale, subtitleRaise, subtitleStyle, subtitleOutline, subtitleFont, subtitleColor, subtitleKeepStyles)

    private fun applySubtitleLook(l: SubtitleLook) {
        val h = created?.let { it.impl as MPVHandle } ?: return
        runCatching {
            h.setPropertyDouble("sub-scale", l.scale.toDouble())
            // Raise is a share of the picture's height; mpv positions by percent from the top.
            h.setPropertyDouble("sub-pos", (100.0 - l.raise * 100.0).coerceIn(50.0, 100.0))
            h.setPropertyString("sub-color", argbHex(l.color.argb))
            when (l.style) {
                SubtitleStyle.NONE -> {
                    h.setPropertyString("sub-border-style", "outline-and-shadow")
                    h.setPropertyDouble("sub-border-size", 0.6)
                    h.setPropertyString("sub-border-color", "#99000000")
                    h.setPropertyDouble("sub-shadow-offset", 1.6)
                    h.setPropertyString("sub-shadow-color", "#A0000000")
                }
                SubtitleStyle.BOX -> {
                    h.setPropertyString("sub-border-style", "background-box")
                    h.setPropertyString("sub-back-color", "#A6000000")
                    h.setPropertyDouble("sub-border-size", 0.0)
                    h.setPropertyDouble("sub-shadow-offset", 0.0)
                }
                SubtitleStyle.OUTLINE -> {
                    h.setPropertyString("sub-border-style", "outline-and-shadow")
                    h.setPropertyDouble("sub-border-size", 1.2 + l.outline * 0.9)
                    h.setPropertyString("sub-border-color", "#FF000000")
                    h.setPropertyDouble("sub-shadow-offset", 0.8)
                    h.setPropertyString("sub-shadow-color", "#80000000")
                }
            }
            h.setPropertyString("sub-font", fontFor(l.font))
            // Keep the file's own ASS styling (only scaled), or dress everything alike.
            h.setPropertyString("sub-ass-override", if (l.keep) "scale" else "force")
        }
    }

    // The system's face for the reader's script: one Han character is drawn differently in Japan, Korea, Taiwan and
    // the mainland, and a Thai reader's lines need a Thai face. Latin, Cyrillic and Greek readers get the CJK one
    // their system would fall back to first (the mainland's, as before).
    private fun fontFor(f: SubtitleFont): String {
        val l = java.util.Locale.getDefault()
        val script = when {
            l.language == "ja" -> "ja"
            l.language == "ko" -> "ko"
            l.language == "th" -> "th"
            l.language == "zh" && (l.script == "Hant" || l.country in setOf("TW", "HK", "MO")) -> "hant"
            else -> "hans"
        }
        return when (f) {
            SubtitleFont.DEFAULT -> when {
                Host.isMac -> mapOf("ja" to "Hiragino Sans", "ko" to "Apple SD Gothic Neo", "th" to "Thonburi", "hant" to "PingFang TC").getOrDefault(script, "PingFang SC")
                Host.isWindows -> mapOf("ja" to "Yu Gothic UI", "ko" to "Malgun Gothic", "th" to "Leelawadee UI", "hant" to "Microsoft JhengHei UI").getOrDefault(script, "Microsoft YaHei UI")
                else -> "sans-serif"
            }
            SubtitleFont.SERIF -> when {
                Host.isMac -> mapOf("ja" to "Hiragino Mincho ProN", "ko" to "AppleMyungjo", "th" to "Thonburi", "hant" to "Songti TC").getOrDefault(script, "Songti SC")
                Host.isWindows -> mapOf("ja" to "Yu Mincho", "ko" to "Batang", "th" to "Leelawadee UI", "hant" to "PMingLiU").getOrDefault(script, "SimSun")
                else -> "serif"
            }
            SubtitleFont.MONO -> when {
                Host.isMac -> "Menlo"
                Host.isWindows -> "Consolas"
                else -> "monospace"
            }
        }
    }

    private fun argbHex(argb: Long): String {
        val a = (argb shr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return "#%02X%06X".format(a, rgb)
    }

    fun changeSpeed(v: Float) {
        speed = v
        runCatching { handle.setPropertyDouble("speed", v.toDouble()) }
    }

    fun changeBoost(percent: Int) {
        boost = percent
        applyVolume()
    }

    private fun applyVolume() {
        val h = created?.let { it.impl as MPVHandle } ?: return
        val s = graph.settings.value
        runCatching {
            h.setPropertyDouble("volume", (s.volume * boost / 100.0).coerceIn(0.0, 200.0))
            h.setPropertyBoolean("mute", s.muted)
        }
    }

    fun setVolume(v: Float) = graph.settings.update { it.copy(volume = v.coerceIn(0f, 100f), muted = false) }

    fun toggleMute() = graph.settings.update { it.copy(muted = !it.muted) }

    fun setSubtitleDelay(ms: Long) {
        subtitleDelayMs = ms.coerceIn(-30_000, 30_000)
        runCatching { handle.setPropertyDouble("sub-delay", subtitleDelayMs / 1000.0) }
    }

    fun setAudioDelay(ms: Long) {
        audioDelayMs = ms.coerceIn(-2_000, 2_000)
        runCatching { handle.setPropertyDouble("audio-delay", audioDelayMs / 1000.0) }
    }

    fun changeAspect(a: Aspect) {
        aspect = a
        // A 3D or spherical picture keeps the shape its format gives it.
        if (format == PictureFormat.FLAT_2D && !spherical) applyAspect(a)
    }

    private fun applyAspect(a: Aspect) {
        val h = created?.let { it.impl as MPVHandle } ?: return
        runCatching {
            h.setPropertyBoolean("keepaspect", a != Aspect.STRETCH)
            h.setPropertyDouble("panscan", if (a == Aspect.FILL) 1.0 else 0.0)
            h.setPropertyString("video-aspect-override", a.override ?: "no")
        }
    }

    // ---- 3D and spherical pictures ------------------------------------------------------------

    /**
     * Show the file as its flags say: a flat 3D film as one eye at its true shape, a spherical one
     * through the view shader at the window's shape, anything else as the aspect setting says.
     * Waits for the frame's size, which a crop needs.
     */
    private fun applyPicture() {
        val h = created?.let { it.impl as MPVHandle } ?: return
        scope.launch {
            var w = 0
            var ht = 0
            repeat(20) {
                w = runCatching { h.getPropertyInt("video-params/w") }.getOrDefault(0)
                ht = runCatching { h.getPropertyInt("video-params/h") }.getOrDefault(0)
                if (w > 0 && ht > 0) return@repeat
                delay(100)
            }
            frameW = w
            frameH = ht
            runCatching {
                if (spherical) {
                    h.setPropertyString("video-crop", "0x0+0+0")
                    h.setPropertyBoolean("keepaspect", true)
                    h.setPropertyDouble("panscan", 0.0)
                    h.setPropertyDouble("video-aspect-override", viewportAspect)
                    viewRequests.trySend(Unit)
                } else {
                    h.command("change-list", "glsl-shaders", "clr", "")
                    val eye = Projection.eye(flags.stereo, w, ht, flags.rightFirst)
                    if (eye != null) {
                        h.setPropertyString("video-crop", eye.crop)
                        h.setPropertyBoolean("keepaspect", true)
                        h.setPropertyDouble("panscan", 0.0)
                        h.setPropertyString("video-aspect-override", eye.aspect?.let { "%.6f".format(Locale.ROOT, it) } ?: "no")
                    } else {
                        h.setPropertyString("video-crop", "0x0+0+0")
                        applyAspect(aspect)
                    }
                }
            }.onFailure { Log.w(TAG, "could not lay the picture out as ${flags}", it) }
        }
    }

    /** Take [f] for the playing file — and keep it, as the headset's format menu does. */
    fun changeFormat(f: PictureFormat) = takeFlags(f.flags(flags))

    /** Take [choice] as the way the playing panorama lies in its frame (全景格式) — and keep it. */
    fun changePano(choice: PanoChoice) = takeFlags(choice.flags(flags))

    private fun takeFlags(next: VideoFlags) {
        val item = current ?: return
        flags = next
        view = SphereView()
        if (!item.id.startsWith("adhoc:")) graph.media.setVideoFlags(item.id, next)
        refreshPano()
    }

    /**
     * Room under the subtitles: [clearPx] of a picture area [screenPx] high — the controls' height
     * while they show, so the bottom line is not hidden behind them (the headset does the same).
     * mpv counts the margin in pixels of a 720-high window, scaled to the real one.
     */
    fun liftSubtitles(clearPx: Float, screenPx: Int) {
        if (screenPx <= 0) return
        val h = created?.let { it.impl as MPVHandle } ?: return
        val margin = maxOf(SUB_MARGIN, (clearPx * 720f / screenPx).roundToInt())
        if (margin == subMargin) return
        subMargin = margin
        runCatching { h.setPropertyString("sub-margin-y", margin.toString()) }
    }
    private var subMargin = SUB_MARGIN

    /** The picture area's size changed: a spherical view keeps the window's shape. */
    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportAspect = width.toDouble() / height
        val h = created?.let { it.impl as MPVHandle } ?: return
        if (spherical) {
            runCatching { h.setPropertyDouble("video-aspect-override", viewportAspect) }
            viewRequests.trySend(Unit)
        }
    }

    /**
     * Turn the view by a drag of [dx], [dy] (fractions of the picture's width and height): the
     * scene follows the hand, as a photo sphere does.
     */
    fun look(dx: Double, dy: Double) {
        if (!spherical) return
        val v = view
        val vfov = 2 * kotlin.math.atan(kotlin.math.tan(v.fov / 2) / viewportAspect)
        // A picture that goes all the way round turns all the way; a 180° or a lens's stops at its edge.
        val l = pano
        val half = l == null || when (l.projection) {
            ProjectionMode.CUBEMAP, ProjectionMode.EAC, ProjectionMode.DUAL_FISHEYE -> false
            ProjectionMode.FISHEYE -> true
            else -> l.ownMesh == null && l.yawTo - l.yawFrom < 359f
        }
        val yaw = (v.yaw - dx * v.fov).let { if (half) it.coerceIn(-Math.PI / 2, Math.PI / 2) else Projection.wrap(it) }
        view = v.copy(yaw = yaw, pitch = (v.pitch + dy * vfov).coerceIn(-1.45, 1.45))
        viewRequests.trySend(Unit)
    }

    /** Wider (out) or narrower (in) by [steps] wheel notches. */
    fun zoomView(steps: Double) {
        if (!spherical) return
        view = view.copy(fov = (view.fov * kotlin.math.exp(steps * 0.08)).coerceIn(Projection.MIN_FOV, Projection.MAX_FOV))
        viewRequests.trySend(Unit)
    }

    fun resetView() {
        view = SphereView()
        viewRequests.trySend(Unit)
    }

    /**
     * Hands the view to mpv — off the UI thread, since mpv takes a moment to swap shaders, and only
     * the latest view: a fast drag skips the views in between rather than queueing them. The
     * spherical shader is written once per format; each view is a tiny shader of its own
     * (Projection.viewShader), listed before it so that it runs first.
     */
    private suspend fun drawViews() {
        val dir = File(Host.dataDir, "shaders").apply { mkdirs() }
        for (request in viewRequests) {
            val h = created?.let { it.impl as MPVHandle } ?: continue
            // The sound field turns with the view (全景声), as the picture does.
            turnSoundField()
            val layout = pano ?: continue
            runCatching {
                // Named by what it says: mpv reads a path once, so another layout needs another name.
                val sphereText = Projection.sphereShader(layout, frameW, frameH, pose())
                val sphere = File(dir, "aura-360-${Integer.toHexString(sphereText.hashCode())}.glsl")
                if (!sphere.isFile) sphere.writeText(sphereText)
                val (name, text) = Projection.viewShader(view, viewportAspect)
                val v = File(dir, name)
                if (!v.isFile) v.writeText(text)
                h.command("change-list", "glsl-shaders", "set", v.absolutePath + File.pathSeparator + sphere.absolutePath)
                // The file became flat while this view was on its way: take it back off.
                if (!spherical) h.command("change-list", "glsl-shaders", "clr", "")
            }.onFailure { Log.w(TAG, "could not draw the view", it) }
        }
    }

    // ---- Loops and timers -----------------------------------------------------------------

    fun cycleAb() {
        val (a, b) = AbRepeat.cycle(abA, abB, player.currentPositionMillis.value)
        abA = a
        abB = b
        runCatching {
            handle.setPropertyString("ab-loop-a", a?.let { "%.3f".format(Locale.ROOT, it / 1000.0) } ?: "no")
            handle.setPropertyString("ab-loop-b", b?.let { "%.3f".format(Locale.ROOT, it / 1000.0) } ?: "no")
        }
    }

    /**
     * No loop: ours and mpv's. mpv keeps a loop set while playing across the next `loadfile`, so
     * the next file would go on looping at the old times while the panel said "not set".
     */
    private fun clearAb() {
        abA = null
        abB = null
        val h = created?.let { it.impl as MPVHandle } ?: return
        runCatching {
            h.setPropertyString("ab-loop-a", "no")
            h.setPropertyString("ab-loop-b", "no")
        }
    }

    fun changeSleep(mode: SleepMode) {
        sleep = mode
        sleepJob?.cancel()
        sleepEndsAt = null
        if (!mode.isTimed) return
        val end = System.currentTimeMillis() + mode.minutes * 60_000L
        sleepEndsAt = end
        sleepJob = scope.launch {
            while (isActive && System.currentTimeMillis() < end) delay(1_000)
            if (isActive) {
                created?.pause()
                sleep = SleepMode.OFF
                sleepEndsAt = null
            }
        }
    }

    // ---- Chapters, pictures, facts ----------------------------------------------------------

    fun currentChapter(pos: Long): Int = chapters.indexOfLast { it.startMs <= pos + 500 }

    fun nextChapter() {
        val i = currentChapter(player.currentPositionMillis.value)
        chapters.getOrNull(i + 1)?.let { seekTo(it.startMs) }
    }

    fun prevChapter() {
        val pos = player.currentPositionMillis.value
        val i = currentChapter(pos)
        val here = chapters.getOrNull(i) ?: return
        // Within three seconds of a chapter's start, "previous" is the one before it.
        val target = if (pos - here.startMs < 3_000) chapters.getOrNull(i - 1) ?: here else here
        seekTo(target.startMs)
    }

    // ---- The seek bar's pictures --------------------------------------------------------------

    /** The picture's shape as shown (width / height), for sizing the seek bar's frames; 16:9 until known. */
    fun videoAspect(): Float {
        val h = created?.let { it.impl as MPVHandle } ?: return 16f / 9f
        val a = runCatching { h.getPropertyDouble("video-params/aspect") }.getOrNull()
        return a?.toFloat()?.takeIf { it in 0.5f..4f } ?: (16f / 9f)
    }

    /** Frames already fetched for the file that is playing: bucket → picture, oldest first out. */
    private val previews = object : LinkedHashMap<Long, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?) = size > 60
    }
    private var previewsFor: String? = null

    /**
     * A small picture of the file at [ms], for the seek bar under the pointer — from mediamp's own
     * second decoder, which seeks to keyframes and draws in software, so the film on screen does
     * not stutter for it. Frames come a bucket apart (1/400 of the film, 2 s at least) and are
     * kept, so sweeping back over the bar costs nothing. Null when there is none to be had.
     */
    suspend fun previewFrame(ms: Long, widthPx: Int, heightPx: Int): ImageBitmap? {
        val item = current ?: return null
        val duration = durationMs().takeIf { it > 0 } ?: return null
        val step = maxOf(2_000L, duration / 400)
        val bucket = (ms.coerceIn(0, duration) / step) * step
        // A panorama's pictures are the view the screen shows, so the cache holds them per view.
        val layout = pano
        val v = view
        // The frame's size: mpv's once it says, else the header's (a slow start leaves mpv's unknown).
        val fw = if (frameW > 0) frameW else panoInfo?.frameWidth ?: 0
        val fh = if (frameH > 0) frameH else panoInfo?.frameHeight ?: 0
        val key = item.id + if (layout != null) {
            "@${Math.round(Math.toDegrees(v.yaw))},${Math.round(Math.toDegrees(v.pitch))},${Math.round(Math.toDegrees(v.fov))},$level,${layout.hashCode()},${fw}x$fh"
        } else ""
        if (previewsFor != key) { previews.clear(); previewsFor = key }
        previews[bucket]?.let { return it }
        val feature = created?.features?.get(FramePreview) ?: return null
        // A panorama's frame is taken larger — the view is a part of it — in its own shape, then looked into.
        val (srcW, srcH) = if (layout != null && fw > 0 && fh > 0) {
            val w = minOf(fw, maxOf(PANO_PREVIEW_SOURCE, widthPx * 3))
            w to maxOf(2, Math.round(w.toDouble() * fh / fw).toInt())
        } else widthPx to heightPx
        val frame = runCatching { feature.getPreviewFrame(bucket, srcW, srcH) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return null
        val image = withContext(Dispatchers.Default) {
            val (pixels, w, h) = if (layout != null && srcW != widthPx) {
                val look = com.therealityteam.videoplayer.data.playback.PanoViewport.Look(
                    Math.toDegrees(v.yaw).toFloat(), Math.toDegrees(v.pitch).toFloat(), Math.toDegrees(v.fov).toFloat(),
                )
                val seen = com.therealityteam.videoplayer.data.playback.PanoFlatten.view(
                    frame.pixels, frame.width, frame.height, layout.meshless(frame.width, frame.height), widthPx, heightPx, look,
                    turn = Projection.poseMatrix(pose()),
                )
                Triple(seen, widthPx, heightPx)
            } else Triple(frame.pixels, frame.width, frame.height)
            java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, w, h, pixels, 0, w)
            }.toComposeImageBitmap()
        }
        if (previewsFor == key) previews[bucket] = image
        return image
    }

    /** The frame on screen, to Pictures/Aura. Returns the file, or null if it could not be saved. */
    suspend fun screenshot(): File? {
        val item = current ?: return null
        val dir = Host.screenshotDir.apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val name = (cardTitle.ifBlank { item.title }).replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(80)
        val file = File(dir, "$name-$stamp.png")
        return runCatching {
            player.features[Screenshots]?.takeScreenshot(file.absolutePath)
            withContext(Dispatchers.IO) { file.takeIf { it.isFile && it.length() > 0 } }
        }.getOrNull()
    }

    /** The playing file's streams in words, for the media info panel. */
    /**
     * The media info panel's rows, as (key, value) — the headset's: title, video, audio, decoder,
     * frames, length. Words in [s]'s language.
     */
    fun mediaInfo(s: Strings = Strings.forLanguage("en")): List<Pair<String, String>> {
        val h = created?.let { it.impl as MPVHandle } ?: return emptyList()
        fun str(p: String) = runCatching { h.getPropertyString(p) }.getOrNull()?.takeIf { it.isNotBlank() }
        fun int(p: String) = runCatching { h.getPropertyInt(p) }.getOrNull()
        fun dbl(p: String) = runCatching { h.getPropertyDouble(p) }.getOrNull()
        val title = cardTitle.ifBlank { current?.title.orEmpty() }
        val video = buildList {
            val w = int("video-params/w") ?: 0
            val hh = int("video-params/h") ?: 0
            if (w > 0 && hh > 0) add("$w×$hh")
            str("video-format")?.let { add(it.uppercase()) }
            str("video-params/pixelformat")?.let { pf -> if ("10" in pf || "p010" in pf) add("10-bit") }
            when (str("video-params/gamma")) {
                "pq" -> add("HDR10 / PQ")
                "hlg" -> add("HLG")
            }
            (dbl("container-fps") ?: dbl("estimated-vf-fps"))?.takeIf { it > 0 }?.let { add("%.3g fps".format(Locale.ROOT, it)) }
        }.joinToString(" · ")
        val audio = buildList {
            str("audio-codec-name")?.let { add(it.uppercase()) }
            int("audio-params/channel-count")?.takeIf { it > 0 }?.let { add(channelLayout(it)) }
            int("audio-params/samplerate")?.takeIf { it > 0 }?.let { add("${it / 1000.0} kHz".replace(".0 ", " ")) }
            // Its sound field decoded to the two ears (SoundFieldFilter).
            if (soundField != null) add(s["pc_audio_field"])
        }.joinToString(" · ")
        val decoder = if (str("video-format") == null) "" else {
            str("hwdec-current")?.takeIf { it != "no" }?.let { s["d_info_hw", it] } ?: s["d_info_sw"]
        }
        val dropped = int("frame-drop-count")
        val decoderDropped = int("decoder-frame-drop-count")
        val frames = if (dropped == null && decoderDropped == null) "" else s["d_info_dropped", dropped ?: 0, decoderDropped ?: 0]
        val length = durationMs().takeIf { it > 0 }?.let(::formatTime).orEmpty()
        return listOf(
            "title" to title, "video" to video, "format" to pictureFormat(s), "audio" to audio,
            "decoder" to decoder, "frames" to frames, "duration" to length,
        ).filter { it.second.isNotBlank() }
    }

    /**
     * The headset's 画面格式 line: a panorama's kind, projection and eyes (360° · EAC · 上下 3D), a spatial
     * video's two views and its camera; empty for a flat frame.
     */
    private fun pictureFormat(s: Strings): String {
        val parts = mutableListOf<String>()
        // A flat film in 3D: its packing (one eye shown — the left, from the second half when the right comes first).
        if (pano == null && flags.projection == ProjectionMode.FLAT) {
            when (flags.stereo) {
                com.therealityteam.videoplayer.data.model.StereoMode.SBS -> parts += s["pc_stereo_sbs"] + " 3D"
                com.therealityteam.videoplayer.data.model.StereoMode.OU -> parts += s["pc_stereo_tb"] + " 3D"
                else -> {}
            }
            if (parts.isNotEmpty() && flags.rightFirst) parts += s["pc_info_right_first"]
        }
        pano?.let { l ->
            val half = l.projection == ProjectionMode.EQ180 || l.projection == ProjectionMode.FISHEYE
            parts += s[if (half) "pc_proj_180" else "pc_proj_360"]
            parts += when {
                l.ownMesh != null -> s["pc_pano_own"]
                l.projection == ProjectionMode.FISHEYE -> s[PanoChoice.labelKey(l.projection)] + " ${l.lensFov.toInt()}°"
                else -> s[PanoChoice.labelKey(l.projection)]
            }
            when (l.stereo) {
                com.therealityteam.videoplayer.data.model.StereoMode.SBS -> parts += s["pc_stereo_sbs"] + " 3D"
                com.therealityteam.videoplayer.data.model.StereoMode.OU -> parts += s["pc_stereo_tb"] + " 3D"
                else -> {}
            }
            if (l.eyesSwapped) parts += s["pc_info_right_first"]
        }
        panoInfo?.takeIf { it.multiview }?.let { said ->
            if (pano == null) parts += s["pc_info_spatial_video"]
            parts += s["pc_info_mv_hevc"]
            if (said.horizontalFov > 0f && said.baselineMm > 0f) parts += s["pc_info_camera", Math.round(said.horizontalFov), Math.round(said.baselineMm)]
        }
        return parts.joinToString(" · ")
    }

    fun channelLayout(n: Int): String = when (n) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${n}ch"
    }

    private companion object {
        const val TAG = "AuraPlayer"

        /** Subtitles' distance from the bottom with the controls hidden (mpv's units, see liftSubtitles). */
        const val SUB_MARGIN = 34
    }
}
