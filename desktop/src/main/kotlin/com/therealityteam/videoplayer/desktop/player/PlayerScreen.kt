package com.therealityteam.videoplayer.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.ChevronsLeft
import com.composables.icons.lucide.ChevronsRight
import com.composables.icons.lucide.ListVideo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Minimize
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.SkipBack
import com.composables.icons.lucide.SkipForward
import com.composables.icons.lucide.Volume1
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.platform.Host
import com.therealityteam.videoplayer.desktop.ui.components.AuraSlider
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.IconButton
import com.therealityteam.videoplayer.desktop.ui.components.Spinner
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.images.Images
import com.therealityteam.videoplayer.desktop.ui.library.queueTitle
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

/**
 * The player, filling the window: the picture, and over it — when the pointer moves, when paused,
 * when a menu is open — the title bar and the controls. Everything the headset's bar does, laid
 * out for a mouse, with the keyboard shortcuts desktop players share.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    player: PlayerController,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
) {
    val s = LocalStrings.current
    val mp = player.player
    val state by mp.state.collectAsState()
    val position by mp.currentPositionMillis.collectAsState()
    val props by mp.mediaProperties.collectAsState()
    val duration = props?.durationMillis ?: 0L
    val bufferedFlow = remember(mp) { mp.features[Buffering]?.bufferedPositionMillis ?: kotlinx.coroutines.flow.MutableStateFlow(0L) }
    val buffered by bufferedFlow.collectAsState(0L)
    val settings by player.run { graphSettings() }.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menuOpen by remember { mutableStateOf<String?>(null) }
    var hoverBar by remember { mutableStateOf(false) }
    var osd by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        while (true) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val idle = now - lastMove > 2_800
    val showChrome = !state.isPlaying || menuOpen != null || hoverBar || !idle || player.error != null || player.upNextIn != null
    fun flash(text: String) { osd = text to System.currentTimeMillis() }
    // Subtitles clear the controls while they show and settle lower once they fade, as on the
    // headset: the bar's height less its fade above the seek bar, and a little air.
    var screenPx by remember { mutableIntStateOf(0) }
    var barPx by remember { mutableIntStateOf(0) }
    val fadePx = with(LocalDensity.current) { (BAR_FADE - 10.dp).toPx() }
    val lift by animateFloatAsState(if (showChrome && barPx > 0) (barPx - fadePx).coerceAtLeast(0f) else 0f, tween(220))
    LaunchedEffect(lift, screenPx) { player.liftSubtitles(lift, screenPx) }
    // The next file's picture, fetched well before the up-next card wants it.
    LaunchedEffect(player.current?.id) {
        delay(20_000)
        player.upNext()?.let { next -> runCatching { Images.still(next, 576, frames = true) } }
    }
    // A word from outside the player — the menu bar's — on the same line as the player's own.
    LaunchedEffect(player.notice) { player.notice?.let { flash(it.first) } }
    // Paused because the sound moved to the speakers: say so.
    LaunchedEffect(player.pausedForSpeakers) { if (player.pausedForSpeakers != null) flash(s["d_paused_speakers"]) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e -> handleKey(e, player, onToggleFullscreen, onExit, fullscreen, ::flash, s) }
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.first().scrollDelta.y
                if (dy != 0f && player.spherical) {
                    player.zoomView(dy.toDouble())
                } else if (dy != 0f) {
                    val v = (settings.volume - dy * 2f).coerceIn(0f, 100f)
                    player.setVolume(v)
                    flash(s["d_volume_n", v.toInt()])
                }
            }
            .onSizeChanged { player.setViewport(it.width, it.height); screenPx = it.height }
            .then(if (fullscreen && !showChrome) Modifier.pointerHoverIcon(HiddenCursor) else Modifier),
    ) {
        MpvMediampPlayerSurface(mp, Modifier.fillMaxSize())
        // Clicks on the picture: one pauses, two go full screen. In a spherical film a drag turns
        // the view, and the pointer says so.
        val spherical = player.spherical
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onToggleFullscreen() },
                    onTap = {
                        if (menuOpen != null) menuOpen = null else player.togglePause()
                        runCatching { focus.requestFocus() }
                    },
                )
            }.then(
                if (spherical) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            lastMove = System.currentTimeMillis()
                            player.look(drag.x.toDouble() / size.width, drag.y.toDouble() / size.height)
                        }
                    }.pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
                } else Modifier,
            ),
        )
        // How to look around, for the first moments of a spherical film.
        var hintFor by remember { mutableStateOf<String?>(null) }
        val itemId = player.current?.id
        LaunchedEffect(itemId, spherical) {
            if (spherical && itemId != null && hintFor != itemId) {
                hintFor = itemId
                flash(s["d_view_hint"])
            }
        }

        // Loading / buffering / errors, centred.
        val loading = state.isLoadingOrBuffering || state.mediaStatus == MediaStatus.Opening
        if (player.error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // A disc image says what it is (encrypted, no film) in the headset's words.
                val disc = player.discProblem
                Txt(
                    when (disc) {
                        MediaOpen.DiscProblem.Kind.ENCRYPTED -> s["vid_err_disc_encrypted"]
                        MediaOpen.DiscProblem.Kind.NO_FILM -> s["vid_err_disc_no_film"]
                        else -> s["video_failed"]
                    },
                    Aura.type.title2, Color.White,
                )
                Spacer(Modifier.height(8.dp))
                Txt(
                    when (disc) {
                        MediaOpen.DiscProblem.Kind.ENCRYPTED -> s["vid_err_disc_encrypted_body"]
                        MediaOpen.DiscProblem.Kind.NO_FILM -> s["vid_err_disc_no_film_body"]
                        MediaOpen.DiscProblem.Kind.UNREADABLE -> s["vid_err_unreachable_body"]
                        null -> player.error.orEmpty()
                    },
                    Aura.type.callout, Color(0xB3FFFFFF), maxLines = 4,
                )
            }
        } else if (player.noPicture != null) {
            // Sound over black: say why, rather than leave a black screen to puzzle over.
            Txt(
                s["vid_no_video_decoder", player.noPicture.orEmpty().ifEmpty { s["d_codec_unknown"] }], Aura.type.callout, Color(0xD9FFFFFF),
                Modifier.align(Alignment.Center).widthIn(max = 520.dp), maxLines = 4, align = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else if (loading) {
            Spinner(40.dp, Color.White, 3.dp, Modifier.align(Alignment.Center))
        }

        // The on-screen message: "Speed 1.5×", "Subtitle delay +0.5 s"…
        osd?.let { (text, at) ->
            if (now - at < 1_400) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 70.dp).clip(AuraShapes.pill)
                        .background(Color(0xCC101114)).padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Txt(text, Aura.type.bodyStrong, Color.White) }
            }
        }

        AnimatedVisibility(showChrome, enter = fadeIn(tween(160)), exit = fadeOut(tween(260)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(player, onExit, Modifier.align(Alignment.TopStart))
                ControlBar(
                    player = player,
                    positionMs = position,
                    durationMs = duration,
                    bufferedMs = buffered,
                    playing = state.playWhenReady,
                    fullscreen = fullscreen,
                    volume = settings.volume,
                    muted = settings.muted,
                    menuOpen = menuOpen,
                    onMenu = { menuOpen = if (menuOpen == it) null else it },
                    onCloseMenu = { menuOpen = null; runCatching { focus.requestFocus() } },
                    onHover = { hoverBar = it },
                    onToggleFullscreen = onToggleFullscreen,
                    onInfo = { infoOpen = !infoOpen },
                    onFlash = ::flash,
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { barPx = it.height },
                )
            }
        }

        if (player.audioOutputFailed) {
            val device = if (settings.audioDevice == "auto") s["d_audio_default_device"]
                else player.audioDevices.firstOrNull { it.first == settings.audioDevice }?.second ?: settings.audioDevice
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(AuraShapes.pill)
                    .background(Color(0xE6181A1F)).border(1.dp, Color(0x33FF6166), AuraShapes.pill)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.VolumeX, Aura.colors.danger, 16.dp)
                Spacer(Modifier.width(8.dp))
                Txt(s["d_audio_failed", device], Aura.type.callout, Color.White, maxLines = 2)
                Spacer(Modifier.width(10.dp))
                Txt(
                    s["d_choose_output"], Aura.type.callout.copy(fontWeight = FontWeight.SemiBold), Aura.colors.accent,
                    Modifier.clickCursor().pointerInput(Unit) { detectTapGestures { menuOpen = "audio" } },
                )
            }
        }
        if (infoOpen) MediaInfoPanel(player, onClose = { infoOpen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 20.dp))
        ResumeToast(player, Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 132.dp))
        PanoGuessToast(player, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
        UpNextCard(player, Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 132.dp))
    }
}

private val HiddenCursor: PointerIcon by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
}

@Composable
private fun TopBar(player: PlayerController, onExit: () -> Unit, modifier: Modifier) {
    val s = LocalStrings.current
    val item = player.current
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
            .padding(start = if (Host.isMac) 80.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(Lucide.ArrowLeft, s["d_go_back"], onExit, tint = Color.White, onPicture = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(player.cardTitle, Aura.type.headline, Color.White)
            if (item != null && player.queue.size > 1) {
                Txt(queueTitle(s, item) + "  ·  " + item.fileName, Aura.type.caption, Color(0xB3FFFFFF))
            } else if (item != null) {
                Txt(item.fileName, Aura.type.caption, Color(0x99FFFFFF))
            }
        }
    }
}

@Composable
private fun ControlBar(
    player: PlayerController,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    playing: Boolean,
    fullscreen: Boolean,
    volume: Float,
    muted: Boolean,
    menuOpen: String?,
    onMenu: (String) -> Unit,
    onCloseMenu: () -> Unit,
    onHover: (Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
    onInfo: () -> Unit,
    onFlash: (String) -> Unit,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    val white = Color.White
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
            .onHoverChange(onHover)
            .padding(start = 24.dp, end = 24.dp, top = BAR_FADE, bottom = 16.dp),
    ) {
        SeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            chapters = player.chapters,
            onSeek = { player.seekTo(it) },
            chapterTitle = { ms -> player.chapters.getOrNull(player.currentChapter(ms))?.title },
            preview = { ms, w, h -> player.previewFrame(ms, w, h) },
            previewAspect = player.videoAspect(),
            positionLabel = s["d_a11y_position"],
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(formatTime(positionMs), Aura.type.caption, white.copy(alpha = 0.85f))
            Spacer(Modifier.weight(1f))
            Txt(if (durationMs > 0) "-" + formatTime(durationMs - positionMs) else "", Aura.type.caption, white.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Lucide.SkipBack, s["pc_previous"], { player.prev() }, tint = white, onPicture = true, enabled = true)
            IconButton(Lucide.ChevronsLeft, s["d_back_n", player.seekStep()], { player.skip(-player.seekStep() * 1000L) }, tint = white, onPicture = true)
            Box(
                Modifier.padding(horizontal = 6.dp).size(46.dp).clip(CircleShape).background(Color(0x26FFFFFF))
                    .clickCursor()
                    .pointerInput(Unit) { detectTapGestures { player.togglePause() } },
                contentAlignment = Alignment.Center,
            ) { Icon(if (playing) Lucide.Pause else Lucide.Play, white, 22.dp) }
            IconButton(Lucide.ChevronsRight, s["d_forward_n", player.seekStep()], { player.skip(player.seekStep() * 1000L) }, tint = white, onPicture = true)
            IconButton(Lucide.SkipForward, s["pc_next"], { player.next() }, tint = white, onPicture = true, enabled = player.hasNext)
            Spacer(Modifier.width(14.dp))
            IconButton(
                when {
                    muted || volume <= 0f -> Lucide.VolumeX
                    volume < 50f -> Lucide.Volume1
                    else -> Lucide.Volume2
                },
                s["d_mute"], { player.toggleMute() }, tint = white, onPicture = true,
            )
            AuraSlider(
                value = if (muted) 0f else volume,
                onChange = { player.setVolume(it) },
                range = 0f..100f,
                modifier = Modifier.width(96.dp),
                label = s["d_key_volume"],
                trackHeight = 3.dp,
                knobSize = 11.dp,
                activeColor = white,
                trackColor = Color(0x40FFFFFF),
            )
            Spacer(Modifier.weight(1f))
            if (player.speed != 1f) {
                Txt("${fmtSpeed(player.speed)}×", Aura.type.caption.copy(fontWeight = FontWeight.SemiBold), white, Modifier.padding(end = 8.dp))
            }
            MenuButton("subs", Lucide.Captions, s["pc_subtitles"], menuOpen, onMenu) {
                SubtitlePanel(player, onCloseMenu)
            }
            MenuButton("audio", Lucide.AudioLines, s["pc_audio"], menuOpen, onMenu) {
                AudioPanel(player, onCloseMenu)
            }
            if (player.queue.size > 1) {
                MenuButton("queue", Lucide.ListVideo, s["pc_queue"], menuOpen, onMenu, width = 420.dp) {
                    QueuePanel(player, onCloseMenu)
                }
            }
            MenuButton("more", Lucide.Settings2, s["d_player_settings"], menuOpen, onMenu, width = 360.dp) {
                SettingsPanel(player, onCloseMenu, onInfo, onFlash)
            }
            IconButton(
                if (fullscreen) Lucide.Minimize else Lucide.Maximize,
                s[if (fullscreen) "d_exit_fullscreen" else "d_fullscreen"],
                onToggleFullscreen, tint = white, onPicture = true,
            )
        }
    }
}

@Composable
private fun MenuButton(
    id: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    open: String?,
    onMenu: (String) -> Unit,
    width: androidx.compose.ui.unit.Dp = 380.dp,
    panel: @Composable () -> Unit,
) {
    Box {
        IconButton(icon, if (open == id) null else tooltip, { onMenu(id) }, tint = Color.White, onPicture = true, selected = open == id, description = tooltip)
        com.therealityteam.videoplayer.desktop.ui.components.Popover(
            expanded = open == id,
            onDismiss = { onMenu(id) },
            above = true,
            alignEnd = true,
            width = width,
        ) { panel() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onHoverChange(onHover: (Boolean) -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Enter) { onHover(true) }
        .onPointerEvent(PointerEventType.Exit) { onHover(false) }

fun fmtSpeed(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

@Composable
private fun ResumeToast(player: PlayerController, modifier: Modifier) {
    val s = LocalStrings.current
    val from = player.resumedFromMs ?: return
    LaunchedEffect(from, player.current?.id) {
        delay(8_000)
        player.resumedFromMs = null
    }
    Row(
        modifier.clip(AuraShapes.pill).background(Color(0xE6181A1F)).border(1.dp, Color(0x22FFFFFF), AuraShapes.pill)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(s["d_resumed_from", formatTime(from)], Aura.type.callout, Color.White)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(AuraShapes.pill).background(Color(0x26FFFFFF)).clickCursor()
                .pointerInput(Unit) { detectTapGestures { player.seekTo(0); player.resumedFromMs = null } }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) { Txt(s["d_from_start"], Aura.type.callout.copy(fontWeight = FontWeight.SemiBold), Color.White) }
    }
}

/**
 * The picture looks like a panorama nothing said it was (PlayerController.panoGuess): 全景播放 takes it as
 * one, 不是 leaves it flat and is remembered — the headset's question, in its words.
 */
