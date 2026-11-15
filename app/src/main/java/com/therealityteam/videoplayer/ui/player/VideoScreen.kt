package com.therealityteam.videoplayer.ui.player

import com.therealityteam.videoplayer.R
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.DynamicShadowComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.ui.foundation.content.setAccessibilityActions
import com.therealityteam.videoplayer.data.playback.SubtitleFont
import com.therealityteam.videoplayer.data.playback.SubtitleColor
import com.therealityteam.videoplayer.data.playback.CuePlacement
import com.therealityteam.videoplayer.data.playback.FrameRect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import com.therealityteam.videoplayer.data.playback.AssRenderer
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.withTimeoutOrNull
import com.therealityteam.videoplayer.data.playback.ControllerModel
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.os.Looper
import android.os.Handler
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.design.PicoTheme
import androidx.compose.ui.res.painterResource
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Button
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pico.spatial.ui.design.SymbolicCircularProgressIndicator
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.ui.library.cachedArtwork
import com.therealityteam.videoplayer.ui.library.decodeArtwork
import com.therealityteam.videoplayer.ui.library.decodeStill
import com.therealityteam.videoplayer.ui.library.formatRuntime
import com.therealityteam.videoplayer.ui.library.titleGradient
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import com.therealityteam.videoplayer.data.playback.SubtitleStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.geometry.DpOffset3D
import com.pico.spatial.ui.foundation.geometry.NormalizedPoint3D
import com.pico.spatial.ui.foundation.window.Augment
import com.pico.spatial.ui.foundation.window.AugmentContentAlignment
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.layout.DepthAlignment
import com.pico.spatial.ui.foundation.layout.alignDepth
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.foundation.layout.offset
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.resize.SpatialContainerUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import com.therealityteam.videoplayer.STAGE_ID
import com.therealityteam.videoplayer.player.Environment
import com.therealityteam.videoplayer.player.Environments
import com.therealityteam.videoplayer.player.CaptionLook
import com.therealityteam.videoplayer.player.Screen
import com.therealityteam.videoplayer.player.ScreenCaptions
import com.therealityteam.videoplayer.data.playback.Oversize
import com.therealityteam.videoplayer.data.playback.PlaybackFailure
import com.therealityteam.videoplayer.data.playback.PlaybackState
import com.therealityteam.videoplayer.data.playback.SubtitleCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.therealityteam.videoplayer.ui.library.LibCategory
import com.therealityteam.videoplayer.ui.library.LibraryViewModel
import com.therealityteam.videoplayer.ui.library.MediaGroup
import com.therealityteam.videoplayer.ui.library.PosterCard
import com.therealityteam.videoplayer.ui.library.SeriesTitles
import com.therealityteam.videoplayer.ui.library.SimilarTitles
import com.therealityteam.videoplayer.ui.library.SurprisePick
import com.therealityteam.videoplayer.data.repository.SettingsRepository
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pico.spatial.ui.design.IconButton
import com.therealityteam.videoplayer.ui.theme.secondaryIconButtonColors
import com.pico.spatial.ui.foundation.tooltip
import androidx.compose.runtime.key

/** Matches pico.spatial.windowcontainer.defaultsize (1280x720 dp) in AndroidManifest.xml. */
private val WINDOW_WIDTH_DP = 1280.dp
private val WINDOW_HEIGHT_DP = 720.dp

/**
 * Depth of the video's SpatialView box. With alignDepth(DepthBack) the video sits ~half this
 * in front of the base panel — enough to clear it (so it renders on hardware) but still behind
 * the Toolbar (~16dp forward). Tunable if the video is occluded by the panel or occludes the bar.
 */
private val VIDEO_DEPTH = 24.dp

/**
 * Z of everything drawn over the video (subtitles, toasts, the hover layer). 14dp cleared the
 * panel while the Toolbar ornament was attached; once the bar auto-hides and the ornament is
 * gone the window's depth settles differently and 14dp falls behind the picture. 30dp clears
 * the panel in both states, and the overlays keep one depth instead of jumping.
 */
private val OVERLAY_Z = 30.dp
// The toolbar's transparent hit plane occupies OVERLAY_Z. Keep captions in front of it.
private val CAPTION_Z = OVERLAY_Z + 4.dp

/**
 * Where the Toolbar sits against the window, as SpatialUI 6.1.9 builds it (ToolbarKt / ToolbarDefaults):
 * an Augment on the window's bottom-front edge, its content's bottom 32dp below that edge, wrapped in
 * 12dp of padding. So of a bar whose content is H tall, H + 24 − 32 covers the window's bottom.
 */
private val TOOLBAR_PADDING = 12.dp
private val TOOLBAR_DROP = 32.dp

/** How far in front of the window's face the bar floats: at 0 the picture was drawn over it (B3110, 2026-10-23). */
private val BAR_LIFT = 48.dp

/**
 * The bar's content height until it has been measured once — the three rows: title, seek bar,
 * transport (with the 56dp play button). The real figure replaces it on the first layout.
 */
private val BAR_CONTENT_ESTIMATE = 138.dp

/**
 * How far past the bar's top edge the click layer stops. Generous on purpose — a click in the last
 * few dp above the bar not toggling costs nothing; a button under the layer costs the whole bar.
 */
private val BAR_MARGIN = 16.dp

/**
 * How far the bar comes down while the film plays on a place's screen (在环境的屏幕上播放). The window
 * holds no picture then, yet the bar still hangs from its foot — and from the seat it lay over the
 * lower quarter of the living room's TV (3 m off, its foot 0.6 m up), subtitles and all. The bar keeps
 * the Toolbar's place (TOOLBAR_DROP below the foot) — it was a Toolbar, whose Augment takes no other
 * offset — so the empty window grows by twice this — the system grows it about its centre — and its foot
 * comes down. Measured from a
 * standing eye: 140dp left the bar's top a degree under the TV's edge; 180dp leaves ~3°. Only for a
 * screen that reaches down that far ([com.therealityteam.videoplayer.player.ScreenPose.reachesBar]).
 */
private val SCREEN_BAR_DROP = 180.dp

/** The gap between the bottom subtitle line and the bar's top edge while the bar is up. */
private val SUBTITLE_BAR_GAP = 12.dp

/**
 * Video player inside the main WindowContainer (official-sample architecture). The video
 * renders on a SpatialView 3D panel; the playback bar is a [Toolbar] belonging to this main
 * window, which renders as a system layer in front of the video. Back returns to the library.
 */
