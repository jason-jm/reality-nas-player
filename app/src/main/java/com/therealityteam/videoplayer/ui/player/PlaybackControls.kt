package com.therealityteam.videoplayer.ui.player

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.Layout
import com.pico.spatial.ui.design.RemovableChip
import com.pico.spatial.ui.design.ToggleableChip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.DotBadge
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.LocalContentColor
import com.pico.spatial.ui.design.LocalDisableAlpha
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Stepper
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleIconButton
import com.pico.spatial.ui.design.ToggleIconButtonDefaults
import com.pico.spatial.ui.design.VerticalDivider
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.MenuItemDefaults
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.SpatialPopup
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import com.pico.spatial.ui.design.windows.rememberSpatialPopupPositionProvider
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.tooltip
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.pico.spatial.ui.graphics.TooltipDirection
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.data.model.ProjectionMode
import com.therealityteam.videoplayer.data.model.StereoMode
import com.therealityteam.videoplayer.data.playback.AbRepeat
import com.therealityteam.videoplayer.data.playback.AspectMode
import com.therealityteam.videoplayer.data.playback.AudioGain
import com.therealityteam.videoplayer.data.playback.Chapter
import com.therealityteam.videoplayer.data.playback.MediaTrack
import com.therealityteam.videoplayer.data.playback.PlaylistMode
import com.therealityteam.videoplayer.data.playback.PlaylistNav
import com.therealityteam.videoplayer.data.playback.SleepMode
import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import com.therealityteam.videoplayer.data.playback.indexAt
import com.therealityteam.videoplayer.platform.DeepLink
import com.therealityteam.videoplayer.data.repository.SkipStore
import com.therealityteam.videoplayer.player.Environment
import com.therealityteam.videoplayer.player.Environments
import com.therealityteam.videoplayer.player.ViewingSeat
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import java.util.Locale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import com.therealityteam.videoplayer.ui.library.EpisodeNaming
import com.therealityteam.videoplayer.ui.library.label
import kotlin.math.roundToInt

/**
 * Floating capsule playback bar — built entirely from PICO Spatial UI (IconButton / ToggleIconButton
 * / Slider / Menu) on the window's system Toolbar, Vibrant on.
 *
 * Row 1: the title and the episode … position / length. Row 2: the seek bar, with the chapters and an
 * A–B loop marked on it. Row 3: back | prev · −15s · play/pause · +15s · next … | 字幕轨道 · 字幕设置 ·
 * 音轨 · 音频设置 · 播放列表 · 播放 · 显示 · 更多. Every button names itself in a tooltip; a menu's
 * button is lit while its panel is open and carries a dot while that panel holds something other than
 * its default — a delay, a boost, a loop, a timer — so the bar says what is set without opening it.
 */
@Composable
fun PlaybackControls(
    vm: PlaybackViewModel,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    immersive: Boolean,
    cinemaDim: Boolean,
    environment: Environment?,
    onToggleImmersive: () -> Unit,
    onToggleCinema: () -> Unit,
    onSelectEnvironment: (Environment?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onHeightChanged: (Dp) -> Unit = {},
    canCollapse: Boolean = false,
) {
    val current by vm.current.collectAsState()
    val aspectMode by vm.aspectMode.collectAsState()
    val chapters by vm.chapters.collectAsState()
    // 虚拟环境's pictures, read once off the main thread before its menu is first opened.
    val appContext = LocalContext.current
    LaunchedEffect(Unit) { EnvironmentThumbs.preload(appContext) }
    val bookmarkMarks by vm.bookmarks.collectAsState()
    // Automation (`-e aura.skip 90000,105000`): set the show's opening end and closing length (ms).
    val skipRequest by DeepLink.skipRequest.collectAsState()
    LaunchedEffect(skipRequest, vm.current.collectAsState().value?.id) {
        val spec = skipRequest ?: return@LaunchedEffect
        if (vm.current.value == null) return@LaunchedEffect
        val parts = spec.split(',').map { it.trim().toLongOrNull()?.takeIf { v -> v > 0 } }
        vm.setSkipMarks(parts.getOrNull(0), parts.getOrNull(1))
        DeepLink.skipRequest.value = null
    }
    // Automation (`-e aura.bookmarks 60000,300000`): mark those moments once a file is playing.
    val bookmarksRequest by DeepLink.bookmarksRequest.collectAsState()
    val playing by vm.current.collectAsState()
    LaunchedEffect(bookmarksRequest, playing?.id) {
        val spec = bookmarksRequest ?: return@LaunchedEffect
        if (playing == null) return@LaunchedEffect
        spec.split(',').mapNotNull { it.trim().toLongOrNull() }.forEach { vm.addBookmarkAt(it) }
        DeepLink.bookmarksRequest.value = null
    }
    val markA by vm.repeatA.collectAsState()
    val markB by vm.repeatB.collectAsState()
    // Prev/next read the queue and its mode as state: vm.hasPrev()/hasNext() read flow values the
    // composition never subscribed to, so a 循环方式 change while paused left 下一个 dimmed.
    val loopMode by vm.playlistMode.collectAsState()
    val queue by vm.queue.collectAsState()
    val playingIndex = queue.indexOfFirst { it.id == current?.id }
    val hasPrev = PlaylistNav.hasPrev(loopMode, playingIndex, queue.size)
    val hasNext = PlaylistNav.hasNext(loopMode, playingIndex, queue.size)
    val density = LocalDensity.current

    var seeking by remember { mutableStateOf<Float?>(null) }
    val fraction = seeking ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPosition = (fraction * duration).toLong()
    // 拖动预览: each point of a drag goes to the engine as it comes — the picture follows the hand —
    // and the release lands on the last one.
    val onScrub: (Float) -> Unit = { f ->
        seeking = f
        vm.scrubTo((f * duration).toLong())
    }
    val onScrubEnd: () -> Unit = {
        seeking?.let { vm.endScrub((it * duration).toLong()) }
        seeking = null
    }
    // The bar stays up while a drag is held, however long the hand rests on one frame.
    val scrubbing = seeking != null
    DisposableEffect(scrubbing) {
        if (scrubbing) vm.holdControls(true)
        onDispose { if (scrubbing) vm.holdControls(false) }
    }
    // A bar that goes mid-drag (the window closing under the hand) lets go where the drag was: the
    // engine must not be left holding the film in its scrubbing mode.
    val dragAt by rememberUpdatedState(seeking?.let { (it * duration).toLong() })
    DisposableEffect(Unit) { onDispose { dragAt?.let { vm.endScrub(it) } } }
    // Automation (`-e aura.scrub 0.2,0.5,0.8`): drag the seek bar through those points as a hand would
    // — the slider's own callbacks, so the picture follows and the tip rides along — holding the last
    // for a few seconds before letting go, for screenshots. The slider is a spatial control `input`
    // cannot reach.
    val scrubRequest by DeepLink.scrubRequest.collectAsState()
    LaunchedEffect(scrubRequest, duration > 0) {
        val spec = scrubRequest ?: return@LaunchedEffect
        if (duration <= 0) return@LaunchedEffect
        val points = spec.split(',').mapNotNull { it.trim().toFloatOrNull()?.coerceIn(0f, 1f) }
        points.forEachIndexed { i, f ->
            onScrub(f)
            kotlinx.coroutines.delay(if (i == points.lastIndex) SCRUB_HOOK_HOLD_MS else SCRUB_HOOK_STEP_MS)
        }
        onScrubEnd()
        // Consumed only now: clearing it re-keys this effect, which would cut the drag short.
        DeepLink.scrubRequest.value = null
    }

    // No background here — the Toolbar container provides the glass panel. A press or release
    // anywhere on the bar — the gaps between the buttons are the bar too — restarts the auto-hide
    // timer, and that is all a press does: it used to mark the bar as hovered as well, and as this
    // platform never delivers the matching Exit (hover lives in the system process), one touch of the
    // bar kept it up for the rest of the film. A pointer merely RESTING on the bar is still asked to
    // hold it up (Enter, and a Move with nothing pressed), for the platform that delivers those.
    //
    // Three rows: what is playing, the seek bar end to end (a film's timeline deserves the width —
    // chapter ticks and fine scrubbing both need it), then the transport at the start and the menus
    // at the end. Its measured height tells the window how much of its bottom the bar covers.
    val geometry = remember { BarGeometry() }
    CompositionLocalProvider(LocalBarGeometry provides geometry) {
        Column(
            modifier = modifier
                .onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }) }
                // The bar's outer edge, for the panels that open above it (see panelLift).
                .onGloballyPositioned { geometry.barTop = it.boundsInWindow().top }
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                PointerEventType.Enter -> vm.setControlsHovered(true)
                                PointerEventType.Exit -> vm.setControlsHovered(false)
                                PointerEventType.Move ->
                                    if (event.changes.any { it.pressed }) vm.touchControls() else vm.setControlsHovered(true)
                                PointerEventType.Press, PointerEventType.Release -> vm.touchControls()
                                else -> Unit
                            }
                        }
                    }
                },
        ) {
            // The bar leaving the screen takes the pointer off it, whatever Compose last reported.
            DisposableEffect(Unit) { onDispose { vm.setControlsHovered(false) } }
            // What is playing and where in it: the title and the episode at the start, the time at the end.
            // The bar is the one piece of the player in front of the picture, so it carries the name.
            val playing = current
            // While the seek bar is dragged the row gives way to the tip riding over the thumb: where
            // the drag is, in time and chapter, read where the eye already is.
            val titleAlpha by animateFloatAsState(if (scrubbing) 0f else 1f, tween(150), label = "barTitle")
            Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).alpha(titleAlpha),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playing?.title?.ifBlank { playing.fileName }.orEmpty(),
                        style = PicoTheme.typography.titleSmall,
                        color = PicoTheme.colorScheme.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val told by vm.told.collectAsState()
                    val run by vm.numberedRun.collectAsState()
                    playing?.let { barSubtitle(LocalContext.current, it, told[it.id]?.name, run) }?.let {
                        Text(
                            text = it,
                            style = PicoTheme.typography.bodySmall,
                            color = PicoTheme.colorScheme.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Tabular figures: proportional digits changed the time's width every second, and the
                // title's ellipsis beside it jittered with them.
                // …and a tap on it turns what follows the position: the length, the time left, or the
                // clock it ends at (at the speed it plays) — the last a film's question at 11 pm.
                val barTime by vm.barTime.collectAsState()
                val speed by vm.speed.collectAsState()
                val timeTap = remember { MutableInteractionSource() }
                Text(
                    text = barTimeText(LocalContext.current, barTime, shownPosition, duration, speed),
                    style = PicoTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = PicoTheme.colorScheme.labelSecondary,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .tooltip(text = stringResource(R.string.pc_time_toggle), direction = TooltipDirection.Top)
                        .clip(RoundedCornerShape(8.dp))
                        .spatialHoverEffect()
                        .clickable(interactionSource = timeTap, indication = LocalIndication.current, role = Role.Button) { vm.cycleBarTime() }
                        .controllerHapticFeedback(interactionSource = timeTap)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            seeking?.let { f -> ScrubTip(f, duration, chapters, Modifier.matchParentSize()) }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = SEEK_SIDE_PAD)) {
                val seekName = stringResource(R.string.pc_seek_bar)
                Slider(
                    value = fraction,
                    onValueChange = onScrub,
                    onValueChangeFinished = onScrubEnd,
                    valueRange = 0f..1f,
                    // Nothing to seek in until the length is known: a drag while loading went to 0.
                    enabled = duration > 0,
                    // Named, and where it stands said in time: read off the headset it was a nameless seek bar
                    // at "13 %" beneath a box with no part in it. One node now, with the slider's own range and
                    // its set-a-position action carried over.
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = seekName
                        stateDescription = "${formatTime(shownPosition)} / ${formatTime(duration)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        if (duration > 0) {
                            setProgress { to -> onScrub(to.coerceIn(0f, 1f)); onScrubEnd(); true }
                        } else {
                            disabled()
                        }
                    },
                )
                if (duration > 0) SeekMarks(chapters, markA, markB, duration, Modifier.matchParentSize(), bookmarks = bookmarkMarks.map { it.positionMs })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BAR_BUTTON_GAP),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BarButton(
                    if (canCollapse) R.drawable.ic_chevron_down else R.drawable.ic_pc_back,
                    stringResource(if (canCollapse) R.string.nav_more_collapse else R.string.pc_back),
                    onClick = onBack,
                )
                BarDivider()
                BarButton(R.drawable.ic_pc_prev, stringResource(R.string.pc_previous), onClick = { vm.playPrev() }, enabled = hasPrev)
                // The bar's ±15s take the controller's path: clamped to the film, so a jump past the end
                // no longer ends it.
                BarButton(R.drawable.ic_pc_rewind, stringResource(R.string.pc_rewind_15), onClick = { vm.seekBy(-SEEK_STEP) })
                PlayPauseButton(isPlaying, onClick = { vm.manager.togglePlayPause() })
                BarButton(R.drawable.ic_pc_forward, stringResource(R.string.pc_forward_15), onClick = { vm.seekBy(SEEK_STEP) })
                BarButton(R.drawable.ic_pc_next, stringResource(R.string.pc_next), onClick = { vm.playNext() }, enabled = hasNext)

                Spacer(Modifier.weight(1f))
                // The transport ends here; what follows chooses and tunes.
                BarDivider()

                // The entries: choosing a track and tuning it pull in opposite directions (a list
                // wants to be long; settings want cards and tiles), so each of subtitles and sound
                // has a list entry and a settings entry, then the queue (播放列表), how it plays
                // (播放), how it's shown (显示), and the rest (更多).
                SubtitleTracksMenu(vm)
                SubtitleSettingsMenu(vm)
                AudioTracksMenu(vm)
                AudioSettingsMenu(vm)
                PlaylistMenu(vm)
                PlaybackMenu(vm)
                // What the picture is shown as: the file's own word (its metadata) counts, not only the name's.
                val pano by vm.pano.collectAsState()
                DisplayMenu(
                    onHold = { vm.holdControls(it) },
                    stereo = pano?.stereo ?: current?.video?.stereo ?: StereoMode.MONO,
                    projection = pano?.projection ?: ProjectionMode.FLAT,
                    onSelectFormat = { s, p -> vm.setVideoMode(s, p) },
                    pano = pano,
                    onSelectPano = { p, lens -> if (lens == OWN_MESH) vm.clearPanoFormat() else vm.setPanoFormat(p, lens) },
                    onTurnPano = { vm.turnPano(it) },
                    onRecenter = { vm.recenterPano() },
                    onLevel = { pitch, roll -> vm.nudgeLevel(pitch, roll) },
                    onLevelReset = { vm.resetLevel() },
                    remoteAtEyeLevel = vm.remoteAtEyeLevel.collectAsState().value,
                    onRemoteHeight = vm::setRemoteAtEyeLevel,
                    fileMesh = remember(pano) { vm.fileHasMesh },
                    aspectMode = aspectMode,
                    barsCropped = vm.frameCrop.collectAsState().value.crops,
                    onSelectAspect = { vm.setAspectMode(it) },
                    immersive = immersive,
                    cinemaDim = cinemaDim,
                    environment = environment,
                    shownPlace = vm.placeShown.collectAsState().value,
                    seat = vm.environmentScene.viewingSeat.collectAsState().value,
                    onSeat = vm::setViewingSeat,
                    onToggleImmersive = onToggleImmersive,
                    onToggleCinema = onToggleCinema,
                    onSelectEnvironment = onSelectEnvironment,
                    dimEnvironment = vm.dimEnvironment.collectAsState().value,
                    onDimEnvironment = { vm.setDimEnvironment(it) },
                    viewingLightPercent = vm.viewingLightPercent.collectAsState().value,
                    onViewingLightChange = vm::setViewingLightPercent,
                    screenPlay = vm.screenPlay.collectAsState().value,
                    onScreenPlay = { vm.setScreenPlay(it) },
                    screenGlow = vm.screenGlow.collectAsState().value,
                    onScreenGlow = vm::setScreenGlow,
                    roomLights = vm.roomLights.collectAsState().value
                        .takeIf { vm.environmentScene.hasLights.collectAsState().value },
                    onRoomLights = { vm.setRoomLights(it) },
                    placeLoading = vm.placeLoading.collectAsState().value,
                    onCancelRoom = vm::cancelRoomChange,
                )
                MoreMenu(vm)
            }
        }
    }
}