@Composable
private fun PanoGuessToast(player: PlayerController, modifier: Modifier) {
    val s = LocalStrings.current
    val guess = player.panoGuess ?: return
    val label = when (guess.projection) {
        com.therealityteam.videoplayer.data.model.ProjectionMode.DUAL_FISHEYE -> "vid_pano_guess_dual"
        com.therealityteam.videoplayer.data.model.ProjectionMode.FISHEYE ->
            if (guess.stereo == com.therealityteam.videoplayer.data.model.StereoMode.MONO) "vid_pano_guess_fisheye" else "vid_pano_guess_fisheye_3d"
        com.therealityteam.videoplayer.data.model.ProjectionMode.EQ180 -> "vid_pano_guess_180_3d"
        else -> if (guess.stereo == com.therealityteam.videoplayer.data.model.StereoMode.MONO) "vid_pano_guess_360" else "vid_pano_guess_360_3d"
    }
    Row(
        modifier.clip(AuraShapes.pill).background(Color(0xE6181A1F)).border(1.dp, Color(0x22FFFFFF), AuraShapes.pill)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(s[label], Aura.type.callout, Color.White)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.clip(AuraShapes.pill).background(Color(0x40FFFFFF)).clickCursor()
                .pointerInput(Unit) { detectTapGestures { player.acceptPanoGuess() } }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) { Txt(s["vid_pano_guess_play"], Aura.type.callout.copy(fontWeight = FontWeight.SemiBold), Color.White) }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(AuraShapes.pill).clickCursor()
                .pointerInput(Unit) { detectTapGestures { player.dismissPanoGuess() } }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) { Txt(s["vid_pano_guess_no"], Aura.type.callout, Color(0xCCFFFFFF)) }
    }
}

