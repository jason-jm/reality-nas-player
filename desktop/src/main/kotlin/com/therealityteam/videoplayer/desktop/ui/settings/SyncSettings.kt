package com.therealityteam.videoplayer.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Glasses
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.TriangleAlert
import com.therealityteam.videoplayer.data.sync.LibrarySync
import com.therealityteam.videoplayer.data.sync.SeenDevice
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.Desktop
import com.therealityteam.videoplayer.desktop.platform.Pickers
import com.therealityteam.videoplayer.desktop.sync.SyncLocation
import com.therealityteam.videoplayer.desktop.sync.SyncService
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.AuraSwitch
import com.therealityteam.videoplayer.desktop.ui.components.AuraTextField
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.Divider
import com.therealityteam.videoplayer.desktop.ui.components.DropdownMenu
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.SettingRow
import com.therealityteam.videoplayer.desktop.ui.components.Spinner
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.menu
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Settings › Sync: off until asked for; then where the folder lives, what this computer is called
 * there, how the last sync went, who else is in it, and a way to take it all off the NAS.
 */
@Composable
internal fun SyncPage(state: AppState) {
    val s = LocalStrings.current
    val c = Aura.colors
    val graph = state.graph
    val st by graph.settings.settings.collectAsState()
    val status by graph.sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    // Finding the candidates lists the mounted shares (a command), so not on the UI thread.
    var candidates by remember { mutableStateOf<List<SyncLocation>?>(null) }
    LaunchedEffect(Unit) { candidates = withContext(Dispatchers.IO) { graph.sync.candidates() } }
    val chosen: SyncLocation? = status.location
        ?: if (st.syncLocation.startsWith("folder:")) SyncLocation.Folder(st.syncLocation.removePrefix("folder:"))
        else candidates?.let { list -> list.firstOrNull { it.id.equals(st.syncLocation, true) } ?: list.firstOrNull().takeIf { st.syncLocation.isBlank() } }

    Txt(s["d_sync_intro"], Aura.type.callout, c.textSecondary, maxLines = 6)
    Spacer(Modifier.height(16.dp))
    Group(null) {
        SettingRow(s["d_sync_enable"], s["d_sync_enable_hint"]) {
            AuraSwitch(st.syncEnabled, { on ->
                graph.settings.update {
                    it.copy(syncEnabled = on, syncLocation = it.syncLocation.ifBlank { chosen?.id.orEmpty() })
                }
            })
        }
        Divider()
        val noPlace = candidates?.isEmpty() == true && chosen == null
        val hint = when {
            noPlace -> s["d_sync_no_location"]
            chosen is SyncLocation.Folder -> s["d_sync_location_hint_folder"]
            else -> s["d_sync_location_hint"]
        }
        SettingRow(s["d_sync_location"], hint) {
            LocationPicker(chosen, candidates.orEmpty(), s) { loc ->
                graph.settings.update { it.copy(syncLocation = loc.id) }
            }
        }
        Divider()
        SettingRow(s["d_sync_device_name"], s["d_sync_device_name_hint"]) {
            AuraTextField(
                st.deviceName, { v -> graph.settings.update { it.copy(deviceName = v.take(60)) } },
                Modifier.width(230.dp), placeholder = SyncService.defaultDeviceName,
            )
        }
    }
    if (st.syncEnabled && chosen != null) {
        Group(s["d_sync_status"]) {
            val last = status.last
            val lastLine = when {
                status.syncing -> s["d_sync_syncing"]
                last == null -> s["d_sync_never"]
                last.error != null -> s["d_sync_failed", last.error ?: ""]
                last.applied > 0 -> s["d_sync_took", ago(last.at, s), last.applied]
                else -> s["d_sync_took_none", ago(last.at, s)]
            }
            SettingRow(s["d_sync_last"], lastLine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status.syncing) {
                        Spinner(16.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    AuraButton(s["d_sync_now"], { scope.launch { graph.sync.syncNow() } }, icon = Lucide.RefreshCw, enabled = !status.syncing)
                }
            }
            if (last?.error != null) Notice(Lucide.CircleAlert, c.danger, s["d_sync_failed", last.error ?: ""])
            else if (status.writable == false) Notice(Lucide.TriangleAlert, c.warning, s["d_sync_readonly"])
            Divider()
            val where = when (chosen) {
                is SyncLocation.Smb -> "smb://${chosen.host}/${chosen.share}/${LibrarySync.FOLDER}"
                is SyncLocation.Mounted -> File(chosen.mount.point, LibrarySync.FOLDER).path
                is SyncLocation.Folder -> File(chosen.path, LibrarySync.FOLDER).path
            }
            val detail = last?.takeIf { it.error == null && it.bytes > 0 }?.let { "$where · ${formatBytes(it.bytes)}" } ?: where
            SettingRow(s["d_sync_folder"], detail) {
                val folder = File(where)
                val shown = if (folder.exists()) folder else folder.parentFile
                if (chosen !is SyncLocation.Smb && shown != null && Desktop.canReveal(shown.absolutePath)) {
                    AuraButton(Desktop.revealLabel(s), { Desktop.reveal(shown.absolutePath) })
                }
            }
        }
        Group(s["d_sync_devices"]) {
            val devices = status.last?.devices.orEmpty()
            if (devices.isEmpty()) Para(null, s["d_sync_devices_none"])
            else devices.forEachIndexed { i, d ->
                if (i > 0) Divider()
                DeviceRow(d, s)
            }
        }
    }
    Group(s["d_sync_what"]) {
        Para(null, s["d_sync_what_body"])
        Divider()
        Para(null, s["d_sync_nas_note"])
    }
    if (chosen != null && (st.syncEnabled || status.last != null)) RemoveGroup(state, s)
}

