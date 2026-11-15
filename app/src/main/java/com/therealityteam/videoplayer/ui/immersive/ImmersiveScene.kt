package com.therealityteam.videoplayer.ui.immersive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.ui.foundation.content.setAccessibilityActions
import com.therealityteam.videoplayer.R
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.foundation.content.SpatialView
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.ui.player.PlaybackViewModel
import kotlinx.coroutines.flow.update
import org.koin.core.context.GlobalContext

/**
 * Full-Space content: the immersive video sphere, the environment drawn around the window
 * (虚拟环境), and the environment's own screen the film can play on. Driven by the same
 * [PlaybackViewModel] / player as the Shared-Space panel, so toggling immersion keeps playback
 * seamless. The environment's root is hung here once; which model it holds follows the view model,
 * so choosing another environment swaps it on the open stage.
 */
@Composable
fun ImmersiveScene() {
    val scope = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID) }
    val vm = remember(scope) { scope.get<PlaybackViewModel>() }
    val environment = vm.environment.collectAsState().value
    // 观影灯光: down slowly once the film plays on its own, up quickly when it is called on.
    val dimmed by vm.environmentDimmed.collectAsState()
    val viewingLightPercent by vm.viewingLightPercent.collectAsState()
    val viewingLevel = viewingLightPercent / 100f
    val light by animateFloatAsState(
        targetValue = if (dimmed) ROOM_DIMMED + (1f - ROOM_DIMMED) * viewingLevel else 1f,
        animationSpec = tween(if (dimmed) LIGHTS_DOWN_MS else LIGHTS_UP_MS),
        label = "roomLight",
    )
    LaunchedEffect(environment) {
        vm.showEnvironment(environment)
        // The place is up: a screen in it can take the film now.
        vm.onEnvironmentShown(environment)
        // A new place comes in as it was made; set it to the light the room is at now.
        vm.environmentScene.setLight(light)
    }
    LaunchedEffect(Unit) { snapshotFlow { light }.collect { vm.environmentScene.setLight(it) } }
    // A layered place's own lights: off by hand (灯光) or while the film plays (观影灯光); they fade as a cinema's do.
    val roomLights by vm.roomLights.collectAsState()
    val held by com.therealityteam.videoplayer.platform.DeepLink.envLightsHeld.collectAsState()
    val house by animateFloatAsState(
        targetValue = when {
            !roomLights -> 0f
            dimmed && !held -> viewingLevel
            else -> 1f
        },
        animationSpec = tween(HOUSE_FADE_MS),
        label = "houseLights",
    )
    LaunchedEffect(Unit) { snapshotFlow { house }.collect { vm.environmentScene.setHouseLights(it) } }
    val lightsRequest by com.therealityteam.videoplayer.platform.DeepLink.envLightsRequest.collectAsState()
    LaunchedEffect(lightsRequest) {
        val asked = lightsRequest ?: return@LaunchedEffect
        vm.setRoomLights(!asked.equals("off", ignoreCase = true))
        com.therealityteam.videoplayer.platform.DeepLink.envLightsHeld.value = asked.equals("hold", ignoreCase = true)
        android.util.Log.i("AuraEnv", "room lights ${if (vm.roomLights.value) "on" else "off"} by automation")
        com.therealityteam.videoplayer.platform.DeepLink.envLightsRequest.value = null
    }
    val ambRequest by com.therealityteam.videoplayer.platform.DeepLink.envAmbienceRequest.collectAsState()
    LaunchedEffect(ambRequest) {
        val asked = ambRequest ?: return@LaunchedEffect
        when {
            asked.equals("off", ignoreCase = true) -> vm.ambienceOn.value = false
            asked.startsWith("cutoff:") -> asked.removePrefix("cutoff:").toFloatOrNull()?.let { vm.environmentScene.ambHouseCutoff = it }
            asked.startsWith("hz:") -> asked.removePrefix("hz:").toFloatOrNull()?.takeIf { it > 0f }?.let { vm.ambienceSampleMs.value = (1000f / it).toLong() }
            else -> { vm.ambienceOn.value = true; asked.toFloatOrNull()?.let { vm.ambienceGain.value = it } }
        }
        android.util.Log.i("AuraEnv", "ambience $asked by automation")
        com.therealityteam.videoplayer.platform.DeepLink.envAmbienceRequest.value = null
    }
    val fxRequest by com.therealityteam.videoplayer.platform.DeepLink.envEffectsRequest.collectAsState()
    LaunchedEffect(fxRequest, environment) {
        val asked = fxRequest ?: return@LaunchedEffect
        vm.environmentScene.setEffects(!asked.equals("off", ignoreCase = true))
        android.util.Log.i("AuraEnv", "effects $asked by automation")
    }
    val realtimeRequest by com.therealityteam.videoplayer.platform.DeepLink.envRealtimeLightsRequest.collectAsState()
    LaunchedEffect(realtimeRequest) {
        val (path, lumensPerWatt) = realtimeRequest ?: return@LaunchedEffect
        vm.environmentScene.setRealtimeLights(path.takeUnless { it == "off" }, lumensPerWatt)
        com.therealityteam.videoplayer.platform.DeepLink.envRealtimeLightsRequest.value = null
    }
    DisposableEffect(Unit) {
        StageScenes.open.update { it + 1 }
        onDispose { StageScenes.open.update { it - 1 } }
    }
    val navigator = com.pico.spatial.ui.platform.containers.LocalSpatialNavigator.current
    val leaveSpace = { vm.returnToWindow(navigator) }
    com.therealityteam.videoplayer.ui.SystemBack(what = "the space closes", onBack = leaveSpace)
    // 全景: the head tracked while the stage is open, and the sphere kept round it (PanoFollow).
    DisposableEffect(Unit) {
        PanoFollow.start(vm.remoteAtEyeLevel)
        com.pico.spatial.core.ecs.System.register(PanoFollowSystem::class.java)
        onDispose {
            com.pico.spatial.core.ecs.System.unregister(PanoFollowSystem::class.java)
            PanoFollow.stop()
            PanoFollow.sphere = null
            PanoFollow.captions = null
            PanoFollow.remote = null
            PanoFollow.forgetCaptions()
        }
    }
    // A click in the place round the viewer — the room, the sky, anything that is not a window — lands on walls of
    // nothing [TAP_SHELL_REACH] off ([tapShell]). With a panel of the bar open it shuts the panel and the bar stays: a
    // panel hears only clicks on the app's windows, and in a place the film is far off on its screen (2026-10-23).
    val context = androidx.compose.ui.platform.LocalContext.current
    val immersive by vm.immersive.collectAsState()
    val remoteAtEyeLevel by vm.remoteAtEyeLevel.collectAsState()
    val remoteHeight = stringResource(if (remoteAtEyeLevel) R.string.remote_eye_level else R.string.remote_below_eye)
    var accessibleRemote by remember { mutableStateOf<Entity?>(null) }
    LaunchedEffect(accessibleRemote, immersive, remoteHeight) {
        accessibleRemote?.components?.get(AccessibilityComponent::class.java)?.apply {
            isAccessibilityElement = immersive
            value = remoteHeight
        }
    }
    val shell = remember { arrayOfNulls<Entity>(1) }
    SpatialView(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectSpatialTapGesture(
                context,
                com.pico.spatial.ui.foundation.gesture.TargetEntity.any { it == shell[0] },
            ) {
                if (com.therealityteam.videoplayer.ui.player.barMenuOpen()) {
                    com.therealityteam.videoplayer.ui.player.closeBarMenus()
                    vm.showControls()
                }
            }
        },
        initial = { content, attachments ->
            content.addEntity(tapShell().also { shell[0] = it })
            content.addEntity(vm.ensureSphere().also { PanoFollow.sphere = it })
            // Its subtitles, ahead of the head (PanoFollow.followCaptions).
            content.addEntity(vm.captionAnchor.also { PanoFollow.captions = it })
            // Its remote follows the viewer at the height chosen in the player.
            val remote = attachments.entity(REMOTE_ID)
            if (remote != null) {
                remote.enabled = false
                content.addEntity(remote)
                remote.components.set(AccessibilityComponent(
                    label = context.getString(R.string.panorama_remote),
                    value = remoteHeight,
                ).apply { isAccessibilityElement = immersive })
                content.setAccessibilityActions(entity = remote, semanticId = "aura.panorama.remote") {
                    onClick(label = context.getString(R.string.remote_toggle_height)) {
                        vm.toggleRemoteHeight()
                        true
                    }
                }
                accessibleRemote = remote
                PanoFollow.remote = remote
            }
            content.addEntity(vm.environmentScene.root)
            // The place's screen, which the film moves onto where there is one (在环境的屏幕上播放).
            content.addEntity(vm.ensureScreen())
        },
        attachments = {
            AttachmentPanel(id = REMOTE_ID) {
                com.pico.spatial.ui.design.PicoTheme {
                    if (immersive) PanoRemote(vm, onLeave = leaveSpace)
                }
            }
        },
    )
}

