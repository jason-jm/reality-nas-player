package com.therealityteam.videoplayer.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import android.view.KeyEvent
import android.view.View

/**
 * The system's 返回 — the BACK key, a gamepad's B, a keyboard's Esc — heard in the window this is composed in while
 * [enabled]. Unheard it finished the window: a title's page or 设置 closed the whole library, and the player's
 * close left nothing of the app in the room with the film stalled behind it. The newest composed answers first
 * (the dispatcher's order), so a page over the wall is asked before the wall. A window with a dispatcher of its own
 * — the player's Toolbar ornament, a dialog that takes the focus while the bar is up — is heard through it; one
 * with none — a space's attached panel (window type 2996, the 全景 remote, which has the focus in a stage) —
 * hands the key to no one, and is heard as a key nothing in it took.
 */
@Composable
fun SystemBack(enabled: Boolean = true, what: String, onBack: () -> Unit) {
    val view = LocalView.current
    val latest by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() {
                android.util.Log.i("AuraWindows", "返回: $what")
                latest()
            }
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(view) {
        var c: Context? = view.context
        while (c is ContextWrapper && c !is OnBackPressedDispatcherOwner) c = c.baseContext
        val owner = view.findViewTreeOnBackPressedDispatcherOwner() ?: c as? OnBackPressedDispatcherOwner
        owner?.onBackPressedDispatcher?.addCallback(callback)
        // An activity's or a dialog's window takes BACK in its dispatcher first, so this is not heard twice there.
        val unhandled = View.OnUnhandledKeyEventListener { _, e ->
            if (e.keyCode != KeyEvent.KEYCODE_BACK || !callback.isEnabled) return@OnUnhandledKeyEventListener false
            if (e.action == KeyEvent.ACTION_UP) callback.handleOnBackPressed()
            true
        }
        view.addOnUnhandledKeyEventListener(unhandled)
        onDispose {
            callback.remove()
            view.removeOnUnhandledKeyEventListener(unhandled)
        }
    }
}
