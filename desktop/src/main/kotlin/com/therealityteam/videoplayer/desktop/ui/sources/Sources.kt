package com.therealityteam.videoplayer.desktop.ui.sources

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MonitorPlay
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.Film
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.ScrapeStatus
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.DiscoveredHost
import com.therealityteam.videoplayer.data.source.SmbEntry
import com.therealityteam.videoplayer.data.source.SourceFault
import com.therealityteam.videoplayer.data.source.faultOf
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.i18n.Strings
import com.therealityteam.videoplayer.desktop.platform.Pickers
import com.therealityteam.videoplayer.desktop.ui.AppState
import com.therealityteam.videoplayer.desktop.ui.addFolder
import com.therealityteam.videoplayer.desktop.ui.components.AuraButton
import com.therealityteam.videoplayer.desktop.ui.components.AuraTextField
import com.therealityteam.videoplayer.desktop.ui.components.ButtonKind
import com.therealityteam.videoplayer.desktop.ui.components.Divider
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.ProgressLine
import com.therealityteam.videoplayer.desktop.ui.components.Spinner
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.components.clickCursor
import com.therealityteam.videoplayer.desktop.ui.components.interactionWash
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import com.therealityteam.videoplayer.ui.library.ScanState
import com.therealityteam.videoplayer.ui.library.TaskPhase
import kotlinx.coroutines.launch
import java.awt.Frame

/** What the right-hand pane is showing. */
private sealed interface Pane {
    data class Source(val id: String) : Pane
    data object Kinds : Pane
    data class Form(val type: SourceType) : Pane
    data object SmbFind : Pane
    data class SmbBrowse(val host: String, val user: String = "", val pass: String = "") : Pane
}

/**
 * Where the library comes from: the sources added so far on the left, with how their last scan
 * went; on the right, one source's details — or adding a new one, which for SMB starts by finding
 * the NAS on the network rather than asking for its address.
 */
