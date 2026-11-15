package com.therealityteam.videoplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.videoplayer.LIBRARY_WC_ID
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Content of the separate player WindowContainer (no base panel). Renders the shared
 * PlaybackViewModel's current item. Back reopens the library window and closes this one, so
 * only one window is ever visible — the video is never sandwiched against the library's panel.
 */
@Composable
fun VideoWindow() {
    val koinScope = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID) }
    val vm = remember(koinScope) { koinScope.get<PlaybackViewModel>() }
    val navigator = LocalSpatialNavigator.current

    // The system's own close — the window's ✕ under it, its task taken away — ends this window without 返回:
    // the film played on, heard and not seen, its session up, until the app was next opened. What 返回 does
    // is done here when nothing has done it (a film still current), the space it was shown in closed too.
    DisposableEffect(vm) {
        onDispose {
            if (vm.current.value == null) return@onDispose
            android.util.Log.i("AuraWindows", "player window closed by the system: the film closes with it")
            val inSpace = vm.immersive.value || vm.cinemaDim.value || vm.environment.value != null
            vm.saveProgress()
            vm.close()
            if (inSpace) kotlinx.coroutines.MainScope().launch { runCatching { navigator.closeStage() } }
        }
    }

    VideoScreen(
        vm = vm,
        onBack = {
            // A library already up (the app's icon tapped mid-film) is the one to go back to.
            val open = com.therealityteam.videoplayer.libraryWindowsOpen.get()
            android.util.Log.i("AuraWindows", "player closed; ${if (open == 0) "library reopened" else "back to the $open library window(s) up"}")
            if (open == 0) navigator.openWindowContainer(LIBRARY_WC_ID)
            navigator.closeWindowContainer()
        },
    )
}