@Composable
private fun UpNextCard(player: PlayerController, modifier: Modifier) {
    val s = LocalStrings.current
    val left = player.upNextIn ?: return
    val next = player.upNext() ?: return
    Column(
        modifier.width(320.dp).shadow(20.dp, AuraShapes.panel).clip(AuraShapes.panel).background(Color(0xF0181A1F))
            .border(1.dp, Color(0x22FFFFFF), AuraShapes.panel).padding(16.dp),
    ) {
        // Its picture: the episode's still, else a frame of it (fetched ahead, see PlayerScreen).
        val px = with(LocalDensity.current) { 288.dp.roundToPx() }
        val picture by produceState<ImageBitmap?>(null, next.id) { value = Images.still(next, px, frames = true) }
        picture?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(AuraShapes.small))
            Spacer(Modifier.height(12.dp))
        }
        Txt(s["cw_next_up"], Aura.type.micro, Color(0x99FFFFFF))
        Spacer(Modifier.height(4.dp))
        Txt(queueTitle(s, next), Aura.type.headline, Color.White)
        Txt(next.fileName, Aura.type.caption, Color(0x99FFFFFF))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(AuraShapes.control).background(Aura.colors.accent).clickCursor()
                    .pointerInput(Unit) { detectTapGestures { player.playUpNextNow() } }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Txt(s["d_play_now_n", left], Aura.type.callout.copy(fontWeight = FontWeight.SemiBold), Color.White) }
            Box(
                Modifier.clip(AuraShapes.control).background(Color(0x26FFFFFF)).clickCursor()
                    .pointerInput(Unit) { detectTapGestures { player.cancelUpNext() } }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Txt(s["cancel"], Aura.type.callout, Color.White) }
        }
    }
}

