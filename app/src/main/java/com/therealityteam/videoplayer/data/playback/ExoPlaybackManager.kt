package com.therealityteam.videoplayer.data.playback

import android.text.Layout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.subtitle.SubtitleLanguage
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Media3/ExoPlayer engine — the path to a serious player. Renders into the Surface of a
 * SurfaceRenderTexture (VideoComponent, the SDK's official third-party-player architecture),
 * which unlocks what CypressMediaPlayer never exposed: track enumeration and selection,
 * embedded and sidecar subtitles (delivered as [activeCues] for the UI to draw), broad
 * container support via Media3's extractors, and precise state callbacks.
 *
 * Threading: every method must be called on the main thread (ExoPlayer's contract); the
 * ViewModel and Compose UI already guarantee that.
 */
@OptIn(UnstableApi::class)
class ExoPlaybackManager(private val context: Context) : PlayerEngine {

    /** The words the track lists and the info panel are written in — the reader's, not always Chinese. */
    private val words = Words(context.applicationContext)

    /**
     * Subtitle delay in microseconds, applied while cues are PARSED (positive = subtitles
     * appear later). Parsing happens once per prepare, so a new value takes effect on the
     * next prepare. [refreshSubtitleTiming] reparses the active source after an adjustment.
     */
    @Volatile
    var subtitleDelayUs: Long = 0L

    private var visibleCueStartUs: Long = C.TIME_UNSET
    private data class SubtitlePreroll(val positionUs: Long, val cueStartUs: Long)
    @Volatile private var subtitlePreroll: SubtitlePreroll? = null

    /** 音量增强 PCM gain stage — first in the sink chain, ahead of Sonic (speed). */
    private val gainProcessor = GainAudioProcessor()

    /** 音画同步 stage — shifts the audio against the video clock by a signed offset. */
    private val delayProcessor = DelayAudioProcessor()

    /** 全景声 stage — a sound-field track to the two ears, turned with the head and the picture. */
    private val ambisonicProcessor = AmbisonicAudioProcessor()

    /** System media session: BT-headphone / controller media keys drive play-pause and seek. */
    private var session: MediaSession? = null

    /** What the session drives while another engine plays the file ([driveSessionWith]); null for ExoPlayer. */
    private var sessionDriver: Player? = null

    /** A playing film waits out a network blip instead of ending on it (see [PatientLoadErrorPolicy]). */
    private val loadErrors = PatientLoadErrorPolicy()

    /** The ASS streams of the MKV being read, as written — what libass draws ([AssRenderer]). */
    val assStreams = AssStreams()

    /** How a file is read — also each 外挂音轨 file, merged in beside the film ([setup]). */
    private val sourceFactory = DefaultMediaSourceFactory(context, AssTapExtractorsFactory(assStreams) { timeUs ->
        val authoredUs = timeUs - subtitleDelayUs.coerceAtLeast(0L)
        val heldCue = subtitlePreroll?.takeIf { it.positionUs == timeUs }
        minOf(authoredUs, heldCue?.cueStartUs ?: authoredUs).coerceAtLeast(0L)
    }).setSubtitleParserFactory(
        // An MKV's ASS events are kept as written, for libass ([assStreams]).
        DelayingSubtitleParserFactory(androidx.media3.extractor.text.DefaultSubtitleParserFactory()) { subtitleDelayUs },
    ).setLoadErrorHandlingPolicy(loadErrors)

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        // Tone-maps HDR down to SDR at the decoder (the spatial panel samples SDR only) and
        // prefers extension/software decoders as fallback — the headset has no DTS/AC3
        // license, so every decode path helps.
        ToneMappingRenderersFactory(context, delayProcessor, gainProcessor, ambisonicProcessor)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
    ).setLoadControl(
        // A 4K remux at 80Mbps fills the default ~50s buffer target with hundreds of MB of
        // samples on the Java heap; an SMB packet-reader allocation then OOMed the whole
        // app (crash-20260831-014743). Cap buffering by bytes and keep durations modest —
        // the NAS is on the LAN, refilling is cheap.
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setBufferDurationsMs(
                /* minBufferMs= */ 15_000,
                /* maxBufferMs= */ 30_000,
                /* bufferForPlaybackMs= */ 1_000,
                /* bufferForPlaybackAfterRebufferMs= */ 2_000,
            )
            .build(),
    ).setMediaSourceFactory(sourceFactory).build().apply {
        // Subtitles: the reader's own language in every spelling a container might use, then
        // English. It used to name Chinese explicitly for everyone, which put a Chinese subtitle
        // ahead of a German one on a German viewer's film.
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*preferredTextLanguages())
            .setPreferredAudioLanguages() // keep container default audio
            .build()
    }

    private val _state = MutableStateFlow(PlaybackState.INIT)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _position = MutableStateFlow(0L)
    override val position: StateFlow<Long> = _position.asStateFlow()

    private val _hasFirstFrame = MutableStateFlow(false)
    override val hasFirstFrame: StateFlow<Boolean> = _hasFirstFrame.asStateFlow()

    private val _dataRate = MutableStateFlow<DataRate?>(null)
    override val dataRate: StateFlow<DataRate?> = _dataRate.asStateFlow()

    private val _sourceDown = MutableStateFlow(false)
    override val sourceDown: StateFlow<Boolean> = _sourceDown.asStateFlow()

    /** The film plays on: a server that had stopped answering is back. */
    private fun sourceBack() {
        if (!_sourceDown.value) return
        _sourceDown.value = false
        android.util.Log.i("AuraPlay", "the file's server answers again: playing on")
    }
    /**
     * A wait for data in the middle of the film began then, with this much of it buffered: at its end, how
     * much more came in that time is how fast the file comes. (Media3's bandwidth meter is not told of
     * these streams' transfers: it said its starting guess, 0.3 MB/s, of a server sending 1.5.)
     */
    private var waitFromMs = 0L
    private var waitFromBufferedMs = 0L

    private val _audioTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val audioTracks: StateFlow<List<MediaTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    override val subtitleTracks: StateFlow<List<MediaTrack>> = _subtitleTracks.asStateFlow()

    private val _activeCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    override val activeCues: StateFlow<List<SubtitleCue>> = _activeCues.asStateFlow()
    private val pgsRecovery = PgsSeekRecovery(
        DefaultDataSource.Factory(context.applicationContext), { player.currentPosition * 1000L - subtitleDelayUs }, ::showCues,
    )

    private val _videoUnsupported = MutableStateFlow<String?>(null)
    override val videoUnsupportedLabel: StateFlow<String?> = _videoUnsupported.asStateFlow()

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    override val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    /** The file comes over http — the stream proxy for a share, or a media server — not from disk. */
    private var playingOverNetwork = false

    private val _videoAspect = MutableStateFlow(0f)
    override val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    override val supportsFd: Boolean = false

    /** When the file now opening was set up — the AuraPerf first-frame line counts from it. */
    private var setupAtMs = 0L

    /**
     * The video decoder's counters as the file was set up, and what they had rendered: a picture is this file's
     * once a decoder taken up since has rendered one, or the same one has rendered more (see onRenderedFirstFrame).
     */
    private var countersAtSetup: androidx.media3.exoplayer.DecoderCounters? = null
    private var renderedAtSetup = 0

    /** Invoked when the current item plays to its end — the ViewModel advances the queue. */
    var onCompleted: (() -> Unit)? = null

    /**
     * Asked, before a failure is shown, whether another engine takes the file (SwitchingEngine):
     * true and this engine says nothing more of it.
     */
    var onUnplayable: ((PlaybackFailure) -> Boolean)? = null

    /**
     * Asked, when the sound plays with no picture drawn, whether another engine takes the file —
     * given the video track's MIME type, or null when a video file gave no picture at all, and its
     * codecs (`avc1.6E0032`: H.264 High 10, see EngineRoute.softwareOnly). True and no 「画面无法解码」
     * is shown.
     */
    var onPictureless: ((mime: String?, codecs: String?) -> Boolean)? = null

    /**
     * The video decoder took the film and gave nothing back — no error, no frame, not one output buffer,
     * Media3 waiting on it for good (the emulator's HEVC decoder on a two-layer MV-HEVC spatial video):
     * with [STALLED_BUFFERED_MS] of the film read, it is not the network. Given the track's MIME type;
     * true when it went elsewhere.
     */
    var onDecoderStalled: ((mime: String?) -> Boolean)? = null
    private var stalledSinceMs = 0L

    /** A file opened with a picture and no sound, or no subtitle, track Media3 could read: given the URI it plays. */
    var onSoundless: ((uri: String?, noSound: Boolean, noText: Boolean) -> Unit)? = null
    private var soundlessAsked = false

    /** Playlist hooks for the media session's skip keys — the queue lives in the ViewModel. */
    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null
    var hasNextItem: (() -> Boolean)? = null
    var hasPreviousItem: (() -> Boolean)? = null

    /**
     * 拖动预览 under way: ExoPlayer's scrubbing mode shows the frame at each point of the drag with
     * the sound off and playback held. The holding and the seeks' buffering are the drag's, not the
     * viewer's: they leave [state] and [position] alone (no 暂停 on the bar, no spinner, no credits
     * crossed on the way), and the release sorts things out with one ordinary seek.
     */
    private var scrubbing = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Even ENDED: a drag to the far right must not finish the file while the hand is still on it.
            if (scrubbing) return
            when (playbackState) {
                Player.STATE_BUFFERING -> if (_state.value != PlaybackState.INIT) {
                    _state.value = PlaybackState.PREPARING
                    if (_hasFirstFrame.value && !seeking()) {
                        waitFromMs = android.os.SystemClock.elapsedRealtime()
                        waitFromBufferedMs = player.bufferedPosition
                    }
                }
                Player.STATE_READY -> {
                    endOfWait()
                    sourceBack()
                    if (seekReadyAtMs > 0) {
                        android.util.Log.i("AuraPerf", "seek → ${seekToMs / 1000}s: playing again in ${android.os.SystemClock.elapsedRealtime() - seekReadyAtMs}ms")
                        seekReadyAtMs = 0L
                    }
                    _duration.value = player.duration.coerceAtLeast(0L)
                    _state.value = when {
                        player.isPlaying -> PlaybackState.PLAYING
                        !player.playWhenReady -> PlaybackState.PAUSED
                        else -> PlaybackState.READY
                    }
                }
                Player.STATE_ENDED -> {
                    _state.value = PlaybackState.READY
                    onCompleted?.invoke()
                }
                Player.STATE_IDLE -> Unit // stays whatever it was; ERROR arrives via onPlayerError
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (scrubbing) return
            if (isPlaying) {
                _state.value = PlaybackState.PLAYING
                // It plays: from here a network blip is waited out (PatientLoadErrorPolicy).
                loadErrors.patient = true
            } else if (_state.value == PlaybackState.PLAYING) {
                _state.value = PlaybackState.PAUSED
            }
        }

        override fun onRenderedFirstFrame() {
            // Told again of the frame the surface already shows — the player's window is kept from film to film,
            // and its surface handed over again at each setup — before this file has rendered one of its own: a
            // missing file said "first picture 40 ms", and the opening card made way for the last film's frame.
            // A picture of this file's is one a decoder taken up since its setup has rendered, or the one that was
            // there then has rendered since (the told-again frame is no new one): the real first always counts.
            val counters = player.videoDecoderCounters
            val rendered = counters?.renderedOutputBufferCount ?: 0
            val ownPicture = if (counters !== countersAtSetup) rendered > 0 else rendered > renderedAtSetup
            if (!ownPicture) {
                android.util.Log.i("AuraPerf", "the surface's last picture told again: not this file's first")
                return
            }
            if (!_hasFirstFrame.value && setupAtMs > 0) {
                android.util.Log.i("AuraPerf", "first frame ${android.os.SystemClock.elapsedRealtime() - setupAtMs}ms after setup")
            }
            if (seekAtMs > 0 && !scrubbing) {
                android.util.Log.i("AuraPerf", "seek ${seekFromMs / 1000}s → ${seekToMs / 1000}s: picture in ${android.os.SystemClock.elapsedRealtime() - seekAtMs}ms")
                seekAtMs = 0L
            }
            _hasFirstFrame.value = true
            // A rendered frame is the ground truth: whatever the capability flags said,
            // this video IS decoding (e.g. Dolby Vision P8 falling back to plain HEVC).
            _videoUnsupported.value = null
            // The frames decoded on the way to this picture from the keyframe before it were never to be shown.
            if (skippedAtReset >= 0) {
                prerollSkipped += (skippedNow() - skippedAtReset).coerceAtLeast(0)
                skippedAtReset = -1
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // A seek (ours, the media keys', the system's): what is skipped from here to its first picture is
            // the way there, not frames lost. Seeks in a row before a picture keep the first one's count.
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                if (skippedAtReset < 0) skippedAtReset = skippedNow()
                if (!scrubbing) pgsRecovery.seek()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A reader that threw over the file's content is reported as an IO error by Media3.
            val crashed = generateSequence(error.cause) { it.cause }
                .any { it is androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException }
            // A file whose streams are laid far apart (a disc's bonus clip: HEVC with its FLAC megabytes
            // away) fills the buffer's byte cap before both have enough to start: Media3 gives up,
            // "stuck buffering and not loading". mpv's reader takes such a file — as a reader balking.
            val stuck = generateSequence(error.cause) { it.cause }
                .any { it is IllegalStateException && it.message?.contains("stuck buffering", ignoreCase = true) == true }
            // The share answered, through the loopback proxy, about the file itself: past its end (416 — a
            // download cut short, its index promising more than is there) is a damaged file, which mpv
            // plays as far as it goes; not there (404) is a missing one. Neither is the NAS being off.
            val status = generateSequence(error.cause) { it.cause }
                .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            // A dub merged in that cannot start or seek where the film is (a Matroska sound without cues):
            // the film goes on in mpv, which reads it, with that dub.
            val dub = pendingDub ?: player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let(::externalAudioOf)?.let(playingAudios::indexOf)?.takeIf { it >= 0 }
            if (dub != null && mergeBroke(error)) {
                android.util.Log.i("AuraTracks", "外挂音轨 ${playingAudios[dub].label}: Media3's merge broke (${error.cause?.message}), to mpv")
                if (onDubNeedsMpv?.invoke(dub) == true) return
            }
            // A video decoder that failed on a picture too big for software to stand in (an 8K
            // panorama past the hardware): said, with what the hardware takes — no slideshow in mpv.
            val renderer = (error as? androidx.media3.exoplayer.ExoPlaybackException)
                ?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER && error.errorCode in 4000..4999 }
            // (Only before its first picture: a film that has played is the hardware's, and a decoder that
            // fails later is over the file.)
            val tooLarge = renderer?.rendererFormat?.takeIf { MimeTypes.isVideo(it.sampleMimeType) && !_hasFirstFrame.value }
                ?.let(DecoderLimits::oversize)
            if (tooLarge != null) {
                android.util.Log.i("AuraPlay", "failed: ${error.errorCodeName} on a picture past software: $tooLarge")
                failTooLarge(tooLarge)
                return
            }
            val failure = when {
                stuck || status == 416 -> PlaybackFailure.DAMAGED
                status == 404 || status == 410 -> PlaybackFailure.MISSING
                else -> PlaybackFailure.of(error.errorCode, onNetwork = playingOverNetwork, readerCrashed = crashed)
            }
            // The codec a decoder refused, as the renderer that failed knew it.
            failedFormat = PlaybackFailure.legacyCodec((error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType)
            android.util.Log.i("AuraPlay", "failed: ${error.errorCodeName} → $failure" +
                " (${(error as? androidx.media3.exoplayer.ExoPlaybackException)?.let { "type ${it.type} format ${it.rendererFormat?.sampleMimeType}" }}, video $videoMime)")
            // Another engine may yet play it (SwitchingEngine): then no failure is shown here.
            if (onUnplayable?.invoke(failure) == true) return
            // A server that did not answer — as the film opened, too, not only once it played — is waited for:
            // the player asks it again until it does (PlaybackViewModel's server probe).
            if (failure == PlaybackFailure.UNREACHABLE) _sourceDown.value = true
            _failure.value = failure
            _state.value = PlaybackState.ERROR
        }

        override fun onTracksChanged(tracks: Tracks) {
            // A merged-in 外挂音轨 is offered, not chosen: Media3 weighs every track alike and takes the one with
            // more channels — the dub's stereo over the film's mono, its 5.1 over the film's stereo. The film's
            // own sound plays until the viewer (or their 首选音轨, or the pick remembered) says otherwise.
            if (playingAudios.isNotEmpty() && !externalAudioSettled && keepFilmSound(tracks)) return
            val sound = enumerate(tracks, C.TRACK_TYPE_AUDIO) + mpvDubs()
            if (sound.map { it.id to it.selected } != _audioTracks.value.map { it.id to it.selected }) {
                android.util.Log.i("AuraTracks", "sound: " + sound.joinToString { "${if (it.selected) "▶" else ""}${it.name} (${it.detail})" })
            }
            _audioTracks.value = sound
            _subtitleTracks.value = enumerate(tracks, C.TRACK_TYPE_TEXT)
            val pgs = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { group ->
                (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
            }?.takeIf { it.containerMimeType == MimeTypes.VIDEO_MATROSKA && it.codecs == MimeTypes.APPLICATION_PGS }
            pgsRecovery.select(player.currentMediaItem?.localConfiguration?.uri, pgs?.id?.toIntOrNull())
            // Remember the video format's description, but pass no judgement here: capability
            // flags lie for formats the player handles via fallback (a Dolby Vision P8 track
            // reports unsupported yet plays fine as plain HEVC). The no-picture watchdog in
            // refreshPosition decides — with the frame clock, not the flags.
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            hasVideoTracks = video.isNotEmpty()
            videoMime = video.firstOrNull()?.getTrackFormat(0)?.sampleMimeType
            videoCodecs = video.firstOrNull()?.getTrackFormat(0)?.codecs
            // A picture that cannot be drawn here has no decoder to wait for: offer the file to another
            // engine at once, rather than after the no-picture watchdog's seconds of sound over black —
            // a codec no reader of ours knows; an old one no decoder on this device takes (MPEG-2 on
            // the emulator; the flags are only trusted for these, see above); an H.264 profile only
            // software decodes (Hi10P: the headset's decoder says it cannot, and, made to, draws green);
            // or, from a video file, no picture at all: the reader passed over one it does not know (a
            // QuickTime's Sorenson).
            val noDecoder = video.isNotEmpty() && video.none { it.isSupported } && EngineRoute.handsOverPicture(videoMime, videoCodecs)
            val soundOnly = video.isEmpty() && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO } && EngineRoute.expectsPicture(currentUri())
            if ((videoMime == EngineRoute.UNKNOWN_VIDEO || noDecoder || soundOnly) && onPictureless?.invoke(videoMime, videoCodecs) == true) return
            // A picture and no sound at all: the reader may have passed over a sound track it does not
            // know — asked about once per file (the caller reads the file's own track list to tell).
            // …or no subtitles: an old fansub's S_ASS goes the same way, and the episode plays unsubtitled.
            val noSound = tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }
            val noText = tracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.mediaTrackGroup.getFormat(0).id?.startsWith(SIDECAR_ID) != true }
            // (Asked of every file with a picture, once: a file whose first sound track Media3 dropped
            // plays another — a commentary — as if it were the film's.)
            if (video.isNotEmpty() && !soundlessAsked) {
                soundlessAsked = true
                onSoundless?.invoke(currentUri(), noSound, noText)
            }
            videoFormatLabel = video.firstOrNull()?.getTrackFormat(0)?.let { f ->
                listOfNotNull(
                    f.codecs ?: f.sampleMimeType?.substringAfterLast('/')?.uppercase(Locale.US),
                    f.width.takeIf { it > 0 }?.let { "${f.width}×${f.height}" },
                ).joinToString(" · ").ifBlank { words.unknownFormat }
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            val presentedUs = cueGroup.presentationTimeUs
            val authoredUs = if (presentedUs == C.TIME_UNSET) C.TIME_UNSET else presentedUs - subtitleDelayUs
            visibleCueStartUs = if (cueGroup.cues.isEmpty()) C.TIME_UNSET else authoredUs
            showCues(pgsRecovery.rendererCues(cueGroup.cues, authoredUs))
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            // Display aspect: honour anamorphic pixels so 1920×1080 stored 4:3 fits as 4:3.
            _videoAspect.value = if (size.width > 0 && size.height > 0) {
                size.width * pixelRatioOf(size.pixelWidthHeightRatio) / size.height
            } else {
                0f
            }
        }
    }

    private fun showCues(cues: List<Cue>) {
        // A fansub's vector shapes (a sign's mask) come out of Media3's ASS reader as their commands.
        CueClock.changedAt = android.os.SystemClock.uptimeMillis()
        _activeCues.value = cues.filterNot { c -> c.text?.let(AssDrawing::isVectorDrawing) == true }.map { c ->
            SubtitleCue(
                text = c.text,
                bitmap = c.bitmap,
                bitmapPlacement = c.bitmapPlacement(),
                placement = CuePlacement.of(c.line, c.lineType == Cue.LINE_TYPE_FRACTION, c.lineAnchor),
                hAlign = when {
                    c.textAlignment == Layout.Alignment.ALIGN_NORMAL -> -1
                    c.textAlignment == Layout.Alignment.ALIGN_OPPOSITE -> 1
                    c.position != Cue.DIMEN_UNSET && c.positionAnchor == Cue.ANCHOR_TYPE_START && c.position < 0.4f -> -1
                    c.position != Cue.DIMEN_UNSET && c.positionAnchor == Cue.ANCHOR_TYPE_END && c.position > 0.6f -> 1
                    else -> 0
                },
                sizeFraction = if (c.textSizeType == Cue.TEXT_SIZE_TYPE_FRACTIONAL && c.textSize > 0f) c.textSize else null,
            )
        }
            // …and an ASS line set in layers (a glow under the fill, a border over it — every line
            // of a fansub's opening) comes out once per layer: the same words in the same place, once.
            .distinctBy { c -> if (c.text == null) c else Triple(c.text.toString(), c.placement, c.hAlign) }
    }

    /**
     * A stereo Matroska written by FFmpeg gives the display size of ONE view — side by side 1920×1080
     * says 960×1080, over-under 1920×540 — and FFmpeg (so mpv) reads it back that way, as square pixels.
     * Media3 takes it for the whole frame's: pixels half as wide, a 0.89:1 frame and each eye thin and
     * tall where mpv shows the same file at 16:9. Exactly half on the packed side is that per-view size.
     */
    private fun pixelRatioOf(ratio: Float): Float {
        val f = player.videoFormat ?: return ratio
        if (f.containerMimeType != MimeTypes.VIDEO_MATROSKA && f.containerMimeType != MimeTypes.VIDEO_WEBM) return ratio
        return when {
            f.stereoMode == C.STEREO_MODE_LEFT_RIGHT && kotlin.math.abs(ratio - 0.5f) < 0.001f -> 1f
            f.stereoMode == C.STEREO_MODE_TOP_BOTTOM && kotlin.math.abs(ratio - 2f) < 0.002f -> 1f
            else -> ratio
        }
    }

    // Which decoders actually run — the info panel says 硬解/软解 and names them.
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    /**
     * How fast the film plays, in bits a second: its playing tracks' bitrates, as its container gives
     * them (an MP4's do; a Matroska's seldom), else null.
     */
    private fun playRate(): Long? {
        val formats = player.currentTracks.groups.filter { it.isSelected && (it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO) }
            .mapNotNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val video = formats.firstOrNull { androidx.media3.common.MimeTypes.isVideo(it.sampleMimeType) } ?: return null
        val of = { f: androidx.media3.common.Format -> listOf(f.averageBitrate, f.bitrate, f.peakBitrate).firstOrNull { it > 0 } ?: 0 }
        if (of(video) <= 0) return null
        return formats.sumOf { of(it).toLong() }
    }

    /** A seek's own wait is no measure of the network: the buffer starts over at the new place. */
    private fun seeking() = seekReadyAtMs > 0

    /** A wait in the middle of the film is over: what it says of the file's coming against its playing. */
    private fun endOfWait() {
        val from = waitFromMs
        waitFromMs = 0L
        if (from == 0L) return
        val waited = android.os.SystemClock.elapsedRealtime() - from
        val gained = player.bufferedPosition - waitFromBufferedMs
        val rate = playRate() ?: return
        if (waited < 500 || gained <= 0) return
        val bitsIn = rate.toDouble() * gained / waited
        _dataRate.value = DataRate(bitsIn / 8, rate / 8.0)
        android.util.Log.i("AuraPerf", "waited for data %d ms: in at %.1f MB/s, the film plays %.1f MB/s".format(java.util.Locale.US, waited, bitsIn / 8e6, rate / 8e6))
    }

    private val analytics = object : AnalyticsListener {

        // A load that failed once the film had played is the server's (PatientLoadErrorPolicy asks again): the
        // viewer is told it waits on it, not only that it loads — 85 s of 加载中 over a NAS that had gone away.
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean,
        ) {
            if (wasCanceled || !loadErrors.patient || _sourceDown.value) return
            _sourceDown.value = true
            android.util.Log.i("AuraPlay", "the file's server stopped answering: waiting for it (${(error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.let { "HTTP ${it.responseCode}" } ?: error.message})")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoderName = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioDecoderName = decoderName
            android.util.Log.i("AuraTracks", "sound decoder: $decoderName")
        }

        // A decoder kept from the last file (a picture or sound of the same kind) is not initialized again:
        // its name comes with the new format instead — or the info panel would not say how this one decodes.
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            if (decoderReuseEvaluation != null && decoderReuseEvaluation.result != androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO) {
                videoDecoderName = decoderReuseEvaluation.decoderName
            }
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            if (decoderReuseEvaluation != null && decoderReuseEvaluation.result != androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO) {
                audioDecoderName = decoderReuseEvaluation.decoderName
            }
        }
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        // Headphones unplugged → pause, the way every player behaves.
        player.setHandleAudioBecomingNoisy(true)
        ensureSession()
    }

    /** The media session, made again for a film after [endSession] let the last one go. */
    fun ensureSession() {
        if (session != null) return
        // A unique id: a new session-scoped manager may start before the old one releases.
        session = MediaSession.Builder(context, SessionPlayer(sessionDriver ?: player)).setId("aura-" + System.nanoTime()).build()
    }

    override fun opening(startMs: Long) {
        _state.value = PlaybackState.PREPARING
        _duration.value = 0L
        _position.value = startMs
        _hasFirstFrame.value = false
    }

    override fun endSession() {
        runCatching { session?.release() }
        session = null
    }

    override fun unload() {
        pgsRecovery.select(null, null)
        player.stop()
        player.clearMediaItems()
    }

    /** The render target — the Surface acquired from the panel's SurfaceRenderTexture. */
    fun setVideoSurface(surface: Surface?) {
        player.setVideoSurface(surface)
    }

    override fun setup(path: String, startPositionMs: Long, sidecars: List<SidecarSubtitle>) {
        ensureSession()
        // A VobSub / .sup beside the file is pictures Media3 has no reader for: mpv's ([SwitchingEngine]).
        val sidecars = sidecars.filterNot { com.therealityteam.videoplayer.data.subtitle.SubtitleEngine.isPicture(it.mimeType) }
        setupAtMs = android.os.SystemClock.elapsedRealtime()
        countersAtSetup = player.videoDecoderCounters
        renderedAtSetup = countersAtSetup?.renderedOutputBufferCount ?: 0
        // Fresh counters come with the file; a resume point's way from its keyframe is the first preroll.
        prerollSkipped = 0
        skippedAtReset = 0
        stopScrubbing()
        _dataRate.value = null
        lastFrameUs = Long.MIN_VALUE
        frameGapCount = 0
        measuredFrameUs = 0L
        _failure.value = null
        failedFormat = null
        oversize = null
        playingOverNetwork = path.startsWith("http://") || path.startsWith("https://") || LocalStreamServer.serves(path)
        _state.value = PlaybackState.PREPARING
        _hasFirstFrame.value = false
        loadErrors.patient = false
        _sourceDown.value = false
        _duration.value = 0L
        _position.value = startPositionMs
        _activeCues.value = emptyList()
        visibleCueStartUs = C.TIME_UNSET
        subtitlePreroll = null
        _videoUnsupported.value = null
        hasVideoTracks = false
        soundlessAsked = false
        videoFormatLabel = null
        _videoAspect.value = 0f
        videoDecoderName = null
        audioDecoderName = null
        _audioTracks.value = emptyList()
        _subtitleTracks.value = emptyList()
        playingSinceMs = 0L
        stalledSinceMs = 0L
        sidecarLabels = sidecars.map { it.label }.toSet()
        sidecarTags = sidecars.associate { it.label to it.tag }
        pgsRecovery.select(null, null)
        assStreams.reset()

        val item = MediaItem.Builder()
            .setUri(path)
            .setMediaMetadata(nowPlaying)
            .setSubtitleConfigurations(
                sidecars.mapIndexed { i, it ->
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.uri))
                        .setId("$SIDECAR_ID$i")
                        .setMimeType(it.mimeType)
                        .setLabel(it.label)
                        .setLanguage(it.language)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                },
            )
            .build()
        // 外挂音轨: each sound file beside the film is a source of its own, merged in — its tracks come
        // last, under the merge's index ("1:…", "2:…"), which is how [enumerate] knows them.
        playingAudios = externalAudio
        mergedAudios = playingAudios.filter { ExternalAudio.media3Merges(it.uri) }
        externalAudioSettled = false
        pendingDub = null
        player.playWhenReady = true
        prepareSource(item, startPositionMs)
    }

    /** Reparse cues at the current frame. Keep the playback intent, track overrides and merged audio. */
    fun refreshSubtitleTiming() {
        val item = player.currentMediaItem ?: return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        val positionMs = player.currentPosition
        // A long subtitle can begin several keyframes before the paused picture. Re-read
        // its original packet as well as the packets moved here by the new delay.
        val positionUs = positionMs * 1000L
        val previous = subtitlePreroll?.takeIf { it.positionUs == positionUs }
        subtitlePreroll = when {
            visibleCueStartUs == C.TIME_UNSET -> previous
            else -> SubtitlePreroll(positionUs, minOf(previous?.cueStartUs ?: visibleCueStartUs, visibleCueStartUs))
        }
        _activeCues.value = emptyList()
        prepareSource(item, positionMs)
        pgsRecovery.seek()
    }

    private fun prepareSource(item: MediaItem, startPositionMs: Long) {
        if (mergedAudios.isEmpty()) {
            player.setMediaItem(item, startPositionMs)
        } else {
            val parts = listOf(sourceFactory.createMediaSource(item)) +
                mergedAudios.map { sourceFactory.createMediaSource(MediaItem.fromUri(it.uri)) }
            player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(*parts.toTypedArray()), startPositionMs)
        }
        player.prepare()
    }

    /**
     * The 外挂音轨 of the next [setup]; those of the file now playing (their order is their id,
     * ExternalAudio.trackId); and the ones of those Media3 reads, merged in. The rest are offered all the
     * same — chosen, the film goes on in mpv ([onDubNeedsMpv]).
     */
    private var externalAudio: List<SidecarAudio> = emptyList()
    private var playingAudios: List<SidecarAudio> = emptyList()
    private var mergedAudios: List<SidecarAudio> = emptyList()

    /** The merged dub last chosen, until it plays: a merge that breaks over it hands it to mpv. */
    private var pendingDub: Int? = null

    /** A dub only mpv plays was chosen (its index among the file's dubs): true when mpv takes the film. */
    var onDubNeedsMpv: ((index: Int) -> Boolean)? = null

    override fun setExternalAudio(audios: List<SidecarAudio>) { externalAudio = audios }

    /** Whether the first choice of sound, with 外挂音轨 merged in, has been made the film's own. */
    private var externalAudioSettled = false

    /**
     * With the tracks first known: when Media3 chose a 外挂音轨, the film's own sound instead — its default
     * track, else its first. True when that changed the choice (the tracks come again, settled).
     */
    private fun keepFilmSound(tracks: Tracks): Boolean {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return false
        externalAudioSettled = true
        val chosen = audio.firstOrNull { it.isSelected } ?: return false
        if (externalAudioOf(chosen) == null) return false
        val own = audio.filter { externalAudioOf(it) == null && it.isSupported }
        val group = own.firstOrNull { g -> (0 until g.length).any { g.getTrackFormat(it).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 } }
            ?: own.firstOrNull() ?: return false
        val track = (0 until group.length).firstOrNull { group.isTrackSupported(it) } ?: return false
        android.util.Log.i("AuraTracks", "外挂音轨 offered, the film's own sound kept")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
        return true
    }

    /** The 外挂音轨 a track group is, by the merge's index its id starts with — null for the film's own. */
    private fun externalAudioOf(group: Tracks.Group): SidecarAudio? {
        if (mergedAudios.isEmpty()) return null
        val index = group.mediaTrackGroup.id.substringBefore(':').toIntOrNull() ?: return null
        return mergedAudios.getOrNull(index - 1)
    }

    /** A merge's child that would not start (or seek) where the film is: an unseekable dub. */
    private fun mergeBroke(error: PlaybackException): Boolean = generateSequence<Throwable>(error) { it.cause }.any { e ->
        e is IllegalStateException && e.message?.let { "Children enabled at different positions" in it || "Unexpected child seekToUs result" in it } == true
    }

    override fun setupFd(fd: Int, length: Long, startPositionMs: Long) {
        ensureSession()
        _failure.value = null
        failedFormat = null
        oversize = null
        playingOverNetwork = true // the fd bridge reads a file on a share
        error("ExoPlaybackManager plays via the HTTP proxy, not the fd bridge (supportsFd=false)")
    }

    override fun selectAudioTrack(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectSubtitleTrack(id: String?) {
        if (id == null) {
            pgsRecovery.select(null, null)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            _activeCues.value = emptyList()
        } else {
            select(C.TRACK_TYPE_TEXT, id)
        }
    }

    private fun select(type: Int, id: String) {
        ExternalAudio.indexOf(id)?.let { index -> selectDub(index); return }
        val (g, t) = id.split(":").let { it[0].toInt() to it[1].toInt() }
        val group = player.currentTracks.groups.filter { it.type == type }.getOrNull(g) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t))
            .build()
    }

    /** The dub [index] of the file's: its merged track chosen, or — one only mpv plays — the film handed to mpv. */
    private fun selectDub(index: Int) {
        val dub = playingAudios.getOrNull(index) ?: return
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && externalAudioOf(it) === dub }
        if (group == null) {
            if (dub !in mergedAudios) onDubNeedsMpv?.invoke(index)
            return
        }
        pendingDub = index
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** The labels of this item's sidecar files, so their tracks are known as external even if the id is lost. */
    private var sidecarLabels: Set<String> = emptySet()

    /** Each sidecar's languages as the app reads them ("chs&eng"), by its label — the menus and 首选字幕 read these. */
    private var sidecarTags: Map<String, String?> = emptyMap()

    private fun enumerate(tracks: Tracks, type: Int): List<MediaTrack> {
        val isText = type == C.TRACK_TYPE_TEXT
        var ordinal = 0
        return tracks.groups.filter { it.type == type }.flatMapIndexed { g, group ->
            (0 until group.length).mapNotNull { t ->
                if (!group.isTrackSupported(t)) return@mapNotNull null
                val f = group.getTrackFormat(t)
                ordinal++
                // Text tracks arrive transcoded to application/x-media3-cues; the original
                // format lives in codecs — prefer it so the menu says SRT/ASS, not MEDIA3-CUES.
                // Sound is the other way round: codecs is the raw profile string (mp4a.40.2).
                val mime = if (isText) f.codecs ?: f.sampleMimeType else f.sampleMimeType
                val dub = if (isText) null else externalAudioOf(group)
                val external = dub != null || isText && (f.id?.startsWith(SIDECAR_ID) == true || f.label in sidecarLabels)
                // A sidecar's languages as read from its name or its lines; the engine only has the main one's code.
                val language = (if (isText && external) sidecarTags[f.label] else dub?.language) ?: f.language
                val lang = languageName(language, words)
                // A sidecar's label is its file name, kept whole (a dub's, the tag its name adds: 国语);
                // a stream's own title only when it names something.
                val title = when {
                    dub != null -> dub.label
                    external -> f.label?.takeIf { it.isNotBlank() }
                    else -> TrackTitle.named(f.label)
                }
                val codec = codecLabel(mime, 0, words)
                val name = when {
                    external -> title ?: words.externalSubtitles
                    title != null -> title
                    lang != null -> lang
                    !isText && codec != null -> codec // an untagged sound track is best known by its codec
                    else -> words.track(ordinal)
                }
                val detail = listOfNotNull(
                    if (isText || dub != null) (if (external) words.external else words.embedded) else null,
                    codec?.takeIf { it != name },
                    if (isText) null else channelLabel(f.channelCount, words),
                    if (isText || f.sampleRate <= 0) null else sampleRateLabel(f.sampleRate),
                    // A sidecar's languages head its row in the menu (SidecarName): not again here.
                    lang?.takeIf { !external && !name.contains(it) },
                ).joinToString(" · ")
                MediaTrack(
                    id = dub?.let { ExternalAudio.trackId(playingAudios.indexOf(it)) } ?: "$g:$t",
                    label = listOf(name, detail).filter { it.isNotBlank() }.joinToString(" · "),
                    language = language,
                    channels = f.channelCount.coerceAtLeast(0),
                    selected = group.isTrackSelected(t),
                    name = name,
                    detail = detail,
                    external = external,
                    streamId = f.id,
                    typeset = isText && mime == androidx.media3.common.MimeTypes.TEXT_SSA,
                    bitmapSubtitle = isText && (mime == MimeTypes.APPLICATION_PGS ||
                        mime == MimeTypes.APPLICATION_DVBSUBS || mime == MimeTypes.APPLICATION_VOBSUB),
                )
            }
        }
    }

    /** The dubs Media3 does not read, offered beside its tracks: chosen, the film goes on in mpv with it. */
    private fun mpvDubs(): List<MediaTrack> = playingAudios.mapIndexedNotNull { i, a ->
        if (a in mergedAudios) return@mapIndexedNotNull null
        val detail = listOfNotNull(words.external, ExternalAudio.codecOf(a.uri)).joinToString(" · ")
        MediaTrack(
            id = ExternalAudio.trackId(i), label = "${a.label} · $detail", language = a.language, selected = false,
            name = a.label, detail = detail, external = true,
        )
    }

    override fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun play() { player.play() }
    override fun pause() { player.pause() }

    /**
     * 逐帧: paused, to the next (or the last) picture — by the frame's own length, from the one shown:
     * picture n begins at n / fps, and a seek is to the first millisecond inside it, so Media3 (whose
     * seeks are exact) shows that picture and no earlier one. 30 fps when the file does not say.
     */
    /**
     * The film's frame rate: its container's word, else what its frames show as they are drawn — a web
     * MKV gives none, and a step taken as a 30 fps frame (33 ms) on a 23.976 fps film (41.7 ms) could land
     * in the frame already shown: 逐帧 showed no change.
     */
    private fun frameRate(): Float? =
        player.videoFormat?.frameRate?.takeIf { it > 0f } ?: measuredFrameUs.takeIf { it > 0 }?.let { 1_000_000f / it }

    private val frameGaps = LongArray(15)
    private var frameGapCount = 0
    private var lastFrameUs = Long.MIN_VALUE
    @Volatile private var measuredFrameUs = 0L

    /** On the playback thread, each frame as it is drawn: the median of the last gaps between them. */
    private val frameTimes = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        val last = lastFrameUs
        lastFrameUs = presentationTimeUs
        if (last == Long.MIN_VALUE) return@VideoFrameMetadataListener
        val gap = presentationTimeUs - last
        if (gap !in 4_000L..100_000L) return@VideoFrameMetadataListener
        frameGaps[frameGapCount % frameGaps.size] = gap
        frameGapCount++
        if (frameGapCount >= 5) {
            val seen = frameGaps.copyOf(minOf(frameGapCount, frameGaps.size)).sorted()
            measuredFrameUs = seen[seen.size / 2]
        }
    }

    init {
        player.setVideoFrameMetadataListener(frameTimes)
    }

    override fun stepFrame(forward: Boolean) {
        player.pause()
        val fps = frameRate() ?: 30f
        val frameMs = 1000.0 / fps
        val shown = Math.round(player.currentPosition / frameMs)
        val next = (shown + if (forward) 1 else -1).coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val target = kotlin.math.ceil(next * frameMs).toLong().let { if (duration != null) it.coerceAtMost(duration) else it }
        seekTo(target)
    }

    override fun seekTo(ms: Long) {
        seekAtMs = android.os.SystemClock.elapsedRealtime()
        seekReadyAtMs = seekAtMs
        seekFromMs = player.currentPosition
        seekToMs = ms
        player.seekTo(ms)
        _position.value = ms
    }

    /** When the last seek was asked for, and from where to where — for the AuraPerf line its picture logs. */
    private var seekAtMs = 0L
    private var seekReadyAtMs = 0L
    private var seekFromMs = 0L
    private var seekToMs = 0L

    override fun beginScrub() {
        if (scrubbing) return
        scrubbing = true
        player.setScrubbingModeEnabled(true)
        android.util.Log.i("AuraScrub", "begin at ${player.currentPosition}ms")
    }

    override fun scrubTo(ms: Long) {
        beginScrub()
        // Short of the very end: a frame there would end the file under the hand. The release
        // lands wherever it lets go, the end included.
        val d = player.duration
        val target = if (d > 0) ms.coerceIn(0L, (d - SCRUB_END_GUARD_MS).coerceAtLeast(0L)) else ms.coerceAtLeast(0L)
        player.seekTo(target)
        android.util.Log.d("AuraScrub", "frame at ${target}ms")
    }

    override fun endScrub(ms: Long) {
        val held = scrubbing
        stopScrubbing()
        seekTo(ms)
        if (held) android.util.Log.i("AuraScrub", "let go at ${ms}ms, playing=${player.playWhenReady}")
    }

    private fun stopScrubbing() {
        if (!scrubbing) return
        scrubbing = false
        player.setScrubbingModeEnabled(false)
        // A pause asked for mid-drag (a headset's key) found the player already held: say so now.
        if (!player.playWhenReady && _state.value == PlaybackState.PLAYING) _state.value = PlaybackState.PAUSED
    }

    override fun setVolume(v: Float) { player.volume = v.coerceIn(0f, 1f) }

    override fun setAudioBoost(factor: Float) { gainProcessor.gain = factor.coerceIn(1f, 2f) }
    override fun setNightMode(on: Boolean) {
        if (gainProcessor.night != on) android.util.Log.i("AuraAudio", "night mode ${if (on) "on" else "off"}")
        gainProcessor.night = on
    }

    override fun setAudioDelay(ms: Long) { delayProcessor.delayUs = ms.coerceIn(-2_000L, 2_000L) * 1_000L }

    override fun setAmbisonic(audio: AmbisonicAudio?) { ambisonicProcessor.ambisonic = audio }

    /**
     * PICO routes media audio through its spatializer output thread by default. Measured here:
     * that path underruns intermittently on 4K + TrueHD 7.1 (audible dropouts) while the plain
     * mixer path is clean, so the app defaults to NEVER and offers AUTO as a setting. ExoPlayer
     * ignores a call that doesn't change the attributes, so this is safe to apply per item.
     */
    override fun setSpatialAudio(enabled: Boolean) {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setSpatializationBehavior(
                    if (enabled) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER,
                )
                .build(),
            /* handleAudioFocus= */ true, // a call or another app taking focus pauses us
        )
    }

    /**
     * Metadata the next [setup] stamps on its MediaItem; the session publishes it as "now playing"
     * (and shows it for a file mpv plays, [MpvSessionPlayer]).
     */
    var nowPlaying: MediaMetadata = MediaMetadata.EMPTY
        private set

    override fun setNowPlaying(title: String?, subtitle: String?, artworkPath: String?) {
        nowPlaying = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // The system's own cards read the second line from these: a description shows the
            // subtitle only under a display title, a media card the artist.
            .setDisplayTitle(title)
            .setArtist(subtitle)
            .setArtworkUri(
                artworkPath?.let {
                    if (it.startsWith("/")) android.net.Uri.fromFile(java.io.File(it)) else android.net.Uri.parse(it)
                },
            )
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
            .build()
    }
    override fun setSpeed(speed: Float) { player.setPlaybackSpeed(speed) }
    override fun setLoop(loop: Boolean) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // No-picture watchdog state: when playback has run this long with a video track and no
    // rendered frame, the video is not decoding (whatever the capability flags claim).
    private var hasVideoTracks = false
    private var videoFormatLabel: String? = null

    override var failedFormat: String? = null
        private set

    override var oversize: Oversize? = null
        private set

    override fun fail(failure: PlaybackFailure) {
        player.stop()
        _failure.value = failure
        _state.value = PlaybackState.ERROR
    }

    /** The picture is past this headset ([oversize]): the player stops, and says so (PlaybackFailure.TOO_LARGE). */
    private fun failTooLarge(o: Oversize) {
        oversize = o
        player.stop()
        _failure.value = PlaybackFailure.TOO_LARGE
        _state.value = PlaybackState.ERROR
    }

    // The video track's MIME type, as the tracks were last read.
    private var videoMime: String? = null
    // …and its codecs (profile and level: `avc1.6E0032`).
    private var videoCodecs: String? = null
    private var playingSinceMs = 0L

    override fun positionNow(): Long = player.currentPosition

    // AuraPerf "frames": every FRAMES_EVERY_MS of playing, the pictures shown and lost in it — how a
    // film keeps up on the headset (an 8K 60 fps panorama; a share's bitrate), as the log can say.
    private var framesAt = 0L
    private var framesRendered = 0
    private var framesLost = 0

    // Media3 counts a frame as skipped both when it was decoded only to reach a seek's (or a resume's) point
    // from the keyframe before it, and when it came too late with no surface to show it on. Only the second
    // is a frame lost: a seek into a film with one keyframe in 10 s read "丢帧 240" for nothing. The first kind
    // is told by when it happens — between a seek and its first picture — and kept apart here.
    private var prerollSkipped = 0
    // The skipped count when the pending seek began; -1 when none is pending.
    private var skippedAtReset = -1

    private fun skippedNow(): Int = player.videoDecoderCounters?.let { it.ensureUpdated(); it.skippedOutputBufferCount } ?: 0

    /** The frames that never reached the screen: dropped for lateness, or skipped other than on a seek's way. */
    private fun lostFrames(c: androidx.media3.exoplayer.DecoderCounters): Int =
        c.droppedBufferCount + (c.skippedOutputBufferCount - prerollSkipped).coerceAtLeast(0)

    private fun framesBeacon() {
        if (_state.value != PlaybackState.PLAYING) { framesAt = 0L; return }
        val c = player.videoDecoderCounters ?: return
        c.ensureUpdated()
        val now = android.os.SystemClock.elapsedRealtime()
        val lost = lostFrames(c)
        if (framesAt == 0L || c.renderedOutputBufferCount < framesRendered) {
            framesAt = now; framesRendered = c.renderedOutputBufferCount; framesLost = lost
            return
        }
        if (now - framesAt < FRAMES_EVERY_MS) return
        val shown = c.renderedOutputBufferCount - framesRendered
        val gone = lost - framesLost
        val f = player.videoFormat
        android.util.Log.i(
            "AuraPerf",
            "frames: $shown shown, $gone dropped in ${now - framesAt} ms " +
                "(${"%.1f".format(Locale.US, shown * 1000f / (now - framesAt))} fps of ${frameRate()?.let { "%.0f".format(Locale.US, it) } ?: "?"}, " +
                "${f?.width}x${f?.height}, $videoDecoderName)",
        )
        framesAt = now; framesRendered = c.renderedOutputBufferCount; framesLost = lost
    }

    override fun refreshPosition() {
        // The drag's own points are not the film's position (see [scrubbing]).
        if (scrubbing) return
        pgsRecovery.refresh()
        if (_state.value == PlaybackState.PLAYING || _state.value == PlaybackState.PAUSED) {
            _position.value = player.currentPosition
            if (_duration.value <= 0L) _duration.value = player.duration.coerceAtLeast(0L)
        }
        framesBeacon()
        if (_state.value == PlaybackState.PREPARING && !_hasFirstFrame.value && hasVideoTracks && videoDecoderName != null) {
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            val enough = (player.currentPosition + STALLED_BUFFERED_MS).let { if (duration != null) minOf(it, duration) else it }
            val now = android.os.SystemClock.elapsedRealtime()
            // Stuck, not slow: fed, and nothing out of it at all — not even the frames skipped on the way
            // to a resume point from its keyframe (a long GOP's seconds of them at 8K).
            val counters = player.videoDecoderCounters?.also { it.ensureUpdated() }
            val fed = counters?.queuedInputBufferCount ?: 0
            val out = counters?.let { it.renderedOutputBufferCount + it.skippedOutputBufferCount + it.droppedBufferCount } ?: 0
            if (player.bufferedPosition < enough || fed == 0 || out > 0) {
                stalledSinceMs = 0L
            } else if (stalledSinceMs == 0L) {
                stalledSinceMs = now
            } else if (now - stalledSinceMs > NO_PICTURE_AFTER_MS && _videoUnsupported.value == null) {
                android.util.Log.w("AuraPlay", "$videoDecoderName was given ${player.bufferedPosition - player.currentPosition} ms of $videoMime ($fed inputs) and gave no picture")
                stalledSinceMs = 0L
                // Too big for software to stand in for the hardware: said, not handed to mpv.
                val tooLarge = player.videoFormat?.let(DecoderLimits::oversize)
                if (tooLarge != null) {
                    android.util.Log.i("AuraPlay", "a picture past software: $tooLarge")
                    failTooLarge(tooLarge)
                    return
                }
                if (onDecoderStalled?.invoke(videoMime) == true) return
                _videoUnsupported.value = videoFormatLabel ?: words.unknownFormat
            }
        } else {
            stalledSinceMs = 0L
        }
        if (_state.value == PlaybackState.PLAYING && !_hasFirstFrame.value && hasVideoTracks) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (playingSinceMs == 0L) playingSinceMs = now
            if (now - playingSinceMs > NO_PICTURE_AFTER_MS && _videoUnsupported.value == null) {
                if (onPictureless?.invoke(videoMime, videoCodecs) == true) return
                _videoUnsupported.value = videoFormatLabel ?: words.unknownFormat
            }
        }
    }

    override fun mediaInfo(): MediaInfo? {
        val v = player.videoFormat
        val a = player.audioFormat
        if (v == null && a == null) return null
        val videoLine = v?.let { f ->
            val size = if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null
            val codec = codecName(f.sampleMimeType)
            val hdr = when (f.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> "HDR10"
                C.COLOR_TRANSFER_HLG -> "HLG"
                else -> null
            }
            // Its profile and depth from its codecs (HEVC Main 10 → 10-bit even when colorInfo is absent),
            // named as mpv's panel names them.
            val profile = CodecProfile.name(f.codecs)
            val depth = CodecProfile.depth(f.codecs)
            val fps = frameRate()?.let { "%.0ffps".format(it) }
            listOfNotNull(size, codec?.let { n -> profile?.let { "$n $it" } ?: n }, depth, hdr, fps).joinToString(" · ")
        }
        val audioLine = a?.let { f ->
            val codec = codecName(f.sampleMimeType)
            val ch = channelLabel(f.channelCount, words)
            val rate = f.sampleRate.takeIf { it > 0 }?.let { sampleRateLabel(it) }
            listOfNotNull(codec, ch, rate).joinToString(" · ")
        }
        return MediaInfo(
            video = videoLine?.ifBlank { null },
            audio = audioLine?.ifBlank { null },
            decoder = DecodeLabel.decoders(videoDecoderName, audioDecoderName, words.hardware, words.software, words.audio),
            // The counters are written on the playback thread: read here without this, the panel
            // showed whatever they held when last synced — 已渲染 52 a minute into a film.
            frames = player.videoDecoderCounters?.let {
                it.ensureUpdated()
                // A frame the renderer skipped for being late never reached the screen either: counted
                // with the dropped ones. Left out, a decoder falling behind (the emulator's 10-bit HEVC:
                // ~1 frame in 24 shown) read "丢帧 11" over 800 frames that never appeared. A seek's way to
                // its point is not counted (lostFrames).
                DecodeLabel.frames(it.renderedOutputBufferCount, lostFrames(it)) { dropped, rendered -> words.frames(dropped, rendered) }
            },
        )
    }

    override fun videoPixels(): Pair<Int, Int>? = player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }

    override fun currentUri(): String? =
        player.currentMediaItem?.localConfiguration?.uri?.toString()

    /**
     * What the media session drives. ExoPlayer holds one MediaItem at a time — the queue is
     * the ViewModel's — so a headset's next/previous keys are routed up to it rather than to
     * ExoPlayer's single-item timeline, and the skip commands are always advertised so those
     * keys reach us at all; the ViewModel decides whether there is anywhere to go.
     */
    private inner class SessionPlayer(base: Player) : ForwardingPlayer(base) {
        override fun seekToNext() { onSkipNext?.invoke() ?: super.seekToNext() }
        override fun seekToNextMediaItem() { onSkipNext?.invoke() ?: super.seekToNextMediaItem() }
        override fun seekToPrevious() { onSkipPrevious?.invoke() ?: super.seekToPrevious() }
        override fun seekToPreviousMediaItem() { onSkipPrevious?.invoke() ?: super.seekToPreviousMediaItem() }
        override fun hasNextMediaItem(): Boolean = hasNextItem?.invoke() ?: super.hasNextMediaItem()
        override fun hasPreviousMediaItem(): Boolean = hasPreviousItem?.invoke() ?: super.hasPreviousMediaItem()
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                super.isCommandAvailable(command)
    }

    /**
     * Points the media session — a headset's keys, the system's now playing — at [other] while
     * another engine plays the file (mpv, through [MpvSessionPlayer]), or back at ExoPlayer for
     * null. Left on ExoPlayer, stopped under mpv, a play/pause key "prepared" it: its empty
     * playlist ended at once and the player went on to the next episode — or a handed-over file's
     * sound played a second time, under mpv's.
     */
    fun driveSessionWith(other: Player?) {
        sessionDriver = other
        val s = session ?: return
        val base = other ?: player
        if ((s.player as? ForwardingPlayer)?.wrappedPlayer === base) return
        s.player = SessionPlayer(base)
    }

    override fun release() {
        pgsRecovery.release()
        runCatching { session?.release() }
        session = null
        runCatching {
            player.removeListener(listener)
            player.release()
        }
        _state.value = PlaybackState.INIT
    }

    /**
     * Shift cues in the parser's time base. For embedded tracks Media3 adds the packet
     * timestamp afterwards, so a negative offset here may still be well into the film.
     */
    private class DelayingSubtitleParserFactory(
        private val delegate: SubtitleParser.Factory,
        private val delayUs: () -> Long,
    ) : SubtitleParser.Factory by delegate {
        override fun create(format: androidx.media3.common.Format): SubtitleParser {
            val real = delegate.create(format)
            return object : SubtitleParser by real {
                override fun parse(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                    outputOptions: SubtitleParser.OutputOptions,
                    output: androidx.media3.common.util.Consumer<CuesWithTiming>,
                ) {
                    val d = delayUs()
                    real.parse(data, offset, length, outputOptions) { cues ->
                        if (d == 0L) {
                            output.accept(cues)
                        } else {
                            val start = if (cues.startTimeUs == C.TIME_UNSET) 0L else cues.startTimeUs
                            output.accept(CuesWithTiming(cues.cues, start + d, cues.durationUs))
                        }
                    }
                }
            }
        }
    }

    companion object {
        /**
         * The reader's language in each form a container may label it with, then English.
         *
         * Two three-letter codes exist for twenty-odd languages and files use both: ISO 639-2/T is
         * what Java hands out (`zho`, `deu`, `fra`) while /B is what a great many containers were
         * tagged with (`chi`, `ger`, `fre`). Asking for only one of them misses half the subtitle
         * tracks in exactly the languages this matters for.
         *
         * In the companion object because the player is built from it during construction, and an
         * instance property declared below that point is still null when it is read.
         */
        private val BIBLIOGRAPHIC = mapOf(
            "sqi" to "alb", "hye" to "arm", "eus" to "baq", "mya" to "bur", "zho" to "chi",
            "ces" to "cze", "nld" to "dut", "fra" to "fre", "kat" to "geo", "deu" to "ger",
            "ell" to "gre", "isl" to "ice", "mkd" to "mac", "mri" to "mao", "msa" to "may",
            "fas" to "per", "ron" to "rum", "slk" to "slo", "bod" to "tib", "cym" to "wel",
        )

        fun preferredTextLanguages(locale: Locale = Locale.getDefault()): Array<String> {
            val iso3 = runCatching { locale.isO3Language }.getOrDefault("")
            return listOf(iso3, BIBLIOGRAPHIC[iso3] ?: "", locale.language, "eng", "en")
                .filter { it.isNotBlank() }.distinct().toTypedArray()
        }

        /** Playing this long with zero rendered frames = the video track is not decoding. */
        private const val NO_PICTURE_AFTER_MS = 4_000L
        private const val FRAMES_EVERY_MS = 5_000L
        /** How much of the film read ahead says a decoder that gives no picture is stuck, not waiting for data. */
        private const val STALLED_BUFFERED_MS = 3_000L

        /** A drag's frames stop this far before the end (see [scrubTo]). */
        private const val SCRUB_END_GUARD_MS = 1_000L

        /** "日语 · DTS 5.1" out of format metadata, degrading gracefully to whatever exists. */
        /** Format.id prefix of a same-folder subtitle file, so its track is known as external. */
        const val SIDECAR_ID = "sidecar:"

        fun trackLabel(label: String?, language: String?, mime: String?, channels: Int, words: Words): String =
            listOfNotNull(label ?: languageName(language, words), codecLabel(mime, channels, words))
                .joinToString(" · ").ifBlank { words.unknownTrack }

        /** "audio/mp4a-latm" → AAC, "application/x-subrip" → SRT, "audio/vnd.dts" → DTS…, with the channel layout for sound. */
        fun codecLabel(mime: String?, channels: Int, words: Words): String? {
            val name = codecName(mime) ?: return null
            return if (MimeTypes.isAudio(mime)) name + (channelLabel(channels, words)?.let { " $it" } ?: "") else name
        }

        /**
         * A codec's name as a reader knows it from its mime type — "audio/vnd.dts" → DTS, "audio/true-hd" →
         * Dolby TrueHD — for the track lists and the media info panel alike (the panel had its own, and
         * read VND.DTS).
         */
        internal fun codecName(mime: String?): String? {
            val raw = mime?.substringAfterLast('/')?.removePrefix("vnd.")?.removePrefix("x-")
                ?.substringBefore(';')?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
            return CODEC_NAMES[raw] ?: raw
        }

        private val CODEC_NAMES = mapOf(
            "MP4A-LATM" to "AAC", "AAC" to "AAC", "RAW" to "PCM", "MPEG" to "MP3",
            "AC3" to "Dolby AC-3", "EAC3" to "Dolby E-AC-3", "EAC3-JOC" to "Dolby Atmos",
            "TRUE-HD" to "Dolby TrueHD", "DTS" to "DTS", "DTS-HD" to "DTS-HD",
            "DTS-UHD" to "DTS:X",
            // Media3's own: audio/vnd.dts.hd (and its Express, ;profile=lbr), audio/vnd.dts.uhd (DTS:X).
            "DTS.HD" to "DTS-HD", "DTS.UHD" to "DTS:X", "OPUS" to "Opus", "FLAC" to "FLAC", "VORBIS" to "Vorbis",
            "SUBRIP" to "SRT", "SSA" to "ASS", "TTML+XML" to "TTML", "VTT" to "VTT",
            "PGS" to "PGS", "VOBSUB" to "VobSub", "DVBSUBS" to "DVB",
            "CEA-608" to "CC", "CEA-708" to "CC",
            // Pictures, by the names the 解码 line and mpv's panel give them (video/avc read "AVC High"
            // above "硬解 H.264"; VP9 came out as VND.ON2.VP9).
            "AVC" to "H.264", "HEVC" to "HEVC", "AV01" to "AV1", "VND.ON2.VP9" to "VP9", "VND.ON2.VP8" to "VP8",
            "DOLBY-VISION" to "Dolby Vision", "MPEG2" to "MPEG-2", "MP4V-ES" to "MPEG-4", "3GPP" to "H.263",
        )

        private fun sampleRateLabel(hz: Int): String =
            com.therealityteam.videoplayer.ui.common.kiloHertzText(hz)

        internal fun channelLabel(count: Int, words: Words): String? = when (count) {
            in Int.MIN_VALUE..0 -> null
            1 -> words.mono
            2 -> words.stereo
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            else -> "${count}ch"
        }

        /** A track's language as the reader names it: 日语 for a Chinese reader, Japanese for an English one. */
        internal fun languageName(code: String?, words: Words): String? {
            if (code.isNullOrBlank() || code == "und") return null
            // Cantonese by its own name (粤语 / Cantonese), not 「中文」: a Hong Kong film's two sound tracks are 粤语
            // and 国语, and read 「中文」 twice they could not be told apart.
            if (code.lowercase().let { it == "yue" || it == "zh-yue" || it.startsWith("yue-") }) {
                return Locale("yue").getDisplayLanguage(words.locale).takeIf { it.isNotBlank() && it != "yue" } ?: code
            }
            // The tags subtitle files carry ("chs&eng", "cht", "sc") and the codes streams do ("chi",
            // "zh-Hant") by the names the reader knows them by: 简体中文、英语 — a bilingual file both.
            val langs = SubtitleLanguage.ofTag(code)
            if (langs.isNotEmpty()) return langs.joinToString(words.languageJoin) { words.languageName(it) }
            val locale = Locale.forLanguageTag(code).takeIf { it.language.isNotBlank() }
                ?: Locale(code)
            return locale.getDisplayLanguage(words.locale).takeIf { it.isNotBlank() && it != code }
                ?.replaceFirstChar { it.titlecase(words.locale) }
                ?: code
        }
    }
}