/** A bar glyph's size inside its 48dp button. */
private val BAR_GLYPH = 22.dp

/**
 * A bar button: PICO's background-less icon button, as the Toolbar sample builds them. The Toolbar
 * is light system glass, so the glyph is the scheme's primary label (dark), and a disabled one fades
 * by the system's own disabled treatment. Its name shows above it on hover — the bar hugs the
 * window's bottom, so the tooltip opens upwards.
 */
@Composable
private fun BarButton(resId: Int, desc: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.tooltip(text = desc, direction = TooltipDirection.Top),
        enabled = enabled,
        size = IconButtonDefaults.Regular,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        Icon(painter = painterResource(resId), contentDescription = desc, modifier = Modifier.size(BAR_GLYPH))
    }
}

/** Play / pause — PICO's primary icon button (the filled one) at its largest, the bar's one emphasis. */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val desc = stringResource(if (isPlaying) R.string.pc_pause else R.string.pc_play)
    IconButton(
        onClick = onClick,
        modifier = Modifier.tooltip(text = desc, direction = TooltipDirection.Top),
        size = IconButtonDefaults.Max,
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_pc_pause else R.drawable.ic_pc_play),
            contentDescription = desc,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun BarDivider() {
    VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 8.dp))
}

/**
 * A menu's button on the bar: lit — PICO's checked toggle fill — while its panel is open, so the
 * panel above has a visible owner, and dotted while that panel holds a value other than its default.
 */
@Composable
private fun BarMenuButton(
    resId: Int,
    desc: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    marked: Boolean = false,
) {
    val colors = PicoTheme.colorScheme
    val geometry = LocalBarGeometry.current
    Box(modifier = Modifier.onGloballyPositioned { geometry.buttonTop = it.boundsInWindow().top }) {
        ToggleIconButton(
            checked = open,
            onCheckedChange = onOpenChange,
            modifier = Modifier.tooltip(text = desc, direction = TooltipDirection.Top),
            enabled = enabled,
            size = ToggleIconButtonDefaults.Regular,
            colors = ToggleIconButtonDefaults.toggleIconButtonColors(
                checkedContainerColor = colors.fillSecondary,
                checkedContentColor = colors.labelPrimary,
                uncheckedContainerColor = Color.Transparent,
                uncheckedContentColor = colors.labelPrimary,
            ),
        ) {
            Icon(painter = painterResource(resId), contentDescription = desc, modifier = Modifier.size(BAR_GLYPH))
        }
        if (marked) {
            DotBadge(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp),
                color = colors.interaction,
            )
        }
    }
}

/**
 * The Regular slider (SliderDefaults.Regular: a 32dp thumb area, a 24dp track) — its thumb travels
 * between insets of half the thumb area, so a mark at fraction f sits at 16dp + f · (width − 32dp).
 */
private val SEEK_INSET = 16.dp

/** The seek bar's own inset inside the bar: the slider sits in a Box padded this much at each side. */
private val SEEK_SIDE_PAD = 6.dp

/** `aura.scrub`: how long each point of the drag is passed through, and how long the last is held. */
private const val SCRUB_HOOK_STEP_MS = 900L
private const val SCRUB_HOOK_HOLD_MS = 6_000L

/**
 * 拖动预览's tip: while the seek bar is dragged, where the thumb is — the time, and the chapter when
 * the file has marks — on a dark capsule riding over the thumb in the title row's place. The picture
 * itself shows the frame there (the engine follows the drag). Kept inside the bar's ends.
 */
@Composable
private fun ScrubTip(fraction: Float, durationMs: Long, chapters: List<Chapter>, modifier: Modifier) {
    val ms = (fraction * durationMs).toLong()
    val at = chapters.indexAt(ms)
    val chapter = if (chapters.size > 1) chapters.getOrNull(at) else null
    val chapterName = chapter?.let { c -> c.title.ifBlank { stringResource(R.string.pc_chapter_n, at + 1) } }
    val light = PicoTheme.colorScheme.labelPrimaryLight
    Layout(
        modifier = modifier,
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    // design-style: design-surface scrub-tip — the one dark capsule on the light bar
                    .background(PicoTheme.colorScheme.fillPrimary)
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(
                    text = formatTime(ms),
                    style = PicoTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = light,
                    maxLines = 1,
                )
                if (chapterName != null) {
                    Text(
                        text = chapterName,
                        style = PicoTheme.typography.bodySmall,
                        color = light.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val tip = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Over the thumb: the slider's travel runs SEEK_SIDE_PAD + SEEK_INSET in from each end.
            val inset = (SEEK_SIDE_PAD + SEEK_INSET).toPx()
            val centre = inset + (constraints.maxWidth - 2 * inset) * fraction.coerceIn(0f, 1f)
            val x = (centre - tip.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - tip.width).coerceAtLeast(0))
            tip.place(x, constraints.maxHeight - tip.height)
        }
    }
}

internal val PLAYBACK_CONTROLS_WIDTH = 1000.dp

/**
 * Between two of the bar's round buttons. At 2dp an open menu's lit button and the highlight of the
 * one beside it, looked at, all but touched — two circles reading as one shape.
 */
private val BAR_BUTTON_GAP = 6.dp
private val SEEK_TRACK_HALF = 12.dp

/**
 * Over the seek bar: a tick where each chapter starts, and an A–B loop — its A and B taller, in the
 * accent, and the stretch between them underlined just below the track, clear of the thumb — so a
 * loop is visible after its panel has closed (seeking past B snaps back to A).
 */
@Composable
private fun SeekMarks(
    chapters: List<Chapter>,
    markA: Long?,
    markB: Long?,
    durationMs: Long,
    modifier: Modifier = Modifier,
    bookmarks: List<Long> = emptyList(),
) {
    val tick = PicoTheme.colorScheme.labelSecondary
    val loop = PicoTheme.colorScheme.interaction
    val dot = PicoTheme.colorScheme.labelPrimary
    Canvas(modifier = modifier) {
        val inset = SEEK_INSET.toPx()
        val span = size.width - 2 * inset
        fun x(ms: Long) = inset + span * (ms.toFloat() / durationMs).coerceIn(0f, 1f)
        fun mark(ms: Long, color: Color, w: Float, h: Float) = drawRoundRect(
            color = color,
            topLeft = Offset(x(ms) - w / 2, (size.height - h) / 2),
            size = Size(w, h),
            cornerRadius = CornerRadius(w / 2),
        )
        if (chapters.size > 1) {
            chapters.forEach { c -> if (c.startMs > 0L) mark(c.startMs, tick, 2.dp.toPx(), 8.dp.toPx()) }
        }
        if (markA != null && markB != null) {
            val y = size.height / 2 + SEEK_TRACK_HALF.toPx() + 2.dp.toPx()
            drawLine(loop, Offset(x(markA), y), Offset(x(markB), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        markA?.let { mark(it, loop, 3.dp.toPx(), 14.dp.toPx()) }
        markB?.let { mark(it, loop, 3.dp.toPx(), 14.dp.toPx()) }
        // 书签: a dot just above the track, clear of the thumb's line and of the A–B underline.
        val dotY = size.height / 2 - SEEK_TRACK_HALF.toPx() - 4.dp.toPx()
        bookmarks.forEach { ms -> drawCircle(dot, radius = 3.dp.toPx(), center = Offset(x(ms), dotY)) }
    }
}

/**
 * Every bar menu opens ABOVE its icon and grows leftwards from it. The bar hugs the window's
 * bottom edge, so a menu that dropped below it fell off the screen; and the icons sit at the
 * bar's right end, so growing leftwards keeps even a wide panel inside the window. The
 * negative offset lifts the panel clear of the bar's capsule (a positive one pulls it down).
 */
@Composable
private fun barMenuPosition(): androidx.compose.ui.window.PopupPositionProvider {
    val lift = panelLift()
    return key(lift) {
        rememberMenuPositionProvider(
            horizontalPlacement = HorizontalPlacement.alignEnd(),
            verticalPlacement = VerticalPlacement.above(offset = -lift),
        )
    }
}

/**
 * Where the bar's top edge and its menu buttons are on screen. A fixed 36dp lift was measured when
 * the bar had only the seek bar above its buttons; with the title and the time over that, a panel
 * came down across the time — hidden just while the sleep timer or a bookmark wants it.
 */
@Stable
private class BarGeometry {
    var barTop by mutableFloatStateOf(Float.NaN)
    var buttonTop by mutableFloatStateOf(Float.NaN)
}

private val LocalBarGeometry = staticCompositionLocalOf { BarGeometry() }

/** How far above its button a panel sits: past the bar's whole height, and a gap beyond its glass. */
@Composable
private fun panelLift(): Dp {
    val g = LocalBarGeometry.current
    val above = g.buttonTop - g.barTop
    if (above.isNaN() || above <= 0f) return 36.dp
    return with(LocalDensity.current) { above.toDp() } + PANEL_GAP
}

/**
 * Between a panel's foot and the bar's top edge. More than it looks it needs: the bar's glass reaches
 * past its content, and the panel floats in front of the window, so seen from the seat — below the
 * eye — it lands lower than laid out; at 16dp the two read as touching.
 */
private val PANEL_GAP = 28.dp

/** Keeps the bar on screen while a menu is open: the bar hides only when nothing holds it. */
@Composable
private fun HoldControls(hold: (Boolean) -> Unit) {
    DisposableEffect(Unit) {
        hold(true)
        onDispose { hold(false) }
    }
}

/**
 * Opens a menu when the launch intent asked for it (`-e aura.open_menu <name>`, one of
 * subtitle · subtitle_settings · audio · audio_settings · playlist · playback · display · more · info),
 * after a beat so the bar has laid out and the popup has an anchor to sit above.
 */
/**
 * Which of the bar's menus is open: one at a time. Each kept a flag of its own and nothing closed one when
 * another opened — read off the headset, all eight panels stood open at once, stacked over the bar. (There is
 * one bar at a time.)
 */
private val openBarMenu = mutableStateOf<Any?>(null)

/** Whether one of the bar's panels is open (read in composition, it follows). */
fun barMenuOpen(): Boolean = openBarMenu.value != null

/**
 * Shuts whichever of the bar's panels is open — for a click outside it that the panel itself never hears: on the
 * picture's layer, which takes the click for itself, or in the place round the viewer, which is no window at all
 * (2026-10-23: only a click on the bar shut a panel).
 */
fun closeBarMenus() {
    openBarMenu.value = null
}

/** A bar menu's open flag, as a share of [openBarMenu]: opening it closes whichever other one was open. */
@Composable
private fun barMenuShown(): MutableState<Boolean> {
    val me = remember { Any() }
    // Gone from the bar (the player closed) is closed, as its own flag was.
    DisposableEffect(me) { onDispose { if (openBarMenu.value === me) openBarMenu.value = null } }
    return remember(me) {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = openBarMenu.value === me
                set(v) {
                    if (v) openBarMenu.value = me else if (openBarMenu.value === me) openBarMenu.value = null
                }
            override fun component1(): Boolean = value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}

@Composable
private fun OpenOnRequest(name: String, open: () -> Unit) {
    val request by DeepLink.openMenuRequest.collectAsState()
    LaunchedEffect(request) {
        if (request == name) {
            kotlinx.coroutines.delay(400)
            android.util.Log.i("AuraMenu", "open_menu $name")
            open()
            // Consumed only now: clearing it first re-keyed this effect and cancelled the open.
            DeepLink.openMenuRequest.value = null
        }
    }
}

/**
 * The bar menus are panels in SKYBOX's sense — one glass card, sections stacked and divided,
 * each section a grid of icon tiles (pick one), a list of rows (pick a track), a stepper card
 * (adjust) or buttons (act), so every kind of control looks like what it does.
 */
@Composable
private fun Panel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Menu(
        onDismissRequest = onDismiss,
        positionProvider = barMenuPosition(),
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        content = content,
    )
}

/** A panel's headings, all one role: the label scale in the secondary label colour. */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = PicoTheme.typography.labelLarge,
        color = PicoTheme.colorScheme.labelSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One titled block of a panel; [trailing] puts small actions at the heading's end. */
@Composable
private fun PanelSection(
    title: String,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (trailing == null) {
            SectionTitle(title)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(title, Modifier.weight(1f))
                trailing()
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * Two short choices side by side, each under its own heading, on the same tile grid — three
 * columns each — so two related questions take one row instead of two. [below] continues the
 * start choice across the whole width (the outline card under 样式).
 */
@Composable
private fun TwinSection(
    startTitle: String,
    endTitle: String,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    below: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle(startTitle)
                Spacer(Modifier.height(10.dp))
                start()
            }
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle(endTitle)
                Spacer(Modifier.height(10.dp))
                end()
            }
        }
        if (below != null) {
            Spacer(Modifier.height(TILE_GAP))
            below()
        }
    }
}

/**
 * The track lists get a wider panel than a Menu allows (480dp): subtitle files carry release
 * names — "Movie.Name.2023.1080p.BluRay.DTS-HD.MA.7.1.x265-GROUP.chs&eng.srt" — whose tail
 * is the part that matters. Same glass, same placement above the bar, on a SpatialPopup.
 */
private val LIST_PANEL_WIDTH = 640.dp
private val LIST_MAX_HEIGHT = 520.dp

@Composable
private fun ListPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SpatialPopup(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(LIST_PANEL_WIDTH),
        popupPositionProvider = panelLift().let { lift ->
            key(lift) {
                rememberSpatialPopupPositionProvider(
                    horizontalPlacement = HorizontalPlacement.alignEnd(),
                    verticalPlacement = VerticalPlacement.above(offset = -lift),
                )
            }
        },
        defaultMinWidth = Dp.Unspecified,
        defaultMinHeight = Dp.Unspecified,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

/**
 * Every tile in every panel is one size: the panels lay their choices on one six-column grid, so a
 * group of three is three of the same tiles a group of six is made of, not three wide flat ones.
 * Groups shorter than a row start at the left, under their heading.
 */
private const val TILE_COLUMNS = 6
private val TILE_HEIGHT = 74.dp
private val TILE_GAP = 8.dp

/** A tile's glyph, a card's glyph and a thumbnail standing in for one share this height. */
private val TILE_GLYPH = 26.dp

/**
 * A SKYBOX-style option tile — a glyph over a label, lit when chosen: the checked fill PICO's own
 * option uses, with the accent on the glyph. Custom because no built-in draws glyph-above-label; it
 * follows the custom-component contract: clip → background → spatialHoverEffect → selectable
 * (LocalIndication, a radio button to accessibility) → controllerHapticFeedback, on one
 * interactionSource. A disabled tile fades by the system's disable alpha and takes no press.
 */
@Composable
private fun OptionTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    openPageLabel: String? = null,
    icon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    label: @Composable () -> Unit,
) {
    val active = ChipsDefaults.toggleableChipColors()
    val background = if (selected) active.activeBackgroundColor else PicoTheme.colorScheme.fillTertiary
    val content = if (selected) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary
    val iconTint = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.labelSecondary
    val press = if (openPageLabel != null) {
        Modifier.clickable(
            interactionSource = interactionSource, indication = LocalIndication.current,
            enabled = enabled, role = Role.Button, onClickLabel = openPageLabel, onClick = onClick,
        )
    } else {
        Modifier.selectable(
            selected = selected, interactionSource = interactionSource, indication = LocalIndication.current,
            enabled = enabled, role = Role.RadioButton, onClick = onClick,
        )
    }
    Column(
        modifier = Modifier
            .height(TILE_HEIGHT)
            .then(modifier)
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .spatialHoverEffect(enabled = enabled)
            .then(press)
            .controllerHapticFeedback(interactionSource = interactionSource)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            CompositionLocalProvider(LocalContentColor provides iconTint) { icon() }
            Spacer(Modifier.height(6.dp))
        }
        CompositionLocalProvider(LocalContentColor provides content) { label() }
    }
}