@Composable
fun SourcesPage(state: AppState, window: Frame?) {
    val c = Aura.colors
    val s = LocalStrings.current
    val lib = state.graph.library
    val sources by lib.sources.collectAsState()
    val status by lib.scanStatus.collectAsState()
    val progress by lib.taskProgress.collectAsState()
    // What each source holds in the library now — true after a restart, unlike a scan's result.
    val items by state.graph.media.items.collectAsState()
    val held = remember(items) {
        items.groupBy { it.sourceId }.mapValues { (_, v) -> Held(v.size, v.count { it.scrapeStatus == ScrapeStatus.MATCHED }) }
    }
    var pane by remember { mutableStateOf<Pane>(if (sources.isEmpty()) Pane.Kinds else Pane.Source(sources.first().id)) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().padding(start = 24.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(s["sources_title"], Aura.type.title1, c.text, Modifier.weight(1f))
                AuraButton(s["sources_add"], { pane = Pane.Kinds }, icon = Lucide.Plus, height = 30.dp)
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (sources.isNotEmpty()) Txt(s["sources_added"], Aura.type.micro, c.textTertiary, Modifier.padding(start = 8.dp, bottom = 6.dp))
                sources.forEach { src ->
                    SourceRow(src, status[src.id], held[src.id], selected = pane == Pane.Source(src.id)) { pane = Pane.Source(src.id) }
                }
                SourceListRow(Lucide.Plus, s["sources_add_ellipsis"], null, selected = pane !is Pane.Source) { pane = Pane.Kinds }
            }
            Column(Modifier.padding(vertical = 14.dp)) {
                progress?.let { p ->
                    Txt(
                        if (p.phase == TaskPhase.SCANNING || p.indeterminate) s["d_task_scanning"] else s["d_task_matching", p.done, p.total],
                        Aura.type.caption, c.textSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(if (p.indeterminate) null else p.fraction)
                    Spacer(Modifier.height(10.dp))
                }
                AuraButton(s["sources_scan_all"], { lib.scanAllSources() }, Modifier.fillMaxWidth(), icon = Lucide.RefreshCw, enabled = progress == null && sources.isNotEmpty())
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.stroke))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 36.dp, vertical = 4.dp)) {
                when (val p = pane) {
                    is Pane.Source -> sources.firstOrNull { it.id == p.id }?.let { src ->
                        SourceDetail(state, src, status[src.id], held[src.id], progress == null,
                            onBrowse = { pane = Pane.SmbBrowse(smbHost(src.uri), src.username.orEmpty(), src.password.orEmpty()) },
                            onRemoved = { pane = Pane.Kinds })
                    } ?: run { pane = Pane.Kinds }
                    Pane.Kinds -> KindChooser(
                        onLocal = {
                            Pickers.chooseFolder(window, s["d_add_folder"])?.let { f ->
                                addFolder(state, f)
                                pane = Pane.Source("local:" + f.absolutePath)
                            }
                        },
                        onKind = { t -> pane = if (t == SourceType.SMB) Pane.SmbFind else Pane.Form(t) },
                    )
                    is Pane.Form -> SourceForm(state, p.type, onBack = { pane = Pane.Kinds },
                        onBrowse = { host, u, pw -> pane = Pane.SmbBrowse(host, u, pw) },
                        onAdded = { id -> pane = Pane.Source(id) })
                    Pane.SmbFind -> SmbFind(state, onBack = { pane = Pane.Kinds },
                        onHost = { host -> pane = Pane.SmbBrowse(host.address) },
                        onManual = { pane = Pane.Form(SourceType.SMB) })
                    is Pane.SmbBrowse -> SmbBrowse(state, p.host, p.user, p.pass, onDone = {
                        pane = sources.lastOrNull()?.let { Pane.Source(it.id) } ?: Pane.Kinds
                    })
                }
                Spacer(Modifier.height(40.dp))
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private fun smbHost(uri: String): String = uri.trim().removePrefix("smb://").removePrefix("//").substringBefore('/').trim()

private fun kindLabel(t: SourceType, s: Strings): String = when (t) {
    SourceType.LOCAL -> s["sources_local_folder"]
    SourceType.SMB -> "SMB"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.DLNA -> "DLNA"
    SourceType.NFS -> "NFS"
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.PLEX -> "Plex"
    SourceType.EMBY -> "Emby"
    SourceType.FTP -> "FTP"
    SourceType.SFTP -> "SFTP"
}

private fun kindIcon(t: SourceType): ImageVector = when (t) {
    SourceType.LOCAL -> Lucide.HardDrive
    SourceType.SMB -> Lucide.Network
    SourceType.WEBDAV -> Lucide.Cloud
    SourceType.DLNA -> Lucide.Tv
    SourceType.NFS, SourceType.FTP, SourceType.SFTP -> Lucide.Server
    SourceType.JELLYFIN, SourceType.EMBY -> Lucide.MonitorPlay
    SourceType.PLEX -> Lucide.Film
}

@Composable
private fun SourceRow(src: MediaSourceConfig, st: ScanState?, held: Held?, selected: Boolean, onClick: () -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(AuraShapes.control).background(interactionWash(source, selected))
            .clickable(interactionSource = source, indication = null, onClick = onClick).clickCursor()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon(src.type), if (selected) c.accent else c.textSecondary, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(src.name.ifBlank { kindLabel(src.type, s) }, Aura.type.bodyStrong, c.text)
            Txt(src.uri.ifBlank { s["sources_autosearch"] }, Aura.type.caption, c.textTertiary)
        }
        when (st) {
            ScanState.Scanning -> Spinner(14.dp)
            is ScanState.Done -> Icon(if (st.count > 0) Lucide.CircleCheck else Lucide.CircleAlert, if (st.count > 0) c.success else c.warning, 15.dp)
            is ScanState.Failed -> Icon(Lucide.CircleAlert, c.danger, 15.dp)
            null -> if (held != null && held.files > 0) Txt(held.files.toString(), Aura.type.caption, c.textTertiary)
        }
    }
}

/** How many of a source's files are in the library, and how many of those were identified. */
data class Held(val files: Int, val identified: Int)

@Composable
private fun SourceListRow(icon: ImageVector, label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val c = Aura.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(AuraShapes.control).background(interactionWash(source, selected))
            .clickable(interactionSource = source, indication = null, onClick = onClick).clickCursor()
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, c.textSecondary, 16.dp)
        Spacer(Modifier.width(10.dp))
        Txt(label, Aura.type.callout, c.text)
    }
}