/** How many of the stage's scenes are up: the player sees from it a space the system closed (VideoScreen). */
object StageScenes {
    val open = kotlinx.coroutines.flow.MutableStateFlow(0)
}

/**
 * Six thin walls of nothing round the stage's origin (the floor under the viewer), [TAP_SHELL_REACH] off each way: no
 * model, only a collider the ray can stop at, past everything a place holds. Static — a collider with no body.
 */
private fun tapShell(): Entity = Entity().apply {
    setName("aura_tap_shell")
    val r = TAP_SHELL_REACH
    val span = 2 * r
    val t = 0.2f
    fun wall(sx: Float, sy: Float, sz: Float, x: Float, y: Float, z: Float) =
        ShapeResource.createBox(com.pico.spatial.core.math.Vector3(sx, sy, sz))
            .offsetByTranslation(com.pico.spatial.core.math.Vector3(x, y, z))
    val walls = listOf(
        wall(span, span, t, 0f, 0f, -r), wall(span, span, t, 0f, 0f, r),
        wall(t, span, span, -r, 0f, 0f), wall(t, span, span, r, 0f, 0f),
        wall(span, t, span, 0f, -r, 0f), wall(span, t, span, 0f, r, 0f),
    )
    components.set(InteractableComponent())
    components.set(com.pico.spatial.core.ecs.CollisionComponent(walls, com.pico.spatial.core.ecs.resource.PhysicsMaterialResource()))
}

/** How far the walls of [tapShell] stand: beyond the walls of every room among the places. */
private const val TAP_SHELL_REACH = 40f

/** The remote's attachment. */
private const val REMOTE_ID = "aura_pano_remote"

/** How much of its light a place keeps while the film plays, and how long the lights take to go. */
private const val ROOM_DIMMED = 0.3f
private const val LIGHTS_DOWN_MS = 1_600
private const val LIGHTS_UP_MS = 500

/** How long a place's own room lights take to come up or go down. */
private const val HOUSE_FADE_MS = 1_800