/** A tile's glyph as a vector icon. */
@Composable
private fun TileIcon(resId: Int) {
    Icon(painterResource(resId), contentDescription = null, modifier = Modifier.size(TILE_GLYPH), tint = LocalContentColor.current)
}

/** A tile's glyph as the value itself — a number is its own best icon (15 / 30 / 60 minutes). */
@Composable
private fun TileValue(text: String) {
    Box(modifier = Modifier.height(TILE_GLYPH), contentAlignment = Alignment.Center) {
        // Laid out whole, past the glyph's box if it must: squeezed into it, titleLarge's line was cut at the edges.
        Text(
            text, style = PicoTheme.typography.titleLarge, color = LocalContentColor.current, maxLines = 1,
            modifier = Modifier.wrapContentHeight(unbounded = true),
        )
    }
}

/** A tile's glyph as a picture — the environment being shown, in place of the generic mountain. */
@Composable
private fun TileThumb(resId: Int) {
    Image(
        painter = EnvironmentThumbs.painter(resId),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.height(TILE_GLYPH).aspectRatio(16f / 10f).clip(RoundedCornerShape(4.dp)),
    )
}

/**
 * A tile's label: one line, its type stepped down until it fits the tile (to [TILE_LABEL_MIN] of the size — Cyrillic
 * words still ran out at 72 %), and only then cut with an ellipsis. A tile is a sixth of a panel, about 60 dp of text: "Immersive", "Mushroom grove",
 * イマーシブ, "Bosque de hongos" and "Oscura durante la riproduzione" were all cut, in English as much as the rest.
 */
@Composable
private fun TileLabel(text: String) {
    val base = PicoTheme.typography.bodyMedium
    // The text's own auto-size (no BoxWithConstraints: a subcomposition, which the menus' intrinsic measuring of
    // their tiles cannot ask — it crashed every menu).
    Text(
        text,
        style = base,
        color = LocalContentColor.current,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = base.fontSize * TILE_LABEL_MIN,
            maxFontSize = base.fontSize,
            stepSize = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
    )
}

/** How small a tile's label may go to fit, of its usual size. */
private const val TILE_LABEL_MIN = 0.62f

/**
 * A single-choice group on the tile grid: [columns] to a row (the full six, or three for one half of a
 * [TwinSection]), more wrapping onto the next, short rows padded so every tile keeps the grid's size.
 */
@Composable
private fun <T> OptionTiles(
    items: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    columns: Int = TILE_COLUMNS,
    enabled: Boolean = true,
    openPageLabel: (T) -> String? = { null },
    icon: @Composable (T) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        items.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                row.forEach { item ->
                    OptionTile(
                        selected = selected(item),
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        openPageLabel = openPageLabel(item),
                        icon = { icon(item) },
                    ) { TileLabel(label(item)) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One row of a track list: the name (a sidecar's file name, shown whole — up to three lines —
 * because its tail is where the language rides), a detail line (source · codec · language),
 * and a badge that tells rows apart at a glance — the language code, or a sound track's
 * channel layout. A null badge is the 关闭 row.
 */
private data class TrackRow(val key: String?, val title: @Composable () -> Unit, val detail: String?, val badge: String?, val selected: Boolean)

/**
 * A sidecar's name with the video's own stem dimmed and the tail that tells it apart lit —
 * ".chs&eng.srt" is what the eye needs, while the whole name stays readable. Drawn as a flow
 * of chunks ending at a dot or a space (text on glass takes one colour per node, so spans cannot
 * do this), which also makes the name wrap there, the tail landing whole on a line: a release's
 * long name with no dot in it ("[Kamigami] Fate Zero - 02 [BD 1920x1080 … PGS(JP,EN)].ass") was
 * one chunk, and a line cut it short. Where its languages are known ("chs&jpn" from its name or its
 * lines) they head it — 简体中文·日语 — and the name follows smaller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidecarName(track: MediaTrack, sharedStem: String?, number: Int? = null) {
    val ctx = LocalContext.current
    val words = remember(ctx) { com.therealityteam.videoplayer.data.playback.Words(ctx) }
    val languages = remember(track.language, words) {
        com.therealityteam.videoplayer.data.playback.ExoPlaybackManager.languageName(track.language, words)
    }
    val name = track.name
    val ext = name.substringAfterLast('.', "")
    val tag = track.language?.takeIf { it.isNotBlank() }
    val tail = when {
        // Several sidecars: the video's stem is what they all share; the rest is each one's own.
        sharedStem != null && name.length > sharedStem.length && name.startsWith(sharedStem) -> name.substring(sharedStem.length)
        // One sidecar: its language tag (the engine may have re-cased it) and extension.
        tag != null && name.endsWith(".$tag.$ext", ignoreCase = true) -> name.takeLast(tag.length + ext.length + 2)
        ext.isNotEmpty() && name.length > ext.length + 1 -> ".$ext"
        else -> ""
    }
    val stem = name.removeSuffix(tail)
    val small = languages != null
    // The name's pieces: smaller under a languages line, at the row's own size without one.
    val piece: @Composable (String, Color, FontWeight?) -> Unit = { text, color, weight ->
        if (small) Text(text, style = PicoTheme.typography.bodySmall, color = color, fontWeight = weight, maxLines = 1)
        else Text(text, color = color, fontWeight = weight, maxLines = 1)
    }
    Column {
        // Two streams of one file in one language (a VobSub's 英语 and its SDH twin) are told apart by number.
        languages?.let { Text(if (number == null) it else "$it $number", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // A dub named by its language alone (Film.粤语.flac) is said once: 粤语, not 粤语 over 粤语.
        if (languages != null && name.equals(languages, ignoreCase = true)) return@Column
        FlowRow(maxLines = 3) {
            Regex("[^.\\s]*[.\\s]|[^.\\s]+$").findAll(stem).forEach { chunk ->
                piece(chunk.value, PicoTheme.colorScheme.labelSecondary, null)
            }
            if (tail.isNotEmpty()) piece(tail, PicoTheme.colorScheme.labelPrimary, FontWeight.SemiBold)
            if (number != null && languages == null) piece(" $number", PicoTheme.colorScheme.labelPrimary, FontWeight.SemiBold)
        }
    }
}

/**
 * The row title for a track: a sidecar by its file name, anything else by its name — with its
 * [number] among tracks that would otherwise read the same ([repeatNumbers]).
 */
private fun MediaTrack.title(sharedStem: String? = null, number: Int? = null): @Composable () -> Unit = if (external) {
    { SidecarName(this, sharedStem, number) }
} else {
    { Text(if (number == null) name else "$name $number", maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/**
 * Each track's number among the tracks that would read the same in a list — name, language and
 * detail alike, like the two English streams of one VobSub — or null for one that stands alone.
 */
private fun repeatNumbers(tracks: List<MediaTrack>): List<Int?> {
    val keys = tracks.map { listOf(it.external, it.name, it.language, it.detail).toString() }
    val counts = keys.groupingBy { it }.eachCount()
    val seen = HashMap<String, Int>()
    return keys.map { k -> if ((counts[k] ?: 0) < 2) null else seen.merge(k, 1, Int::plus) }
}

/**
 * What a video's sidecar files all share — its stem, up to the last dot of their common
 * prefix ("X.chs.srt" and "X.chs&eng.srt" share "X.chs", so the stem is "X"). Null with fewer
 * than two sidecars, where nothing can be compared.
 */
private fun sharedSidecarStem(tracks: List<MediaTrack>): String? {
    val names = tracks.filter { it.external }.map { it.name }
    if (names.size < 2) return null
    val prefix = names.reduce { a, b -> a.commonPrefixWith(b) }
    return prefix.substring(0, prefix.lastIndexOf('.').coerceAtLeast(0)).takeIf { it.isNotEmpty() }
}

private fun MediaTrack.badge(fallback: String): String =
    language?.takeIf { it.isNotBlank() && it != "und" }
        ?.substringBefore('.')?.substringBefore('&')?.substringBefore('-')
        ?.uppercase(Locale.ROOT)?.take(3)?.takeIf { it.isNotBlank() }
        ?: fallback

private fun channelLayout(channels: Int): String = when (channels) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    in 3..Int.MAX_VALUE -> "${channels}ch"
    else -> "AUD"
}

/**
 * A track list is as long as the file makes it — a film can carry a dozen subtitle languages —
 * so it is a list of two-line rows, check-marked, not a cloud of chips that grows ragged with
 * the count. It has a panel to itself, fills it before scrolling, and opens on the chosen track.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackList(rows: List<TrackRow>, choices: Boolean = true, onSelect: (String?) -> Unit) {
    val scroll = rememberScrollState()
    val chosen = remember { BringIntoViewRequester() }
    LaunchedEffect(rows) { chosen.bringIntoView() }
    val group = if (choices) Modifier.selectableGroup() else Modifier
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(scroll).then(group)) {
        rows.forEach { row ->
            MenuItem(
                title = row.title,
                subtitle = row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                modifier = (if (row.selected) Modifier.bringIntoViewRequester(chosen) else Modifier)
                    .then(if (choices) Modifier.semantics {
                        role = Role.RadioButton
                        selected = row.selected
                    } else Modifier),
                onClick = { onSelect(row.key) },
                leadingIcon = { TrackBadge(row.badge, row.selected) },
                trailingIcon = if (row.selected) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.interaction) }
                } else {
                    null
                },
                contentColors = MenuItemDefaults.menuItemColors(
                    containerColor = if (row.selected) PicoTheme.colorScheme.fillTertiary else Color.Unspecified,
                ),
            )
        }
    }
}

/** The row's badge: a language / layout code in a small pill, accent-filled when chosen; the 关闭 row shows ⊘. */
@Composable
private fun TrackBadge(text: String?, selected: Boolean) {
    Box(modifier = Modifier.size(width = 40.dp, height = 24.dp), contentAlignment = Alignment.Center) {
        if (text == null) {
            Icon(
                painterResource(R.drawable.ic_opt_off),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.labelSecondary,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    style = PicoTheme.typography.labelMedium,
                    color = if (selected) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A chapter row's height: the menu item's 8dp paddings around a title and a time line. The list
 * shows three and a half of them, so the cut row says there is more below.
 */
private val CHAPTER_ROW = 56.dp

/** A chapter's picture in its row: 16:9, as tall as the title and time lines beside it. */
private val CHAPTER_THUMB_W = 64.dp
private val CHAPTER_THUMB_H = 36.dp

/**
 * The chapter list: the chapter's picture (its number until the picture comes), title (or "章节 N"
 * for an unnamed mark), start time, a check on the one playing. Films carry a dozen or more, so it
 * shows three and a half rows and scrolls, opening on the current chapter. [stills] null: the file
 * gives no pictures, and the rows keep the plain number badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterList(chapters: List<Chapter>, current: Int, stills: Map<Long, android.graphics.Bitmap>?, onSelect: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val chosen = remember { BringIntoViewRequester() }
    LaunchedEffect(current) { if (current >= 0) chosen.bringIntoView() }
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = CHAPTER_ROW * 3.5f).verticalScroll(scroll)) {
        chapters.forEachIndexed { i, c ->
            val on = i == current
            MenuItem(
                title = { Text(c.title.ifBlank { stringResource(R.string.pc_chapter_n, i + 1) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = { Text(formatTime(c.startMs)) },
                modifier = if (on) Modifier.bringIntoViewRequester(chosen) else Modifier,
                onClick = { onSelect(i) },
                leadingIcon = { if (stills == null) TrackBadge("${i + 1}", on) else ChapterThumb(stills[c.startMs], i + 1, on) },
                trailingIcon = if (on) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.interaction) }
                } else {
                    null
                },
                contentColors = MenuItemDefaults.menuItemColors(
                    containerColor = if (on) PicoTheme.colorScheme.fillTertiary else Color.Unspecified,
                ),
            )
        }
    }
}

/**
 * A chapter's picture, cropped to fill its 16:9 frame and faded in as it arrives; until then — or
 * when no picture came for this mark — the chapter's number on the badge's fill (the accent for the
 * one playing). The row's own fill and check say which is playing once the pictures are in.
 */
@Composable
private fun ChapterThumb(still: android.graphics.Bitmap?, number: Int, on: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    val image = remember(still) { still?.asImageBitmap() }
    val shown by animateFloatAsState(if (image != null) 1f else 0f, tween(220), label = "chapterStill")
    Box(
        modifier = Modifier
            .size(width = CHAPTER_THUMB_W, height = CHAPTER_THUMB_H)
            .clip(shape)
            .background(if (on && image == null) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary),
        contentAlignment = Alignment.Center,
    ) {
        if (shown < 1f) {
            Text(
                "$number",
                style = PicoTheme.typography.labelMedium,
                color = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelSecondary,
                maxLines = 1,
            )
        }
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown },
            )
        }
    }
}

/** Adjustments sit three to a row as cards of equal width — two tiles of the grid each. */
@Composable
private fun AdjustRow(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP), content = content)
}

/** The −/+ of a stepper in the primary label colour: the library's pale default reads as disabled on glass. */
@Composable
private fun StepGlyph(resId: Int) {
    Icon(painterResource(resId), contentDescription = null, modifier = Modifier.size(16.dp), tint = PicoTheme.colorScheme.labelPrimary)
}

private data class AdjustmentRange(
    val current: Float,
    val bounds: ClosedFloatingPointRange<Float>,
    val step: Float,
) {
    private val intervals get() = ((bounds.endInclusive - bounds.start) / step).roundToInt()
    val canDecrease get() = current > bounds.start
    val canIncrease get() = current < bounds.endInclusive
    val progress get() = ProgressBarRangeInfo(current, bounds, steps = intervals - 1)

    fun stepsTo(target: Float): Int {
        fun index(value: Float) = ((value.coerceIn(bounds) - bounds.start) / step).roundToInt()
        return index(target) - index(current)
    }
}

private fun offsetRange(milliseconds: Long, limitMs: Long): AdjustmentRange {
    val seconds = limitMs / 1000f
    return AdjustmentRange(milliseconds / 1000f, -seconds..seconds, PlayerAdjust.OFFSET_STEP_MS / 1000f)
}

/**
 * The built-in stepper, restyled to live inside a card: no fill of its own, the value set in title
 * weight. At either end of its range the stepper's own −/+ go disabled, faded by the system.
 */
@Composable
private fun ValueStepper(
    label: String,
    value: String,
    range: AdjustmentRange,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onStep: (Int) -> Unit,
) {
    // The native stepper exposes two generic buttons and a read-only text field. Services should
    // encounter one named, adjustable value in each card, with the same steps as the pointer buttons.
    Box(modifier = modifier.clearAndSetSemantics {
        contentDescription = label
        stateDescription = value
        progressBarRangeInfo = range.progress
        if (enabled) {
            setProgress { target ->
                if (!target.isFinite()) return@setProgress false
                val steps = range.stepsTo(target)
                if (steps != 0) onStep(steps)
                steps != 0
            }
        } else {
            disabled()
        }
    }, propagateMinConstraints = true) {
        Stepper(
            value = value,
            onStep = onStep,
            decreaseIcon = { StepGlyph(R.drawable.ic_opt_minus) },
            increaseIcon = { StepGlyph(R.drawable.ic_opt_plus) },
            increasable = enabled && range.canIncrease,
            decreasable = enabled && range.canDecrease,
            textStyle = PicoTheme.typography.titleMedium,
            textColor = PicoTheme.colorScheme.labelPrimary,
            paddingValues = PaddingValues(0.dp),
            backgroundColor = Color.Transparent,
        )
    }
}

/** The ↺ an adjustment shows while it is off its default; one tap puts it back. */
@Composable
private fun ResetButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val desc = stringResource(R.string.pc_reset, label)
    IconButton(
        onClick = onClick,
        modifier = modifier.tooltip(text = desc, direction = TooltipDirection.Top),
        size = IconButtonDefaults.Min,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = PicoTheme.colorScheme.labelSecondary,
        ),
    ) {
        Icon(painterResource(R.drawable.ic_opt_reset), contentDescription = desc, modifier = Modifier.size(16.dp))
    }
}

/**
 * A continuous adjustment as a card on the tiles' fill: glyph and title over a −/value/+ stepper
 * that spans the card, so the label, the value and its controls read as one object. A disabled
 * card (an engine that cannot do it) stays in its place, faded, rather than leaving a hole.
 */
@Composable
private fun RowScope.AdjustCard(
    icon: Int,
    label: String,
    value: String,
    range: AdjustmentRange,
    enabled: Boolean = true,
    reset: (() -> Unit)? = null,
    onStep: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .clip(RoundedCornerShape(12.dp))
            .background(PicoTheme.colorScheme.fillTertiary)
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        // Glyph over label, centred, exactly as the tiles are drawn; the stepper takes the
        // card's full width — its −/+ are 44dp each, and a third of the panel leaves the value
        // only the middle, so any side padding wrapped "100%".
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(TILE_GLYPH), tint = PicoTheme.colorScheme.labelSecondary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            ValueStepper(
                label, value, range, enabled,
                modifier = Modifier.fillMaxWidth(),
                onStep = onStep,
            )
        }
        if (reset != null && enabled) ResetButton(label, reset, Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
    }
}

/** The same card when the adjustment has the row to itself: glyph and title at the start, stepper at the end. */
@Composable
private fun AdjustWideCard(
    icon: Int,
    label: String,
    value: String,
    range: AdjustmentRange,
    enabled: Boolean = true,
    reset: (() -> Unit)? = null,
    onStep: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .clip(RoundedCornerShape(12.dp))
            .background(PicoTheme.colorScheme.fillTertiary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(TILE_GLYPH), tint = PicoTheme.colorScheme.labelSecondary)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = PicoTheme.typography.bodyMedium,
            color = PicoTheme.colorScheme.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (reset != null && enabled) ResetButton(label, reset)
        Spacer(Modifier.width(8.dp))
        ValueStepper(label, value, range, enabled, onStep = onStep)
    }
}