@Composable
private fun SourceDetail(
    state: AppState,
    src: MediaSourceConfig,
    st: ScanState?,
    held: Held?,
    idle: Boolean,
    onBrowse: () -> Unit,
    onRemoved: () -> Unit,
) {
    val c = Aura.colors
    val s = LocalStrings.current
    var confirm by remember(src.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(kindIcon(src.type), c.accent, 26.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(src.name.ifBlank { kindLabel(src.type, s) }, Aura.type.title1, c.text)
            Txt(if (src.type == SourceType.LOCAL) s["sources_local_desc"] else s["sources_kind_share", kindLabel(src.type, s)], Aura.type.callout, c.textSecondary)
        }
        AuraButton(s["sources_scan"], { state.graph.library.rescanSource(src) }, kind = ButtonKind.Primary, icon = Lucide.RefreshCw, enabled = idle && st != ScanState.Scanning)
    }
    Spacer(Modifier.height(24.dp))
    Section(s["sources_status"]) {
        val (line1, line2, tone) = when (st) {
            null -> if (held != null && held.files > 0) {
                Triple(s["d_source_in_library", held.files], s["d_source_identified", held.identified], 1)
            } else Triple(s["sources_not_scanned"], s["sources_not_scanned_hint"], 0)
            ScanState.Scanning -> Triple(s["sources_scanning"], s["sources_scanning_hint"], 0)
            is ScanState.Done -> if (st.count > 0) {
                Triple(s.plural("sources_found_n", st.count), listOfNotNull(s["sources_handed_off"], st.skipped?.let { s.plural("sources_anilist_down", it) }).joinToString(" · "), 1)
            } else Triple(s["sources_found_none"], s["sources_check_path"], 2)
            is ScanState.Failed -> Triple(s["sources_connect_failed"], faultText(st.fault, st.detail, src, s), 3)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (tone) {
                1 -> Icon(Lucide.CircleCheck, c.success, 16.dp)
                2 -> Icon(Lucide.CircleAlert, c.warning, 16.dp)
                3 -> Icon(Lucide.CircleAlert, c.danger, 16.dp)
                else -> if (st == ScanState.Scanning) Spinner(16.dp) else Icon(Lucide.Folder, c.textTertiary, 16.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Txt(line1, Aura.type.bodyStrong, c.text)
                if (line2.isNotBlank()) Txt(line2, Aura.type.caption, c.textSecondary, maxLines = 3)
            }
        }
    }
    Section(s["sources_connection"]) {
        KeyValue(s["sources_address"], src.uri.ifBlank { s["sources_autosearch"] })
        when (src.type) {
            SourceType.LOCAL, SourceType.DLNA -> {}
            SourceType.PLEX -> KeyValue(s["sources_token"], if (src.password.isNullOrBlank() && src.token.isNullOrBlank()) s["sources_token_missing"] else s["sources_token_saved"])
            else -> KeyValue(s["sources_account"], src.username?.takeIf { it.isNotBlank() } ?: s["sources_anonymous"])
        }
        if (src.type == SourceType.SMB) {
            Spacer(Modifier.height(10.dp))
            AuraButton(s["sources_browse"], onBrowse, icon = Lucide.FolderOpen)
        }
    }
    Section(s["sources_manage"]) {
        if (!confirm) {
            AuraButton(s["sources_remove"], { confirm = true }, kind = ButtonKind.Danger, icon = Lucide.Trash2)
            Spacer(Modifier.height(6.dp))
            Txt(s["sources_remove_hint"], Aura.type.caption, c.textTertiary, maxLines = 3)
        } else {
            Txt(s["sources_remove_q", src.name], Aura.type.bodyStrong, c.text)
            Spacer(Modifier.height(4.dp))
            Txt(s["sources_remove_body"], Aura.type.caption, c.textSecondary, maxLines = 3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuraButton(s["remove"], { state.graph.library.removeSource(src.id); onRemoved() }, kind = ButtonKind.Danger)
                AuraButton(s["cancel"], { confirm = false })
            }
        }
    }
}

fun faultText(fault: SourceFault, detail: String?, src: MediaSourceConfig, s: Strings): String = when (fault) {
    SourceFault.AUTH -> s["fault_auth"]
    SourceFault.NO_SHARE -> s["fault_no_share"]
    SourceFault.NO_FOLDER -> s["fault_no_folder", src.uri]
    SourceFault.NO_ADDRESS -> s["fault_no_address"]
    SourceFault.UNREACHABLE -> s["fault_unreachable"]
    SourceFault.SERVER -> s["fault_server", detail.orEmpty().take(120)]
    SourceFault.HOST_CHANGED -> s["fault_host_changed"]
    SourceFault.NFS_REFUSED -> s["fault_nfs_refused"]
    SourceFault.UNKNOWN -> detail.orEmpty().take(120)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Aura.colors
    Txt(title, Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 8.dp))
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).clip(AuraShapes.card).background(c.surface).border(1.dp, c.stroke, AuraShapes.card).padding(16.dp)) {
        content()
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Txt(k, Aura.type.callout, Aura.colors.textSecondary, Modifier.width(110.dp))
        Txt(v, Aura.type.callout, Aura.colors.text, maxLines = 2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChooser(onLocal: () -> Unit, onKind: (SourceType) -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    Txt(s["sources_add_title"], Aura.type.title1, c.text)
    Spacer(Modifier.height(4.dp))
    Txt(s["sources_add_subtitle"], Aura.type.body, c.textSecondary)
    Spacer(Modifier.height(22.dp))
    Txt(s["sources_local_media"], Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KindCard(Lucide.HardDrive, s["sources_local_folder"], s["sources_local_folder_hint"], onLocal)
    }
    Spacer(Modifier.height(20.dp))
    Txt(s["sources_group_share"], Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KindCard(Lucide.Network, "SMB", s["source_blurb_smb"]) { onKind(SourceType.SMB) }
        KindCard(Lucide.Cloud, "WebDAV", s["source_blurb_webdav"]) { onKind(SourceType.WEBDAV) }
        KindCard(Lucide.Server, "FTP", s["source_blurb_ftp"]) { onKind(SourceType.FTP) }
        KindCard(Lucide.Server, "SFTP", s["source_blurb_sftp"]) { onKind(SourceType.SFTP) }
        KindCard(Lucide.Server, "NFS", s["source_blurb_nfs"]) { onKind(SourceType.NFS) }
        KindCard(Lucide.Tv, "DLNA", s["source_blurb_dlna"]) { onKind(SourceType.DLNA) }
    }
    Spacer(Modifier.height(20.dp))
    Txt(s["sources_group_server"], Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KindCard(Lucide.MonitorPlay, "Jellyfin", s["source_blurb_jellyfin"]) { onKind(SourceType.JELLYFIN) }
        KindCard(Lucide.MonitorPlay, "Emby", s["source_blurb_emby"]) { onKind(SourceType.EMBY) }
        KindCard(Lucide.Film, "Plex", s["source_blurb_plex"]) { onKind(SourceType.PLEX) }
    }
    Spacer(Modifier.height(18.dp))
    Txt(s["sources_smb_tip"], Aura.type.caption, c.textTertiary, Modifier.widthIn(max = 640.dp), maxLines = 3)
}

@Composable
private fun KindCard(icon: ImageVector, title: String, blurb: String, onClick: () -> Unit) {
    val c = Aura.colors
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.width(236.dp).height(118.dp).clip(AuraShapes.card).background(c.surface)
            .background(interactionWash(source)).border(1.dp, c.stroke, AuraShapes.card)
            .clickable(interactionSource = source, indication = null, onClick = onClick).clickCursor()
            .padding(16.dp),
    ) {
        Icon(icon, c.accent, 22.dp)
        Spacer(Modifier.height(10.dp))
        Txt(title, Aura.type.headline, c.text)
        Spacer(Modifier.height(2.dp))
        Txt(blurb, Aura.type.caption, c.textSecondary, maxLines = 2)
    }
}

@Composable
private fun SourceForm(state: AppState, type: SourceType, onBack: () -> Unit, onBrowse: (String, String, String) -> Unit, onAdded: (String) -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    var name by remember(type) { mutableStateOf("") }
    var uri by remember(type) { mutableStateOf("") }
    var user by remember(type) { mutableStateOf("") }
    var pass by remember(type) { mutableStateOf("") }
    val key = type.name.lowercase()
    BackRow(onBack)
    Txt(s["sources_add_kind", kindLabel(type, s)], Aura.type.title1, c.text)
    Spacer(Modifier.height(18.dp))
    Column(Modifier.widthIn(max = 560.dp)) {
        // A file server is named like a share: an FTP or SFTP one is most often the same NAS.
        val egKey = if (type == SourceType.FTP || type == SourceType.SFTP || type == SourceType.NFS) "smb" else key
        Field(s["sources_name"], name, { name = it }, s["source_name_eg_$egKey"], s["sources_name_hint", kindLabel(type, s)])
        val (label, hint) = when (type) {
            SourceType.SMB -> s["source_uri_label_host"] to s["source_uri_hint_smb"]
            SourceType.DLNA -> s["source_uri_label_device"] to s["source_uri_hint_dlna"]
            SourceType.JELLYFIN, SourceType.EMBY, SourceType.PLEX, SourceType.WEBDAV, SourceType.FTP, SourceType.SFTP, SourceType.NFS ->
                s["source_uri_label_server"] to s["source_uri_hint_$key"]
            else -> s["source_uri_label_path"] to ""
        }
        val placeholder = when (type) {
            SourceType.SMB -> "192.168.1.100"
            SourceType.WEBDAV -> "https://example.com/dav"
            SourceType.FTP, SourceType.SFTP -> "192.168.1.100/video"
            SourceType.NFS -> "192.168.1.100/volume1/video"
            SourceType.JELLYFIN, SourceType.EMBY -> "192.168.1.100:8096"
            SourceType.PLEX -> "192.168.1.100:32400"
            SourceType.DLNA -> s["source_uri_eg_search"]
            else -> ""
        }
        Field(label, uri, { uri = it }, placeholder, hint)
        when (type) {
            SourceType.DLNA -> {}
            SourceType.PLEX -> Field("X-Plex-Token", pass, { pass = it }, "", s["sources_plex_token_hint"], password = true)
            // NFS asks no password: the NAS trusts the address, and sees it as a user id.
            SourceType.NFS -> Field(s["sources_nfs_ids_label"], user, { user = it }, "1026:100", s["sources_nfs_ids_hint"])
            else -> {
                Field(s["sources_username"], user, { user = it }, "", if (type == SourceType.SMB || type == SourceType.FTP) s["sources_anonymous_hint"] else "")
                Field(s["sources_password"], pass, { pass = it }, "", "", password = true)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (type == SourceType.SMB) {
                AuraButton(s["sources_browse"], { onBrowse(smbHost(uri), user, pass) }, kind = ButtonKind.Primary, enabled = uri.isNotBlank(), icon = Lucide.FolderOpen)
            }
            val canAdd = type == SourceType.DLNA || uri.isNotBlank()
            // An SMB address without a share cannot be scanned: that is what Browse is for.
            val smbNeedsShare = type == SourceType.SMB && smbHost(uri) == uri.trim().removePrefix("smb://").trim('/')
            AuraButton(
                s["sources_add_and_scan"],
                {
                    val normalized = when {
                        type == SourceType.SMB && !uri.trim().startsWith("smb://") -> "smb://" + uri.trim().trimStart('/')
                        // `192.168.1.100/video` typed, `sftp://192.168.1.100/video` kept.
                        type == SourceType.FTP || type == SourceType.SFTP || type == SourceType.NFS ->
                            key + "://" + uri.trim().let { it.substringAfter("://", it) }.trimEnd('/')
                        else -> uri.trim()
                    }
                    // Added again by the user, an SFTP server is taken as it now is (a reinstall's new key).
                    if (type == SourceType.SFTP) com.therealityteam.videoplayer.data.source.RemoteAddress.parse(normalized)?.let {
                        com.therealityteam.videoplayer.data.source.PinnedHostKeys.forget(it.host, it.port)
                    }
                    val cfg = MediaSourceConfig(
                        id = "${key}_${(normalized + name).hashCode().toUInt().toString(16)}",
                        type = type,
                        name = name.ifBlank { kindLabel(type, s) },
                        uri = normalized,
                        username = user.trim().ifBlank { null },
                        password = pass.ifBlank { null },
                        addedAt = System.currentTimeMillis(),
                    )
                    state.graph.library.addSource(cfg)
                    onAdded(cfg.id)
                },
                kind = if (type == SourceType.SMB) ButtonKind.Secondary else ButtonKind.Primary,
                enabled = canAdd && !smbNeedsShare,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, hint: String, password: Boolean = false) {
    val c = Aura.colors
    Txt(label, Aura.type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), c.text)
    Spacer(Modifier.height(6.dp))
    AuraTextField(value, onChange, Modifier.fillMaxWidth(), placeholder = placeholder, password = password, height = 34.dp)
    if (hint.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Txt(hint, Aura.type.caption, c.textTertiary, maxLines = 3)
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.padding(bottom = 10.dp).clip(AuraShapes.control).clickable(onClick = onBack).clickCursor().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.ArrowLeft, Aura.colors.textSecondary, 15.dp)
        Spacer(Modifier.width(6.dp))
        Txt(s["d_go_back"], Aura.type.callout, Aura.colors.textSecondary)
    }
}

/** Finding SMB servers on the networks this computer is on. */
@Composable
private fun SmbFind(state: AppState, onBack: () -> Unit, onHost: (DiscoveredHost) -> Unit, onManual: () -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    val discovery = state.graph.smbDiscovery
    val hints = remember { mutableStateListOf<String>().apply { addAll(state.graph.sources.all().filter { it.type == SourceType.SMB }.map { smbHost(it.uri) }) } }
    val found = remember { mutableStateListOf<DiscoveredHost>() }
    var round by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(true) }
    var subnet by remember { mutableStateOf("") }
    LaunchedEffect(round) {
        searching = true
        found.clear()
        runCatching {
            discovery.discover(hints.toList()).collect { h -> if (found.none { it.address == h.address }) found.add(h) }
        }
        searching = false
    }
    BackRow(onBack)
    Txt(s["smb_find_servers"], Aura.type.title1, c.text)
    Spacer(Modifier.height(4.dp))
    val networks = remember(round) { runCatching { discovery.plannedNetworks(hints.toList()) }.getOrDefault(emptyList()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            Spinner(14.dp)
            Spacer(Modifier.width(8.dp))
        }
        Txt(
            when {
                searching -> s["smb_scanning_nets", networks.joinToString(", ")]
                found.isEmpty() -> s["smb_none_found"]
                else -> s.plural("smb_found_n", found.size)
            },
            Aura.type.callout, c.textSecondary,
        )
    }
    Spacer(Modifier.height(18.dp))
    Column(Modifier.widthIn(max = 640.dp)) {
        if (found.isNotEmpty()) {
            Txt(s["smb_on_this_network"], Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 8.dp))
            found.sortedBy { it.name ?: it.address }.forEach { h ->
                val source = remember(h.address) { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().clip(AuraShapes.control).background(interactionWash(source))
                        .clickable(interactionSource = source, indication = null) { onHost(h) }.clickCursor()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Server, c.accent, 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(h.name ?: s["smb_a_server"], Aura.type.bodyStrong, c.text)
                        Txt(h.address, Aura.type.caption, c.textTertiary)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (!searching) AuraButton(s["smb_search_again"], { round++ }, icon = Lucide.RefreshCw)
        Spacer(Modifier.height(24.dp))
        Txt(s["smb_cant_find"], Aura.type.headline, c.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuraTextField(subnet, { subnet = it }, Modifier.width(240.dp), placeholder = "192.168.2.1", height = 32.dp)
            Spacer(Modifier.width(8.dp))
            AuraButton(s["smb_search_subnet"], {
                if (subnet.isNotBlank()) {
                    hints.add(0, subnet.trim())
                    round++
                }
            }, enabled = subnet.isNotBlank())
        }
        Spacer(Modifier.height(4.dp))
        Txt(s["smb_other_subnet_hint"], Aura.type.caption, c.textTertiary, maxLines = 2)
        Spacer(Modifier.height(14.dp))
        AuraButton(s["smb_type_address"], onManual)
        Spacer(Modifier.height(4.dp))
        Txt(s["smb_type_address_hint"], Aura.type.caption, c.textTertiary)
    }
}

/** Browsing an SMB server like a file manager, adding the folders to scan. */
@Composable
private fun SmbBrowse(state: AppState, host: String, user0: String, pass0: String, onDone: () -> Unit) {
    val c = Aura.colors
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var user by remember(host) { mutableStateOf(user0) }
    var pass by remember(host) { mutableStateOf(pass0) }
    var share by remember(host) { mutableStateOf<String?>(null) }
    var path by remember(host) { mutableStateOf("") }
    var entries by remember(host) { mutableStateOf<List<SmbEntry>?>(null) }
    var needLogin by remember(host) { mutableStateOf(false) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val sources by state.graph.library.sources.collectAsState()
    LaunchedEffect(host, share, path, round) {
        entries = null
        failure = null
        runCatching {
            val u = user.ifBlank { null }
            val p = pass.ifBlank { null }
            if (share == null) state.graph.smb.listShares(host, u, p)
            else state.graph.smb.browse(host, share!!, path, u, p)
        }.onSuccess { entries = it; needLogin = false }
            .onFailure { e ->
                if (faultOf(e) == SourceFault.AUTH) needLogin = true
                else failure = e.message ?: e.toString()
                entries = emptyList()
            }
    }
    val here = "smb://$host" + (share?.let { "/$it" } ?: "") + (if (path.isNotBlank()) "/$path" else "")
    Txt(host, Aura.type.title1, c.text)
    Txt(here, Aura.type.caption, c.textTertiary)
    Spacer(Modifier.height(14.dp))
    Column(Modifier.widthIn(max = 720.dp)) {
        if (needLogin) {
            Txt(s["smb_need_login"], Aura.type.headline, c.text)
            Txt(s["smb_fill_creds"], Aura.type.caption, c.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuraTextField(user, { user = it }, Modifier.width(180.dp), placeholder = s["smb_username"])
                AuraTextField(pass, { pass = it }, Modifier.width(180.dp), placeholder = s["smb_password"], password = true, onSubmit = { round++ })
                AuraButton(s["smb_sign_in"], { round++ }, kind = ButtonKind.Primary)
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (share != null) {
                AuraButton(s["smb_up"], {
                    if (path.isBlank()) share = null else path = path.substringBeforeLast('/', "")
                }, icon = Lucide.ArrowUp)
                val added = sources.any { it.type == SourceType.SMB && it.uri.trimEnd('/') == here }
                AuraButton(
                    if (added) s["smb_added"] else s["smb_add_this"],
                    {
                        state.graph.library.addSource(
                            MediaSourceConfig(
                                id = "smb_${here.hashCode().toUInt().toString(16)}",
                                type = SourceType.SMB,
                                name = share + (if (path.isNotBlank()) "/$path" else ""),
                                uri = here,
                                username = user.ifBlank { null },
                                password = pass.ifBlank { null },
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    kind = ButtonKind.Primary, enabled = !added, icon = if (added) Lucide.Check else Lucide.Plus,
                )
            }
            Spacer(Modifier.weight(1f))
            AuraButton(s["smb_done"], onDone)
        }
        Spacer(Modifier.height(14.dp))
        val list = entries
        when {
            list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(16.dp)
                Spacer(Modifier.width(8.dp))
                Txt(s["smb_reading"], Aura.type.callout, c.textSecondary)
            }
            failure != null -> Txt(s["smb_cannot_open"] + " · " + failure, Aura.type.callout, c.danger, maxLines = 3)
            list.isEmpty() && !needLogin -> Txt(if (share == null) s["smb_no_shares"] else s["smb_nothing_inside"], Aura.type.callout, c.textSecondary)
            else -> {
                val dirs = list.filter { it.isDirectory }
                val videos = list.filter { !it.isDirectory }
                if (dirs.isNotEmpty()) {
                    Txt(if (share == null) s["smb_shares"] else s["smb_folders"], Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 6.dp))
                    dirs.forEach { d ->
                        val childUri = here + "/" + d.name
                        val isAdded = sources.any { it.type == SourceType.SMB && it.uri.trimEnd('/') == childUri }
                        val source = remember(childUri) { MutableInteractionSource() }
                        Row(
                            Modifier.fillMaxWidth().clip(AuraShapes.control).background(interactionWash(source))
                                .clickable(interactionSource = source, indication = null) {
                                    if (share == null) { share = d.name; path = "" } else path = if (path.isBlank()) d.name else "$path/${d.name}"
                                }.clickCursor()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (share == null) Lucide.HardDrive else Lucide.Folder, c.accent, 17.dp)
                            Spacer(Modifier.width(10.dp))
                            Txt(d.name, Aura.type.body, c.text, Modifier.weight(1f))
                            if (isAdded) Txt(s["smb_added_as_source"], Aura.type.caption, c.success)
                        }
                    }
                }
                if (videos.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Txt(s["smb_videos_here", videos.size], Aura.type.micro, c.textTertiary, Modifier.padding(bottom = 6.dp))
                    videos.take(100).forEach { v ->
                        Txt(v.name, Aura.type.caption, c.textSecondary, Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
                    }
                    if (videos.size > 100) Txt(s.plural("smb_more_videos", videos.size - 100), Aura.type.caption, c.textTertiary, Modifier.padding(10.dp))
                }
            }
        }
    }
}