@Composable
fun VideoScreen(vm: PlaybackViewModel, onBack: () -> Unit) {
    com.therealityteam.videoplayer.ui.JankWatch("player")
    val current by vm.current.collectAsState()
    val item = current ?: return

    val state by vm.manager.state.collectAsState()
    val duration by vm.manager.duration.collectAsState()
    val position by vm.manager.position.collectAsState()
    val immersive by vm.immersive.collectAsState()
    val cinemaDim by vm.cinemaDim.collectAsState()
    val environment by vm.environment.collectAsState()
    val capture by vm.capture.collectAsState()
    // 在环境的屏幕上播放: the film, and so its subtitles, are on the place's screen, not in this window.
    val onScreen by vm.onScreen.collectAsState()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()

    // Size the video mesh to the window's own dimensions (1280x720 dp) so it fills the window
    // plane exactly — the glass base panel never peeks out beside the video.
    val converter = LocalPhysicalLengthConverter.current
    val panelWidthM = converter.dpToLength(WINDOW_WIDTH_DP, LengthUnit.Meters)
    val panelHeightM = converter.dpToLength(WINDOW_HEIGHT_DP, LengthUnit.Meters)

    val cues by vm.manager.activeCues.collectAsState()
    androidx.compose.runtime.SideEffect { com.therealityteam.videoplayer.data.playback.CueClock.shown(cues) }
    val subtitleScale by vm.subtitleScale.collectAsState()
    val subtitleRaise by vm.subtitleRaise.collectAsState()
    val subtitleStyle by vm.subtitleStyle.collectAsState()
    val subtitleFont by vm.subtitleFont.collectAsState()
    val subtitleColor by vm.subtitleColor.collectAsState()
    val subtitleOutline by vm.subtitleOutline.collectAsState()
    val subtitleKeepStyles by vm.subtitleKeepStyles.collectAsState()
    val subtitleMenus by vm.subtitleMenus.collectAsState()
    // 第二字幕: its cue for this instant, read from the player itself every tenth of a second — the
    // shared position ticks twice a second, and a line half a second late reads as out of sync.
    val secondary by vm.secondary.collectAsState()
    val subtitleDelay by vm.subtitleDelayMs.collectAsState()
    val currentSubtitleDelay by rememberUpdatedState(subtitleDelay)
    val ass by vm.ass.collectAsState()
    val networkDown by vm.networkDown.collectAsState()
    val frameRect by vm.frameRect.collectAsState()
    val screenFrameRect by vm.screenFrameRect.collectAsState()
    val videoAspect by vm.manager.videoAspect.collectAsState()
    var secondaryLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(secondary, subtitleDelay) {
        val track = secondary?.track
        if (track == null) {
            secondaryLine = null
            return@LaunchedEffect
        }
        while (true) {
            secondaryLine = track.cueAt(vm.manager.positionNow() - subtitleDelay)?.text
            delay(100)
        }
    }
    val videoUnsupported by vm.manager.videoUnsupportedLabel.collectAsState()
    val failure by vm.manager.failure.collectAsState()

    // After a quiet spell while playing the bar RESTS (BarPlate): there, but drawn only while the eyes are on it, gone a
    // couple of seconds after they leave; after BAR_REST_MS of rest it goes altogether. Pause, a menu, a click anywhere
    // on the bar, or a click on the picture brings it back up and restarts the timer; a pointer the platform reports
    // RESTING on the bar holds it up for as long as it rests there. A click on the picture while the bar is up dismisses
    // it at once. [barVisible]: the bar is there (up or resting) — the picture's click layer, the subtitles and the
    // opening words keep clear of it; [barAwake]: it is up whatever the eyes do.
    val controlsTouchedAt by vm.controlsTouchedAt.collectAsState()
    val controlsDismissedAt by vm.controlsDismissedAt.collectAsState()
    val controlsHold by vm.controlsHold.collectAsState()
    val controlsHovered by vm.controlsHovered.collectAsState()
    var windowBarActive by remember { mutableStateOf(true) }
    val barVisible = windowBarActive && !immersive
    var barAwake by remember { mutableStateOf(true) }
    // The resting toolbar stays mounted for gaze input after its plate has disappeared.
    // Return a sign to its authored position as soon as that plate finishes fading.
    var coverBitmapSubtitles by remember { mutableStateOf(true) }
    LaunchedEffect(barVisible, barAwake, controlsHovered) {
        if (barVisible && !barAwake && !controlsHovered) delay((REST_FADE_DELAY_MS + REST_FADE_MS).toLong())
        coverBitmapSubtitles = barVisible && (barAwake || controlsHovered)
    }
    // How much of the window's bottom the bar covers, from its measured height: the click layer stops
    // above it, and the bottom subtitle line clears it. Both were constants sized for a two-row bar,
    // and the third row (what is playing) put the subtitles over the seek bar.
    var barContentHeight by remember { mutableStateOf(BAR_CONTENT_ESTIMATE) }
    // The window's picture waits for the bar: the bar is a window of its own, attached after the player's, and the
    // window's depth settles again once it is — for a moment at the start the picture stood in front of the bar and the
    // bar came forward through it (B3110, 2026-10-23). So the picture shows once the bar has laid out and settled.
    var barLaidOut by remember { mutableStateOf(false) }
    LaunchedEffect(barLaidOut) {
        vm.holdWindowPicture(!barLaidOut)
        delay(if (barLaidOut) BAR_SETTLE_MS else PICTURE_HOLD_LIMIT_MS)
        vm.holdWindowPicture(false)
    }
    DisposableEffect(Unit) { onDispose { vm.holdWindowPicture(false) } }
    val barCover = (barContentHeight + TOOLBAR_PADDING * 2 - TOOLBAR_DROP).coerceAtLeast(0.dp)
    LaunchedEffect(immersive, controlsTouchedAt, controlsDismissedAt, controlsHold, controlsHovered, state) {
        if (immersive) return@LaunchedEffect
        if (controlsDismissedAt > controlsTouchedAt) {
            barAwake = false
            windowBarActive = false
            return@LaunchedEffect
        }
        windowBarActive = true
        barAwake = true
        if (state == PlaybackState.PLAYING && controlsHold == 0 && !controlsHovered) {
            delay(BAR_QUIET_MS)
            barAwake = false
            delay(BAR_REST_MS)
            windowBarActive = false
        }
    }
    // The room of an environment dims while the bar is down or resting (see PlaybackViewModel.environmentDimmed).
    LaunchedEffect(barVisible, barAwake) { vm.setBarShown(barVisible && barAwake) }
    // A panel of the bar shuts on a click outside it, and the bar is built again then (barGeneration): a panel is a window
    // that takes the focus, and the focus did not come back to the bar's own window, whose hover stayed dead until the
    // bar had gone and come again (2026-10-23). [menuShutAt]: the click that shut a panel is not also the picture's.
    val menuOpen = barMenuOpen()
    var barGeneration by remember { mutableIntStateOf(0) }
    val menuShutAt = remember { longArrayOf(0L) }
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            menuWasOpen = true
        } else if (menuWasOpen) {
            menuWasOpen = false
            menuShutAt[0] = android.os.SystemClock.elapsedRealtime()
            barGeneration++
        }
    }
    // One click on the picture: up if it was down, down if it was up. It arrives as a short press
    // on the layer over the picture — the ray reaches that layer because the video entity is
    // sorted behind the window's 2D content (see ensurePanel). Guarded against a double delivery
    // all the same, which would toggle it straight back.
    val lastPictureClick = remember { longArrayOf(0L) }
    val onPictureClick: () -> Unit = {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPictureClick[0] > 300) {
            lastPictureClick[0] = now
            when {
                // With a panel open (or just shut by this same click, as a panel can hear it too) the click shuts the
                // panel and the bar stays.
                barMenuOpen() || now - menuShutAt[0] < MENU_SHUT_CLICK_MS -> { closeBarMenus(); vm.showControls() }
                barVisible && barAwake -> vm.dismissControls()
                else -> vm.showControls()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            vm.manager.refreshPosition()
            vm.enforceAbRepeat()
            delay(500)
        }
    }

    // While the film is on a screen that reaches down behind the bar, the empty window is taller (see
    // SCREEN_BAR_DROP), and it gets back the size it had — the user's own, if they resized it — when
    // the film leaves the screen. A far screen (the rooftop's, the island's) leaves the bar where it is.
    val context = LocalContext.current
    val pictureClick by rememberUpdatedState(onPictureClick)
    var accessiblePanel by remember { mutableStateOf<Entity?>(null) }
    var cinemaControls by remember { mutableStateOf<Entity?>(null) }
    LaunchedEffect(accessiblePanel, cinemaControls, state, immersive, onScreen, capture) {
        val status = context.getString(state.spokenStatus())
        accessiblePanel?.components?.get(AccessibilityComponent::class.java)?.apply {
            value = status
            isAccessibilityElement = !immersive && !onScreen && !capture
        }
        cinemaControls?.let {
            it.enabled = onScreen && !immersive && !capture
            it.components[AccessibilityComponent::class.java]?.value = status
        }
    }
    val density = LocalDensity.current
    val placeShown by vm.placeShown.collectAsState()
    val viewingSeat by vm.environmentScene.viewingSeat.collectAsState()
    val screenUnderBar = onScreen && placeShown?.screenPose(viewingSeat)?.reachesBar() == true
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    var grownFrom by remember { mutableStateOf<IntSize?>(null) }
    val ungrow: () -> Unit = {
        grownFrom?.let {
            SpatialContainerUtils.resizeCurrentWindowContainer(context, it.width, it.height)
            android.util.Log.i("AuraScreenBar", "window back to ${it.width}x${it.height}")
        }
        grownFrom = null
    }
    LaunchedEffect(screenUnderBar, windowSize != IntSize.Zero) {
        if (!screenUnderBar) {
            ungrow()
            return@LaunchedEffect
        }
        if (grownFrom != null || windowSize == IntSize.Zero) return@LaunchedEffect
        val drop = with(density) { SCREEN_BAR_DROP.roundToPx() }
        grownFrom = windowSize
        val sent = SpatialContainerUtils.resizeCurrentWindowContainer(context, windowSize.width, windowSize.height + 2 * drop)
        android.util.Log.i(
            "AuraScreenBar",
            "film on the screen: window ${windowSize.width}x${windowSize.height} grows to " +
                "${windowSize.width}x${windowSize.height + 2 * drop} (sent=$sent)",
        )
    }

    val close: () -> Unit = {
        scope.launch {
            ungrow()
            if (immersive || cinemaDim || environment != null) {
                navigator.closeStage(); vm.setImmersive(false); vm.setCinemaDim(false); vm.setEnvironment(null)
            }
            vm.saveProgress()
            vm.close()
            onBack()
        }
    }
    // The system's 返回 (the BACK key, a gamepad's B, a keyboard's Esc) closes the player as its own does. Unheard, it
    // finished the window under the film: nothing of the app left in the room, the film stalled buffering behind
    // it with its session still up, and where it was never kept.
    com.therealityteam.videoplayer.ui.SystemBack(what = "the player closes") { close() }

    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged {
            if (it != windowSize) android.util.Log.i("AuraScreenBar", "window laid out at ${it.width}x${it.height}")
            windowSize = it
        },
    ) {
        // Depth layering: the window's system base panel (Material.Regular) sits at the back of
        // the depth volume; the Toolbar ornament floats ~16dp in front of it. A SpatialView
        // would otherwise auto-raise its content by half the window depth (way in front of the
        // Toolbar, occluding it). So align to the back but give it a small depth so the video
        // sits just IN FRONT of the base panel (visible, not occluded by it on hardware) yet
        // still BEHIND the Toolbar.
        Box(modifier = Modifier.fillMaxSize().alignDepth(DepthAlignment.DepthBack)) {
            SpatialView(
                modifier = Modifier.fillMaxSize().depth(VIDEO_DEPTH),
                initial = { content, _ ->
                    vm.preparePanel(panelWidthM, panelHeightM, anyShape = true)
                    val panel = vm.ensurePanel(panelWidthM, panelHeightM)
                    content.addEntity(panel)
                    panel.components.set(AccessibilityComponent(
                        label = context.getString(R.string.player_screen),
                        value = context.getString(vm.manager.state.value.spokenStatus()),
                    ).apply { isAccessibilityElement = true })
                    content.setAccessibilityActions(entity = panel, semanticId = "aura.player.screen") {
                        onClick(label = context.getString(R.string.screen_toggle_controls)) { pictureClick(); true }
                        customActions = listOf(CustomAccessibilityAction(context.getString(R.string.screen_play_pause)) {
                            vm.manager.toggleFromScreen()
                        })
                    }
                    accessiblePanel = panel
                    // The wall screen is metres outside the stage host's accessibility bounds.
                    // Publish its controls on the same near-field plane that receives the viewer's input.
                    val cinema = Entity().apply {
                        setName("aura_cinema_controls")
                        enabled = false
                        val clear = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                            setBaseColor(Color4(0f, 0f, 0f, 0f))
                            setDepthWrite(false)
                        }
                        components.set(ModelComponent(MeshResource.createPlane(panelWidthM, panelHeightM), clear))
                        components.set(DynamicShadowComponent(false, false))
                    }
                    content.addEntity(cinema)
                    cinema.components.set(AccessibilityComponent(
                        label = context.getString(R.string.cinema_screen),
                        value = context.getString(vm.manager.state.value.spokenStatus()),
                    ).apply { isAccessibilityElement = true })
                    content.setAccessibilityActions(entity = cinema, semanticId = "aura.cinema.screen") {
                        onClick(label = context.getString(R.string.screen_play_pause)) { vm.manager.toggleFromScreen() }
                        customActions = listOf(CustomAccessibilityAction(context.getString(R.string.screen_show_controls)) {
                            vm.showControls()
                            true
                        })
                    }
                    cinemaControls = cinema
                },
            )
        }
        // The layer in front of the video panel that a click on the picture lands on. In Shared
        // Space it is also where the thumbstick arrives (as scroll: sideways steps the film,
        // up/down the volume) and where a held trigger asks for double speed. Hovering over the
        // picture no longer touches the bar — a click does, and it toggles, so a hover that woke
        // it would undo every dismissal.
        //
        // Three things about it were learned the hard way, each on the emulator's ray:
        //  - It is PAINTED, faintly. This window has no base panel, and the ray is tested
        //    against what the window paints — the bar's buttons, the video — so a layer that
        //    paints nothing is nothing to hit and a click on the picture landed on no one. One
        //    part in 255 of black is invisible over a moving picture; being painted is the point.
        //  - It is in FRONT of the SpatialView, in this order and at this depth. Painted behind
        //    it (on the root), the 3D view's region hid it from the ray again.
        //  - It stops short of the bar. At 30dp it is in front of the Toolbar's plane, and where
        //    the two overlapped — the window's bottom band while the bar is up — it took the
        //    bar's clicks and the buttons died. So while the bar is up the layer ends above the
        //    band, and the band is the bar's; when the bar is down there is no band.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (barVisible) barCover + BAR_MARGIN else 0.dp)
                .offset(z = OVERLAY_Z)
                .background(Color.Black.copy(alpha = 1f / 255f))
                .pointerInput(Unit) {
                    var stick = ControllerModel.State()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Scroll) {
                                val d = event.changes.firstOrNull()?.scrollDelta ?: continue
                                // A scroll tick is a push; treat it as a stick at full deflection
                                // for one frame so the model's dead zone and cadence apply.
                                val (next, intents) = ControllerModel.step(
                                    stick, x = d.x.coerceIn(-1f, 1f), y = (-d.y).coerceIn(-1f, 1f),
                                    triggerDown = false, nowMs = android.os.SystemClock.elapsedRealtime(),
                                )
                                stick = ControllerModel.step(next, 0f, 0f, false, android.os.SystemClock.elapsedRealtime()).first
                                intents.forEach(vm::onControllerIntent)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 全景 in the window: a drag looks round it (the picture follows the hand); a
                        // press that does not move is a click, as anywhere else.
                        if (vm.panoInWindow) {
                            var moved = false
                            var last = down.position
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) { if (!moved) onPictureClick(); break }
                                if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (moved) {
                                    val d = change.position - last
                                    vm.lookPanoBy(d.x / size.width, d.y / size.height)
                                    change.consume()
                                }
                                last = change.position
                            }
                            return@awaitEachGesture
                        }
                        val released = withTimeoutOrNull(ControllerModel.HOLD_MS) { waitForUpOrCancellation() }
                        if (released == null) {
                            vm.holdBoost(true)
                            // Released, or the layer gone mid-hold: the chosen speed comes back either way.
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                vm.holdBoost(false)
                            }
                        } else {
                            // A short press on the picture, released where it began: a click.
                            onPictureClick()
                        }
                    }
                },
        )
        // In Full Space the window's pointer stream stops carrying the stick; the tracking
        // provider carries every button, trigger and thumbstick value instead (and only there —
        // it reports NOT_IN_FULL_SPACE otherwise).
        if (immersive || cinemaDim || environment != null) ControllerInput(vm)
        // 开场: until a film's first picture, its place shows the film — not a black panel, nor the
        // last film's last frame — and the picture fades in over it as it comes.
        val firstFrame by vm.manager.hasFirstFrame.collectAsState()
        // A seek takes the film through a moment of waiting on its data (0.3–2 s): no news. The bar's
        // button stays what it was rather than turning to ▶ and back on every skip, and 加载中 waits
        // a moment before it says so ([PlaybackViewModel.waitingLong]) — it flashed over the picture on each one. A film
        // not yet begun, or a network gone, is told at once as before.
        var playingIntent by remember { mutableStateOf(false) }
        LaunchedEffect(state) {
            when (state) {
                PlaybackState.PLAYING -> playingIntent = true
                PlaybackState.PAUSED, PlaybackState.READY, PlaybackState.ERROR, PlaybackState.INIT -> playingIntent = false
                PlaybackState.PREPARING -> Unit
            }
        }
        val slowLoad by vm.waitingLong.collectAsState()
        val waitingQuietly = state == PlaybackState.PREPARING && firstFrame && !slowLoad && !networkDown
        val opening = current != null && !firstFrame && state != PlaybackState.ERROR && videoUnsupported == null &&
            !onScreen && !immersive
        val openingAlpha by animateFloatAsState(
            targetValue = if (opening) 1f else 0f,
            // In at once (it hides what was there), out slowly (the film arrives through it).
            animationSpec = tween(if (opening) 0 else OPENING_FADE_MS),
            label = "opening",
        )
        val openingItem = current
        // Still opening while the clock has not moved: mpv reports playing as soon as the file is
        // read, and waits on the picture after that (seconds, on a Blu-ray's hardware-decoder fallback).
        // A file with no picture plays on, its clock moving, and the spinner goes.
        var openedAt by remember(openingItem?.id) { mutableStateOf<Long?>(null) }
        var clockMoved by remember(openingItem?.id) { mutableStateOf(false) }
        LaunchedEffect(openingItem?.id, position, state) {
            if (state != PlaybackState.PLAYING || clockMoved) return@LaunchedEffect
            val from = openedAt ?: position.also { openedAt = it }
            if (kotlin.math.abs(position - from) > OPENING_CLOCK_MOVED_MS) clockMoved = true
        }
        // A capture (-e aura.env_capture) leaves it out too: a file with no picture (the silent track the emulator plays
        // a place with — its video sampling crashes MoltenVK) keeps its card up for good.
        if (openingAlpha > 0f && openingItem != null && !capture) {
            val told by vm.told.collectAsState()
            val run by vm.numberedRun.collectAsState()
            // Its words clear the bar while it shows (the bar is the one thing on screen to press), as
            // the subtitles' bottom line does.
            val wordsBottom by animateDpAsState(if (barVisible) barCover + SUBTITLE_BAR_GAP else OPENING_MARGIN, label = "openingWords")
            OpeningCard(
                item = openingItem,
                subtitle = barSubtitle(LocalContext.current, openingItem, told[openingItem.id]?.name, run),
                loading = state == PlaybackState.PREPARING || (state == PlaybackState.PLAYING && !clockMoved),
                wordsBottom = wordsBottom,
                modifier = Modifier.fillMaxSize().offset(z = OVERLAY_Z - 1.dp).alpha(openingAlpha),
            )
        }
        // 暂停: a pause left alone a while shows what is paused — its name, this episode's story, how long
        // is left and when it would end — over the picture's left, clear of the bar. Anything done
        // with the player puts it away at once.
        val pausedSince = remember(state) { android.os.SystemClock.elapsedRealtime() }
        var pauseCardUp by remember { mutableStateOf(false) }
        LaunchedEffect(state, controlsTouchedAt, controlsHold, controlsHovered, onScreen, immersive) {
            pauseCardUp = false
            if (state != PlaybackState.PAUSED || controlsHold != 0 || controlsHovered || onScreen || immersive || current == null) return@LaunchedEffect
            delay(PAUSE_CARD_AFTER_MS)
            android.util.Log.i("AuraPause", "card up ${android.os.SystemClock.elapsedRealtime() - pausedSince} ms into the pause")
            pauseCardUp = true
        }
        val pauseAlpha by animateFloatAsState(
            targetValue = if (pauseCardUp) 1f else 0f,
            animationSpec = tween(if (pauseCardUp) PAUSE_CARD_IN_MS else PAUSE_CARD_OUT_MS),
            label = "pauseCard",
        )
        val pausedItem = current
        if (pauseAlpha > 0f && pausedItem != null) {
            val told by vm.told.collectAsState()
            val run by vm.numberedRun.collectAsState()
            val duration by vm.manager.duration.collectAsState()
            val speed by vm.speed.collectAsState()
            PauseCard(
                item = pausedItem,
                subtitle = barSubtitle(LocalContext.current, pausedItem, told[pausedItem.id]?.name, run),
                // This episode's story — a Japanese one is none for a Chinese reader — else the show's.
                story = com.therealityteam.videoplayer.ui.common.readableSynopsis(told[pausedItem.id]?.overview)
                    ?: com.therealityteam.videoplayer.ui.common.readableSynopsis(pausedItem.overview),
                leftMs = ((duration - position).coerceAtLeast(0L) / speed.coerceAtLeast(0.25f)).toLong().takeIf { duration > 0 },
                modifier = Modifier.fillMaxSize().offset(z = OVERLAY_Z - 1.dp).alpha(pauseAlpha),
            )
        }
        // Every overlay sits at OVERLAY_Z: the panel keeps the previous film's last frame while the
        // next one loads, and a capsule left on the window plane would sit behind it.
        when {
            // The opening card says it already, on its own picture.
            opening -> Unit
            waitingQuietly -> Unit
            state == PlaybackState.PREPARING -> {
                // A film that keeps stopping because its file comes slower than it plays says why, and what helps.
                val slow by vm.slowLink.collectAsState()
                val sourceDown by vm.manager.sourceDown.collectAsState()
                val server = current?.path?.let(::hostOf)
                StatusCapsule(
                    // Waiting on a network that is gone says so — and that it goes on by itself; a server that has
                    // stopped answering while the network is up, by its name.
                    when {
                        networkDown -> stringResource(R.string.vid_waiting_network)
                        sourceDown && server != null -> stringResource(R.string.vid_waiting_server, server)
                        sourceDown -> stringResource(R.string.vid_waiting_source)
                        else -> stringResource(R.string.vid_loading)
                    },
                    PicoTheme.colorScheme.labelSecondary,
                    Modifier.align(Alignment.Center).offset(z = OVERLAY_Z),
                    loading = true,
                    detail = slow?.takeIf { !networkDown }?.let {
                        stringResource(R.string.vid_slow_link, megabytes(it.inBytesPerSec), megabytes(it.playBytesPerSec))
                    },
                )
            }
            state == PlaybackState.ERROR -> FailureCard(
                failure = failure,
                host = current?.path?.let(::hostOf),
                format = current?.fileName?.let(PlaybackFailure::legacyFormat) ?: vm.manager.failedFormat,
                onRetry = { vm.retry() },
                networkDown = networkDown,
                onHeadset = if (failure == PlaybackFailure.UNREACHABLE) rememberCopyInQueue(vm) else null,
                oversize = vm.manager.oversize,
                modifier = Modifier.align(Alignment.Center).offset(z = OVERLAY_Z),
            )
            videoUnsupported != null -> {
                // The panel keeps the PREVIOUS video's last frame when the new one never
                // decodes — black it out so the message isn't floating over a stale movie.
                Box(
                    modifier = Modifier.fillMaxSize().offset(z = OVERLAY_Z - 1.dp).background(Color.Black),
                )
                StatusCapsule(
                    stringResource(R.string.vid_no_video_decoder, videoUnsupported.orEmpty()),
                    PicoTheme.colorScheme.labelPrimary,
                    Modifier.align(Alignment.Center).offset(z = OVERLAY_Z),
                )
            }
            else -> Unit
        }

        if (onScreen) {
            // A .ass on a place's screen: libass's picture on the film there, in place of its plain lines.
            ass?.let { renderer ->
                AssScreenFeed(
                    renderer = renderer,
                    captions = vm.screenCaptions,
                    scale = subtitleScale,
                    frameRect = screenFrameRect,
                    videoAspect = videoAspect,
                    playing = state == PlaybackState.PLAYING,
                    clockMs = { vm.manager.positionNow() - currentSubtitleDelay },
                )
            }
            ScreenCaptionFeed(
                captions = vm.screenCaptions,
                cues = if (ass != null) emptyList() else cues,
                look = CaptionLook(
                    scale = subtitleScale,
                    raise = subtitleRaise,
                    style = subtitleStyle,
                    font = subtitleFont,
                    color = subtitleColor.argb.toInt(),
                    outlineDp = subtitleOutline,
                    keepStyles = subtitleKeepStyles,
                    preview = subtitleMenus.preview,
                    frameRect = screenFrameRect,
                ),
                keepLine = subtitleMenus.keepLine && ass == null,
                secondLine = secondaryLine,
            )
        } else if (immersive) {
            // 沉浸空间: the lines on their own face ahead of the head (vm.sphereCaptions), not in the
            // window's plane behind the turned head. A .ass's typesetting belongs on a frame; there is
            // none round the viewer, so its plain lines are read.
            ScreenCaptionFeed(
                captions = vm.sphereCaptions,
                cues = cues,
                look = CaptionLook(
                    // The window's size was a line about a degree high round the viewer: too small to read.
                    scale = subtitleScale * PANO_CAPTION_SCALE,
                    raise = subtitleRaise,
                    style = subtitleStyle,
                    font = subtitleFont,
                    color = subtitleColor.argb.toInt(),
                    outlineDp = subtitleOutline,
                    keepStyles = subtitleKeepStyles,
                    preview = subtitleMenus.preview,
                ),
                keepLine = subtitleMenus.keepLine,
                secondLine = secondaryLine,
            )
        } else {
            // A .ass beside a file Media3 plays: libass's picture of it, laid on the frame — and the
            // engine's plain lines of it not drawn as well. The second subtitle stays a line under it.
            val assShown = ass != null && !immersive
            ass?.takeIf { assShown }?.let { renderer ->
                AssOverlay(
                    renderer = renderer,
                    frameRect = frameRect,
                    playing = state == PlaybackState.PLAYING,
                    clockMs = { vm.manager.positionNow() - currentSubtitleDelay },
                    lift = if (barVisible) (barCover + SUBTITLE_BAR_GAP - ASS_REST_BOTTOM).coerceAtLeast(0.dp) else 0.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SubtitleOverlay(
                if (assShown) emptyList() else cues,
                frameRect = frameRect,
                scale = subtitleScale,
                raiseDp = WINDOW_HEIGHT_DP * subtitleRaise,
                style = subtitleStyle,
                barVisible = barVisible,
                coverBitmaps = coverBitmapSubtitles,
                barClearance = barCover + SUBTITLE_BAR_GAP,
                font = subtitleFont,
                color = subtitleColor,
                outlineDp = subtitleOutline,
                keepStyles = subtitleKeepStyles,
                preview = subtitleMenus.preview,
                // libass's line is up already: no sample line in a quiet stretch.
                keepLine = subtitleMenus.keepLine && !assShown,
                secondLine = secondaryLine,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 截图 says where it is: "正在截图…" with the system spinner for as long as the frame is decoded
        // again (seconds, over a share), then saved or failed for a moment — in the top stack below,
        // so it no longer lands on the 续看 prompt. (Not the system snackbar: it opens 80dp above
        // the window's bottom edge, which this three-row bar covers while it is up.)
        val snapshotting by vm.snapshotting.collectAsState()
        val snapshotNotice by vm.snapshotNotice.collectAsState()
        var snapshotOutcome by remember { mutableStateOf<PlaybackViewModel.SnapshotStatus?>(null) }
        LaunchedEffect(snapshotNotice) {
            val notice = snapshotNotice ?: return@LaunchedEffect
            if (!vm.claimSnapshotNotice(notice)) return@LaunchedEffect
            if (notice.status == PlaybackViewModel.SnapshotStatus.WORKING) {
                snapshotOutcome = null
                return@LaunchedEffect
            }
            snapshotOutcome = notice.status
            delay(SNAPSHOT_OUTCOME_MS)
            snapshotOutcome = null
        }
        // A picture software cannot keep up with (a camera's 4K60 4:2:2, which no decoder here takes):
        // said once a file, for a while, so the stutter is not taken for a broken player or network.
        val decodeBehind by vm.manager.decodeBehind.collectAsState()
        var decodeNotice by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(decodeBehind) {
            val name = decodeBehind ?: return@LaunchedEffect
            decodeNotice = name
            delay(DECODE_BEHIND_MS)
            decodeNotice = null
        }
        // Snapshot deep link (-e aura.snapshot 1).
        val snapReq by com.therealityteam.videoplayer.platform.DeepLink.snapshotRequest.collectAsState()
        LaunchedEffect(snapReq) {
            if (snapReq) {
                com.therealityteam.videoplayer.platform.DeepLink.snapshotRequest.value = false
                vm.snapshot()
            }
        }
        // Frame dump (-e aura.frame_dump <name>), once a picture is up.
        val frameDumpReq by com.therealityteam.videoplayer.platform.DeepLink.frameDumpRequest.collectAsState()
        LaunchedEffect(frameDumpReq, firstFrame) {
            val name = frameDumpReq ?: return@LaunchedEffect
            if (!firstFrame) return@LaunchedEffect
            com.therealityteam.videoplayer.platform.DeepLink.frameDumpRequest.value = null
            vm.dumpFrame(name)
        }
        // 去黑边 deep link (-e aura.crop l,t,r,b|off|probe), once the film plays.
        val cropReq by com.therealityteam.videoplayer.platform.DeepLink.cropRequest.collectAsState()
        LaunchedEffect(cropReq, state) {
            val request = cropReq ?: return@LaunchedEffect
            if (state != PlaybackState.PLAYING) return@LaunchedEffect
            com.therealityteam.videoplayer.platform.DeepLink.cropRequest.value = null
            vm.debugCrop(request)
        }
        // Audio-sync deep link (-e aura.audio_delay_ms 300): an absolute offset, for automation.
        val delayReq by com.therealityteam.videoplayer.platform.DeepLink.audioDelayMsRequest.collectAsState()
        LaunchedEffect(delayReq) {
            delayReq?.let {
                com.therealityteam.videoplayer.platform.DeepLink.audioDelayMsRequest.value = null
                vm.setAudioDelay(it)
            }
        }
        // 倍速 deep link (-e aura.speed 2), for automation.
        val speedReq by com.therealityteam.videoplayer.platform.DeepLink.speedRequest.collectAsState()
        LaunchedEffect(speedReq) {
            speedReq?.let {
                com.therealityteam.videoplayer.platform.DeepLink.speedRequest.value = null
                android.util.Log.i("AuraAV", "倍速 ${it}× by automation")
                vm.setSpeed(it)
            }
        }
        // 续看: the film resumed where it was left; one tap goes back to the start. The
        // prompt shows for eight seconds, or until the film is restarted.
        val resumedFrom by vm.resumedFromMs.collectAsState()
        val panoGuess by vm.panoGuess.collectAsState()
        LaunchedEffect(resumedFrom) {
            if (resumedFrom != null) {
                delay(8_000)
                vm.dismissResumePrompt()
            }
        }
        // 跳过片头: the file started past its show's opening, or 自动跳过片头 skipped the one its chapters
        // name — for six seconds, with a way back to it.
        val introSkippedTo by vm.introSkippedTo.collectAsState()
        LaunchedEffect(introSkippedTo) {
            if (introSkippedTo != null) {
                delay(6_000)
                vm.dismissIntroSkipped()
            }
        }
        // 跳过片头 / 跳过片尾 by the file's own chapter names: up for the chapter's first seconds, then
        // while the bar is — there when wanted, not across the picture for the whole song.
        val chapterSkip by vm.chapterSkip.collectAsState()
        var skipFresh by remember { mutableStateOf(false) }
        LaunchedEffect(chapterSkip?.chapterIndex, chapterSkip?.kind) {
            skipFresh = chapterSkip != null
            if (skipFresh) {
                delay(SKIP_OFFER_FRESH_MS)
                skipFresh = false
            }
        }
        // Automation (`-e aura.chapter_skip 1`): press 跳过 a few seconds after the offer comes up, so a
        // screenshot can catch the offer first — the button is a spatial control `input` cannot reach.
        val chapterSkipReq by com.therealityteam.videoplayer.platform.DeepLink.chapterSkipRequest.collectAsState()
        LaunchedEffect(chapterSkipReq, chapterSkip != null) {
            if (!chapterSkipReq || chapterSkip == null) return@LaunchedEffect
            delay(SKIP_HOOK_DELAY_MS)
            vm.skipChapter()
            com.therealityteam.videoplayer.platform.DeepLink.chapterSkipRequest.value = false
        }
        // Top centre, stacked so they never overlap: the 续看 prompt, the snapshot's progress and
        // outcome, and "2×" while a held trigger (or a held press on the picture) plays at double
        // speed — the 倍速 tiles keep the chosen speed lit, so without it nothing said why the film raced.
        val speedHeld by vm.speedHeld.collectAsState()
        // 虚拟环境: a place being read — before its stage opens, or on it — and one that could not be
        // read, said here too: the panel may be closed (or never opened: a deep link) while it loads.
        val placeLoading by vm.placeLoading.collectAsState()
        val placeFailed by vm.placeFailed.collectAsState()
        LaunchedEffect(placeFailed) {
            if (placeFailed == null) return@LaunchedEffect
            delay(PLACE_FAILED_MS)
            vm.clearPlaceFailed()
        }
        LaunchedEffect(vm, navigator) {
            vm.closeStage.collect { close ->
                if (close) {
                    vm.stageClosed()
                    navigator.closeStage()
                }
            }
        }
        Column(
            // In a window grown for a screen its top is higher by the drop; the stack keeps its place.
            modifier = Modifier.align(Alignment.TopCenter).offset(z = OVERLAY_Z)
                .padding(top = 40.dp + if (grownFrom != null) SCREEN_BAR_DROP else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            resumedFrom?.let { from ->
                OverlayToast(text = stringResource(R.string.vid_resumed_at, formatClock(from))) {
                    Button(
                        onClick = { vm.restartFromBeginning() },
                        size = ButtonDefaults.Small,
                        colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.vid_from_start)) }
                }
            }
            // 全景 by its look: a film with no word of its own that looks like a panorama — asked, never assumed.
            panoGuess?.let { guess ->
                OverlayToast(text = stringResource(panoGuessText(guess))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.acceptPanoGuess() }, size = ButtonDefaults.Small) { Text(stringResource(R.string.vid_pano_guess_play)) }
                        Button(onClick = { vm.dismissPanoGuess() }, size = ButtonDefaults.Small, colors = secondaryButtonColors()) {
                            Text(stringResource(R.string.vid_pano_guess_no))
                        }
                    }
                }
            }
            if (introSkippedTo != null) {
                OverlayToast(text = stringResource(R.string.vid_intro_skipped)) {
                    Button(
                        onClick = { vm.watchIntro() },
                        size = ButtonDefaults.Small,
                        colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.vid_watch_intro)) }
                }
            }
            chapterSkip?.let { offer ->
                if (skipFresh || barVisible) {
                    val left = formatClock((offer.endMs - position).coerceAtLeast(0L))
                    OverlayToast(
                        text = stringResource(
                            if (offer.kind == com.therealityteam.videoplayer.data.playback.ChapterSkip.Kind.INTRO) R.string.vid_chapter_intro
                            else R.string.vid_chapter_outro,
                            left,
                        ),
                    ) {
                        Button(
                            onClick = { vm.skipChapter() },
                            size = ButtonDefaults.Small,
                            colors = secondaryButtonColors(),
                        ) { Text(stringResource(R.string.vid_skip)) }
                    }
                }
            }
            if (snapshotting) {
                OverlayToast(
                    text = stringResource(R.string.pc_snapshot_working),
                    leading = { CircularProgressIndicator(progressSize = CircularProgressDefaults.Small) },
                )
            }
            snapshotOutcome?.let {
                OverlayToast(
                    text = stringResource(
                        if (it == PlaybackViewModel.SnapshotStatus.SAVED) R.string.pc_snapshot_saved else R.string.pc_snapshot_failed,
                    ),
                )
            }
            if (speedHeld) OverlayToast(text = stringResource(R.string.pc_hold_speed))
            placeLoading?.let { place ->
                OverlayToast(
                    text = stringResource(R.string.pc_env_entering, stringResource(place.label)),
                    leading = { CircularProgressIndicator(progressSize = CircularProgressDefaults.Small) },
                ) {
                    Button(
                        onClick = { vm.cancelRoomChange(place) },
                        size = ButtonDefaults.Small, colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.cancel)) }
                }
            }
            placeFailed?.let { place -> OverlayToast(text = stringResource(R.string.pc_env_failed, stringResource(place.label))) }
            decodeNotice?.let { OverlayToast(text = stringResource(R.string.vid_decode_behind, it)) }
        }

        // 连播: the next item is announced with a countdown; the card starts it, or not.
        val nextUp by vm.nextUp.collectAsState()
        // A card of its own per item, so one item's picture never lingers on the next one's card.
        val told by vm.told.collectAsState()
        val run by vm.numberedRun.collectAsState()
        nextUp?.let { next -> key(next.item.id) {
            // The film has stopped (not credits still running under a 片尾 mark): the next one's picture
            // fills the panel behind the card, rather than the black of a finished film.
            if (state != PlaybackState.PLAYING && !onScreen && !immersive) {
                NextUpBackdrop(next.item, Modifier.fillMaxSize().offset(z = OVERLAY_Z - 1.dp))
            }
            NextUpCard(
                item = next.item,
                info = told[next.item.id],
                numberedRun = run,
                startsAtMs = next.startsAtMs,
                stillWatching = next.stillWatching,
                onPlayNow = { vm.playNextUp() },
                onCancel = { vm.cancelNextUp() },
                modifier = Modifier.align(Alignment.Center),
            )
        } }

        // 看完了: the queue played to its end — what to do now, and what to watch next.
        val finished by vm.finished.collectAsState()
        finished?.let { item -> key(item.id) {
            // As at 接下来播放: the film's own picture behind the card once it has stopped, not black.
            if (state != PlaybackState.PLAYING && !onScreen && !immersive) {
                NextUpBackdrop(item, Modifier.fillMaxSize().offset(z = OVERLAY_Z - 1.dp))
            }
            FinishedCard(
                item = item,
                onReplay = { vm.replay() },
                onClose = { vm.dismissFinished() },
                onBack = close,
                onOpen = { card ->
                    com.therealityteam.videoplayer.platform.DeepLink.openKeyRequest.value = card.key
                    close()
                },
                // Centred in the picture above the bar — the bar stays up at the end, and a card
                // centred on the whole window sat on its top edge.
                modifier = Modifier.align(Alignment.Center).padding(bottom = barCover),
            )
        } }

        // 虚拟环境: from the window, the environment is set BEFORE the stage opens (the stage shows
        // what the view model holds as it composes); on an open stage it is swapped in place; null leaves.
        val selectEnvironment: (Environment?) -> Unit = { vm.selectEnvironment(navigator, it) }

        // A space the system closed (its task taken away from outside) left the switches saying it was up: the
        // film stayed on the place's screen, gone with it, and the window showed nothing while it played on.
        // A stage that would not open did the same. With no space up for a while, the film comes back here.
        val inSpace = immersive || cinemaDim || environment != null
        LaunchedEffect(inSpace) {
            if (!inSpace) return@LaunchedEffect
            com.therealityteam.videoplayer.ui.immersive.StageScenes.open.collectLatest { scenes ->
                if (scenes > 0) return@collectLatest
                delay(SPACE_GONE_MS)
                android.util.Log.i("AuraWindows", "the space is gone without us: the film back to its window")
                vm.setImmersive(false)
                vm.setCinemaDim(false)
                vm.setEnvironment(null)
            }
        }

        // Space deep link (-e aura.space immersive|cinema|env): the same switch the 显示 panel flips.
        val spaceReq by com.therealityteam.videoplayer.platform.DeepLink.spaceRequest.collectAsState()
        LaunchedEffect(spaceReq, state) {
            val wanted = spaceReq ?: return@LaunchedEffect
            if (state != PlaybackState.PLAYING) return@LaunchedEffect   // wait for the film to be up
            delay(500)
            when (wanted) {
                "immersive" -> if (!immersive) { if (cinemaDim || environment != null) navigator.closeStage(); navigator.openStage(STAGE_ID, StageStyle.Full); vm.setImmersive(true) }
                "cinema" -> if (!cinemaDim) { if (immersive || environment != null) navigator.closeStage(); navigator.openStage(STAGE_ID, StageStyle.Progressive); vm.setCinemaDim(true) }
                "env", "room" -> if (environment == null) selectEnvironment(vm.lastEnvironment())
            }
            // Consumed only now: clearing the request first re-keys this effect and cancels the wait.
            com.therealityteam.videoplayer.platform.DeepLink.spaceRequest.value = null
        }

        // 全景视频自动沉浸: a 360°/180° film, once its file has been read and it plays, opens in 沉浸空间 by
        // itself (once per film — taken back to the window by hand, it stays there); a flat film after one
        // taken in so goes back to the window. A film taken in by hand is left where it was.
        val pano by vm.pano.collectAsState()
        val panoSettled by vm.panoSettled.collectAsState()
        var panoHandled by remember { mutableStateOf<String?>(null) }
        var autoImmersedFor by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(pano, panoSettled, current?.id, state, immersive) {
            val id = current?.id ?: return@LaunchedEffect
            if (panoSettled != id || state != PlaybackState.PLAYING) return@LaunchedEffect
            if (pano != null) {
                if (panoHandled == id) return@LaunchedEffect
                panoHandled = id
                when {
                    immersive -> if (autoImmersedFor != null) autoImmersedFor = id
                    vm.panoAutoImmersive && !cinemaDim && environment == null -> {
                        android.util.Log.i("AuraPano", "opens immersed: ${pano?.projection} ${pano?.stereo}")
                        navigator.openStage(STAGE_ID, StageStyle.Full); vm.setImmersive(true)
                        autoImmersedFor = id
                    }
                }
            } else if (immersive && autoImmersedFor != null && autoImmersedFor != id) {
                android.util.Log.i("AuraPano", "a flat film: back to the window")
                navigator.closeStage(); vm.setImmersive(false)
                autoImmersedFor = null
            } else if (!immersive) {
                autoImmersedFor = null
            }
        }

        // Automation (-e aura.pano guess:accept | guess:dismiss): answer 全景播放 / 不是 a few seconds after it is asked.
        val guessNow by vm.panoGuess.collectAsState()
        val guessReq by com.therealityteam.videoplayer.platform.DeepLink.panoRequest.collectAsState()
        LaunchedEffect(guessReq, guessNow != null) {
            val answer = guessReq?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("guess:") } ?: return@LaunchedEffect
            if (guessNow == null) return@LaunchedEffect
            delay(4_000)
            if (answer == "guess:accept") vm.acceptPanoGuess() else vm.dismissPanoGuess()
        }

        // Panorama deep link (-e aura.pano turn:90,tilt:-60,recenter,window,look:90:10): once the sphere is up, the
        // 画面方向 buttons (and a tilt, to bring a pole into the emulator's still view); back to the window and a look there.
        val panoReq by com.therealityteam.videoplayer.platform.DeepLink.panoRequest.collectAsState()
        val immersiveNow by rememberUpdatedState(immersive)
        val stateNow by rememberUpdatedState(state)
        LaunchedEffect(panoReq) {
            val steps = panoReq ?: return@LaunchedEffect
            // Keyed on the request alone: a step that leaves the stage must not cut the rest short.
            snapshotFlow { immersiveNow && stateNow == PlaybackState.PLAYING }.first { it }
            delay(3_000)   // the sphere made, and turned to the head once
            steps.split(',').map { it.trim() }.forEach { step ->
                when {
                    step == "window" -> { navigator.closeStage(); vm.setImmersive(false); delay(1_000) }
                    step.startsWith("level:") -> step.removePrefix("level:").split(':').mapNotNull { it.toFloatOrNull() }
                        .takeIf { it.size == 2 }?.let { (p, r) -> vm.nudgeLevel(p, r) }
                    step == "level" -> vm.resetLevel()
                    step == "pause" -> vm.manager.pause()
                    step == "format:flat" -> vm.setVideoMode(com.therealityteam.videoplayer.data.model.StereoMode.MONO, com.therealityteam.videoplayer.data.model.ProjectionMode.FLAT)
                    step == "remote:turn" -> vm.remoteTurning.value = true
                    step == "remote:format" -> vm.remoteFormat.value = true
                    step.startsWith("menu:") -> { vm.showControls(); delay(1_500); com.therealityteam.videoplayer.platform.DeepLink.openMenuRequest.value = step.removePrefix("menu:"); delay(1_500) }
                    step.startsWith("look:") -> step.removePrefix("look:").split(':').mapNotNull { it.toFloatOrNull() }
                        .takeIf { it.size == 2 }?.let { (y, p) -> vm.lookPanoTo(y, p) }
                    step == "recenter" -> vm.recenterPano()
                    step.startsWith("turn:") -> step.removePrefix("turn:").toFloatOrNull()?.let { vm.turnPano(it) }
                    step.startsWith("tilt:") -> step.removePrefix("tilt:").toFloatOrNull()?.let { vm.tiltPano(it) }
                }
            }
            com.therealityteam.videoplayer.platform.DeepLink.panoRequest.value = null
        }

        // Sleep deep link (-e aura.sleep END_OF_ITEM|M15|…): the timer the 播放 panel sets, once the film is up.
        val sleepReq by com.therealityteam.videoplayer.platform.DeepLink.sleepRequest.collectAsState()
        LaunchedEffect(sleepReq, state) {
            val wanted = sleepReq ?: return@LaunchedEffect
            if (state != PlaybackState.PLAYING) return@LaunchedEffect
            com.therealityteam.videoplayer.data.playback.SleepMode.entries.firstOrNull { it.name == wanted }?.let { vm.setSleepMode(it) }
            com.therealityteam.videoplayer.platform.DeepLink.sleepRequest.value = null
        }

        val closeReq by com.therealityteam.videoplayer.platform.DeepLink.closePlayerRequest.collectAsState()
        LaunchedEffect(closeReq) {
            val asked = closeReq ?: return@LaunchedEffect
            com.therealityteam.videoplayer.platform.DeepLink.closePlayerRequest.value = null
            // Sent with no player up, it waited — and closed the next film the moment its window came (a sweep's
            // first file never played): only a request of the last few seconds closes.
            if (android.os.SystemClock.elapsedRealtime() - asked < 3_000) close()
        }

        // Track deep link (-e aura.track sub:<text>|sub:off|sub2:<text>|sub2:off|audio:<text>, several
        // joined by ';'): picks, once the file's tracks are known — all of them, or it waits for more.
        val benchReq by com.therealityteam.videoplayer.platform.DeepLink.seekBenchRequest.collectAsState()
        LaunchedEffect(benchReq) {
            val steps = benchReq?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return@LaunchedEffect
            // Keyed on the request alone: the seeks themselves take the state through buffering.
            vm.manager.state.first { it == PlaybackState.PLAYING }
            delay(3_000)
            steps.forEach { s -> vm.seekBy(s * 1000L); delay(4_000) }
            android.util.Log.i("AuraPerf", "seek bench done: ${steps.size} seeks")
            // Cleared last: the request is this effect's key, and clearing it ends the effect.
            com.therealityteam.videoplayer.platform.DeepLink.seekBenchRequest.value = null
        }
        val framesReq by com.therealityteam.videoplayer.platform.DeepLink.frameStepRequest.collectAsState()
        LaunchedEffect(framesReq) {
            val steps = framesReq?.split(',')?.map { it.trim() }?.filter { it == "f" || it == "b" } ?: return@LaunchedEffect
            vm.manager.state.first { it == PlaybackState.PLAYING }
            delay(2_000)
            steps.forEach { s ->
                vm.stepFrame(forward = s == "f")
                delay(1_500)
                android.util.Log.i("AuraFrames", "step $s → ${vm.manager.position.value} ms")
            }
            com.therealityteam.videoplayer.platform.DeepLink.frameStepRequest.value = null
        }
        val trackReq by com.therealityteam.videoplayer.platform.DeepLink.trackRequest.collectAsState()
        val subtitleTracks by vm.manager.subtitleTracks.collectAsState()
        val audioTracks by vm.manager.audioTracks.collectAsState()
        LaunchedEffect(trackReq, state, subtitleTracks.size, audioTracks.size) {
            val wanted = trackReq ?: return@LaunchedEffect
            if (state != PlaybackState.PLAYING) return@LaunchedEffect
            val picks = wanted.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            fun resolves(pick: String): Boolean {
                val kind = pick.substringBefore(':')
                val text = pick.substringAfter(':')
                return when {
                    text == "off" -> kind == "sub" || kind == "sub2"
                    kind == "sub" || kind == "sub2" -> subtitleTracks.any { it.name.contains(text, ignoreCase = true) && (kind == "sub" || it.external) }
                    kind == "audio" -> audioTracks.any { it.name.contains(text, ignoreCase = true) }
                    // `pictures:` — 还有 N 个图形字幕: the first picture subtitle file, in mpv.
                    kind == "pictures" -> (vm.manager as? com.therealityteam.videoplayer.data.playback.SwitchingEngine)?.pictureSidecars().orEmpty().isNotEmpty()
                    else -> true
                }
            }
            if (!picks.all(::resolves)) return@LaunchedEffect
            picks.forEach { pick ->
                val kind = pick.substringBefore(':')
                val text = pick.substringAfter(':')
                when {
                    kind == "sub" && text == "off" -> vm.selectSubtitleTrack(null)
                    kind == "sub2" && text == "off" -> vm.selectSecondarySubtitle(null)
                    kind == "sub" -> subtitleTracks.firstOrNull { it.name.contains(text, ignoreCase = true) }?.let { vm.selectSubtitleTrack(it.id) }
                    kind == "sub2" -> subtitleTracks.firstOrNull { it.external && it.name.contains(text, ignoreCase = true) }?.let { vm.selectSecondarySubtitle(it.name) }
                    kind == "audio" -> audioTracks.firstOrNull { it.name.contains(text, ignoreCase = true) }?.let { vm.selectAudioTrack(it.id) }
                    kind == "pictures" -> vm.showPictureSubtitles()
                }
            }
            com.therealityteam.videoplayer.platform.DeepLink.trackRequest.value = null
        }

        // Environment deep links, for measuring and placing a model by screenshot:
        //   -e aura.env <id> | -e aura.env_file <path>   enter one (a catalogue id, or a file to try)
        //   -e aura.env_pose x,y,z,yaw[,scale[,light]]   re-seat (and re-light) the one shown, live
        //   -e aura.env_screen x,y,z,width,facing[,aspect]|off   place its screen (model units), live
        //   -e aura.env_capture 1|0                      hide the picture and the bar, for a thumbnail
        val envReq by com.therealityteam.videoplayer.platform.DeepLink.envRequest.collectAsState()
        LaunchedEffect(envReq, state) {
            val wanted = envReq ?: return@LaunchedEffect
            android.util.Log.i("AuraLink", "env request $wanted, state $state, shown ${environment?.id}")
            if (state != PlaybackState.PLAYING) return@LaunchedEffect
            delay(500)
            // "a|b|a" (quoted for the device shell) enters a, then swaps on the open stage to b and back,
            // each once the place before is up and has been on show a while — swaps by deep link, in one
            // launch: a later `am start` reaches a running Aura without its extras.
            val places = wanted.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { if (it.startsWith("/")) Environments.fromFile(it) else Environments.byId(it) }
            if (places.isNotEmpty()) scope.launch {
                places.forEachIndexed { i, env ->
                    if (i > 0) {
                        withTimeoutOrNull(ENV_UP_WAIT_MS) { vm.placeShown.first { it?.id == places[i - 1].id } }
                        delay(ENV_SWAP_DWELL_MS)
                    }
                    selectEnvironment(env)
                }
            }
            com.therealityteam.videoplayer.platform.DeepLink.envRequest.value = null
        }
        val envPoseReq by com.therealityteam.videoplayer.platform.DeepLink.envPoseRequest.collectAsState()
        LaunchedEffect(envPoseReq, environment) {
            val pose = envPoseReq ?: return@LaunchedEffect
            // The view model's own, not this composition's: a screen asked for in the same launch may
            // have been set on it a moment ago, and a stale copy would take it back.
            val shown = vm.environment.value ?: return@LaunchedEffect
            val v = pose.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (v.size >= 4) {
                vm.setEnvironment(shown.copy(otherChairs = emptyList(), seatX = v[0], seatY = v[1], seatZ = v[2], yaw = v[3],
                    scale = v.getOrElse(4) { shown.scale }, light = v.getOrElse(5) { shown.light }))
            }
            com.therealityteam.videoplayer.platform.DeepLink.envPoseRequest.value = null
        }
        val envScreenReq by com.therealityteam.videoplayer.platform.DeepLink.envScreenRequest.collectAsState()
        LaunchedEffect(envScreenReq, environment, onScreen) {
            val req = envScreenReq ?: return@LaunchedEffect
            val shown = vm.environment.value ?: return@LaunchedEffect
            val v = req.split(',').mapNotNull { it.trim().toFloatOrNull() }
            when {
                // "off" takes the film off a screen it has been on for a moment — the way back into the
                // window (and the window's own size) that leaving the place or 在屏幕上播放 takes.
                req.trim() == "off" -> {
                    if (!onScreen) return@LaunchedEffect
                    delay(3_000)
                    vm.setEnvironment(shown.copy(screen = null))
                }
                // A seventh figure (1/0) gives the screen its dark backing; without it the place's own choice stands.
                v.size >= 5 -> vm.setEnvironment(
                    shown.copy(screen = Screen(v[0], v[1], v[2], v[3], v[4], v.getOrElse(5) { 16f / 9f },
                        backing = v.getOrNull(6)?.let { it != 0f } ?: shown.screen?.backing ?: false)),
                )
            }
            com.therealityteam.videoplayer.platform.DeepLink.envScreenRequest.value = null
        }
        val envCaptureReq by com.therealityteam.videoplayer.platform.DeepLink.envCaptureRequest.collectAsState()
        LaunchedEffect(envCaptureReq) {
            val on = envCaptureReq ?: return@LaunchedEffect
            vm.setCapture(on)
            com.therealityteam.videoplayer.platform.DeepLink.envCaptureRequest.value = null
        }

        // The playback bar: an Augment of this (main) window where SpatialUI's Toolbar would put it — on its bottom-front
        // edge, TOOLBAR_DROP below it in TOOLBAR_PADDING — composed only while there (up or resting; the ornament has no
        // hide switch). Not the Toolbar itself: its window carries a glass of its own, which no hover effect can fade, so
        // a resting bar left an empty pane behind (BarPlate draws the bar's plate, inside its fade). An Augment of no
        // material sorted behind the picture where the Toolbar's layer sat in front of it; BAR_LIFT brings it forward.
        if (barVisible && !capture) key(barGeneration) {
            Augment(
                anchor = NormalizedPoint3D.BottomFront,
                alignment = AugmentContentAlignment.BottomCenter,
                offset = DpOffset3D(0.dp, TOOLBAR_DROP, BAR_LIFT),
                enableMaterialBackground = false,
                // As the Toolbar's: the bar takes the focus while it is up, and 返回 lands on it.
                focusable = true,
            ) {
                Box(modifier = Modifier.padding(TOOLBAR_PADDING)) {
                    // The bar is a window of its own and takes the focus while it is up: 返回 lands here then.
                    com.therealityteam.videoplayer.ui.SystemBack(what = "the player closes (from its bar)") { close() }
                    BarPlate(awake = barAwake) {
                        PlaybackControls(
                            vm = vm,
                            isPlaying = state == PlaybackState.PLAYING || (state == PlaybackState.PREPARING && playingIntent && firstFrame),
                            position = position,
                            duration = duration,
                            immersive = immersive,
                            cinemaDim = cinemaDim,
                            environment = environment,
                            onToggleImmersive = { vm.toggleImmersive(navigator) },
                            onToggleCinema = { vm.toggleCinema(navigator) },
                            onSelectEnvironment = selectEnvironment,
                            onBack = close,
                            onHeightChanged = { barContentHeight = it; barLaidOut = true },
                            modifier = Modifier.width(PLAYBACK_CONTROLS_WIDTH),
                        )
                    }
                }
            }
        }
    }
}