private fun Cue.bitmapPlacement(): BitmapSubtitlePlacement? {
    if (bitmap == null || lineType != Cue.LINE_TYPE_FRACTION || line !in 0f..1f ||
        position !in 0f..1f || !size.isFinite() || size <= 0f
    ) return null
    fun anchor(type: Int) = when (type) {
        Cue.ANCHOR_TYPE_MIDDLE -> 0.5f
        Cue.ANCHOR_TYPE_END -> 1f
        else -> 0f
    }
    return BitmapSubtitlePlacement(
        position, line, size, bitmapHeight.takeIf { it.isFinite() && it > 0f },
        anchor(positionAnchor), anchor(lineAnchor),
    )
}

/** The track lists' and the info panel's words, read once from the app's resources. */
class Words(context: Context) {
    val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val unknownFormat: String = context.getString(R.string.pc_track_unknown_format)
    val unknownTrack: String = context.getString(R.string.pc_track_unknown)
    val externalSubtitles: String = context.getString(R.string.pc_track_external_sub)
    val external: String = context.getString(R.string.pc_track_external)
    val embedded: String = context.getString(R.string.pc_track_embedded)
    val mono: String = context.getString(R.string.pc_ch_mono)
    val stereo: String = context.getString(R.string.pc_ch_stereo)
    val hardware: String = context.getString(R.string.pc_dec_hardware)
    val software: String = context.getString(R.string.pc_dec_software)
    val audio: String = context.getString(R.string.pc_dec_audio)
    private val simplified: String = context.getString(R.string.pc_lang_simplified)
    private val traditional: String = context.getString(R.string.pc_lang_traditional)
    /** Between the languages of a bilingual track: "简体中文、英语". */
    val languageJoin: String = context.getString(R.string.pc_lang_join)
    private val trackN: String = context.getString(R.string.pc_track_n)
    private val framesFormat: String = context.getString(R.string.pc_frames)
    fun track(n: Int): String = String.format(locale, trackN, n)

    /** A language as the reader names it: 简体中文 and 繁体中文 by the app's words, the rest by the system's. */
    fun languageName(lang: TrackPreference.Lang): String = when (lang) {
        TrackPreference.Lang.SIMPLIFIED -> simplified
        TrackPreference.Lang.TRADITIONAL -> traditional
        TrackPreference.Lang.CHINESE -> Locale.CHINESE.getDisplayLanguage(locale)
        TrackPreference.Lang.ENGLISH -> Locale.ENGLISH.getDisplayLanguage(locale)
        TrackPreference.Lang.JAPANESE -> Locale.JAPANESE.getDisplayLanguage(locale)
        // The rest by the system's name for them, capitalised as a list item ("Allemand", "Немецкий").
        else -> Locale.forLanguageTag(lang.code).getDisplayLanguage(locale)
    }.replaceFirstChar { it.titlecase(locale) }
    fun frames(dropped: Int, rendered: Int): String = String.format(locale, framesFormat, dropped, rendered)
}