@Composable
private fun LocationPicker(chosen: SyncLocation?, candidates: List<SyncLocation>, s: Strings, onPick: (SyncLocation) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val window = LocalSettingsWindow.current
    Box {
        val text = when (chosen) {
            null -> s["d_sync_choose_folder"]
            is SyncLocation.Smb -> "${chosen.label} · ${chosen.detail}"
            else -> chosen.label
        }
        AuraButton(text, { open = true }, Modifier.widthIn(max = 280.dp))
        DropdownMenu(open, { open = false }, alignEnd = true, entries = menu {
            candidates.forEach { loc -> check(loc.label, loc.id == chosen?.id, detail = loc.detail) { onPick(loc) } }
            if (chosen is SyncLocation.Folder) check(chosen.label, true, detail = chosen.detail) {}
            separator()
            item(s["d_sync_choose_folder"]) {
                Pickers.chooseFolder(window, s["d_sync_choose_folder"])?.let { onPick(SyncLocation.Folder(it.absolutePath)) }
            }
        })
    }
}

@Composable
private fun DeviceRow(d: SeenDevice, s: Strings) {
    val c = Aura.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (d.platform.contains("pico", ignoreCase = true) || d.platform.contains("android", ignoreCase = true)) Lucide.Glasses else Lucide.Laptop, c.textSecondary, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(d.name, Aura.type.bodyStrong, c.text)
            Txt(s["d_sync_seen", d.platform.ifBlank { "—" }, if (d.lastSeen > 0) ago(d.lastSeen, s) else "—"], Aura.type.callout, c.textSecondary)
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, tint, 16.dp, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Txt(text, Aura.type.callout, tint, maxLines = 4)
    }
}

@Composable
private fun RemoveGroup(state: AppState, s: Strings) {
    val c = Aura.colors
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Group(s["set_danger"]) {
        if (!confirm) {
            SettingRow(s["d_sync_remove"], s["d_sync_remove_hint"]) {
                AuraButton(s["d_sync_remove_button"], { confirm = true }, kind = ButtonKind.Danger)
            }
        } else {
            Column(Modifier.padding(vertical = 10.dp)) {
                Txt(s["d_sync_remove_q"], Aura.type.bodyStrong, c.text)
                Spacer(Modifier.height(4.dp))
                Txt(s["d_sync_remove_body"], Aura.type.callout, c.textSecondary, maxLines = 4)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AuraButton(s["d_sync_remove_confirm"], {
                        busy = true
                        scope.launch {
                            val r = state.graph.sync.remove()
                            busy = false
                            confirm = false
                            r.onSuccess {
                                // Off too: left on, the next sync would write this computer's part back.
                                state.graph.settings.update { it.copy(syncEnabled = false) }
                                state.toast(s["d_sync_removed"])
                            }.onFailure { e -> state.toast(s["d_sync_failed", e.message ?: e.toString()]) }
                        }
                    }, kind = ButtonKind.Danger, enabled = !busy)
                    AuraButton(s["cancel"], { confirm = false }, enabled = !busy)
                    if (busy) Spinner(16.dp)
                }
            }
        }
    }
}

/** "just now", "5 min ago", "yesterday", "3 days ago", then the date. */
internal fun ago(at: Long, s: Strings, now: Long = System.currentTimeMillis()): String {
    val min = (now - at).coerceAtLeast(0) / 60_000
    return when {
        min < 1 -> s["d_ago_now"]
        min < 60 -> s["d_ago_min", min.toInt()]
        min < 24 * 60 -> s["d_ago_hour", (min / 60).toInt()]
        min < 48 * 60 -> s["d_ago_yesterday"]
        min < 30 * 24 * 60 -> s["d_ago_day", (min / (24 * 60)).toInt()]
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