/** How long the bar stays up after the last touch while the film plays, before it rests (BarPlate). */
private const val BAR_QUIET_MS = 3_000L

/** How long the bar rests — shown while looked at — before it goes altogether, and the subtitles come down. */
private const val BAR_REST_MS = 60_000L

/** A click on the picture this soon after a panel of the bar shut is the click that shut it. */
private const val MENU_SHUT_CLICK_MS = 400L

/** How long the window's picture waits after the bar has laid out, and at most if the bar never does. */
private const val BAR_SETTLE_MS = 300L
private const val PICTURE_HOLD_LIMIT_MS = 1_500L

/** A chapter skip's offer stays up this long on its own; after that, only while the bar is. */
private const val SKIP_OFFER_FRESH_MS = 10_000L

/** `aura.chapter_skip`: how long after the offer appears the hook presses 跳过. */
private const val SKIP_HOOK_DELAY_MS = 6_000L

/** How long a snapshot's outcome stays up. */
private const val SNAPSHOT_OUTCOME_MS = 2_500L
/** How long the 解码跟不上 notice stays: long enough to read twice, then the film is left alone. */
private const val DECODE_BEHIND_MS = 10_000L

/** How long "could not be loaded" stays up for a place. */
private const val PLACE_FAILED_MS = 3_000L