/**
 * An action as the button it is, with its icon; buttons in a row share the width. Panel actions are
 * secondary — light fill, dark label — so a panel of tiles is not dotted with dark pills. [hint]
 * says what it does beyond its name, as the pointer rests on it.
 */
@Composable
private fun RowScope.ActionButton(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean = true, hint: String? = null) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).let { m -> hint?.let { m.tooltip(text = it, direction = TooltipDirection.Top) } ?: m },
        enabled = enabled,
        size = ButtonDefaults.Regular,
        colors = secondaryButtonColors(),
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) },
    ) { ButtonLabel(label) }
}

/**
 * A button's label on one line: a third of a panel is about 75 dp of text, and a long word wrapped inside a word
 * ("Informacj / e", "Zrzut / ekranu"). Its type steps down to fit, as a tile's label does, and only then cuts.
 */
@Composable
private fun ButtonLabel(text: String) {
    val base = com.pico.spatial.ui.design.LocalTextStyle.current
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = base.fontSize * TILE_LABEL_MIN,
            maxFontSize = base.fontSize,
            stepSize = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
    )
}

/** A read-only label/value line of the info panel, its label in a column [labelWidth] wide. */
@Composable
private fun InfoRow(label: String, value: String, labelWidth: Dp, small: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(labelWidth))
        Text(
            value,
            style = if (small) PicoTheme.typography.bodySmall else PicoTheme.typography.bodyMedium,
            color = PicoTheme.colorScheme.labelPrimary,
            maxLines = if (small) 3 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The info panel's label column: as wide as its widest label in this language, and a gap. A fixed 64 dp broke
 * Длительность, Decodificador and Αποκωδικοποιητής inside the word (seen on the headset's screenshots).
 */
@Composable
private fun infoLabelWidth(vararg labels: Int): Dp {
    val style = PicoTheme.typography.bodyMedium
    val texts = labels.map { stringResource(it) }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, style, density) {
        val widest = texts.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        with(density) { widest.toDp() + INFO_LABEL_GAP }.coerceIn(INFO_LABEL_MIN, INFO_LABEL_MAX)
    }
}

private val INFO_LABEL_GAP = 12.dp
private val INFO_LABEL_MIN = 64.dp
private val INFO_LABEL_MAX = 160.dp

/** 立体 — how the frame packs the eyes: one picture, two side by side, two stacked. */
private enum class Stereo(val mode: StereoMode, @StringRes val label: Int, val icon: Int) {
    MONO(StereoMode.MONO, R.string.pc_stereo_flat, R.drawable.ic_opt_stereo_2d),
    SBS(StereoMode.SBS, R.string.pc_stereo_sbs, R.drawable.ic_opt_stereo_sbs),
    OU(StereoMode.OU, R.string.pc_stereo_tb, R.drawable.ic_opt_stereo_ou),
}

/** 投影 — what the picture is drawn on: a flat screen, a half dome, a whole sphere. */
private enum class Projection(val mode: ProjectionMode, @StringRes val label: Int, val icon: Int) {
    FLAT(ProjectionMode.FLAT, R.string.pc_proj_flat, R.drawable.ic_opt_proj_flat),
    EQ180(ProjectionMode.EQ180, R.string.pc_proj_180, R.drawable.ic_opt_proj_180),
    EQ360(ProjectionMode.EQ360, R.string.pc_proj_360, R.drawable.ic_opt_proj_360),
}

/** The 投影 tile a projection belongs under: a fisheye is a 180°, a cubemap or a 360 camera's pair a 360°. */
private fun projectionFamily(p: ProjectionMode): ProjectionMode = when (p) {
    ProjectionMode.EQ180, ProjectionMode.FISHEYE -> ProjectionMode.EQ180
    ProjectionMode.EQ360, ProjectionMode.EAC, ProjectionMode.CUBEMAP, ProjectionMode.DUAL_FISHEYE -> ProjectionMode.EQ360
    else -> ProjectionMode.FLAT
}

/**
 * 全景格式's tiles as the window's 显示 menu lays them out — for the immersive remote (PanoRemote), so the
 * two offer the same choices the same way.
 */
@Composable
internal fun PanoFormatTiles(vm: PlaybackViewModel, pano: com.therealityteam.videoplayer.data.playback.PanoLayout, columns: Int = TILE_COLUMNS) {
    val ctx = LocalContext.current
    val fileMesh = remember(pano) { vm.fileHasMesh }
    OptionTiles(
        panoOptions(pano, fileMesh),
        selected = {
            if (pano.ownMesh != null) it.lens == OWN_MESH
            else it.projection == pano.projection && (it.lens == 0 || it.lens == pano.lensFov.toInt())
        },
        label = { ctx.getString(it.label) },
        onSelect = { if (it.lens == OWN_MESH) vm.clearPanoFormat() else vm.setPanoFormat(it.projection, it.lens) },
        columns = columns,
    ) { o -> if (o.lens > 0) TileValue("${o.lens}°") else TileIcon(o.icon) }
}

/** One choice of 全景格式: a projection, and for a fisheye its lens's angle. */
private data class PanoOption(val projection: ProjectionMode, val lens: Int, @StringRes val label: Int, val icon: Int)

/**
 * The formats a panorama of [p]'s family comes in — for a file with a mesh of its own ([fileMesh]),
 * that mesh first: a 180° is a half sphere or a fisheye's circle
 * (lenses of 180–220°, named on the tile); a 360° a whole sphere, YouTube's EAC, a cubemap, or a
 * 360 camera's two circles.
 */
private fun panoOptions(p: com.therealityteam.videoplayer.data.playback.PanoLayout, fileMesh: Boolean): List<PanoOption> =
    if (fileMesh) listOf(
        // The file's own mesh first, as it is the one that is right.
        PanoOption(ProjectionMode.EQ360, OWN_MESH, R.string.pc_pano_own, R.drawable.ic_opt_pano_eac),
        PanoOption(ProjectionMode.EQ360, 0, R.string.pc_pano_equirect, R.drawable.ic_opt_pano_equirect),
        PanoOption(ProjectionMode.EAC, 0, R.string.pc_pano_eac, R.drawable.ic_opt_pano_eac),
        PanoOption(ProjectionMode.CUBEMAP, 0, R.string.pc_pano_cube, R.drawable.ic_opt_pano_cube),
    ) else if (projectionFamily(p.projection) == ProjectionMode.EQ180) listOf(
        PanoOption(ProjectionMode.EQ180, 0, R.string.pc_pano_equirect, R.drawable.ic_opt_pano_equirect),
        PanoOption(ProjectionMode.FISHEYE, 180, R.string.pc_pano_fisheye, R.drawable.ic_opt_pano_fisheye),
        PanoOption(ProjectionMode.FISHEYE, 190, R.string.pc_pano_fisheye, R.drawable.ic_opt_pano_fisheye),
        PanoOption(ProjectionMode.FISHEYE, 200, R.string.pc_pano_fisheye, R.drawable.ic_opt_pano_fisheye),
        PanoOption(ProjectionMode.FISHEYE, 220, R.string.pc_pano_fisheye, R.drawable.ic_opt_pano_fisheye),
    ) else listOf(
        PanoOption(ProjectionMode.EQ360, 0, R.string.pc_pano_equirect, R.drawable.ic_opt_pano_equirect),
        PanoOption(ProjectionMode.EAC, 0, R.string.pc_pano_eac, R.drawable.ic_opt_pano_eac),
        PanoOption(ProjectionMode.CUBEMAP, 0, R.string.pc_pano_cube, R.drawable.ic_opt_pano_cube),
        PanoOption(ProjectionMode.DUAL_FISHEYE, 0, R.string.pc_pano_dual, R.drawable.ic_opt_pano_dual),
    )

/** The 全景格式 choice that is the file's own mesh (a mesh projection), as its lens field. */
private const val OWN_MESH = -1

/** How far 左转 / 右转 turn a panorama: a third of the way to looking sideways. */
private const val PANO_TURN = 30f

/** 上移 / 下移 / 左旋 / 右旋 a press: fine enough to set a horizon by. */
private const val PANO_LEVEL_STEP = 5f


/**
 * Where the film is watched: the window, the immersive sphere, the darkened cinema stage, or the
 * window inside a virtual environment — a room, an island, a starship (picked on the panel's second page).
 */
private enum class Space(@StringRes val label: Int, val icon: Int) {
    WINDOW(R.string.pc_space_window, R.drawable.ic_opt_window),
    IMMERSIVE(R.string.pc_space_immersive, R.drawable.ic_opt_immersive),
    CINEMA(R.string.pc_space_cinema, R.drawable.ic_opt_cinema),
    ENVIRONMENT(R.string.pc_space_environment, R.drawable.ic_opt_environment),
}

private fun AspectMode.icon(): Int = when (this) {
    AspectMode.AUTO -> R.drawable.ic_opt_aspect_auto
    AspectMode.FILL -> R.drawable.ic_opt_aspect_fill
    AspectMode.R_16_9 -> R.drawable.ic_opt_aspect_16_9
    AspectMode.R_4_3 -> R.drawable.ic_opt_aspect_4_3
    AspectMode.R_2_35 -> R.drawable.ic_opt_aspect_235
}

private fun AspectMode.tileLabel(ctx: android.content.Context): String = when (this) {
    AspectMode.AUTO -> ctx.getString(R.string.pc_auto)
    AspectMode.FILL -> ctx.getString(R.string.pc_aspect_fill)
    else -> label
}

private fun PlaylistMode.icon(): Int = when (this) {
    PlaylistMode.SEQUENTIAL -> R.drawable.ic_opt_seq
    PlaylistMode.REPEAT_ALL -> R.drawable.ic_opt_repeat
    PlaylistMode.REPEAT_ONE -> R.drawable.ic_opt_repeat_one
    PlaylistMode.SHUFFLE -> R.drawable.ic_opt_shuffle
}

// The core enums carry Chinese labels for the desktop build; the headset reads its own resources,
// so English and Traditional Chinese get theirs.
@StringRes
private fun PlaylistMode.labelRes(): Int = when (this) {
    PlaylistMode.SEQUENTIAL -> R.string.pc_loop_sequential
    PlaylistMode.REPEAT_ALL -> R.string.pc_loop_all
    PlaylistMode.REPEAT_ONE -> R.string.pc_loop_one
    PlaylistMode.SHUFFLE -> R.string.pc_loop_shuffle
}

@StringRes
private fun SubtitleStyle.labelRes(): Int = when (this) {
    SubtitleStyle.NONE -> R.string.pc_sub_style_plain
    SubtitleStyle.BOX -> R.string.pc_sub_style_box
    SubtitleStyle.OUTLINE -> R.string.pc_sub_style_outline
}

@StringRes
private fun SubtitleFont.labelRes(): Int = when (this) {
    SubtitleFont.DEFAULT -> R.string.pc_font_default
    SubtitleFont.SERIF -> R.string.pc_font_serif
    SubtitleFont.MONO -> R.string.pc_font_mono
}

@StringRes
private fun SubtitleColor.labelRes(): Int = when (this) {
    SubtitleColor.WHITE -> R.string.pc_colour_white
    SubtitleColor.YELLOW -> R.string.pc_colour_yellow
    SubtitleColor.CYAN -> R.string.pc_colour_cyan
    SubtitleColor.GREEN -> R.string.pc_colour_green
}

/** The timed modes show their minutes as the glyph; only 关闭 and 播完停止 need an icon. */
private fun SleepMode.tileLabel(ctx: android.content.Context): String = when {
    isTimed -> ctx.getString(R.string.pc_minutes)
    this == SleepMode.END_OF_ITEM -> ctx.getString(R.string.pc_stop_after)
    else -> ctx.getString(R.string.pc_off)
}

/** Each style's glyph is the style itself: the letter bare, on its box, as an outline. */
private fun SubtitleStyle.icon(): Int = when (this) {
    SubtitleStyle.NONE -> R.drawable.ic_opt_sub_plain
    SubtitleStyle.BOX -> R.drawable.ic_opt_sub_box
    SubtitleStyle.OUTLINE -> R.drawable.ic_opt_sub_outline
}

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** One dial, the needle swung to the speed — the icon says the value, not merely "speed". */
private fun speedIcon(v: Float): Int = when (v) {
    0.5f -> R.drawable.ic_opt_speed_050
    0.75f -> R.drawable.ic_opt_speed_075
    1.25f -> R.drawable.ic_opt_speed_125
    1.5f -> R.drawable.ic_opt_speed_150
    2.0f -> R.drawable.ic_opt_speed_200
    else -> R.drawable.ic_opt_speed_100
}

/**
 * The speaker gains a wave per step of boost. 关闭 is ⊘, as every 关闭 in these panels is — the
 * muted speaker it used to be read as "mute", when it means the sound as it is.
 */
private fun boostIcon(step: Int): Int = when (step) {
    1 -> R.drawable.ic_opt_boost_1
    2 -> R.drawable.ic_opt_boost_2
    3 -> R.drawable.ic_opt_boost_3
    else -> R.drawable.ic_opt_off
}

/** 显示 — how the picture is shown: format, aspect, and which space it plays in. */
@Composable
private fun DisplayMenu(
    onHold: (Boolean) -> Unit,
    stereo: StereoMode,
    projection: ProjectionMode,
    onSelectFormat: (StereoMode, ProjectionMode) -> Unit,
    pano: com.therealityteam.videoplayer.data.playback.PanoLayout?,
    onSelectPano: (ProjectionMode, Int) -> Unit,
    onTurnPano: (Float) -> Unit,
    onRecenter: () -> Unit,
    /** 上移 / 下移 / 左旋 / 右旋: the picture moved up (degrees) and rolled clockwise; 放平 undoes both. */
    onLevel: (Float, Float) -> Unit,
    onLevelReset: () -> Unit,
    remoteAtEyeLevel: Boolean,
    onRemoteHeight: (Boolean) -> Unit,
    /** The file has a mesh of its own (a mesh projection): offered first, and back after another choice. */
    fileMesh: Boolean,
    aspectMode: AspectMode,
    barsCropped: Boolean,
    onSelectAspect: (AspectMode) -> Unit,
    immersive: Boolean,
    cinemaDim: Boolean,
    environment: Environment?,
    shownPlace: Environment?,
    seat: ViewingSeat,
    onSeat: (Environment, ViewingSeat) -> Unit,
    onToggleImmersive: () -> Unit,
    onToggleCinema: () -> Unit,
    onSelectEnvironment: (Environment?) -> Unit,
    dimEnvironment: Boolean,
    onDimEnvironment: (Boolean) -> Unit,
    viewingLightPercent: Int,
    onViewingLightChange: (Int) -> Unit,
    screenPlay: Boolean,
    onScreenPlay: (Boolean) -> Unit,
    screenGlow: Boolean,
    onScreenGlow: (Boolean) -> Unit,
    roomLights: Boolean?,
    onRoomLights: (Boolean) -> Unit,
    placeLoading: Environment?,
    onCancelRoom: (Environment) -> Unit,
) {
    val ctx = LocalContext.current
    var show by barMenuShown()
    // The environments get a page of their own: a grid of pictures would crowd the settings above.
    var environments by remember { mutableStateOf(false) }
    val space = when {
        immersive -> Space.IMMERSIVE
        cinemaDim -> Space.CINEMA
        environment != null -> Space.ENVIRONMENT
        else -> Space.WINDOW
    }
    Box {
        BarMenuButton(
            R.drawable.ic_pc_format,
            stringResource(R.string.pc_display),
            open = show,
            onOpenChange = { show = it; if (!it) environments = false },
            // A forced aspect is the one thing here that is not visible from the space you are in.
            marked = aspectMode != AspectMode.AUTO,
        )
        OpenOnRequest("display") { show = true }
        OpenOnRequest("environments") { show = true; environments = true }
        if (show) {
            HoldControls(onHold)
            Panel(onDismiss = { show = false; environments = false }) {
                if (environments) {
                    EnvironmentPage(
                        current = shownPlace,
                        onBack = { environments = false },
                        onSelect = onSelectEnvironment,
                        seat = seat,
                        onSeat = onSeat,
                        dim = dimEnvironment,
                        onDim = onDimEnvironment,
                        viewingLightPercent = viewingLightPercent,
                        onViewingLightChange = onViewingLightChange,
                        screenPlay = screenPlay,
                        onScreenPlay = onScreenPlay,
                        screenGlow = screenGlow,
                        onScreenGlow = onScreenGlow,
                        lights = roomLights,
                        onLights = onRoomLights,
                        loading = placeLoading,
                        onCancelRoom = onCancelRoom,
                    )
                    return@Panel
                }
                val viewingSpace: @Composable () -> Unit = {
                    // The stages are mutually exclusive in the ViewModel, so they are one choice here.
                    PanelSection(stringResource(R.string.pc_viewing_space)) {
                        OptionTiles(
                            Space.entries,
                            selected = { it == space },
                            // This tile opens the room picker even when the viewer is already in a room.
                            openPageLabel = { if (it == Space.ENVIRONMENT) ctx.getString(R.string.pc_space_environment) else null },
                            // An environment being shown names itself and shows its picture.
                            label = { s -> if (s == Space.ENVIRONMENT && environment != null) ctx.getString(environment.label) else ctx.getString(s.label) },
                            onSelect = { chosen ->
                                when (chosen) {
                                    Space.WINDOW -> if (immersive) onToggleImmersive() else if (cinemaDim) onToggleCinema() else if (environment != null) onSelectEnvironment(null)
                                    Space.IMMERSIVE -> if (!immersive) onToggleImmersive()
                                    Space.CINEMA -> if (!cinemaDim) onToggleCinema()
                                    // Which one is the next page's question; the panel stays up to ask it.
                                    Space.ENVIRONMENT -> environments = true
                                }
                                if (chosen != Space.ENVIRONMENT && chosen != space) show = false
                            },
                        ) { s ->
                            if (s == Space.ENVIRONMENT && environment != null) TileThumb(environment.thumb) else TileIcon(s.icon)
                        }
                    }
                }
                if (immersive) {
                    viewingSpace()
                    HorizontalDivider()
                }
                // 画面格式 as two questions side by side: how the frame packs the eyes, and what it is
                // drawn on. They used to be one row of five where 180° kept whichever packing came
                // before it, unseen. A choice here stays open — the lit tile is the answer — unless it
                // moves the film between the window and the sphere.
                TwinSection(
                    startTitle = stringResource(R.string.pc_stereo),
                    endTitle = stringResource(R.string.pc_projection),
                    start = {
                        OptionTiles(
                            Stereo.entries,
                            selected = { it.mode == stereo || (it == Stereo.MONO && stereo == StereoMode.UNKNOWN) },
                            label = { ctx.getString(it.label) },
                            onSelect = { onSelectFormat(it.mode, projection) },
                            columns = TILE_COLUMNS / 2,
                        ) { TileIcon(it.icon) }
                    },
                    end = {
                        OptionTiles(
                            Projection.entries,
                            selected = { it.mode == projectionFamily(projection) },
                            label = { ctx.getString(it.label) },
                            onSelect = { p ->
                                // Within its family a fisheye or a cubemap stays what it is; 全景格式 below tells them apart.
                                if (p.mode != projectionFamily(projection)) onSelectFormat(stereo, p.mode)
                                // A panorama is watched from inside it: 180° and 360° take the film into
                                // 沉浸空间, 平面 brings it back to the window.
                                val sphere = p != Projection.FLAT
                                if (sphere != immersive) {
                                    onToggleImmersive()
                                    show = false
                                }
                            },
                            columns = TILE_COLUMNS / 2,
                        ) { TileIcon(it.icon) }
                    },
                )
                HorizontalDivider()
                // What the panorama is: asked in 沉浸空间 and in the window's view into it alike.
                val panoFormat: @Composable (com.therealityteam.videoplayer.data.playback.PanoLayout) -> Unit = { p ->
                    PanelSection(stringResource(R.string.pc_pano_format)) {
                        OptionTiles(
                            panoOptions(p, fileMesh),
                            selected = {
                                if (p.ownMesh != null) it.lens == OWN_MESH
                                else it.projection == p.projection && (it.lens == 0 || it.lens == p.lensFov.toInt())
                            },
                            label = { ctx.getString(it.label) },
                            onSelect = { onSelectPano(it.projection, it.lens) },
                        ) { o -> if (o.lens > 0) TileValue("${o.lens}°") else TileIcon(o.icon) }
                    }
                    HorizontalDivider()
                }
                if (immersive) {
                    // In 沉浸空间 there is no frame to fit: what the panorama is, and which way it faces.
                    if (pano != null) panoFormat(pano)
                    PanelSection(stringResource(R.string.pc_pano_direction)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                            ActionButton(R.drawable.ic_opt_turn_left, stringResource(R.string.pc_pano_turn_left), onClick = { onTurnPano(-PANO_TURN) }, hint = stringResource(R.string.pc_pano_turn_hint))
                            ActionButton(R.drawable.ic_opt_recenter, stringResource(R.string.pc_pano_recenter), onClick = onRecenter, hint = stringResource(R.string.pc_pano_recenter_hint))
                            ActionButton(R.drawable.ic_opt_turn_right, stringResource(R.string.pc_pano_turn_right), onClick = { onTurnPano(PANO_TURN) }, hint = stringResource(R.string.pc_pano_turn_hint))
                        }
                        // A crooked camera's horizon: moved up or down, rolled level — kept for the film.
                        Spacer(Modifier.height(TILE_GAP))
                        val levelHint = stringResource(R.string.pc_pano_level_hint)
                        // Two rows, as the turn above: up and down; and the roll either way with 放平 between,
                        // where 回正 stands in the row above (five in one row folded their names in two).
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                            ActionButton(R.drawable.ic_opt_tilt_up, stringResource(R.string.pc_pano_tilt_up), onClick = { onLevel(PANO_LEVEL_STEP, 0f) }, hint = levelHint)
                            ActionButton(R.drawable.ic_opt_tilt_down, stringResource(R.string.pc_pano_tilt_down), onClick = { onLevel(-PANO_LEVEL_STEP, 0f) }, hint = levelHint)
                        }
                        Spacer(Modifier.height(TILE_GAP))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                            ActionButton(R.drawable.ic_opt_roll_left, stringResource(R.string.pc_pano_roll_left), onClick = { onLevel(0f, -PANO_LEVEL_STEP) }, hint = levelHint)
                            ActionButton(R.drawable.ic_opt_level, stringResource(R.string.pc_pano_level), onClick = onLevelReset, hint = levelHint)
                            ActionButton(R.drawable.ic_opt_roll_right, stringResource(R.string.pc_pano_roll_right), onClick = { onLevel(0f, PANO_LEVEL_STEP) }, hint = levelHint)
                        }
                    }
                } else if (pano != null) {
                    // A panorama in the window fills it, nothing to fit: what it is. (How much of it shows is the
                    // window's own: it is a window onto the sphere — nearer, and more of it is seen.)
                    panoFormat(pano)
                } else {
                // 画面比例 — AUTO preserves the source aspect (letterboxed); the rest force one.
                PanelSection(stringResource(R.string.pc_aspect)) {
                    OptionTiles(
                        AspectMode.entries,
                        selected = { it == aspectMode },
                        label = { it.tileLabel(ctx) },
                        onSelect = { onSelectAspect(it) },
                    ) { TileIcon(it.icon()) }
                    // 去黑边: 自动 showing a scope film as a scope panel says so — 16:9 brings the frame back.
                    if (aspectMode == AspectMode.AUTO && barsCropped) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.pc_bars_cropped),
                            style = PicoTheme.typography.bodySmall,
                            color = PicoTheme.colorScheme.labelSecondary,
                        )
                    }
                }
                }
                HorizontalDivider()
                if (!immersive) viewingSpace()
                if (space == Space.WINDOW || space == Space.IMMERSIVE) {
                    PanelSection(stringResource(R.string.panorama_remote)) {
                        SegmentControl(modifier = Modifier.fillMaxWidth()) {
                            SegmentItem(
                                selected = !remoteAtEyeLevel,
                                onClick = { onRemoteHeight(false) },
                                title = { Text(stringResource(R.string.remote_below_eye)) },
                            )
                            SegmentItem(
                                selected = remoteAtEyeLevel,
                                onClick = { onRemoteHeight(true) },
                                title = { Text(stringResource(R.string.remote_eye_level)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The four rooms in the Places menu. The current room stays selected while a new room loads. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnvironmentPage(
    current: Environment?,
    onBack: () -> Unit,
    onSelect: (Environment) -> Unit,
    seat: ViewingSeat,
    onSeat: (Environment, ViewingSeat) -> Unit,
    dim: Boolean,
    onDim: (Boolean) -> Unit,
    viewingLightPercent: Int,
    onViewingLightChange: (Int) -> Unit,
    screenPlay: Boolean,
    onScreenPlay: (Boolean) -> Unit,
    screenGlow: Boolean,
    onScreenGlow: (Boolean) -> Unit,
    lights: Boolean?,
    onLights: (Boolean) -> Unit,
    loading: Environment?,
    onCancelRoom: (Environment) -> Unit,
) {
    val back = stringResource(R.string.pc_back)
    var choosingSeat by remember(current?.id, loading?.id) { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { if (choosingSeat) choosingSeat = false else onBack() },
            modifier = Modifier.tooltip(text = back, direction = TooltipDirection.Top),
            size = IconButtonDefaults.Small,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor = PicoTheme.colorScheme.labelPrimary,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_pc_back), contentDescription = back, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        val title = if (choosingSeat && current != null) {
            stringResource(R.string.pc_env_seat) + " · " + stringResource(current.label)
        } else stringResource(R.string.pc_space_environment)
        SectionTitle(title, Modifier.weight(1f))
        if (loading != null) {
            Button(
                onClick = { onCancelRoom(loading) },
                size = ButtonDefaults.Small,
            ) { Text(stringResource(R.string.cancel)) }
        } else if (!choosingSeat && current != null && current.otherChairs.isNotEmpty()) {
            Button(onClick = { choosingSeat = true }, size = ButtonDefaults.Small) {
                Text(stringResource(R.string.pc_env_seat))
            }
        }
    }
    if (choosingSeat && current != null) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())
            .selectableGroup().padding(vertical = 8.dp)) {
            current.seatChoices.forEach { option ->
                val chosen = seat == option
                MenuItem(
                    title = { Text(stringResource(option.label)) },
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        selected = chosen
                    },
                    onClick = { onSeat(current, option) },
                    trailingIcon = if (chosen) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                            modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.interaction) }
                    } else null,
                    contentColors = MenuItemDefaults.menuItemColors(
                        containerColor = if (chosen) PicoTheme.colorScheme.fillTertiary else Color.Unspecified,
                    ),
                )
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            Environments.all.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    row.forEach { env ->
                        EnvironmentTile(
                            env, selected = env.id == current?.id, loading = env.id == loading?.id,
                            onClick = { onSelect(env) }, modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider()
        val canUseScreen = current == null || current.screen != null
        PanelSection(stringResource(R.string.pc_env_screen)) {
            if (canUseScreen) {
                SegmentControl(modifier = Modifier.fillMaxWidth()) {
                    SegmentItem(
                        selected = screenPlay,
                        onClick = { onScreenPlay(true) },
                        modifier = Modifier.tooltip(text = stringResource(R.string.pc_env_screen_hint), direction = TooltipDirection.Top),
                        title = { Text(stringResource(R.string.cinema_screen)) },
                    )
                    SegmentItem(
                        selected = !screenPlay,
                        onClick = { onScreenPlay(false) },
                        title = { Text(stringResource(R.string.pc_space_window)) },
                    )
                }
            } else {
                Text(stringResource(R.string.pc_env_screen_none), style = PicoTheme.typography.bodyMedium)
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            SettingChip(R.drawable.ic_env_glow, R.string.pc_env_glow, R.string.pc_env_glow_hint,
                screenGlow, onScreenGlow, enabled = canUseScreen && screenPlay)
            SettingChip(R.drawable.ic_env_dim, R.string.pc_env_dim, R.string.pc_env_dim_hint, dim, onDim)
            if (lights != null) {
                SettingChip(R.drawable.ic_env_lights, R.string.pc_env_lights, R.string.pc_env_lights_hint, lights, onLights)
            }
        }
        if (dim && lights != false) {
            val label = stringResource(R.string.pc_env_viewing_light)
            var drag by remember { mutableStateOf<Int?>(null) }
            val percent = drag ?: viewingLightPercent
            val valueText = com.therealityteam.videoplayer.ui.common.percentText(percent)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.weight(1f), style = PicoTheme.typography.bodyMedium)
                Text(valueText, style = PicoTheme.typography.labelLarge)
            }
            Slider(
                value = percent.toFloat(), valueRange = 0f..100f,
                onValueChange = { drag = it.roundToInt() },
                onValueChangeFinished = { drag?.let(onViewingLightChange); drag = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = valueText
                    progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), 0f..100f)
                    setProgress { value -> onViewingLightChange(value.roundToInt().coerceIn(0, 100)); true }
                },
            )
        }
    }
}

