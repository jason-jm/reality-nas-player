package com.therealityteam.videoplayer

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Immersion
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.therealityteam.videoplayer.ui.AuraApp
import com.therealityteam.videoplayer.ui.immersive.ImmersiveScene
import com.therealityteam.videoplayer.ui.player.VideoWindow

/** Stage id for the on-demand Full-Space immersive sphere. */
const val STAGE_ID = "AuraImmersiveStage"

/** Default (library) window id — must match pico.spatial.windowcontainer.id in the manifest. */
const val LIBRARY_WC_ID = "AuraLibraryWindow"

/** Separate player window — opened on play, closed on back. */
const val VIDEO_WC_ID = "AuraVideoWindow"

/**
 * The player window's tag: opened under it, a play while the player is up (the app's icon tapped again
 * mid-film, a cast, a second film picked from the library brought back beside it) reuses that window. With
 * no tag every open made another — 11 players stood in the room after 11 plays, each a VideoScreen of the
 * one PlaybackViewModel, so one 拖动 +10 min seeked eleven times.
 */
const val VIDEO_WC_TAG = "player"

/**
 * Library windows up now, counted by [AuraApp]: the player's 返回 opens one only when none is. With the app's
 * icon tapped mid-film the library stands beside the player already, and 返回 made a second (an untagged
 * openWindowContainer always makes a new window; the default one is not opened under a tag to reuse).
 */
val libraryWindowsOpen = java.util.concurrent.atomic.AtomicInteger()

/**
 * Two windows so each gets the right material:
 * - Library = the default WindowContainer with the system base panel (materialbackground=1),
 *   which is the real frosted glass AND enables the Vibrant color system.
 * - Video = a separate WindowContainer with enableMaterialBackground=false, so the SpatialView
 *   video is the only surface (no base panel to peek out behind it). On play we open the video
 *   window and close the library; on back we reopen the library and close the video window.
 * Stage = the Full-Space immersive sphere.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { PicoTheme { AuraApp() } }

        WindowContainer(
            id = VIDEO_WC_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(1280.dp, 720.dp),
            enableMaterialBackground = false,
        ) { PicoTheme { VideoWindow() } }

        Stage(id = STAGE_ID, immersion = Immersion(min = 0, max = 100, default = 65)) {
            PicoTheme { ImmersiveScene() }
        }
    }