/** `aura.env a|b`: how long to wait for a place to come up, and how long it is on show before the next. */
private const val ENV_UP_WAIT_MS = 30_000L
private const val ENV_SWAP_DWELL_MS = 8_000L

/**
 * Feeds the controller's per-frame snapshots through [ControllerModel] while a Stage is open.
 * The right stick leads, the left one stands in when the right rests; either trigger counts.
 * Snapshots arrive on the tracking thread, so the model runs there and only the intents cross
 * to the main thread.
 */
@Composable
private fun ControllerInput(vm: PlaybackViewModel) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(vm) {
        val provider = ControllerTrackingProvider()
        var state = ControllerModel.State()
        var triggerWasDown = false
        val listener = ControllerTrackingProvider.ControllerActionListener { action ->
            val right = action.right.thumbstickValue
            val left = action.left.thumbstickValue
            val stick = if (ControllerModel.axisOf(right.x, right.y) != ControllerModel.Axis.NONE) right else left
            val triggerDown = action.right.triggerPressed || action.left.triggerPressed
            val (next, intents) = ControllerModel.step(
                state, stick.x, stick.y,
                triggerDown = triggerDown,
                nowMs = android.os.SystemClock.elapsedRealtime(),
            )
            state = next
            // In Full Space the window's own pointer stream is gone, and with it the one thing that
            // used to bring a hidden bar back: a click. A trigger press — or a stick step, which
            // the person expects to see land — is that click here. The edge only, so a held trigger
            // (double speed) does not keep the bar up for the whole of it.
            val pressed = triggerDown && !triggerWasDown
            triggerWasDown = triggerDown
            if (pressed || intents.isNotEmpty()) mainHandler.post { vm.touchControls() }
            if (intents.isNotEmpty()) mainHandler.post { intents.forEach(vm::onControllerIntent) }
        }
        provider.addControllerActionListener(listener)
        provider.start()
        onDispose {
            provider.removeControllerActionListener(listener)
            provider.stop()
            mainHandler.post { vm.holdBoost(false) }
        }
    }
}