@Composable
private fun MediaInfoPanel(player: PlayerController, onClose: () -> Unit, modifier: Modifier) {
    val s = LocalStrings.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val rows = remember(tick, s) { player.mediaInfo(s) }
    Column(
        modifier.width(360.dp).clip(AuraShapes.panel).background(Color(0xE6101114)).border(1.dp, Color(0x22FFFFFF), AuraShapes.panel)
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .padding(16.dp),
    ) {
        Txt(s["pc_media_info"], Aura.type.headline, Color.White)
        Spacer(Modifier.height(8.dp))
        rows.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Txt(s["pc_info_$k"], Aura.type.caption, Color(0x99FFFFFF), Modifier.width(72.dp))
                Txt(v, Aura.type.caption, Color.White, maxLines = 3)
            }
        }
    }
}

/** Keys, in the conventions desktop players share. Returns true when the key was ours. */
private fun handleKey(
    e: KeyEvent,
    player: PlayerController,
    onToggleFullscreen: () -> Unit,
    onExit: () -> Unit,
    fullscreen: Boolean,
    flash: (String) -> Unit,
    s: com.therealityteam.videoplayer.desktop.i18n.Strings,
): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val cmd = if (Host.isMac) e.isMetaPressed else e.isCtrlPressed
    val step = player.seekStep() * 1000L
    // ⌥ + arrows turn a spherical film's view, a twelfth of a turn at a time; ⌥ + = / - zoom.
    if (e.isAltPressed && player.spherical) {
        val turn = Math.toRadians(30.0) / player.view.fov
        when (e.key) {
            Key.DirectionLeft -> { player.look(-turn, 0.0); return true }
            Key.DirectionRight -> { player.look(turn, 0.0); return true }
            Key.DirectionUp -> { player.look(0.0, 0.3); return true }
            Key.DirectionDown -> { player.look(0.0, -0.3); return true }
            Key.Equals -> { player.zoomView(-2.0); return true }
            Key.Minus -> { player.zoomView(2.0); return true }
            Key.Zero -> { player.resetView(); return true }
            else -> {}
        }
    }
    when (e.key) {
        Key.Spacebar, Key.K -> player.togglePause()
        Key.DirectionLeft, Key.J -> { player.skip(if (e.isShiftPressed) -60_000 else -step); return true }
        Key.DirectionRight, Key.L -> { player.skip(if (e.isShiftPressed) 60_000 else step); return true }
        Key.DirectionUp -> { val v = player.volume() + 5f; player.setVolume(v); flash(s["d_volume_n", v.coerceAtMost(100f).toInt()]) }
        Key.DirectionDown -> { val v = player.volume() - 5f; player.setVolume(v); flash(s["d_volume_n", v.coerceAtLeast(0f).toInt()]) }
        Key.M -> player.toggleMute()
        Key.F -> if (!cmd) onToggleFullscreen() else return false
        Key.Escape -> if (fullscreen) onToggleFullscreen() else onExit()
        Key.N -> player.next()
        Key.P -> player.prev()
        Key.PageDown -> player.nextChapter()
        Key.PageUp -> player.prevChapter()
        Key.LeftBracket -> { val v = (player.speed - 0.25f).coerceAtLeast(0.25f); player.changeSpeed(v); flash(s["d_speed_n", fmtSpeed(v)]) }
        Key.RightBracket -> { val v = (player.speed + 0.25f).coerceAtMost(4f); player.changeSpeed(v); flash(s["d_speed_n", fmtSpeed(v)]) }
        Key.Backspace -> { player.changeSpeed(1f); flash(s["d_speed_n", "1"]) }
        Key.Z -> { player.setSubtitleDelay(player.subtitleDelayMs - 100); flash(s["d_sub_delay_n", delayText(player.subtitleDelayMs)]) }
        Key.X -> { player.setSubtitleDelay(player.subtitleDelayMs + 100); flash(s["d_sub_delay_n", delayText(player.subtitleDelayMs)]) }
        Key.S -> if (cmd && e.isShiftPressed) return false else cycleSubtitle(player, flash, s)
        Key.A -> cycleAudio(player, flash, s)
        Key.B -> player.cycleAb()
        // 逐帧, as mpv's own keys: , back and . on (⌘, is 设置).
        Key.Comma -> if (!cmd) { player.stepFrame(forward = false); flash(s["pc_frame_back"]) } else return false
        Key.Period -> if (!cmd) { player.stepFrame(forward = true); flash(s["pc_frame_forward"]) } else return false
        else -> return false
    }
    return true
}

fun delayText(ms: Long): String = (if (ms > 0) "+" else "") + "%.1f".format(ms / 1000.0) + " s"

private fun cycleSubtitle(player: PlayerController, flash: (String) -> Unit, s: com.therealityteam.videoplayer.desktop.i18n.Strings) {
    val tracks = player.subtitleTracks
    if (tracks.isEmpty()) return
    val i = tracks.indexOfFirst { it.selected }
    val next = if (i + 1 >= tracks.size) null else tracks[i + 1]
    player.selectSubtitle(next?.id)
    flash(if (next == null) s["d_subtitles_off"] else s["d_subtitle_n", trackName(next, s)])
}

private fun cycleAudio(player: PlayerController, flash: (String) -> Unit, s: com.therealityteam.videoplayer.desktop.i18n.Strings) {
    val tracks = player.audioTracks
    if (tracks.size < 2) return
    val i = tracks.indexOfFirst { it.selected }
    val next = tracks[(i + 1) % tracks.size]
    player.selectAudio(next.id)
    flash(s["d_audio_n", trackName(next, s)])
}

/** The control bar's darkening above its seek bar. */
private val BAR_FADE = 48.dp