/** A setting of the environment page as the system's toggleable chip: lit while on, what it does on hover. */
@Composable
private fun SettingChip(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    @StringRes hint: Int,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ToggleableChip(
        label = { Text(stringResource(label), maxLines = 1) },
        isToggleOn = on,
        onClick = { onChange(!on) },
        modifier = Modifier.tooltip(text = stringResource(hint), direction = TooltipDirection.Top),
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
        enabled = enabled,
        chipSize = ChipsDefaults.Regular,
    )
}

/**
 * One environment: its picture over its name, on the chips' active fill when it is the one shown, and
 * dimmed under the system's spinner while its place is being read ([loading]) — the seconds between
 * the pick and the place going up. Follows the custom-component contract like [OptionTile]: clip →
 * background → spatialHoverEffect → selectable(LocalIndication) → controllerHapticFeedback, one
 * interactionSource.
 */
@Composable
private fun EnvironmentTile(
    env: Environment,
    selected: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val active = ChipsDefaults.toggleableChipColors()
    val background = if (selected) active.activeBackgroundColor else PicoTheme.colorScheme.fillTertiary
    val labelColor = if (selected) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary
    val name = stringResource(env.label)
    val loadingText = if (loading) stringResource(R.string.pc_env_entering, name) else null
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .spatialHoverEffect()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .controllerHapticFeedback(interactionSource = interactionSource)
            .semantics { if (loadingText != null) stateDescription = loadingText }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(2.4f)) {
            Image(
                painter = EnvironmentThumbs.painter(env.thumb),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
            )
            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x8C000000)), // design-style: fixed-figma-color scrim over a photo
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White.withVibrant(Vibrant.None), // design-style: fixed-figma-color spinner over a photo
                        progressSize = CircularProgressDefaults.Small,
                    )
                }
            }
            if (selected && !loading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(0x8C000000)), // design-style: fixed-figma-color badge over a photo
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = Color.White, // design-style: fixed-figma-color badge over a photo
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = PicoTheme.typography.bodyMedium, color = labelColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 字幕轨道 — the file's subtitle tracks (embedded and same-name sidecars) as one long list with 关闭.
 * The bar shows the subtitles glyph struck through while none is on.
 */