/**
 * 开场: the film before its first picture — the episode's still or the work's backdrop (the title's
 * colours where it has neither), darkened toward the corner its name sits in, with which one it is
 * and, while it opens, the system spinner. White on the picture, out of the glass's colour adaptation
 * (Vibrant.None), as the library's featured card sets type over imagery. A file with no picture —
 * music — keeps it while it plays.
 */
@Composable
private fun OpeningCard(item: MediaItem, subtitle: String?, loading: Boolean, wordsBottom: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { OPENING_PICTURE_WIDTH.roundToPx() }
    val picture by produceState(initialValue = cachedArtwork(item.stillPath ?: item.backdropPath, px), item.id) {
        (decodeStill(ctx, item, px) ?: decodeArtwork(item.backdropPath, px))?.let { value = it }
    }
    val title = item.title.ifBlank { item.fileName }
    val stops = remember(item.id) { titleGradient(item.showTitle?.takeIf { it.isNotBlank() } ?: title) }
    val white = Color.White.withVibrant(Vibrant.None)
    // Rounded as the panel under it is (VideoEntityAssembler's 2 cm, a window's dp being a millimetre).
    Box(modifier = modifier.clip(RoundedCornerShape(OPENING_CORNER)).background(Brush.linearGradient(stops))) {
        picture?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.12f).withVibrant(Vibrant.None),
                    0.5f to Color.Black.copy(alpha = 0.28f).withVibrant(Vibrant.None),
                    1f to Color.Black.copy(alpha = 0.78f).withVibrant(Vibrant.None),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = OPENING_MARGIN + 8.dp, end = OPENING_MARGIN, bottom = wordsBottom)
                .widthIn(max = 760.dp),
        ) {
            Text(title, style = PicoTheme.typography.displayMedium, color = white, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = PicoTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.86f).withVibrant(Vibrant.None),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 20.dp),
                ) {
                    CircularProgressIndicator(color = white, progressSize = CircularProgressDefaults.Small)
                    Text(
                        stringResource(R.string.vid_loading),
                        style = PicoTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f).withVibrant(Vibrant.None),
                    )
                }
            }
        }
    }
}

