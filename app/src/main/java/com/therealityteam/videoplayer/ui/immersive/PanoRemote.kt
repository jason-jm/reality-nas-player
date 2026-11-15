package com.therealityteam.videoplayer.ui.immersive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleIconButton
import com.pico.spatial.ui.design.ToggleIconButtonDefaults
import com.pico.spatial.ui.design.VerticalDivider
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.foundation.tooltip
import com.pico.spatial.ui.graphics.TooltipDirection
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.playback.PlaybackState
import com.therealityteam.videoplayer.platform.DeepLink
import com.therealityteam.videoplayer.ui.player.PlaybackViewModel
import com.therealityteam.videoplayer.ui.player.PLAYBACK_CONTROLS_WIDTH
import com.therealityteam.videoplayer.ui.player.PlaybackControls
import com.therealityteam.videoplayer.ui.player.barMenuOpen
import com.therealityteam.videoplayer.ui.player.closeBarMenus

/**
 * 沉浸空间's remote: the few things a panorama needs while it is all round — play, a step back or on,
 * where the film is, 回正, 全景格式 and 画面方向 (turn, up and down, roll and level), each opened above,
 * and back to the window. The strip and the full controls that open in its place follow the viewer.
 */
@Composable
fun PanoRemote(vm: PlaybackViewModel, onLeave: () -> Unit) {
    val navigator = LocalSpatialNavigator.current
    val item by vm.current.collectAsState()
    var expanded by remember(item?.id) { mutableStateOf(false) }
    val state by vm.manager.state.collectAsState()
    val position by vm.manager.position.collectAsState()
    val duration by vm.manager.duration.collectAsState()
    val playing = state == PlaybackState.PLAYING
    // 画面方向, opened above the strip: in 沉浸空间 the window (and its 显示 menu) may be out of sight.
    val turning by vm.remoteTurning.collectAsState()
    // …and 全景格式, for a format guessed wrong, put right without going back to the window.
    val choosing by vm.remoteFormat.collectAsState()
    val pano by vm.pano.collectAsState()
    val eyeLevel by vm.remoteAtEyeLevel.collectAsState()
    // Debug menu requests use the same panels and controls as a click on the remote.
    val requestedMenu by DeepLink.openMenuRequest.collectAsState()
    val requestedScrub by DeepLink.scrubRequest.collectAsState()
    LaunchedEffect(requestedMenu, requestedScrub) {
        if (requestedMenu != null || requestedScrub != null) expanded = true
    }
    com.therealityteam.videoplayer.ui.SystemBack(what = "panorama remote") {
        when {
            expanded && barMenuOpen() -> closeBarMenus()
            expanded -> expanded = false
            turning -> vm.remoteTurning.value = false
            choosing -> vm.remoteFormat.value = false
            else -> onLeave()
        }
    }
    if (expanded) {
        Box(
            modifier = Modifier
                .width(PLAYBACK_CONTROLS_WIDTH)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(style = Material.Thick)
                .padding(6.dp),
        ) {
            PlaybackControls(
                vm = vm, isPlaying = playing, position = position, duration = duration,
                immersive = true, cinemaDim = false, environment = null,
                onToggleImmersive = onLeave,
                onToggleCinema = { vm.toggleCinema(navigator) },
                onSelectEnvironment = { vm.selectEnvironment(navigator, it) },
                onBack = { expanded = false },
                canCollapse = true,
            )
        }
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val layout = pano
        if (choosing && layout != null) {
            Column(
                modifier = Modifier
                    .width(FORMAT_WIDTH)
                    .clip(RoundedCornerShape(24.dp))
                    .backgroundMaterial(style = Material.Thick)
                    .padding(12.dp),
            ) {
                com.therealityteam.videoplayer.ui.player.PanoFormatTiles(vm, layout, columns = FORMAT_COLUMNS)
            }
        }
        if (turning) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .backgroundMaterial(style = Material.Thick)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                RemoteButton(R.drawable.ic_opt_turn_left, stringResource(R.string.pc_pano_turn_left)) { vm.turnPano(-TURN) }
                RemoteButton(R.drawable.ic_opt_turn_right, stringResource(R.string.pc_pano_turn_right)) { vm.turnPano(TURN) }
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))
                RemoteButton(R.drawable.ic_opt_tilt_up, stringResource(R.string.pc_pano_tilt_up)) { vm.nudgeLevel(LEVEL, 0f) }
                RemoteButton(R.drawable.ic_opt_tilt_down, stringResource(R.string.pc_pano_tilt_down)) { vm.nudgeLevel(-LEVEL, 0f) }
                VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))
                RemoteButton(R.drawable.ic_opt_roll_left, stringResource(R.string.pc_pano_roll_left)) { vm.nudgeLevel(0f, -LEVEL) }
                RemoteButton(R.drawable.ic_opt_level, stringResource(R.string.pc_pano_level)) { vm.resetLevel() }
                RemoteButton(R.drawable.ic_opt_roll_right, stringResource(R.string.pc_pano_roll_right)) { vm.nudgeLevel(0f, LEVEL) }
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(style = Material.Thick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RemoteButton(R.drawable.ic_pc_rewind, stringResource(R.string.pc_rewind_15)) { vm.seekBy(-STEP_MS) }
            IconButton(onClick = { vm.manager.togglePlayPause() }, size = IconButtonDefaults.Max) {
                Icon(
                    painter = painterResource(if (playing) R.drawable.ic_pc_pause else R.drawable.ic_pc_play),
                    contentDescription = stringResource(if (playing) R.string.pc_pause else R.string.pc_play),
                    modifier = Modifier.size(22.dp),
                )
            }
            RemoteButton(R.drawable.ic_pc_forward, stringResource(R.string.pc_forward_15)) { vm.seekBy(STEP_MS) }
            // Where the film is: a thin line, and the clock.
            Box(modifier = Modifier.width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(PicoTheme.colorScheme.fillTertiary)) {
                val done = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(done).background(PicoTheme.colorScheme.labelPrimary))
            }
            Text(
                "${clock(position)} / ${clock(duration)}",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            VerticalDivider(modifier = Modifier.height(24.dp).padding(horizontal = 4.dp))
            RemoteButton(R.drawable.ic_opt_recenter, stringResource(R.string.pc_pano_recenter)) { vm.recenterPano() }
            val colors = PicoTheme.colorScheme
            // 全景格式 only for a panorama (a flat film in a place has none to choose).
            if (layout != null) ToggleIconButton(
                checked = choosing,
                onCheckedChange = { vm.remoteFormat.value = it; if (it) vm.remoteTurning.value = false },
                modifier = Modifier.tooltip(text = stringResource(R.string.pc_pano_format), direction = TooltipDirection.Top),
                size = ToggleIconButtonDefaults.Regular,
                colors = ToggleIconButtonDefaults.toggleIconButtonColors(
                    checkedContainerColor = colors.fillSecondary,
                    checkedContentColor = colors.labelPrimary,
                    uncheckedContainerColor = Color.Transparent,
                    uncheckedContentColor = colors.labelPrimary,
                ),
            ) {
                Icon(painter = painterResource(R.drawable.ic_opt_pano_equirect), contentDescription = stringResource(R.string.pc_pano_format), modifier = Modifier.size(20.dp))
            }
            ToggleIconButton(
                checked = turning,
                onCheckedChange = { vm.remoteTurning.value = it; if (it) vm.remoteFormat.value = false },
                modifier = Modifier.tooltip(text = stringResource(R.string.pc_pano_direction), direction = TooltipDirection.Top),
                size = ToggleIconButtonDefaults.Regular,
                colors = ToggleIconButtonDefaults.toggleIconButtonColors(
                    checkedContainerColor = colors.fillSecondary,
                    checkedContentColor = colors.labelPrimary,
                    uncheckedContainerColor = Color.Transparent,
                    uncheckedContentColor = colors.labelPrimary,
                ),
            ) {
                Icon(painter = painterResource(R.drawable.ic_opt_level), contentDescription = stringResource(R.string.pc_pano_direction), modifier = Modifier.size(20.dp))
            }
            val heightLabel = stringResource(R.string.remote_toggle_height)
            val heightState = stringResource(if (eyeLevel) R.string.remote_eye_level else R.string.remote_below_eye)
            ToggleIconButton(
                checked = eyeLevel,
                onCheckedChange = vm::setRemoteAtEyeLevel,
                modifier = Modifier
                    .tooltip(text = "$heightLabel\n$heightState", direction = TooltipDirection.Top)
                    .semantics { stateDescription = heightState },
                size = ToggleIconButtonDefaults.Regular,
                colors = ToggleIconButtonDefaults.toggleIconButtonColors(
                    checkedContainerColor = colors.fillSecondary,
                    uncheckedContainerColor = Color.Transparent,
                ),
            ) {
                Icon(painterResource(R.drawable.ic_opt_position), contentDescription = heightLabel, modifier = Modifier.size(20.dp))
            }
            RemoteButton(R.drawable.ic_pc_more, stringResource(R.string.screen_show_controls)) {
                vm.remoteFormat.value = false
                vm.remoteTurning.value = false
                expanded = true
            }
            RemoteButton(R.drawable.ic_opt_window, stringResource(R.string.pc_space_window), onClick = onLeave)
        }
    }
}

@Composable
private fun RemoteButton(icon: Int, desc: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.tooltip(text = desc, direction = TooltipDirection.Top),
        size = IconButtonDefaults.Regular,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) {
        Icon(painter = painterResource(icon), contentDescription = desc, modifier = Modifier.size(20.dp))
    }
}

/** mm:ss, or h:mm:ss past an hour. */
private fun clock(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** The window's own steps, so the two controls agree: 15 s, a 30° turn, 5° of level. */
private const val STEP_MS = 15_000L
private const val TURN = 30f
private const val LEVEL = 5f

/** 全景格式 above the strip: its tiles in one row (the most a kind has is five: equirect and four lenses). */
private val FORMAT_WIDTH = 600.dp
private const val FORMAT_COLUMNS = 5