@Composable
private fun SubtitleTracksMenu(vm: PlaybackViewModel) {
    var show by barMenuShown()
    val tracks by vm.manager.subtitleTracks.collectAsState()
    val secondary by vm.secondary.collectAsState()
    val anySelected = tracks.any { it.selected }
    Box {
        BarMenuButton(
            if (anySelected || secondary != null) R.drawable.ic_pc_subtitle else R.drawable.ic_pc_subtitle_off,
            stringResource(R.string.pc_subtitles),
            open = show,
            onOpenChange = { show = it },
        )
        OpenOnRequest("subtitle") { show = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            HoldControls { vm.holdSubtitleMenu(settings = false, open = it) }
            ListPanel(onDismiss = { show = false }) {
                // Subtitle files of pictures beside the film (a release's VobSub): only mpv draws them.
                val engine = vm.manager as? com.therealityteam.videoplayer.data.playback.SwitchingEngine
                val current by vm.current.collectAsState()
                val inMpv by (engine?.usingMpv ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
                val pictures = remember(engine, current?.id, inMpv, tracks) { engine?.pictureSidecars().orEmpty() }
                PanelSection(if (tracks.size > 1) stringResource(R.string.pc_subtitles_n, tracks.size) else stringResource(R.string.pc_subtitles)) {
                    if (tracks.isEmpty() && pictures.isEmpty()) {
                        Text(stringResource(R.string.pc_no_subtitles), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
                    } else if (tracks.isNotEmpty()) {
                        val stem = sharedSidecarStem(tracks)
                        val rows = listOf(TrackRow(null, { Text(stringResource(R.string.pc_off)) }, null, null, !anySelected)) +
                            tracks.zip(repeatNumbers(tracks)) { it, n -> TrackRow(it.id, it.title(stem, n), it.detail, it.badge("CC"), it.selected) }
                        TrackList(rows) { vm.selectSubtitleTrack(it) }
                    }
                    // …and a VobSub / .sup beside the film: pictures, which only mpv draws — offered
                    // through it, the file going on there where it is, that subtitle on.
                    if (pictures.isNotEmpty()) {
                        val label = pluralStringResource(R.plurals.pc_more_picture_subs, pictures.size, pictures.size)
                        TrackList(listOf(TrackRow(MORE_PICTURES, { Text(label) }, stringResource(R.string.pc_more_picture_subs_hint), "+${pictures.size}", false)), choices = false) {
                            show = false
                            vm.showPictureSubtitles()
                        }
                    }
                }
                // 第二字幕 — one of the subtitle files beside the video, drawn under the first: two
                // languages at once where a release ships them apart. Files only: the engine renders a
                // single text track, so the second is read and timed by the app itself — text files
                // only: a picture subtitle has no lines to read.
                val files = tracks.filter { it.external && !vm.isPictureSidecar(it.name) }
                if (files.isNotEmpty()) {
                    HorizontalDivider()
                    PanelSection(stringResource(R.string.pc_second_subtitle)) {
                        val stem = sharedSidecarStem(tracks)
                        val rows = listOf(TrackRow(null, { Text(stringResource(R.string.pc_off)) }, null, null, secondary == null)) +
                            files.zip(repeatNumbers(files)) { it, n -> TrackRow(it.name, it.title(stem, n), it.detail, it.badge("CC"), secondary?.label == it.name) }
                        TrackList(rows) { vm.selectSecondarySubtitle(it) }
                    }
                }
            }
        }
    }
}

/**
 * 字幕设置 — size, position and delay as three cards; the bare/box/outline style beside the typeface,
 * the outline's thickness under them; the colours. The bar dots it while a delay is in force — the
 * one setting here that belongs to this film rather than to every film.
 */
@Composable
private fun SubtitleSettingsMenu(vm: PlaybackViewModel) {
    val ctx = LocalContext.current
    var show by barMenuShown()
    val scale by vm.subtitleScale.collectAsState()
    val raise by vm.subtitleRaise.collectAsState()
    val style by vm.subtitleStyle.collectAsState()
    val delay by vm.subtitleDelayMs.collectAsState()
    val outline by vm.subtitleOutline.collectAsState()
    val font by vm.subtitleFont.collectAsState()
    val color by vm.subtitleColor.collectAsState()
    val keepStyles by vm.subtitleKeepStyles.collectAsState()
    val isTypeset by vm.subtitleIsTypeset.collectAsState()
    val typeset by vm.subtitleTypeset.collectAsState()
    Box {
        BarMenuButton(
            R.drawable.ic_pc_subtitle_settings,
            stringResource(R.string.pc_subtitle_settings),
            open = show,
            onOpenChange = { show = it },
            marked = delay != 0L,
        )
        OpenOnRequest("subtitle_settings") { show = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            HoldControls { vm.holdSubtitleMenu(settings = true, open = it) }
            Panel(onDismiss = { show = false }) {
                // ASS only: the fansub's own typesetting, or the reader's look below. Size applies either way.
                if (isTypeset) {
                    PanelSection(stringResource(R.string.pc_typeset)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.pc_typeset_keep),
                                    style = PicoTheme.typography.bodyMedium,
                                    color = PicoTheme.colorScheme.labelPrimary,
                                )
                                Text(
                                    stringResource(if (typeset) R.string.pc_typeset_on_hint else R.string.pc_typeset_off_hint),
                                    style = PicoTheme.typography.bodySmall,
                                    color = PicoTheme.colorScheme.labelSecondary,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            val typesetName = stringResource(R.string.pc_typeset_keep)
                            Switch(checked = typeset, onCheckedChange = { vm.setSubtitleTypeset(it) }, modifier = Modifier.semantics { contentDescription = typesetName })
                        }
                    }
                    HorizontalDivider()
                }
                PanelSection(stringResource(R.string.pc_size_and_place)) {
                    AdjustRow {
                        val scalePct = PlayerAdjust.percent(scale)
                        AdjustCard(
                            R.drawable.ic_opt_text_size,
                            stringResource(R.string.pc_size),
                            com.therealityteam.videoplayer.ui.common.percentText(scalePct),
                            range = AdjustmentRange(
                                scalePct.toFloat(), PlayerAdjust.SCALE_MIN_PCT.toFloat()..PlayerAdjust.SCALE_MAX_PCT.toFloat(),
                                PlayerAdjust.SCALE_STEP_PCT.toFloat(),
                            ),
                            reset = if (scalePct != PlayerAdjust.SCALE_DEFAULT_PCT) ({ vm.setSubtitleScale(PlayerAdjust.SCALE_DEFAULT_PCT / 100f) }) else null,
                        ) { vm.adjustSubtitleScale(it) }
                        val raisePct = PlayerAdjust.percent(raise)
                        AdjustCard(
                            R.drawable.ic_opt_position,
                            stringResource(R.string.pc_position),
                            com.therealityteam.videoplayer.ui.common.percentText(raisePct),
                            range = AdjustmentRange(
                                raisePct.toFloat(), PlayerAdjust.RAISE_MIN_PCT.toFloat()..PlayerAdjust.RAISE_MAX_PCT.toFloat(),
                                PlayerAdjust.RAISE_STEP_PCT.toFloat(),
                            ),
                            reset = if (raisePct != PlayerAdjust.RAISE_MIN_PCT) ({ vm.setSubtitleRaise(0f) }) else null,
                        ) { vm.adjustSubtitleRaise(it) }
                        // The built-in engine cannot shift its subtitles: the card stays, faded.
                        AdjustCard(
                            R.drawable.ic_opt_delay,
                            stringResource(R.string.pc_delay),
                            PlayerAdjust.signedSeconds(delay),
                            range = offsetRange(delay, PlayerAdjust.SUBTITLE_DELAY_LIMIT_MS),
                            enabled = vm.supportsSubtitleDelay,
                            reset = if (delay != 0L) ({ vm.adjustSubtitleDelay(0) }) else null,
                        ) { vm.adjustSubtitleDelay(it) }
                    }
                }
                HorizontalDivider()
                // The fansub's typesetting kept: its fonts, colours and outlines are the file's, and
                // the reader's look below would change nothing — shown faded, as the delay card is when
                // the engine cannot shift subtitles, rather than lit as if a tap would do something.
                val readerLook = !(isTypeset && typeset)
                // 样式 and 字体 side by side; the outline's thickness under them, always in its place
                // and live only with 描边, so picking a style no longer makes the panel jump.
                TwinSection(
                    startTitle = stringResource(R.string.pc_style),
                    endTitle = stringResource(R.string.pc_font),
                    start = {
                        OptionTiles(
                            SubtitleStyle.entries,
                            selected = { it == style },
                            label = { ctx.getString(it.labelRes()) },
                            onSelect = { vm.setSubtitleStyle(it) },
                            columns = TILE_COLUMNS / 2,
                            enabled = readerLook,
                        ) { TileIcon(it.icon()) }
                    },
                    end = {
                        // Each typeface shows itself as the tile's glyph.
                        OptionTiles(
                            SubtitleFont.entries,
                            selected = { it == font },
                            label = { ctx.getString(it.labelRes()) },
                            onSelect = { vm.setSubtitleFont(it) },
                            columns = TILE_COLUMNS / 2,
                            enabled = readerLook,
                        ) { f -> TileGlyphText("Aa", f.family()) }
                    },
                    below = {
                        AdjustWideCard(
                            R.drawable.ic_opt_outline_width,
                            stringResource(R.string.pc_outline),
                            "$outline",
                            range = AdjustmentRange(
                                outline.toFloat(), PlayerAdjust.OUTLINE_MIN.toFloat()..PlayerAdjust.OUTLINE_MAX.toFloat(), 1f,
                            ),
                            enabled = readerLook && style == SubtitleStyle.OUTLINE,
                            reset = if (outline != PlayerAdjust.OUTLINE_DEFAULT) ({ vm.setSubtitleOutline(PlayerAdjust.OUTLINE_DEFAULT) }) else null,
                        ) { vm.adjustSubtitleOutline(it) }
                    },
                )
                HorizontalDivider()
                // 颜色 — the swatches on one line, with the switch that lets a file's own colours win
                // beside them.
                PanelSection(stringResource(R.string.pc_colour)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectableGroup().alpha(if (readerLook) 1f else LocalDisableAlpha.current),
                    ) {
                        SubtitleColor.entries.forEach { c ->
                            ColorSwatch(color = Color(c.argb), selected = c == color, label = ctx.getString(c.labelRes()), enabled = readerLook) { vm.setSubtitleColor(c) }
                            Spacer(Modifier.width(8.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.pc_keep_file_colours),
                            style = PicoTheme.typography.bodyMedium,
                            color = PicoTheme.colorScheme.labelPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        val keepName = stringResource(R.string.pc_keep_file_colours)
                        Switch(checked = keepStyles, onCheckedChange = { vm.setSubtitleKeepStyles(it) }, enabled = readerLook, modifier = Modifier.semantics { contentDescription = keepName })
                    }
                }
            }
        }
    }
}

/** A tile glyph that is the option itself: "Aa" set in the typeface on offer. */
@Composable
private fun TileGlyphText(text: String, family: FontFamily) {
    Box(modifier = Modifier.height(TILE_GLYPH), contentAlignment = Alignment.Center) {
        Text(
            text, style = PicoTheme.typography.titleLarge, fontFamily = family, color = LocalContentColor.current, maxLines = 1,
            modifier = Modifier.wrapContentHeight(unbounded = true),
        )
    }
}

/**
 * A colour swatch — the colour is its own glyph, on a disc of the tiles' fill so white still has an
 * edge on light glass; the chosen one sits on the lit fill with a check. A 40dp target, named in a
 * tooltip. Custom because no built-in is a round colour button; it follows the custom-component
 * contract (clip → background → spatialHoverEffect → selectable(LocalIndication) →
 * controllerHapticFeedback, one interactionSource).
 */
@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
) {
    val ring = if (selected) ChipsDefaults.toggleableChipColors().activeBackgroundColor else PicoTheme.colorScheme.fillTertiary
    Box(
        modifier = Modifier
            .size(40.dp)
            .then(modifier)
            .tooltip(text = label, direction = TooltipDirection.Top)
            .clip(CircleShape)
            .background(ring)
            .spatialHoverEffect()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .controllerHapticFeedback(interactionSource = interactionSource)
            .padding(7.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(color)) // design-style: fixed-figma-color subtitle swatch
        if (selected) {
            // Drawn on the swatch's own colour, so it takes whichever of black/white reads on it.
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = (if (color.luminance() > 0.5f) Color.Black else Color.White).withVibrant(Vibrant.None), // design-style: fixed-figma-color check on a swatch
            )
        }
    }
}

private fun SubtitleFont.family(): FontFamily = when (this) {
    SubtitleFont.DEFAULT -> FontFamily.Default
    SubtitleFont.SERIF -> FontFamily.Serif
    SubtitleFont.MONO -> FontFamily.Monospace
}