/**
 * 暂停 card: what is paused, as the opening card names it — the show's or film's name large, this
 * episode under it — its story in a few lines, and how much is left with the clock time it would end
 * at. Set over a shade that darkens the picture's left only, so the paused frame still reads.
 */
@Composable
private fun PauseCard(item: MediaItem, subtitle: String?, story: String?, leftMs: Long?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val white = Color.White.withVibrant(Vibrant.None)
    val soft = Color.White.copy(alpha = 0.78f).withVibrant(Vibrant.None)
    val name = item.showTitle?.takeIf { it.isNotBlank() } ?: item.title.ifBlank { item.fileName }
    Box(modifier = modifier.clip(RoundedCornerShape(OPENING_CORNER))) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.8f).withVibrant(Vibrant.None),
                    0.5f to Color.Black.copy(alpha = 0.6f).withVibrant(Vibrant.None),
                    0.9f to Color.Black.copy(alpha = 0f).withVibrant(Vibrant.None),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.CenterStart)
                .padding(start = OPENING_MARGIN + 8.dp, end = OPENING_MARGIN, bottom = 96.dp)
                .widthIn(max = 560.dp),
        ) {
            Text(stringResource(R.string.vid_paused), style = PicoTheme.typography.titleSmall, color = soft)
            Text(
                name,
                style = PicoTheme.typography.displaySmall,
                color = white,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            subtitle?.let {
                Text(it, style = PicoTheme.typography.titleMedium, color = white, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            story?.let {
                Text(it, style = PicoTheme.typography.bodyLarge, color = soft, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            }
            leftMs?.let { left ->
                val endsAt = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(System.currentTimeMillis() + left))
                Text(
                    stringResource(R.string.vid_paused_left, spanOf(ctx, left), endsAt),
                    style = PicoTheme.typography.bodyMedium,
                    color = soft,
                    modifier = Modifier.padding(top = 18.dp),
                )
            }
        }
    }
}

/** A span of time as people say it: 「1 小时 12 分钟」, 「8 分钟」, under a minute 「不到 1 分钟」. */
private fun spanOf(ctx: android.content.Context, ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).toInt()
    val h = minutes / 60
    val m = minutes % 60
    return when {
        ms < 60_000 -> ctx.getString(R.string.vid_span_under_minute)
        h == 0 -> ctx.getString(R.string.vid_span_minutes, m)
        m == 0 -> ctx.getString(R.string.vid_span_hours, h)
        else -> ctx.getString(R.string.vid_span_hours_minutes, h, m)
    }
}

/** A pause left alone this long shows the 暂停 card; it comes in slowly and goes at once. */
private const val PAUSE_CARD_AFTER_MS = 6_000L
private const val PAUSE_CARD_IN_MS = 700
private const val PAUSE_CARD_OUT_MS = 150

/**
 * A status line over the picture (加载中 / 无法播放) on a capsule of the system's thick glass: the
 * frame behind it can be anything, and PICO's typesetting asks text over a varying picture for a
 * blurred backing rather than a bare colour. [loading] puts the system spinner before the words.
 */
@Composable
private fun StatusCapsule(text: String, color: Color, modifier: Modifier = Modifier, loading: Boolean = false, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .backgroundMaterial(style = Material.Thick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        if (loading) CircularProgressIndicator(progressSize = CircularProgressDefaults.Small)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = PicoTheme.typography.titleMedium, color = color, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(
                    detail,
                    style = PicoTheme.typography.bodyMedium,
                    color = PicoTheme.colorScheme.labelTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
    }
}

/** A rate in megabytes a second, as the capsule says it: "16", "4.5" ("4,5" in German). */
private fun megabytes(bytesPerSec: Double): String {
    val mb = bytesPerSec / 1e6
    return com.therealityteam.videoplayer.ui.common.decimalText(mb, if (mb >= 10) 0 else 1, if (mb >= 10) 0 else 1)
}

/**
 * Why a file did not play, in words that say what to do: a share that could not be reached names
 * its server and can be tried again; a damaged file or a format the headset lacks says so and offers
 * no retry that could not help. It used to be one red "无法播放此文件" for all of them — for a NAS
 * that was merely asleep, too. On the system's thick glass, like the other words over the picture.
 */
@Composable
private fun FailureCard(
    failure: PlaybackFailure?,
    host: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** An old format the headset has no reader for (PlaybackFailure.legacyFormat): RMVB, WMV, FLV. */
    format: String? = null,
    /** The headset itself has no network: it is that, and the film comes back with it by itself. */
    networkDown: Boolean = false,
    /** Out of the share's reach: an episode of the queue that is on the headset, and how to play it. */
    onHeadset: Pair<String, () -> Unit>? = null,
    /** A picture past this headset (PlaybackFailure.TOO_LARGE): what it is, and what the hardware takes. */
    oversize: Oversize? = null,
) {
    val kind = failure ?: PlaybackFailure.OTHER
    // An old format is named as what it is, and the file is not blamed: 「头显还不支持 RMVB 格式」 —
    // no other engine on the headset reads it either, so none is offered.
    val legacy = format?.takeIf { kind == PlaybackFailure.UNSUPPORTED }
    val title = if (legacy != null) stringResource(R.string.vid_err_format, legacy) else stringResource(
        when (kind) {
            PlaybackFailure.UNREACHABLE -> if (networkDown) R.string.vid_err_offline else R.string.vid_err_unreachable
            PlaybackFailure.MISSING -> R.string.vid_err_missing
            PlaybackFailure.DAMAGED -> R.string.vid_err_damaged
            PlaybackFailure.UNSUPPORTED -> R.string.vid_err_unsupported
            PlaybackFailure.TOO_LARGE -> R.string.vid_err_too_large
            PlaybackFailure.DISC_ENCRYPTED -> R.string.vid_err_disc_encrypted
            PlaybackFailure.DISC_NO_FILM -> R.string.vid_err_disc_no_film
            PlaybackFailure.OTHER -> R.string.vid_cannot_play
        },
    )
    val body = when (kind) {
        PlaybackFailure.UNREACHABLE -> when {
            networkDown -> stringResource(R.string.vid_err_network_down)
            host != null -> stringResource(R.string.vid_err_unreachable_host, host)
            else -> stringResource(R.string.vid_err_unreachable_body)
        }
        PlaybackFailure.MISSING -> stringResource(R.string.vid_err_missing_body)
        PlaybackFailure.DAMAGED -> stringResource(R.string.vid_err_damaged_body)
        PlaybackFailure.UNSUPPORTED -> stringResource(if (legacy != null) R.string.vid_err_format_body else R.string.vid_err_unsupported_body)
        PlaybackFailure.TOO_LARGE -> listOfNotNull(oversize?.let { tooLargeWhy(it) }, stringResource(R.string.vid_err_too_large_hint)).joinToString("\n")
        PlaybackFailure.DISC_ENCRYPTED -> stringResource(R.string.vid_err_disc_encrypted_body)
        PlaybackFailure.DISC_NO_FILM -> stringResource(R.string.vid_err_disc_no_film_body)
        PlaybackFailure.OTHER -> null
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(20.dp))
            .backgroundMaterial(style = Material.Thick)
            .padding(horizontal = 28.dp, vertical = 22.dp),
    ) {
        Icon(
            painterResource(if (kind == PlaybackFailure.UNREACHABLE) R.drawable.ic_line_nas else R.drawable.ic_line_warning),
            contentDescription = null,
            tint = PicoTheme.colorScheme.labelSecondary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary, textAlign = TextAlign.Center)
        body?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, textAlign = TextAlign.Center)
        }
        if (kind.retryable || onHeadset != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Away from the NAS with copies along: the way on is the one that is here.
                onHeadset?.let { (label, play) ->
                    Button(
                        onClick = play,
                        size = ButtonDefaults.Regular,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_line_downloaded), null, Modifier.size(18.dp)) },
                    ) { Text(stringResource(R.string.vid_play_copy, label)) }
                }
                if (kind.retryable) {
                    Button(onClick = onRetry, size = ButtonDefaults.Regular, colors = secondaryButtonColors()) {
                        Text(stringResource(R.string.vid_retry))
                    }
                }
            }
        }
    }
}

/** What a picture past this headset is — codec, size, rate — and what its hardware said of it. */
@Composable
private fun tooLargeWhy(o: Oversize): String {
    val picture = listOfNotNull(
        o.codec,
        "${o.width}×${o.height}",
        o.fps.takeIf { it > 0f }?.let { stringResource(R.string.vid_err_too_large_fps, kotlin.math.round(it).toInt()) },
    ).joinToString(" · ")
    return when (val hw = o.hardware) {
        Oversize.Hardware.None -> stringResource(R.string.vid_err_too_large_none, picture, o.codec ?: picture)
        is Oversize.Hardware.Size ->
            if (hw.width > 0) stringResource(R.string.vid_err_too_large_size, picture, "${hw.width}×${hw.height}")
            else stringResource(R.string.vid_err_too_large_size_any, picture)
        is Oversize.Hardware.Rate -> androidx.compose.ui.res.pluralStringResource(R.plurals.vid_err_too_large_rate, hw.fps, picture, hw.fps)
        Oversize.Hardware.Failed -> stringResource(R.string.vid_err_too_large_failed, picture)
    }
}

/**
 * The first file of the queue after this one (else before it) whose copy is on the headset — named
 * as the bar would name it — with what plays it; null when none is.
 */
@Composable
private fun rememberCopyInQueue(vm: PlaybackViewModel): Pair<String, () -> Unit>? {
    val ctx = LocalContext.current
    val store = remember { org.koin.core.context.GlobalContext.get().get<com.therealityteam.videoplayer.data.download.DownloadStore>() }
    val all by store.all.collectAsState()
    val queue by vm.queue.collectAsState()
    val current by vm.current.collectAsState()
    val told by vm.told.collectAsState()
    val run by vm.numberedRun.collectAsState()
    val pick = remember(all, queue, current) {
        val here = all.filter { it.isDone }.mapTo(HashSet()) { it.itemId }
        val i = queue.indexOfFirst { it.id == current?.id }
        (queue.drop(i + 1) + queue.take(i.coerceAtLeast(0))).firstOrNull { it.id in here && store.localCopy(it.id) != null }
    } ?: return null
    return pick.queueTitle(ctx, told[pick.id]?.name, run) to { vm.playAt(pick) }
}

