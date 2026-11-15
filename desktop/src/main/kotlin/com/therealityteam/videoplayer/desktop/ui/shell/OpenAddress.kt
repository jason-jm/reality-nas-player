package com.therealityteam.videoplayer.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.AuraTextField
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * File › Open Address…: a video by its address — on the web, on a share (smb://), a stream —
 * played as it is, without joining the library. An address on the clipboard is offered already
 * typed, as QuickTime's Open Location does.
 */
@Composable
fun OpenAddressDialog(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(540.dp, 200.dp)),
        title = s["d_open_url"].trimEnd('…', '.'),
        resizable = false,
    ) {
        OpenAddressForm(remember { clipboardAddress().orEmpty() }, onOpen = { onClose(); onOpen(it) }, onCancel = onClose)
    }
}

/** The dialog's contents: a line of explanation, the address, Cancel and Play. */
@Composable
internal fun OpenAddressForm(initial: String, onOpen: (String) -> Unit, onCancel: () -> Unit) {
    val s = LocalStrings.current
    val c = Aura.colors
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        val a = text.trim()
        if (isAddress(a)) onOpen(a)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false
            }
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Txt(s["d_open_url_hint"], Aura.type.callout, c.textSecondary, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        AuraTextField(
            text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = "https://…   smb://…", onSubmit = ::submit, height = 34.dp,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AuraButton(s["cancel"], onCancel)
            Spacer(Modifier.width(8.dp))
            AuraButton(s["d_play"], ::submit, kind = ButtonKind.Primary, enabled = isAddress(text.trim()))
        }
    }
}

/** Something the player can open: an address with a scheme, or a file on this computer. */
internal fun isAddress(a: String): Boolean =
    ADDRESS.matches(a) || (a.isNotEmpty() && runCatching { File(a.removePrefix("file://")).isFile }.getOrDefault(false))

private val ADDRESS = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$")

/** The clipboard's text, when it is a video's address and nothing else. */
private fun clipboardAddress(): String? = runCatching {
    val t = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    t?.trim()?.takeIf { it.length < 4096 && Regex("^(https?|smb|rtsp|rtmps?|ftp|dav|davs)://\\S+$", RegexOption.IGNORE_CASE).matches(it) }
}.getOrNull()