/** 音轨 — the sound tracks as one long list. */
@Composable
private fun AudioTracksMenu(vm: PlaybackViewModel) {
    var show by barMenuShown()
    val tracks by vm.manager.audioTracks.collectAsState()
    Box {
        BarMenuButton(R.drawable.ic_pc_audio, stringResource(R.string.pc_audio), open = show, onOpenChange = { show = it })
        OpenOnRequest("audio") { show = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            ListPanel(onDismiss = { show = false }) {
                PanelSection(if (tracks.size > 1) stringResource(R.string.pc_audio_n, tracks.size) else stringResource(R.string.pc_audio)) {
                    if (tracks.isEmpty()) {
                        Text(stringResource(R.string.pc_default_track), style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
                    } else {
                        // 全景声: the sound-field track says so — it is heard turned with the head, not as its channels.
                        val field by vm.soundField.collectAsState()
                        val fieldWord = stringResource(R.string.pc_audio_field)
                        val rows = tracks.zip(repeatNumbers(tracks)) { it, n ->
                            val detail = if (field?.channels == it.channels) listOf(it.detail, fieldWord).filter { d -> d.isNotBlank() }.joinToString(" · ") else it.detail
                            TrackRow(it.id, it.title(number = n), detail, it.badge(channelLayout(it.channels)), it.selected)
                        }
                        TrackList(rows) { id -> id?.let { vm.selectAudioTrack(it) } }
                    }
                    // Sound tracks the file has and Media3 could not read: offered through mpv, which
                    // reads them all — the rest of the file's tracks go with it.
                    val engine = vm.manager as? com.therealityteam.videoplayer.data.playback.SwitchingEngine
                    val current by vm.current.collectAsState()
                    val dropped by produceState(emptyList<String>(), engine, current?.id) {
                        value = engine?.droppedSoundTracks().orEmpty()
                    }
                    if (dropped.isNotEmpty()) {
                        val label = pluralStringResource(R.plurals.pc_more_sound, dropped.size, dropped.size)
                        TrackList(listOf(TrackRow(MORE_SOUND, { Text(label) }, stringResource(R.string.pc_more_sound_hint), "+${dropped.size}", false)), choices = false) {
                            show = false
                            engine?.playDroppedSoundInMpv()
                        }
                    }
                }
            }
        }
    }
}

/** The row key of 还有 N 条音轨 — no track of Media3's. */
private const val MORE_SOUND = "aura:more_sound"

/** The row key of 还有 N 个图形字幕 — no track of Media3's either. */
private const val MORE_PICTURES = "aura:more_pictures"

/** 音频设置 — the A/V offset card and the boost ladder as tiles; dotted while either is moved. */
@Composable
private fun AudioSettingsMenu(vm: PlaybackViewModel) {
    val ctx = LocalContext.current
    var show by barMenuShown()
    val boost by vm.audioBoost.collectAsState()
    val audioDelay by vm.audioDelayMs.collectAsState()
    val night by vm.nightMode.collectAsState()
    Box {
        BarMenuButton(
            R.drawable.ic_pc_audio_settings,
            stringResource(R.string.pc_audio_settings),
            open = show,
            onOpenChange = { show = it },
            marked = boost != 1.0f || audioDelay != 0L || night,
        )
        OpenOnRequest("audio_settings") { show = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            Panel(onDismiss = { show = false }) {
                // 音画同步 — nudge the sound against the picture in 0.1s steps.
                PanelSection(stringResource(R.string.pc_av_sync)) {
                    AdjustWideCard(
                        R.drawable.ic_opt_sync,
                        stringResource(R.string.pc_offset),
                        PlayerAdjust.signedSeconds(audioDelay),
                        range = offsetRange(audioDelay, PlayerAdjust.AUDIO_DELAY_LIMIT_MS),
                        reset = if (audioDelay != 0L) ({ vm.adjustAudioDelay(0) }) else null,
                    ) { vm.adjustAudioDelay(it) }
                }
                HorizontalDivider()
                // 音量增强 — above-unity PCM gain for quiet dialogue; 100% is bit-exact off.
                PanelSection(stringResource(R.string.pc_volume_boost)) {
                    OptionTiles(
                        AudioGain.LEVELS,
                        selected = { it == boost },
                        label = { g -> if (g == 1.0f) ctx.getString(R.string.pc_off) else com.therealityteam.videoplayer.ui.common.percentText((g * 100).toInt()) },
                        onSelect = { vm.setAudioBoost(it) },
                    ) { TileIcon(boostIcon(AudioGain.LEVELS.indexOf(it))) }
                }
                HorizontalDivider()
                // 夜间模式 — what it does in a line, and the switch beside it, as 保留文件自带颜色 reads.
                PanelSection(stringResource(R.string.pc_night_mode)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.pc_night_mode_hint),
                            style = PicoTheme.typography.bodyMedium,
                            color = PicoTheme.colorScheme.labelSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        // Named for whatever reads the panel: its words are nodes of their own beside it.
                        val nightName = stringResource(R.string.pc_night_mode)
                        Switch(checked = night, onCheckedChange = { vm.setNightMode(it) }, modifier = Modifier.semantics { contentDescription = nightName })
                    }
                }
            }
        }
    }
}

/**
 * 播放 — speed and loop mode as tiles, A–B repeat as the one-button action it is. Its bar glyph is
 * the speed dial at the current speed, and it is dotted while a loop mode or an A–B loop is on.
 */