/** The server a network path points at: "192.168.1.126" of smb://192.168.1.126/video/…, or null. */
private fun hostOf(path: String): String? =
    path.substringAfter("://", "").substringBefore('/').substringAfter('@').substringBefore(':').takeIf { it.isNotBlank() }

/**
 * A line of glass over the picture, with an optional glyph before it ([leading], e.g. a spinner)
 * and an action after it; the caller places it.
 */
/** 沉浸空间's lines against the window's: about two degrees high at the default size. */
private const val PANO_CAPTION_SCALE = 1.8f

/** What a film's frames look like it is, said to the viewer. */
private fun panoGuessText(g: com.therealityteam.videoplayer.data.playback.ProjectionGuess.Guess): Int = when (g.projection) {
    com.therealityteam.videoplayer.data.model.ProjectionMode.DUAL_FISHEYE -> R.string.vid_pano_guess_dual
    com.therealityteam.videoplayer.data.model.ProjectionMode.FISHEYE ->
        if (g.stereo == com.therealityteam.videoplayer.data.model.StereoMode.MONO) R.string.vid_pano_guess_fisheye else R.string.vid_pano_guess_fisheye_3d
    com.therealityteam.videoplayer.data.model.ProjectionMode.EQ180 -> R.string.vid_pano_guess_180_3d
    else -> if (g.stereo == com.therealityteam.videoplayer.data.model.StereoMode.MONO) R.string.vid_pano_guess_360 else R.string.vid_pano_guess_360_3d
}

@Composable
private fun OverlayToast(
    text: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .backgroundMaterial(style = Material.Thick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        leading?.invoke()
        Text(text, style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelPrimary)
        action?.invoke()
    }
}

/**
 * 接下来播放 — the next item's card at the end of a film: what comes next as a picture (its still,
 * else the show's backdrop, else its title's colours) with the seconds counting down in a ring
 * over it, its name and where it belongs, and the two ways out. The ring is driven by wall time
 * so it stays honest when the composition is late.
 */
@Composable
private fun NextUpCard(
    item: MediaItem,
    /** What the database tells of it: its name heads the card, what happens in it follows. */
    info: com.therealityteam.videoplayer.data.scrape.EpisodeInfo? = null,
    /** Whether the queue is a run numbered by its names (PlaybackViewModel.numberedRun). */
    numberedRun: Boolean = false,
    startsAtMs: Long?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // Three in a row started by the countdown with nobody touching anything: ask before the fourth.
    stillWatching: Boolean = false,
) {
    val ctx = LocalContext.current
    var remaining by remember { mutableStateOf(NEXT_UP_TOTAL_MS) }
    LaunchedEffect(startsAtMs) {
        if (startsAtMs == null) return@LaunchedEffect
        while (true) {
            remaining = (startsAtMs - android.os.SystemClock.elapsedRealtime()).coerceIn(0L, NEXT_UP_TOTAL_MS)
            delay(100)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier
            .offset(z = OVERLAY_Z)
            .clip(RoundedCornerShape(22.dp))
            .backgroundMaterial(style = Material.Thick)
            .padding(14.dp),
    ) {
        NextUpPicture(
            item,
            progress = { 1f - remaining.toFloat() / NEXT_UP_TOTAL_MS },
            seconds = if (startsAtMs != null) ((remaining + 999) / 1000).toInt() else null,
        )
        // As wide as its words need, within bounds: a short name no longer leaves half the card empty.
        Column(modifier = Modifier.widthIn(min = 200.dp, max = NEXT_UP_TEXT_MAX)) {
            if (stillWatching) {
                // The question is the card's headline; what would play is what it is asking about.
                Text(stringResource(R.string.vid_still_watching), style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.vid_still_watching_next, item.queueTitle(ctx, info?.name, numberedRun)),
                    style = PicoTheme.typography.bodyMedium,
                    color = PicoTheme.colorScheme.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // An episode reads as the bar names one — 接下来播放 · 曼达洛人, then 第 1 季 第 3 集：罪孽 —
                // a film by its own name.
                val episodic = item.episode != null || numberedRun
                val show = item.showTitle?.takeIf { episodic && it.isNotBlank() }
                Text(
                    listOfNotNull(stringResource(R.string.vid_up_next), show).joinToString(" · "),
                    style = PicoTheme.typography.labelLarge,
                    color = PicoTheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (episodic) barSubtitle(ctx, item, info?.name, numberedRun) else null) ?: item.queueTitle(ctx, info?.name, numberedRun),
                    style = PicoTheme.typography.titleMedium,
                    color = PicoTheme.colorScheme.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // What happens in it, as the series' page tells it.
                com.therealityteam.videoplayer.ui.common.readableSynopsis(info?.overview)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            nextUpDetail(item)?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            // The card's two ways out at the regular size: it is the one decision on screen.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCancel, size = ButtonDefaults.Regular, colors = secondaryButtonColors()) {
                    Text(stringResource(R.string.vid_cancel))
                }
                Button(onClick = onPlayNow, size = ButtonDefaults.Regular) {
                    Text(stringResource(
                        when {
                            stillWatching -> R.string.vid_keep_watching
                            startsAtMs != null -> R.string.vid_play_now
                            else -> R.string.vid_play_next
                        },
                    ))
                }
            }
        }
    }
}

/**
 * Behind the 接下来播放 card once the film has stopped — the next one's still (else its show's
 * backdrop, else its title's colours) — and behind 看完了, the finished one's: across the panel,
 * darkened so the card stays the thing to read.
 */
@Composable
private fun NextUpBackdrop(item: MediaItem, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { OPENING_PICTURE_WIDTH.roundToPx() }
    val picture by produceState(initialValue = cachedArtwork(item.stillPath ?: item.backdropPath, px), item.id) {
        (decodeStill(ctx, item, px) ?: decodeArtwork(item.backdropPath, px))?.let { value = it }
    }
    val stops = remember(item.id) { titleGradient(item.showTitle?.takeIf { it.isNotBlank() } ?: item.title.ifBlank { item.fileName }) }
    val shown by animateFloatAsState(if (picture != null) 1f else 0.6f, tween(OPENING_FADE_MS), label = "nextUpBackdrop")
    Box(modifier = modifier.clip(RoundedCornerShape(OPENING_CORNER)).background(Brush.linearGradient(stops))) {
        picture?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(shown)) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f).withVibrant(Vibrant.None)))
    }
}

/**
 * The next item as a picture with the countdown ring at its centre and the seconds left inside
 * it — white on a dark disc, which reads on any picture, where the ring's default greys had all
 * but vanished into the glass.
 */
@Composable
private fun NextUpPicture(item: MediaItem, progress: () -> Float, seconds: Int?) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { NEXT_UP_PICTURE_WIDTH.roundToPx() }
    val picture by produceState(initialValue = cachedArtwork(item.stillPath ?: item.backdropPath, px), item.id) {
        (decodeStill(ctx, item, px) ?: decodeArtwork(item.backdropPath, px))?.let { value = it }
    }
    val stops = remember(item.id) { titleGradient(item.showTitle?.takeIf { it.isNotBlank() } ?: item.title.ifBlank { item.fileName }) }
    val startsIn = seconds?.let { stringResource(R.string.vid_starts_in, it) }
    Box(
        modifier = Modifier
            .size(NEXT_UP_PICTURE_WIDTH, NEXT_UP_PICTURE_WIDTH * 9 / 16)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(stops)),
        contentAlignment = Alignment.Center,
    ) {
        picture?.let { Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // A light veil keeps the picture itself vivid; the disc under the ring is what makes it read,
        // even on a still that is mostly white.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f).withVibrant(Vibrant.None)))
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f).withVibrant(Vibrant.None)))
        if (seconds == null || startsIn == null) {
            // 自动播放下一集 off: nothing counts down — the disc carries a play mark instead.
            Icon(
                painterResource(R.drawable.ic_pc_play),
                contentDescription = null,
                tint = Color.White.withVibrant(Vibrant.None),
                modifier = Modifier.size(24.dp),
            )
        } else {
            SymbolicCircularProgressIndicator(
                progress = progress,
                progressSymbol = {
                    Text(
                        "$seconds",
                        style = PicoTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = Color.White.withVibrant(Vibrant.None),
                    )
                },
                modifier = Modifier.semantics { contentDescription = startsIn },
                colors = CircularProgressDefaults.circleProgressColors(
                    backgroundColor = Color.White.copy(alpha = 0.3f).withVibrant(Vibrant.None),
                    indicatorColor = Color.White.withVibrant(Vibrant.None),
                ),
                progressSize = CircularProgressDefaults.circleProgressSize(44.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}

/** Under the next item's name: the show it belongs to (or, for a film, its year), and how long it runs. */
@Composable
private fun nextUpDetail(item: MediaItem): String? {
    // An episode's show heads the card already; a film's year is told here.
    val whose = if (item.episode != null) null else item.year?.toString()
    val length = item.runtime?.takeIf { it > 0 }?.let { formatRuntime(it) }
    return listOfNotNull(whose, length).joinToString(" · ").ifBlank { null }
}

/**
 * 看完了: the queue played to its end. What it was, 重看 and 返回 — and, from this library, up to four
 * titles like it that nobody has begun (SimilarTitles), each opening its page in the library. Over a
 * film's credits too, when a 片尾 mark ended it there, so it can be put away (✕) and the credits seen.
 */
@Composable
private fun FinishedCard(
    item: MediaItem,
    onReplay: () -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onOpen: (MediaGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val colors = PicoTheme.colorScheme
    // The title's card and what is like it, worked out off the main thread from the library.
    val found by produceState<Pair<MediaGroup?, List<MediaGroup>>>(null to emptyList(), item.id) {
        value = withContext(Dispatchers.Default) {
            val library = GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID).get<LibraryViewModel>().groups.value
            val showAdult = GlobalContext.get().get<SettingsRepository>().get().showAdult
            val visible = if (showAdult) library else library.filterNot { it.category == LibCategory.ADULT }
            val group = visible.firstOrNull { g -> g.items.any { it.id == item.id } }
            // A film of a series is followed, first, by the next of it not yet seen: 帝国反击战 after 新希望.
            val next = group?.let { SeriesTitles.nextAfter(it, visible) }
            val similar = group?.let { g -> SimilarTitles.of(g, visible).filter(SurprisePick::fresh) }.orEmpty()
            group to (listOfNotNull(next) + similar.filterNot { it.key == next?.key }).take(FINISHED_SUGGESTIONS)
        }
    }
    val (group, suggestions) = found
    // As wide as the posters it offers, and never so narrow that its name and buttons crowd.
    val width = maxOf(FINISHED_MIN_WIDTH, FINISHED_POSTER * suggestions.size + FINISHED_GAP * (suggestions.size - 1).coerceAtLeast(0) + 48.dp)
    Column(
        modifier = modifier
            .offset(z = OVERLAY_Z)
            .width(width)
            .clip(RoundedCornerShape(22.dp))
            .backgroundMaterial(style = Material.Thick)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vid_finished), style = PicoTheme.typography.labelLarge, color = colors.labelSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    group?.title ?: item.queueTitle(ctx),
                    style = PicoTheme.typography.titleLarge,
                    color = colors.labelPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.tooltip(text = stringResource(R.string.vid_finished_close)),
                colors = secondaryIconButtonColors(),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.vid_finished_close), tint = colors.labelPrimary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onReplay, size = ButtonDefaults.Regular, colors = secondaryButtonColors()) {
                Text(stringResource(R.string.det_watch_again))
            }
            Button(onClick = onBack, size = ButtonDefaults.Regular) {
                Text(stringResource(R.string.pc_back))
            }
        }
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.vid_watch_next), style = PicoTheme.typography.titleSmall, color = colors.labelPrimary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(FINISHED_GAP)) {
                suggestions.forEach { card -> PosterCard(group = card, onClick = { onOpen(card) }, modifier = Modifier.width(FINISHED_POSTER)) }
            }
        }
    }
}

private const val FINISHED_SUGGESTIONS = 4
private val FINISHED_POSTER = 132.dp
private val FINISHED_GAP = 16.dp
private val FINISHED_MIN_WIDTH = 420.dp

private val NEXT_UP_TEXT_MAX = 300.dp
private val NEXT_UP_PICTURE_WIDTH = 224.dp

/** 开场: how wide its picture is decoded (the window's width), and how long it takes to fade as the film comes. */
private val OPENING_PICTURE_WIDTH = 1280.dp
private const val OPENING_FADE_MS = 600
private val OPENING_CORNER = 20.dp
private val OPENING_MARGIN = 48.dp
private const val OPENING_CLOCK_MOVED_MS = 600L

/** The countdown the card shows; matches the view model's delay. */
private const val NEXT_UP_TOTAL_MS = 5_000L