@Composable
private fun PlaybackMenu(vm: PlaybackViewModel) {
    val ctx = LocalContext.current
    var show by barMenuShown()
    val speed by vm.speed.collectAsState()
    val loop by vm.playlistMode.collectAsState()
    val ab by vm.repeatState.collectAsState()
    val markA by vm.repeatA.collectAsState()
    val markB by vm.repeatB.collectAsState()
    val skips by vm.skipMarks.collectAsState()
    Box {
        BarMenuButton(
            speedIcon(speed),
            stringResource(R.string.pc_playback_menu),
            open = show,
            onOpenChange = { show = it },
            marked = loop != PlaylistMode.SEQUENTIAL || ab != AbRepeat.State.IDLE || !skips.isEmpty,
        )
        OpenOnRequest("playback") { show = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            Panel(onDismiss = { show = false }) {
                // 逐帧 at the heading's end: one picture back or on, paused — the slowest speed there is.
                PanelSection(
                    stringResource(R.string.pc_speed),
                    trailing = {
                        ChapterStepButton(R.drawable.ic_pc_frame_back, stringResource(R.string.pc_frame_back), enabled = true) { vm.stepFrame(forward = false) }
                        ChapterStepButton(R.drawable.ic_pc_frame_forward, stringResource(R.string.pc_frame_forward), enabled = true) { vm.stepFrame(forward = true) }
                    },
                ) {
                    OptionTiles(
                        SPEEDS,
                        selected = { it == speed },
                        label = { com.therealityteam.videoplayer.ui.common.speedText(it) },
                        onSelect = { vm.setSpeed(it) },
                    ) { TileIcon(speedIcon(it)) }
                }
                HorizontalDivider()
                PanelSection(stringResource(R.string.pc_repeat_mode)) {
                    OptionTiles(
                        PlaylistMode.entries,
                        selected = { it == loop },
                        label = { ctx.getString(it.labelRes()) },
                        onSelect = { vm.setPlaylistMode(it) },
                    ) { TileIcon(it.icon()) }
                }
                HorizontalDivider()
                // One button walks A → B (loop starts) → clear; the text beside it says where the marks are,
                // and the seek bar shows them in the accent once this panel has closed.
                PanelSection(stringResource(R.string.pc_ab_repeat)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (ab) {
                                AbRepeat.State.IDLE -> stringResource(R.string.pc_unset)
                                AbRepeat.State.A_SET -> stringResource(R.string.pc_ab_waiting, formatTime(markA ?: 0))
                                AbRepeat.State.LOOPING -> stringResource(R.string.pc_ab_looping, formatTime(markA ?: 0), formatTime(markB ?: 0))
                            },
                            style = PicoTheme.typography.bodyMedium,
                            color = PicoTheme.colorScheme.labelPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { vm.cycleAbRepeat() },
                            size = ButtonDefaults.Regular,
                            colors = secondaryButtonColors(),
                            leadingIcon = { Icon(painterResource(R.drawable.ic_opt_ab), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        ) {
                            Text(
                                when (ab) {
                                    AbRepeat.State.IDLE -> stringResource(R.string.pc_set_a)
                                    AbRepeat.State.A_SET -> stringResource(R.string.pc_set_b)
                                    AbRepeat.State.LOOPING -> stringResource(R.string.pc_clear_loop)
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
                SkipSection(vm, skips)
            }
        }
    }
}

/**
 * 跳过片头片尾 in the 播放 panel: two buttons mark where this show's opening ends and its closing
 * begins, from where the picture is; the heading says what is set and ↺ at its end clears both. Every
 * later episode starts past the opening, and its closing brings on the next one (the countdown card's
 * 取消 stays for the credits).
 */
@Composable
private fun SkipSection(vm: PlaybackViewModel, marks: SkipStore.Marks) {
    val parts = listOfNotNull(
        marks.introEndMs?.let { stringResource(R.string.pc_skip_intro_set, formatTime(it)) },
        marks.outroLengthMs?.let { stringResource(R.string.pc_skip_outro_set, formatTime(it)) },
    )
    PanelSection(
        (listOf(stringResource(R.string.pc_skip)) + parts).joinToString(" · "),
        trailing = if (marks.isEmpty) null else ({
            ChapterStepButton(R.drawable.ic_opt_reset, stringResource(R.string.pc_skip_clear), enabled = true) { vm.clearSkipMarks() }
        }),
    ) {
        // What each mark does to the episodes after this one is said as the pointer rests on its button:
        // a line of explanation under them took the panel past a menu's 480 dp, and its foot scrolled away.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            ActionButton(
                R.drawable.ic_pc_next,
                stringResource(R.string.pc_skip_mark_intro),
                onClick = { vm.markIntroEnd() },
                hint = stringResource(R.string.pc_skip_intro_hint),
            )
            ActionButton(
                R.drawable.ic_opt_end,
                stringResource(R.string.pc_skip_mark_outro),
                onClick = { vm.markOutroStart() },
                hint = stringResource(R.string.pc_skip_outro_hint),
            )
        }
    }
}

/**
 * 播放列表 — the queue as one long list: what 播放 lined up (a season, a folder), numbered, the
 * playing entry marked, each with where it was left off; a tap jumps to it. With nothing else
 * queued the button rests, faded — a list of one has nothing to choose.
 */
@Composable
private fun PlaylistMenu(vm: PlaybackViewModel) {
    var show by barMenuShown()
    val queue by vm.queue.collectAsState()
    val current by vm.current.collectAsState()
    Box {
        BarMenuButton(
            R.drawable.ic_opt_playlist,
            stringResource(R.string.pc_queue),
            open = show,
            onOpenChange = { show = it },
            enabled = queue.size > 1,
        )
        OpenOnRequest("playlist") { show = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            ListPanel(onDismiss = { show = false }) {
                PanelSection(if (queue.size > 1) stringResource(R.string.pc_queue_n, queue.size) else stringResource(R.string.pc_queue)) {
                    val told by vm.told.collectAsState()
                    val run by vm.numberedRun.collectAsState()
                    QueueList(queue, current?.id, told, run) { vm.playAt(it); show = false }
                }
            }
        }
    }
}

/** A queue entry's title: season/episode first when it is one, else the title, else the file. */
/**
 * The bar's second line: which one this is — 第 9 集, 第 2 季 第 3 集, OVA 02, NCOP 01 — as the detail
 * page and the playlist name it, or a film's year; and an episode's own [name] beside it, where the
 * database has one: 第 1 季 第 2 集：孩子. It was the system's "now playing" line, 剧名 · S01E02, which
 * under a title that already is the show's name said the name again — and, for a show numbered
 * without seasons ([09]), said nothing else. Now the system's line is this one too.
 */
internal fun barSubtitle(ctx: android.content.Context, item: MediaItem, name: String? = null, numberedRun: Boolean = false): String? {
    // Where it came from, when it came from a phone: the bar otherwise reads as the library's.
    if (item.sourceId == com.therealityteam.videoplayer.data.cast.CAST_SOURCE) return ctx.getString(R.string.pc_from_cast)
    val info = EpisodeNaming.parse(item, numberedRun)
    return when (info.cat) {
        EpisodeNaming.Cat.EPISODE -> {
            val label = EpisodeNaming.label(ctx, item, numberedRun)
            val which = info.season?.let { ctx.getString(R.string.det_episode_in_season, it, label) } ?: label
            if (name.isNullOrBlank()) which else ctx.getString(R.string.ep_with_name, which, name)
        }
        EpisodeNaming.Cat.OVA, EpisodeNaming.Cat.EXTRA -> EpisodeNaming.label(ctx, item, numberedRun)
        // A run's instalment with no number (its prologue) is called by its own name; a film in parts
        // by the part it is — 第 1 部分 · 2001 — as both halves are called the film.
        EpisodeNaming.Cat.FEATURE -> name?.takeIf { numberedRun && it.isNotBlank() } ?: info.name?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                com.therealityteam.videoplayer.ui.library.DetailPlay.partNumber(item.fileName)?.let { ctx.getString(R.string.pc_film_part, it) },
                item.year?.toString(),
            ).joinToString(" · ").ifBlank { null }
    }
}

/**
 * …and an episode by its own [name] where the database has one: S1 第2集 · 孩子, not the show's name
 * again — or, in a run numbered by its names ([numberedRun]), by the number its file holds:
 * 第1集 · 喉舌与职责 where the scan read none.
 */
internal fun MediaItem.queueTitle(ctx: android.content.Context, name: String? = null, numberedRun: Boolean = false): String {
    val own = name?.ifBlank { null }
    // An episode, an OVA, a trailer: named as the bar names it — 第 7 集, 第 1 季 第 2 集：孩子, 预告 20.
    // The list sits under a bar that already says the show; "第1集 · 高达G之复国运动" down twenty-six
    // rows said it twenty-six times more, in a spacing of its own.
    when (EpisodeNaming.parse(this, numberedRun).cat) {
        EpisodeNaming.Cat.EPISODE, EpisodeNaming.Cat.OVA, EpisodeNaming.Cat.EXTRA ->
            barSubtitle(ctx, this, own, numberedRun)?.let { return it }
        EpisodeNaming.Cat.FEATURE -> Unit
    }
    if (numberedRun && own != null) return own
    // The halves of a film read apart: 指环王1：护戒使者 · 第 2 部分.
    return com.therealityteam.videoplayer.ui.library.DetailPlay.partNumber(fileName)
        ?.let { "${title.ifBlank { fileName }} · ${ctx.getString(R.string.pc_film_part, it)}" }
        ?: title.ifBlank { fileName }
}

/** Where an entry was left: watched through, part-way, or untouched (then its file name). */
private fun MediaItem.queueDetail(ctx: android.content.Context): String = when {
    progress.watched -> ctx.getString(R.string.pc_watched, fileName)
    progress.started -> ctx.getString(R.string.pc_stopped_at, formatTime(progress.positionMs), fileName)
    else -> fileName
}

/** Whether the headset has no network now — the session's player knows. */
@Composable
private fun networkDownFlow(): kotlinx.coroutines.flow.StateFlow<Boolean> = remember {
    org.koin.core.context.GlobalContext.get().getScope(com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID)
        .get<PlaybackViewModel>().networkDown
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueList(
    queue: List<MediaItem>,
    currentId: String?,
    told: Map<String, com.therealityteam.videoplayer.data.scrape.EpisodeInfo>,
    numberedRun: Boolean,
    onPlay: (MediaItem) -> Unit,
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val chosen = remember { BringIntoViewRequester() }
    LaunchedEffect(queue, currentId) { chosen.bringIntoView() }
    // Which of them are on the headset — marked; and, away from the network, the rest faded: they
    // would only stop at 网络已断开.
    val store = remember { org.koin.core.context.GlobalContext.get().get<com.therealityteam.videoplayer.data.download.DownloadStore>() }
    val copies by store.all.collectAsState()
    val here = remember(copies) { copies.filter { it.isDone }.mapTo(HashSet()) { it.itemId } }
    val offline by networkDownFlow().collectAsState()
    // Names that come out alike — a disc's nine 菜单 — are numbered in the queue's order, as the
    // detail page numbers them: 菜单 1 … 菜单 9.
    val titles = remember(queue, told, numberedRun) {
        com.therealityteam.videoplayer.ui.library.DetailPlay.numberRepeats(queue.map { it.queueTitle(ctx, told[it.id]?.name, numberedRun) })
    }
    // The badge says the episode, as the title beside it does: by place in the queue, a show that
    // starts at 第 0 集 read 「2 · 第 1 集」 all the way down. A row that is no episode (an OVA, a
    // trailer) keeps the badge's room empty; a queue of no episodes is numbered by place.
    val badges = remember(queue, numberedRun) {
        val episodes = queue.map { entry ->
            EpisodeNaming.parse(entry, numberedRun).takeIf { it.cat == EpisodeNaming.Cat.EPISODE }?.episode
                ?.let { n -> if (n % 1.0 == 0.0) n.toInt().toString() else n.toString() }
        }
        if (episodes.any { it != null }) episodes else queue.indices.map { "${it + 1}" }
    }
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(scroll)) {
        queue.forEachIndexed { i, entry ->
            val playing = entry.id == currentId
            val onHeadset = entry.id in here
            val outOfReach = offline && !onHeadset && entry.path.contains("://") && !entry.path.startsWith("file://")
            MenuItem(
                title = { Text(titles[i], maxLines = 2, overflow = TextOverflow.Ellipsis) },
                subtitle = { Text(entry.queueDetail(ctx), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = (if (playing) Modifier.bringIntoViewRequester(chosen) else Modifier)
                    .alpha(if (outOfReach) LocalDisableAlpha.current else 1f),
                onClick = { onPlay(entry) },
                leadingIcon = {
                    badges[i]?.let { TrackBadge(it, playing) } ?: Spacer(Modifier.size(width = 40.dp, height = 24.dp))
                },
                trailingIcon = when {
                    playing -> {
                        { Icon(painterResource(R.drawable.ic_pc_play), contentDescription = stringResource(R.string.pc_now_playing), modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.interaction) }
                    }
                    onHeadset -> {
                        { Icon(painterResource(R.drawable.ic_line_downloaded), contentDescription = stringResource(R.string.dl_on_headset), modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelTertiary) }
                    }
                    else -> null
                },
                contentColors = MenuItemDefaults.menuItemColors(
                    containerColor = if (playing) PicoTheme.colorScheme.fillTertiary else Color.Unspecified,
                ),
            )
        }
    }
}

/**
 * 更多 — the utilities that belong to no single concern: actions as buttons (save a still,
 * media info), chapters, and the sleep timer as tiles. Dotted while the sleep timer is armed.
 */
@Composable
private fun MoreMenu(vm: PlaybackViewModel) {
    val ctx = LocalContext.current
    var show by barMenuShown()
    // A panel of the bar like the others: one open at a time, and shut by a click outside it ([closeBarMenus]).
    var showInfo by barMenuShown()
    val current by vm.current.collectAsState()
    val duration by vm.manager.duration.collectAsState()
    val sleep by vm.sleepMode.collectAsState()
    val remaining by vm.sleepRemainingMs.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val snapshotting by vm.snapshotting.collectAsState()
    Box {
        BarMenuButton(
            R.drawable.ic_pc_more,
            stringResource(R.string.pc_more),
            open = show,
            onOpenChange = { show = it },
            marked = sleep != SleepMode.OFF,
        )
        OpenOnRequest("more") { show = true }
        OpenOnRequest("info") { showInfo = true }
        if (show) {
            HoldControls { vm.holdControls(it) }
            Panel(onDismiss = { show = false }) {
                PanelSection(stringResource(R.string.pc_tools)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                        // The panel steps aside for the still; a capture under way holds the next one back.
                        ActionButton(R.drawable.ic_opt_snapshot, stringResource(R.string.pc_screenshot), onClick = { vm.snapshot(); show = false }, enabled = !snapshotting)
                        ActionButton(R.drawable.ic_info, stringResource(R.string.pc_media_info), onClick = { show = false; showInfo = true })
                        DownloadButton(vm, current) { show = false }
                    }
                }
                HorizontalDivider()
                BookmarkSection(vm)
                if (chapters.isNotEmpty()) {
                    HorizontalDivider()
                    ChapterSection(vm, chapters)
                }
                HorizontalDivider()
                val timedRemaining = remaining?.takeIf { sleep.isTimed }
                PanelSection(if (timedRemaining != null) stringResource(R.string.pc_sleep_left, formatTime(timedRemaining)) else stringResource(R.string.pc_sleep)) {
                    OptionTiles(
                        SleepMode.entries,
                        selected = { it == sleep },
                        label = { it.tileLabel(ctx) },
                        onSelect = { vm.setSleepMode(it) },
                    ) { m ->
                        when {
                            m.isTimed -> TileValue("${m.minutes}")
                            m == SleepMode.END_OF_ITEM -> TileIcon(R.drawable.ic_opt_end)
                            // 关闭 is ⊘ as everywhere else; the moon read as the timer being on.
                            else -> TileIcon(R.drawable.ic_opt_off)
                        }
                    }
                }
            }
        }
        if (showInfo) {
            HoldControls { vm.holdControls(it) }
            // Re-read every second while open: the decoder line fills in and the frame tally is live.
            val info by produceState(vm.manager.mediaInfo(), current?.id) {
                while (true) {
                    value = vm.manager.mediaInfo()
                    kotlinx.coroutines.delay(1_000)
                }
            }
            Panel(onDismiss = { showInfo = false }) {
                PanelSection(stringResource(R.string.pc_media_info)) {
                    val w = infoLabelWidth(
                        R.string.pc_info_title, R.string.pc_info_episode, R.string.pc_info_file, R.string.pc_info_source,
                        R.string.pc_info_video, R.string.pc_info_format, R.string.pc_info_audio, R.string.pc_info_decoder,
                        R.string.pc_info_frames, R.string.pc_info_duration,
                    )
                    InfoRow(stringResource(R.string.pc_info_title), current?.title?.ifBlank { null } ?: current?.fileName ?: "—", w)
                    // Which episode, as the bar names it — 第 1 季 第 2 集：孩子 — and the file it is: the
                    // title alone said only the show.
                    val told by vm.told.collectAsState()
                    val run by vm.numberedRun.collectAsState()
                    current?.takeIf { it.episode != null || it.season != null || run }
                        ?.let { barSubtitle(LocalContext.current, it, told[it.id]?.name, run) }
                        ?.let { InfoRow(stringResource(R.string.pc_info_episode), it, w) }
                    current?.fileName?.let { InfoRow(stringResource(R.string.pc_info_file), it, w, small = true) }
                    // Where it is read from: the copy on the headset, or the share it lives on.
                    val fromCopy by vm.playingCopy.collectAsState()
                    current?.let { c ->
                        val where = when {
                            fromCopy -> stringResource(R.string.dl_on_headset)
                            c.path.startsWith("smb://") -> stringResource(R.string.pc_info_from_share, c.path.removePrefix("smb://").substringBefore('/'))
                            // A file server's: its protocol and host ("SFTP · nas").
                            com.therealityteam.videoplayer.data.source.RemoteAddress.parse(c.path) != null ->
                                com.therealityteam.videoplayer.data.source.RemoteAddress.parse(c.path)!!.let { "${it.scheme.uppercase()} · ${it.host}" }
                            c.path.startsWith("http") -> c.path.substringAfter("://").substringBefore('/').substringBefore(':')
                            else -> null
                        }
                        where?.let { InfoRow(stringResource(R.string.pc_info_source), it, w) }
                    }
                    InfoRow(stringResource(R.string.pc_info_video), info?.video ?: "—", w)
                    // What the picture is, where it is more than a flat frame: a panorama's projection and
                    // eyes, a spatial video's two coded views (one shown).
                    val pano by vm.pano.collectAsState()
                    val said by vm.panoFile.collectAsState()
                    pictureFormat(pano, said, current?.video)?.let { InfoRow(stringResource(R.string.pc_info_format), it, w) }
                    InfoRow(stringResource(R.string.pc_info_audio), info?.audio ?: "—", w)
                    InfoRow(stringResource(R.string.pc_info_decoder), info?.decoder ?: "—", w)
                    InfoRow(stringResource(R.string.pc_info_frames), info?.frames ?: "—", w)
                    InfoRow(stringResource(R.string.pc_info_duration), if (duration > 0) formatTime(duration) else "—", w)
                }
            }
        }
    }
}

/**
 * The 画面格式 line of 媒体信息: a panorama's kind, projection and eyes (360° · EAC · 上下 3D), a flat
 * film's 3D packing, a spatial video's two views and its camera — null for a plain flat frame, which the
 * 画面 line says all of.
 */
@Composable
private fun pictureFormat(
    pano: com.therealityteam.videoplayer.data.playback.PanoLayout?,
    said: com.therealityteam.videoplayer.data.playback.SphericalInfo?,
    flags: com.therealityteam.videoplayer.data.model.VideoFlags?,
): String? {
    val parts = mutableListOf<String>()
    // A flat film in 3D: its packing, and — the right eye first — that it is shown as its left eye.
    if (pano == null && flags != null && flags.projection == ProjectionMode.FLAT) {
        when (flags.stereo) {
            com.therealityteam.videoplayer.data.model.StereoMode.SBS -> parts += stringResource(R.string.pc_stereo_sbs_3d)
            com.therealityteam.videoplayer.data.model.StereoMode.OU -> parts += stringResource(R.string.pc_stereo_ou_3d)
            else -> {}
        }
        if (parts.isNotEmpty() && flags.rightFirst) parts += stringResource(R.string.pc_info_right_first)
    }
    if (pano != null) {
        val half = pano.projection == ProjectionMode.EQ180 || pano.projection == ProjectionMode.FISHEYE
        parts += stringResource(if (half) R.string.pc_proj_180 else R.string.pc_proj_360)
        parts += when {
            pano.ownMesh != null -> stringResource(R.string.pc_pano_own)
            pano.projection == ProjectionMode.FISHEYE -> stringResource(R.string.pc_pano_fisheye) + " ${pano.lensFov.toInt()}°"
            pano.projection == ProjectionMode.EAC -> stringResource(R.string.pc_pano_eac)
            pano.projection == ProjectionMode.CUBEMAP -> stringResource(R.string.pc_pano_cube)
            pano.projection == ProjectionMode.DUAL_FISHEYE -> stringResource(R.string.pc_pano_dual)
            else -> stringResource(R.string.pc_pano_equirect)
        }
        when (pano.stereo) {
            com.therealityteam.videoplayer.data.model.StereoMode.SBS -> parts += stringResource(R.string.pc_stereo_sbs_3d)
            com.therealityteam.videoplayer.data.model.StereoMode.OU -> parts += stringResource(R.string.pc_stereo_ou_3d)
            else -> {}
        }
        if (pano.eyesSwapped) parts += stringResource(R.string.pc_info_right_first)
    }
    if (said?.multiview == true) {
        if (pano == null) parts += stringResource(R.string.pc_info_spatial_video)
        parts += stringResource(R.string.pc_info_mv_hevc)
        if (said.horizontalFov > 0f && said.baselineMm > 0f) {
            parts += stringResource(R.string.pc_info_camera, Math.round(said.horizontalFov), Math.round(said.baselineMm))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 下载到本机 from the player: this file and the next few of the queue — the evening's episodes taken
 * along before the headset leaves the house. Says 已在本机 for a file already here, how far for one
 * on its way.
 */
@Composable
private fun RowScope.DownloadButton(vm: PlaybackViewModel, current: com.therealityteam.videoplayer.data.model.MediaItem?, onDone: () -> Unit) {
    val downloader = remember { org.koin.core.context.GlobalContext.get().get<com.therealityteam.videoplayer.data.download.Downloader>() }
    val list by downloader.store.all.collectAsState()
    val active by downloader.active.collectAsState()
    val queue by vm.queue.collectAsState()
    val item = current ?: return
    if (!downloader.canDownload(item)) return
    val mine = active?.takeIf { it.itemId == item.id } ?: list.firstOrNull { it.itemId == item.id }
    when {
        // Labels short enough for a third of the panel ("下载到本机" broke onto a second line); the
        // pointer resting on the button reads the whole of it.
        mine?.isDone == true -> ActionButton(
            R.drawable.ic_line_downloaded,
            stringResource(R.string.dl_player_done),
            onClick = {},
            enabled = false,
            hint = stringResource(R.string.dl_on_headset),
        )
        mine != null && mine.state != com.therealityteam.videoplayer.data.download.Download.State.FAILED -> ActionButton(
            R.drawable.ic_line_download,
            if (mine.totalBytes > 0) com.therealityteam.videoplayer.ui.common.percentText((mine.fraction * 100).toInt()) else stringResource(R.string.dl_state_queued),
            onClick = {},
            enabled = false,
        )
        else -> {
            val have = list.mapTo(HashSet()) { it.itemId }
            val take = (queue.dropWhile { it.id != item.id }.ifEmpty { listOf(item) })
                .filter { (it.id == item.id || it.id !in have) && downloader.canDownload(it) }
                .take(PLAYER_DOWNLOAD_AHEAD)
            ActionButton(
                R.drawable.ic_line_download,
                if (take.size > 1) pluralStringResource(R.plurals.dl_player_n, take.size, take.size) else stringResource(R.string.dl_player_one),
                onClick = {
                    if (mine != null) downloader.resume(item.id)
                    downloader.add(take)
                    onDone()
                },
                hint = stringResource(R.string.dl_add),
            )
        }
    }
}

/** How many of the queue the player's 下载 takes, this file included. */
private const val PLAYER_DOWNLOAD_AHEAD = 5

/**
 * 章节 in the 更多 panel: the heading says which chapter is playing, its end holds 上一章 / 下一章,
 * and the list below shows three and a half rows. Reading the position here, not in [MoreMenu],
 * keeps the bar's 更多 button from recomposing twice a second while its panel is closed.
 */
@Composable
private fun ChapterSection(vm: PlaybackViewModel, chapters: List<Chapter>) {
    val position by vm.manager.position.collectAsState()
    val at = chapters.indexAt(position)
    val stills by vm.chapterStills.collectAsState()
    val noStills by vm.chapterStillsNone.collectAsState()
    // Shown: the chapters are pictured (once per film; kept ones come straight from the cache).
    LaunchedEffect(chapters) { vm.wantChapterStills() }
    PanelSection(
        if (at >= 0) stringResource(R.string.pc_chapters_at, at + 1, chapters.size) else stringResource(R.string.pc_chapters_n, chapters.size),
        trailing = {
            ChapterStepButton(R.drawable.ic_pc_prev, stringResource(R.string.pc_prev_chapter), enabled = at >= 0) { vm.previousChapter() }
            ChapterStepButton(R.drawable.ic_pc_next, stringResource(R.string.pc_next_chapter), enabled = at < chapters.lastIndex) { vm.nextChapter() }
        },
    ) {
        ChapterList(chapters, at, stills.takeUnless { noStills }) { vm.seekToChapter(it) }
    }
}

/**
 * 书签 in the 更多 panel: ＋ at the heading's end marks where the picture is now; each mark is a chip
 * with its time — a tap goes there, its ✕ forgets it. The same marks are dots over the seek bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkSection(vm: PlaybackViewModel) {
    val marks by vm.bookmarks.collectAsState()
    PanelSection(
        if (marks.isEmpty()) stringResource(R.string.pc_bookmarks) else stringResource(R.string.pc_bookmarks_n, marks.size),
        trailing = {
            ChapterStepButton(R.drawable.ic_line_plus, stringResource(R.string.pc_bookmark_add), enabled = true) { vm.addBookmark() }
        },
    ) {
        if (marks.isEmpty()) {
            Text(
                stringResource(R.string.pc_bookmark_empty),
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
            )
        } else {
            FlowRow(
                modifier = Modifier.heightIn(max = 136.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                marks.forEach { mark ->
                    val time = formatTime(mark.positionMs)
                    RemovableChip(
                        label = { Text(time, style = PicoTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")) },
                        onLeadingClick = { vm.seekToBookmark(mark.positionMs) },
                        onTrailingRemoveClick = { vm.removeBookmark(mark.positionMs) },
                        visible = true,
                        chipSize = ChipsDefaults.Regular,
                        modifier = Modifier.semantics { contentDescription = time },
                    )
                }
            }
        }
    }
}

/** 上一章 / 下一章 at the end of the chapter heading: the light icon button, named in a tooltip. */
@Composable
private fun ChapterStepButton(resId: Int, desc: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.tooltip(text = desc, direction = TooltipDirection.Top),
        enabled = enabled,
        size = IconButtonDefaults.Small,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        Icon(painterResource(resId), contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

private const val SEEK_STEP = 15_000L

/** The bar's time: the position, then the length, the time left (−) or the clock it ends at. */
private fun barTimeText(ctx: android.content.Context, mode: String, position: Long, duration: Long, speed: Float): String {
    val at = formatTime(position)
    if (duration <= 0) return "$at / ${formatTime(duration)}"
    val left = (duration - position).coerceAtLeast(0)
    return when (mode) {
        "left" -> "$at / −${formatTime(left)}"
        "ends" -> {
            val clock = android.text.format.DateFormat.getTimeFormat(ctx)
                .format(java.util.Date(System.currentTimeMillis() + (left / speed.coerceAtLeast(0.1f)).toLong()))
            "$at · ${ctx.getString(R.string.pc_ends_at, clock)}"
        }
        else -> "$at / ${formatTime(duration)}"
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