/** mm:ss, or h:mm:ss past an hour. */
private fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Draws the engine's active subtitle cues over the video in the three lanes a cue can ask
 * for — bottom (the default), middle, top (ASS `\an8`) — with the file's own styling (colours,
 * weights, slants, relative sizes) carried through as spans, the user's typeface and colour
 * underneath, and the chosen look: a soft edge and shadow (阴影, the default), on a box, or
 * outlined with a real stroke of the chosen thickness. Bitmap cues (PGS / VobSub) draw as images.
 * While a 字幕 menu is open ([preview]) the bottom lane moves to the top, clear of its panel, and
 * with the settings open ([keepLine]) a quiet stretch keeps the last line — or a sample — up.
 *
 * 2D content renders on the BACK face of its layout box — the window plane — while the
 * video's SpatialView panel floats in front of it, hiding anything drawn there. offset(z)
 * lifts the subtitles past the panel (see [OVERLAY_Z]); the bar's clearance keeps them off
 * the Toolbar while it shows.
 */
@Composable
private fun SubtitleOverlay(
    cues: List<SubtitleCue>,
    frameRect: FrameRect,
    scale: Float,
    modifier: Modifier = Modifier,
    raiseDp: Dp = 0.dp,
    style: SubtitleStyle = SubtitleStyle.NONE,
    barVisible: Boolean = true,
    coverBitmaps: Boolean = barVisible,
    barClearance: Dp = 84.dp,
    font: SubtitleFont = SubtitleFont.DEFAULT,
    color: SubtitleColor = SubtitleColor.WHITE,
    outlineDp: Int = 2,
    keepStyles: Boolean = true,
    preview: Boolean = false,
    keepLine: Boolean = false,
    secondLine: String? = null,
) {
    // Clear the bar while it shows — by what it actually covers — and settle lower once it has faded.
    val bottom by animateDpAsState(if (barVisible) barClearance else 28.dp, label = "subtitleBottom")
    val shown = rememberShownCues(cues, keepLine, secondLine)
    if (shown.isEmpty()) return
    Box(modifier = modifier.offset(z = CAPTION_Z)) {
        val (pictures, lines) = shown.partition { it.bitmapPlacement != null }
        if (pictures.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                clipRect {
                    for (cue in pictures) {
                        val image = cue.bitmap ?: continue
                        val rect = cue.pictureBounds(frameRect, size.width, size.height, scale, raiseDp.toPx(), preview,
                            coveredBottom = if (coverBitmaps) bottom.toPx() else 0f) ?: continue
                        drawImage(
                            image.asImageBitmap(),
                            dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                            dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
        }
        // While a 字幕 panel is up — over the bottom of the picture — its bottom lane joins the top one.
        lines.groupBy { if (preview && it.placement == CuePlacement.BOTTOM) CuePlacement.TOP else it.placement }.forEach { (lane, group) ->
            val (alignment, padding) = when (lane) {
                CuePlacement.TOP -> Alignment.TopCenter to PaddingValues(top = 36.dp, start = 48.dp, end = 48.dp)
                CuePlacement.MIDDLE -> Alignment.Center to PaddingValues(start = 48.dp, end = 48.dp)
                CuePlacement.BOTTOM -> Alignment.BottomCenter to PaddingValues(bottom = bottom + raiseDp, start = 48.dp, end = 48.dp)
            }
            Column(
                modifier = Modifier.align(alignment).fillMaxWidth().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                group.forEach { cue -> CueLine(cue, scale, style, font, color, outlineDp, keepStyles) }
            }
        }
    }
}

/**
 * A fansub's .ass as libass drew it ([AssRenderer]), laid over the picture where its whole frame
 * lies in the window ([frameRect]) and drawn for the moment each frame shows — every frame while
 * it plays (an opening's karaoke moves with the song), now and then while paused. libass draws at
 * the frame's size, no wider than [MAX_ASS_WIDTH]. While the bar is up the picture's bottom lines
 * would sit under it: the layer rises by [lift], as [SubtitleOverlay]'s lines do, and settles back.
 */
@Composable
private fun AssOverlay(
    renderer: AssRenderer,
    frameRect: FrameRect,
    playing: Boolean,
    clockMs: () -> Long,
    lift: Dp,
    modifier: Modifier = Modifier,
) {
    val rise by animateDpAsState(lift, label = "assLift")
    BoxWithConstraints(modifier.offset(z = CAPTION_Z)) {
        val windowW = constraints.maxWidth.toFloat()
        val windowH = constraints.maxHeight.toFloat()
        val frameW = windowW * frameRect.width
        val frameH = windowH * frameRect.height
        val drawScale = (MAX_ASS_WIDTH / frameW).coerceAtMost(1f)
        val drawW = (frameW * drawScale).roundToInt().coerceAtLeast(1)
        val drawH = (frameH * drawScale).roundToInt().coerceAtLeast(1)
        LaunchedEffect(renderer, drawW, drawH) { renderer.setSize(drawW, drawH) }
        val clock by rememberUpdatedState(clockMs)
        LaunchedEffect(renderer, playing) {
            while (true) {
                if (playing) withFrameMillis { } else delay(ASS_PAUSED_DRAW_MS)
                renderer.renderAt(clock())
            }
        }
        val frame by renderer.frame.collectAsState()
        val risePx = with(LocalDensity.current) { rise.toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val f = frame ?: return@Canvas
            val sx = frameW / f.frameWidth
            val sy = frameH / f.frameHeight
            drawImage(
                image = f.bitmap.asImageBitmap(),
                dstOffset = IntOffset(
                    (windowW * frameRect.left + f.x * sx).roundToInt(),
                    (windowH * frameRect.top + f.y * sy - risePx).roundToInt(),
                ),
                dstSize = IntSize((f.bitmap.width * sx).roundToInt(), (f.bitmap.height * sy).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/**
 * [AssOverlay] for a place's screen: libass draws at the screen captions' width for the film's
 * whole frame, and each new picture becomes the texture of the quad on the film (ScreenCaptions).
 * A texture is made per picture, so an opening's karaoke is drawn [ASS_SCREEN_FPS] times a second
 * there, not every frame.
 */
@Composable
private fun AssScreenFeed(
    renderer: AssRenderer,
    captions: ScreenCaptions,
    scale: Float,
    frameRect: FrameRect,
    videoAspect: Float,
    playing: Boolean,
    clockMs: () -> Long,
) {
    val aspect = if (videoAspect > 0f) videoAspect else 16f / 9f
    // Lines set larger on a screen across the room, as its plain ones are; the window's size again when leaving.
    LaunchedEffect(renderer, scale, captions.boost) { renderer.setFontScale(scale * captions.boost) }
    DisposableEffect(renderer, scale) { onDispose { renderer.setFontScale(scale) } }
    LaunchedEffect(renderer, aspect) {
        renderer.setSize(CaptionPainterWidth, (CaptionPainterWidth / aspect).roundToInt().coerceAtLeast(1))
    }
    val clock by rememberUpdatedState(clockMs)
    LaunchedEffect(renderer, playing) {
        while (true) {
            delay(if (playing) 1000L / ASS_SCREEN_FPS else ASS_PAUSED_DRAW_MS)
            renderer.renderAt(clock())
        }
    }
    val rect by rememberUpdatedState(frameRect)
    LaunchedEffect(renderer, captions) {
        renderer.frame.collect { f ->
            val r = rect
            captions.showAss(f?.bitmap, f?.x ?: 0, f?.y ?: 0, f?.frameWidth ?: 0, f?.frameHeight ?: 0, r.left, r.top, r.width, r.height)
        }
    }
    DisposableEffect(renderer, captions) { onDispose { captions.clearAss() } }
}

/** The pixels across a place's screen libass draws at — as its plain lines are painted (CaptionPainter.FACE_PX). */
private const val CaptionPainterWidth = 1920
/** How often an opening's karaoke is drawn on a place's screen: a texture is made each time. */
private const val ASS_SCREEN_FPS = 15

/** libass draws no wider than this: a line is no sharper in the headset past it, and every pixel is copied. */
private const val MAX_ASS_WIDTH = 1920f
/** How often a paused film's subtitles are drawn again: a seek, a delay changed. */
private const val ASS_PAUSED_DRAW_MS = 200L
/** Where a picture's bottom line rests with no bar, as [SubtitleOverlay]'s does: what the bar's clearance is measured from. */
private val ASS_REST_BOTTOM = 28.dp

/**
 * The lines to show now: the cues; or, in a quiet stretch while the settings are open ([keepLine]),
 * the last line shown — a sample before any; and the second subtitle's line under them, in the same
 * lane and look, a little smaller.
 */
@Composable
private fun rememberShownCues(cues: List<SubtitleCue>, keepLine: Boolean, secondLine: String?): List<SubtitleCue> {
    var lastLine by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    LaunchedEffect(cues) { if (cues.isNotEmpty()) lastLine = cues }
    val sample = stringResource(R.string.vid_subtitle_sample)
    val first = when {
        cues.isNotEmpty() -> cues
        keepLine -> lastLine.ifEmpty { listOf(SubtitleCue(text = sample)) }
        else -> emptyList()
    }
    return first + listOfNotNull(secondLine?.takeIf { it.isNotBlank() }?.let { SubtitleCue(text = it, sizeFraction = SECOND_LINE_FRACTION) })
}

/**
 * While the film plays on a place's screen its lines go there, drawn by [captions] as
 * [SubtitleOverlay] draws them in the window — the same cues and look — and come down with it.
 */
@Composable
private fun ScreenCaptionFeed(
    captions: ScreenCaptions,
    cues: List<SubtitleCue>,
    look: CaptionLook,
    keepLine: Boolean,
    secondLine: String?,
) {
    val shown = rememberShownCues(cues, keepLine, secondLine)
    LaunchedEffect(shown, look) { captions.show(shown, look) }
    DisposableEffect(captions) { onDispose { captions.clear() } }
}

@Composable
private fun CueLine(
    cue: SubtitleCue,
    scale: Float,
    style: SubtitleStyle,
    font: SubtitleFont,
    color: SubtitleColor,
    outlineDp: Int,
    keepStyles: Boolean,
) {
    if (cue.bitmap != null) {
        Image(bitmap = cue.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(0.85f))
        return
    }
    val text = cue.text?.takeIf { it.isNotBlank() } ?: return
    // A file's own size is relative to its picture; 48pt at 720 lines is our 24sp base.
    val sizeFactor = cue.sizeFraction?.let { (it / (48f / 720f)).coerceIn(0.6f, 1.8f) } ?: 1f
    val base = TextStyle(
        fontSize = (24 * scale * sizeFactor).sp,
        fontFamily = font.family(),
        textAlign = when (cue.hAlign) { -1 -> TextAlign.Start; 1 -> TextAlign.End; else -> TextAlign.Center },
        color = Color(color.argb),
    )
    val fill = text.toAnnotated(keepColours = keepStyles)
    val contentAlignment = when (cue.hAlign) { -1 -> Alignment.CenterStart; 1 -> Alignment.CenterEnd; else -> Alignment.Center }
    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = contentAlignment) {
        when (style) {
            // 阴影 (the default): a hairline of soft dark round the letters and a shadow under
            // them. A shadow alone left white text melting into a bright frame (a sky, snow, a
            // white wall); the edge is all but invisible on a dark one, so it still looks bare.
            SubtitleStyle.NONE -> Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                val density = LocalDensity.current
                BasicText(
                    text = text.toAnnotated(keepColours = false),
                    style = base.copy(
                        color = Color(0x8C000000), // design-style: fixed-figma-color subtitle edge over video
                        drawStyle = Stroke(width = with(density) { SOFT_EDGE.toPx() }, join = StrokeJoin.Round, cap = StrokeCap.Round),
                    ),
                )
                BasicText(
                    text = fill,
                    style = base.copy(
                        shadow = Shadow(
                            color = Color(0xB3000000), // design-style: fixed-figma-color shadow over video
                            offset = Offset(0f, with(density) { 1.dp.toPx() }),
                            blurRadius = with(density) { 3.dp.toPx() },
                        ),
                    ),
                )
            }
            SubtitleStyle.BOX -> BasicText(
                text = fill,
                style = base,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99000000)) // design-style: fixed-figma-color subtitle box over video
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // 描边: a real stroke under the fill, at the chosen thickness; the stroke pass keeps
            // the weights so a bold word's outline is bold too, but not the colours.
            SubtitleStyle.OUTLINE -> Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                val strokePx = with(LocalDensity.current) { (outlineDp * 2).dp.toPx() }
                BasicText(
                    text = text.toAnnotated(keepColours = false),
                    style = base.copy(
                        color = Color.Black,
                        drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round, cap = StrokeCap.Round),
                    ),
                )
                BasicText(text = fill, style = base)
            }
        }
    }
}

/**
 * The second subtitle's size, as a cue's own size fraction: 0.85 of the 24sp base (48/720 is the
 * base, see CueLine) — under the first line and clearly the lesser of the two.
 */
private const val SECOND_LINE_FRACTION = 0.85f * 48f / 720f

/** The default look's stroke: centred on the letters' outline, so 1dp of it shows outside the fill. */
private val SOFT_EDGE = 2.dp

private fun SubtitleFont.family(): FontFamily = when (this) {
    SubtitleFont.DEFAULT -> FontFamily.Default
    SubtitleFont.SERIF -> FontFamily.Serif
    SubtitleFont.MONO -> FontFamily.Monospace
}

/** How long no space may be up while the switches say one is before the film comes back to its window: a stage
 *  closed and opened again (沉浸 ↔ 虚拟环境) is up again well within it. */
private const val SPACE_GONE_MS = 3_000L
