package com.therealityteam.videoplayer.ui.library

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.SourceFault
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.ui.common.DangerRow
import com.therealityteam.videoplayer.ui.common.DetailActionRow
import com.therealityteam.videoplayer.ui.common.DetailNote
import com.therealityteam.videoplayer.ui.common.DetailPane
import com.therealityteam.videoplayer.ui.common.DetailRow
import com.therealityteam.videoplayer.ui.common.GroupLabel
import com.therealityteam.videoplayer.ui.common.LabelledField
import com.therealityteam.videoplayer.ui.common.MasterDetailScaffold
import com.therealityteam.videoplayer.ui.common.MasterEntry
import com.therealityteam.videoplayer.ui.common.MasterSection
import com.therealityteam.videoplayer.ui.common.PaneBackButton
import com.therealityteam.videoplayer.ui.common.SheetHook
import com.therealityteam.videoplayer.ui.theme.dangerButtonColors
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/** The network shares a user can add here. LOCAL is not one of them — it is always present. */
private val ADDABLE = listOf(SourceType.SMB, SourceType.WEBDAV, SourceType.FTP, SourceType.SFTP, SourceType.NFS, SourceType.DLNA)

/** The media servers, whose libraries come in already sorted. */
private val SERVERS = listOf(SourceType.JELLYFIN, SourceType.EMBY, SourceType.PLEX)

/** What the master column has selected; the detail pane is a function of this. */
private sealed interface SourceSel {
    /** The 「添加来源」 flow: pick a kind, then fill in that kind's form. */
    data object Add : SourceSel
    data object Local : SourceSel
    data class Configured(val id: String) : SourceSel

    /** The same form as adding, filled in from a configured source and saved back to its id. */
    data class Editing(val id: String) : SourceSel
}

/**
 * The find-a-share flow, which owns the detail pane while it runs: look for servers on the
 * network, then walk the one that was picked. It sits outside [SourceSel] because it is reached
 * both from 添加来源 and from an existing source's 浏览共享.
 */
private sealed interface SmbFlow {
    data object Discover : SmbFlow

    /** [fromDiscovery]: entered from the server search, which the walk's first back step returns to. */
    data class Browse(
        val host: String,
        val username: String,
        val password: String,
        val fromDiscovery: Boolean = false,
    ) : SmbFlow
}

/** Extract the bare host from an smb:// URI (or a raw host) for share-browsing. */
private fun smbHost(uri: String): String =
    uri.trim().removePrefix("smb://").removePrefix("//").substringBefore('/').trim()

/**
 * Whether an SMB address names a share as well as a host — `host/share[/path]`. A bare host is a
 * fine place to start browsing, but scanning it can only fail: the reader needs a share to open.
 */
private fun smbHasShare(uri: String): Boolean {
    val parts = uri.trim().removePrefix("smb://").removePrefix("//").trim('/').split('/')
    return parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()
}

/**
 * Where a source is, for the second line of its row: a share's or server's host — and a folder on the
 * headset by its path under the shared storage. That one has no host, and read 「本机 · 正在搜索网络」
 * (the words for a DLNA search with no address).
 */
private fun displayWhere(type: com.therealityteam.videoplayer.data.model.SourceType, uri: String): String =
    if (type == com.therealityteam.videoplayer.data.model.SourceType.LOCAL) {
        val path = uri.trim().removePrefix("file://")
        (LOCAL_ROOTS.firstOrNull { path.startsWith(it) }?.let { path.removePrefix(it) } ?: path).trim('/').ifEmpty { "/" }
    } else {
        displayHost(uri)
    }

/** The shared storage's own names: a path under it is told from there. */
private val LOCAL_ROOTS = listOf("/storage/emulated/0/", "/sdcard/")

/** The host part of any source address, for the second line of its row: no scheme, no path. */
private fun displayHost(uri: String): String =
    uri.trim()
        .substringAfter("://", uri.trim())
        .removePrefix("//")
        .substringBefore('/')

private fun SourceType.label() = when (this) {
    SourceType.SMB -> "SMB"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.FTP -> "FTP"
    SourceType.SFTP -> "SFTP"
    SourceType.NFS -> "NFS"
    SourceType.DLNA -> "DLNA"
    SourceType.JELLYFIN -> "Jellyfin"
    SourceType.EMBY -> "Emby"
    SourceType.PLEX -> "Plex"
    // A stable identifier, not a label: this is what gets STORED when a source is added without a
    // name, and a stored name must not change when the reader changes language. See [labelText].
    SourceType.LOCAL -> "Local"
    else -> name
}

/**
 * What to call this kind on screen. Every other name is a protocol and reads the same everywhere;
 * only LOCAL is a word, and a word has to come from the reader's language.
 */
@Composable
private fun SourceType.labelText(): String =
    if (this == SourceType.LOCAL) stringResource(R.string.source_local) else label()

@DrawableRes
private fun SourceType.icon() = when (this) {
    SourceType.SMB, SourceType.FTP, SourceType.SFTP, SourceType.NFS -> R.drawable.ic_line_nas
    SourceType.WEBDAV -> R.drawable.ic_line_cloud
    SourceType.DLNA -> R.drawable.ic_line_cast
    SourceType.JELLYFIN, SourceType.EMBY, SourceType.PLEX -> R.drawable.ic_line_media_server
    else -> R.drawable.ic_line_folder
}

/** One line describing what the kind is for, shown on its row in the picker. */
@StringRes
private fun SourceType.blurb(): Int = when (this) {
    SourceType.SMB -> R.string.source_blurb_smb
    SourceType.WEBDAV -> R.string.source_blurb_webdav
    SourceType.FTP -> R.string.source_blurb_ftp
    SourceType.SFTP -> R.string.source_blurb_sftp
    SourceType.NFS -> R.string.source_blurb_nfs
    SourceType.DLNA -> R.string.source_blurb_dlna
    SourceType.JELLYFIN -> R.string.source_blurb_jellyfin
    SourceType.EMBY -> R.string.source_blurb_emby
    SourceType.PLEX -> R.string.source_blurb_plex
    else -> 0
}

/** What a configured source IS, under its name: a share, a media server, or the DLNA search. */
@Composable
private fun SourceType.kindLine(): String = when (this) {
    SourceType.SMB, SourceType.WEBDAV, SourceType.FTP, SourceType.SFTP, SourceType.NFS -> stringResource(R.string.sources_kind_share, labelText())
    SourceType.DLNA -> stringResource(R.string.sources_kind_dlna)
    else -> stringResource(R.string.sources_kind_server, labelText())
}

@StringRes
private fun SourceType.uriLabel(): Int = when (this) {
    SourceType.SMB -> R.string.source_uri_label_host
    SourceType.DLNA -> R.string.source_uri_label_device
    else -> R.string.source_uri_label_server
}

@StringRes
private fun SourceType.uriHint(): Int = when (this) {
    SourceType.SMB -> R.string.source_uri_hint_smb
    SourceType.WEBDAV -> R.string.source_uri_hint_webdav
    SourceType.FTP -> R.string.source_uri_hint_ftp
    SourceType.SFTP -> R.string.source_uri_hint_sftp
    SourceType.NFS -> R.string.source_uri_hint_nfs
    SourceType.DLNA -> R.string.source_uri_hint_dlna
    SourceType.JELLYFIN -> R.string.source_uri_hint_jellyfin
    SourceType.EMBY -> R.string.source_uri_hint_emby
    SourceType.PLEX -> R.string.source_uri_hint_plex
    else -> R.string.source_uri_label_path
}

/** A share may be a guest's; a media server always wants its own account. */
private val SourceType.needsAccount get() = this == SourceType.JELLYFIN || this == SourceType.EMBY

/** An example name, in the flavour of the kind — a NAS box for SMB, a living-room device for DLNA. */
@StringRes
private fun SourceType.namePlaceholder(): Int = when (this) {
    SourceType.SMB, SourceType.FTP, SourceType.SFTP, SourceType.NFS -> R.string.source_name_eg_smb
    SourceType.WEBDAV -> R.string.source_name_eg_webdav
    SourceType.DLNA -> R.string.source_name_eg_dlna
    SourceType.JELLYFIN -> R.string.source_name_eg_jellyfin
    SourceType.EMBY -> R.string.source_name_eg_emby
    SourceType.PLEX -> R.string.source_name_eg_plex
    else -> 0
}

/** An example address. Only the DLNA one is a sentence; the rest are addresses, the same anywhere. */
private fun SourceType.uriPlaceholder(): String? = when (this) {
    SourceType.SMB -> "192.168.1.100"
    SourceType.WEBDAV -> "https://example.com/dav"
    SourceType.FTP, SourceType.SFTP -> "192.168.1.100/video"
    SourceType.NFS -> "192.168.1.100/volume1/video"
    SourceType.JELLYFIN, SourceType.EMBY -> "192.168.1.100:8096"
    SourceType.PLEX -> "192.168.1.100:32400"
    else -> null
}

/** The kinds whose address is a scheme, a host and a path on it (RemoteAddress). */
private val FILE_SERVERS = setOf(SourceType.FTP, SourceType.SFTP, SourceType.NFS)

/**
 * The address as kept: an FTP, SFTP or NFS one with its scheme in front (`192.168.1.100/video` typed,
 * `sftp://192.168.1.100/video` kept); any other kind's as typed.
 */
private fun remoteAddress(type: SourceType, uri: String): String {
    val u = uri.trim()
    if (type !in FILE_SERVERS) return u
    return type.name.lowercase() + "://" + u.substringAfter("://", u).trimEnd('/')
}

/** What is wrong with an address as typed, or null — an EMPTY address is "not yet", not an error. */
@StringRes
private fun addressProblem(type: SourceType, uri: String): Int? {
    val u = uri.trim()
    if (u.isEmpty()) return null
    return when {
        type == SourceType.WEBDAV && !u.startsWith("http://", ignoreCase = true) &&
            !u.startsWith("https://", ignoreCase = true) -> R.string.sources_webdav_scheme
        // An FTP/SFTP address may be typed with its scheme or without — but not another kind's.
        type in FILE_SERVERS && "://" in u &&
            u.substringBefore("://").lowercase() != type.name.lowercase() -> R.string.sources_remote_scheme
        // Only the host: a share or a folder may well have a space in its name ("My Movies").
        displayHost(u).any { it.isWhitespace() } -> R.string.sources_address_spaces
        else -> null
    }
}

/**
 * The state of the headset's own folders: each is scanned as `local:<dir>`, so the master row and
 * the 本地媒体 page read them together — scanning while any is, the total once all are done.
 */
private fun localScanState(status: Map<String, ScanState>): ScanState? {
    val local = status.filterKeys { it.startsWith("local:") }.values
    if (local.isEmpty()) return null
    if (local.any { it is ScanState.Scanning }) return ScanState.Scanning
    val done = local.filterIsInstance<ScanState.Done>()
    if (done.isEmpty()) return local.firstOrNull { it is ScanState.Failed }
    return ScanState.Done(done.sumOf { it.count }, done.firstNotNullOfOrNull { it.skipped })
}

/**
 * 媒体来源 — a master–detail page: every source the library reads from is a row on the left, and
 * the selected one's address, credentials, scan state and actions fill the right.
 *
 * The previous version was one scrolling column that mixed the list of existing sources with the
 * add-a-source form, so the form was always on screen even when nobody was adding anything, and
 * a source's own status was a line of small text wedged between two buttons. Adding is now its
 * own destination and, as in Infuse, it asks for the KIND first and only then for that kind's
 * fields — which is why the SMB form can say "just the host is enough" while the DLNA one says
 * "leave it blank".
 */
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val koinScope = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID) }
    val vm = remember(koinScope) { koinScope.get<LibraryViewModel>() }
    val sources by vm.sources.collectAsState()
    val status by vm.scanStatus.collectAsState()
    val taskProgress by vm.taskProgress.collectAsState()
    // What each source has in the library already: a scan's outcome lives only as long as the app, so
    // after a restart a share holding 9,081 files read 尚未扫描 — 点右上角「扫描」开始.
    val items by vm.items.collectAsState()
    val held = remember(items) { items.groupingBy { if (it.sourceId.startsWith("local:")) LOCAL_HELD else it.sourceId }.eachCount() }
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 扫描本地 reads the headset's Movies / Download, which since Android 13 list no videos to an app the system's
    // 「视频」 permission was not given to: the scan found 0 files beside two films. Asked for on the tap; refused,
    // the scan still reads Aura's own folder, and the page says what is missing and where to allow it.
    var mediaRefused by remember { mutableStateOf(false) }
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        android.util.Log.i("AuraScan", "permission to read videos: ${if (granted) "given" else "refused"}")
        mediaRefused = !granted
        vm.scanLocal()
    }
    val scanLocal: () -> Unit = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            mediaRefused = false
            vm.scanLocal()
        } else {
            askMedia.launch(android.Manifest.permission.READ_MEDIA_VIDEO)
        }
    }

    // Null until the user picks something: the page then opens on the first source when there is
    // one — what a returning user comes here to look at — and on 添加来源 when there is none. A
    // pick whose source has gone (removed, here or by a sync) falls back the same way.
    var chosen by remember { mutableStateOf<SourceSel?>(null) }
    val selection: SourceSel = chosen?.takeIf { sel ->
        when (sel) {
            is SourceSel.Configured -> sources.any { it.id == sel.id }
            is SourceSel.Editing -> sources.any { it.id == sel.id }
            else -> true
        }
    } ?: sources.firstOrNull()?.let { SourceSel.Configured(it.id) } ?: SourceSel.Add
    // `-e aura.detail_sheet scan_local` (with `aura.screen sources`): 本地媒体 picked and 扫描本地 pressed.
    SheetHook("scan_local") { chosen = SourceSel.Local; scanLocal() }

    // The add form, hoisted above the detail pane so switching selection and coming back does not
    // silently discard half-typed credentials.
    var addType by remember { mutableStateOf<SourceType?>(null) }
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    fun clearForm() { name = ""; uri = ""; user = ""; pass = "" }

    // The find-and-pick flow, which takes over the detail pane while it runs. Null means the pane
    // is showing whatever the master column has selected.
    var smbFlow by remember { mutableStateOf<SmbFlow?>(null) }

    /** Every master-row tap goes through here, so picking a row always leaves the share flow too. */
    fun select(sel: SourceSel) {
        smbFlow = null
        chosen = sel
    }

    // Automation hook: `-e aura.screen sources_add` opens the server search, and
    // `-e aura.screen sources_browse:<host>` the browser for one host. Both panes are otherwise
    // only reachable by tapping, which no screenshot run can do.
    val screenRequest by com.therealityteam.videoplayer.platform.DeepLink.screenRequest.collectAsState()
    LaunchedEffect(screenRequest) {
        val r = screenRequest?.lowercase() ?: return@LaunchedEffect
        when {
            r == "sources_add" -> { chosen = SourceSel.Add; smbFlow = SmbFlow.Discover }
            r.startsWith("sources_browse:") -> smbFlow = SmbFlow.Browse(r.substringAfter(':'), "", "")
            else -> return@LaunchedEffect
        }
        com.therealityteam.videoplayer.platform.DeepLink.screenRequest.value = null
    }

    /** Every SMB host already configured — the networks worth scanning besides the headset's own. */
    val knownSmbHosts = remember(sources) {
        sources.filter { it.type == SourceType.SMB }
            .map { smbHost(it.uri) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    val busy = taskProgress != null

    MasterDetailScaffold(
        title = stringResource(R.string.sources_title),
        onBack = onBack,
        master = {
            MasterSection(stringResource(R.string.sources_added)) {
                MasterEntry(
                    label = stringResource(R.string.sources_local_media),
                    supporting = stringResource(R.string.sources_local_dirs),
                    icon = R.drawable.ic_line_folder,
                    selected = selection is SourceSel.Local,
                    onClick = { select(SourceSel.Local) },
                    trailing = localScanState(status)?.let { st -> @Composable { StatusDot(st) } },
                )
                sources.forEach { s ->
                    MasterEntry(
                        label = s.name.ifBlank { s.type.labelText() },
                        supporting = stringResource(
                            R.string.md_pair,
                            s.type.labelText(),
                            displayWhere(s.type, s.uri).ifBlank { stringResource(R.string.sources_autosearch) },
                        ),
                        icon = s.type.icon(),
                        selected = when (val sel = selection) {
                            is SourceSel.Configured -> sel.id == s.id
                            is SourceSel.Editing -> sel.id == s.id
                            else -> false
                        },
                        onClick = { select(SourceSel.Configured(s.id)) },
                        trailing = status[s.id]?.let { st -> @Composable { StatusDot(st) } },
                    )
                }
            }
            MasterSection(stringResource(R.string.sources_add)) {
                MasterEntry(
                    label = stringResource(R.string.sources_add_ellipsis),
                    icon = R.drawable.ic_line_add,
                    selected = selection is SourceSel.Add,
                    onClick = { select(SourceSel.Add); addType = null },
                )
            }
        },
        masterFooter = {
            taskProgress?.let {
                LibraryTaskProgress(it, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                Spacer(Modifier.height(12.dp))
            }
            // Only the network sources: the headset's own folders are scanned from 本地媒体. Off
            // while any scan or scrape runs — a second one is not queued, it would simply not start.
            if (sources.isNotEmpty()) {
                Button(
                    onClick = { vm.scanAllSources() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    colors = secondaryButtonColors(),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_line_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) { Text(stringResource(R.string.sources_scan_all)) }
            }
        },
        detail = {
            val flow = smbFlow
            if (flow != null) {
                when (flow) {
                    is SmbFlow.Discover -> SmbDiscoverPane(
                        knownHosts = knownSmbHosts,
                        onPickHost = { picked ->
                            // The second share on a NAS that is already a source signs in as that
                            // source did, instead of asking for the account all over again.
                            val known = sources.firstOrNull {
                                it.type == SourceType.SMB && smbHost(it.uri).equals(picked, ignoreCase = true) &&
                                    !it.username.isNullOrBlank()
                            }
                            smbFlow = SmbFlow.Browse(
                                host = picked,
                                username = known?.username.orEmpty(),
                                password = known?.password.orEmpty(),
                                fromDiscovery = true,
                            )
                        },
                        // "I already know the address" drops into the plain form for SMB.
                        onManual = {
                            smbFlow = null
                            chosen = SourceSel.Add
                            addType = SourceType.SMB
                            clearForm()
                        },
                        onBack = {
                            smbFlow = null
                            chosen = SourceSel.Add
                            addType = null
                        },
                    )
                    is SmbFlow.Browse -> key(flow) {
                        SmbBrowsePane(
                            host = flow.host,
                            initialUsername = flow.username.ifBlank { null },
                            initialPassword = flow.password.ifBlank { null },
                            backLabel = stringResource(if (flow.fromDiscovery) R.string.smb_back_servers else R.string.det_back),
                            onBack = { smbFlow = if (flow.fromDiscovery) SmbFlow.Discover else null },
                            // Done lands on what was just added, where its scan can be watched.
                            onDone = { addedId ->
                                smbFlow = null
                                if (addedId != null) chosen = SourceSel.Configured(addedId)
                            },
                        )
                    }
                }
                return@MasterDetailScaffold
            }
            when (val sel = selection) {
                is SourceSel.Add, is SourceSel.Editing -> {
                    val editing = (sel as? SourceSel.Editing)?.let { e -> sources.firstOrNull { it.id == e.id } }
                    AddSourceDetail(
                        type = editing?.type ?: addType,
                        editing = editing,
                        // SMB starts by looking for servers rather than by asking for an address;
                        // the other kinds have nothing to discover, so they go straight to a form.
                        onPickType = { picked ->
                            clearForm()
                            if (picked == SourceType.SMB) smbFlow = SmbFlow.Discover else addType = picked
                        },
                        onPickLocal = { select(SourceSel.Local) },
                        onBack = {
                            clearForm()
                            if (editing != null) chosen = SourceSel.Configured(editing.id) else addType = null
                        },
                        name = name, onName = { name = it },
                        uri = uri, onUri = { uri = it },
                        user = user, onUser = { user = it },
                        pass = pass, onPass = { pass = it },
                        onBrowse = {
                            if (uri.isNotBlank()) {
                                smbFlow = SmbFlow.Browse(
                                    host = smbHost(uri),
                                    username = user.ifBlank { editing?.username.orEmpty() },
                                    password = pass.ifBlank { editing?.password.orEmpty() },
                                )
                            }
                        },
                        onSave = { type ->
                            val address = remoteAddress(type, uri)
                            val cfg = if (editing != null) {
                                editing.copy(
                                    name = name.ifBlank { type.label() },
                                    uri = address,
                                    username = user.trim().ifBlank { null },
                                    // A blank secret on an edit keeps the saved one: it is never shown.
                                    password = pass.ifBlank { editing.password },
                                )
                            } else {
                                MediaSourceConfig(
                                    id = "${type.name.lowercase()}_${(uri + name).hashCode().toUInt().toString(16)}",
                                    type = type,
                                    name = name.ifBlank { type.label() },
                                    uri = address,
                                    username = user.trim().ifBlank { null },
                                    password = pass.ifBlank { null },
                                    addedAt = 0L,
                                )
                            }
                            // Saved again by the user, an SFTP server is taken as it now is: a key it changed
                            // (a reinstall) is the one trusted from here on.
                            if (type == SourceType.SFTP) com.therealityteam.videoplayer.data.source.RemoteAddress.parse(address)?.let {
                                com.therealityteam.videoplayer.data.source.PinnedHostKeys.forget(it.host, it.port)
                            }
                            vm.addSource(cfg)
                            clearForm()
                            addType = null
                            chosen = SourceSel.Configured(cfg.id)
                            val message = context.getString(
                                if (editing != null) R.string.sources_saved_toast else R.string.sources_added_toast,
                                cfg.name,
                            )
                            scope.launch { snackbar.show(message = message) }
                        },
                    )
                }

                is SourceSel.Local -> LocalSourceDetail(
                    state = localScanState(status),
                    held = held[LOCAL_HELD] ?: 0,
                    busy = busy,
                    mediaRefused = mediaRefused,
                    onScan = scanLocal,
                    onAllow = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )

                is SourceSel.Configured -> {
                    val source = sources.firstOrNull { it.id == sel.id }
                    if (source != null) {
                        ConfiguredSourceDetail(
                            source = source,
                            state = status[source.id],
                            held = held[source.id] ?: 0,
                            onScan = { vm.rescanSource(source) },
                            onEdit = {
                                name = source.name
                                uri = source.uri
                                user = source.username.orEmpty()
                                pass = ""
                                chosen = SourceSel.Editing(source.id)
                            },
                            onBrowse = {
                                smbFlow = SmbFlow.Browse(
                                    smbHost(source.uri),
                                    source.username.orEmpty(),
                                    source.password.orEmpty(),
                                )
                            },
                            onRemove = {
                                val message = context.getString(R.string.sources_removed, source.name)
                                vm.removeSource(source.id)
                                chosen = null
                                scope.launch { snackbar.show(message = message) }
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Scan state as a compact glyph next to a source in the master column — the system spinner while
 * it scans, a tinted mark after — each with a spoken name, since the colour alone carries it.
 */
@Composable
private fun StatusDot(state: ScanState) {
    val colors = PicoTheme.colorScheme
    when (state) {
        ScanState.Scanning -> {
            val label = stringResource(R.string.sources_scanning_short)
            CircularProgressIndicator(
                progressSize = CircularProgressDefaults.Small,
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        is ScanState.Done -> Icon(
            painter = painterResource(if (state.count > 0) R.drawable.ic_line_check_circle else R.drawable.ic_line_warning),
            contentDescription = if (state.count > 0) {
                androidx.compose.ui.res.pluralStringResource(R.plurals.sources_found_n, state.count, state.count)
            } else {
                stringResource(R.string.sources_found_none)
            },
            tint = if (state.count > 0) colors.passable else colors.alert,
            modifier = Modifier.size(16.dp),
        )
        is ScanState.Failed -> Icon(
            painter = painterResource(R.drawable.ic_line_warning),
            contentDescription = stringResource(R.string.sources_connect_failed),
            tint = colors.error,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * A source's scan state as the first row of its page: a spinner while it runs, then what was
 * found, tinted the way its master-column glyph is — or why nothing could be.
 */
@Composable
private fun ScanStatusRow(state: ScanState?, held: Int) {
    val colors = PicoTheme.colorScheme
    when (state) {
        ScanState.Scanning -> DetailRow(
            title = stringResource(R.string.sources_scanning),
            description = stringResource(R.string.sources_scanning_hint),
            busy = true,
        )
        is ScanState.Done -> DetailRow(
            title = if (state.count > 0) androidx.compose.ui.res.pluralStringResource(R.plurals.sources_found_n, state.count, state.count) else stringResource(R.string.sources_found_none),
            description = state.skipped?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.sources_anilist_down, it, it) }
                ?: if (state.count > 0) stringResource(R.string.sources_handed_off) else stringResource(R.string.sources_check_path),
            icon = if (state.count > 0 && state.skipped == null) R.drawable.ic_line_check_circle else R.drawable.ic_line_warning,
            iconTint = if (state.count > 0 && state.skipped == null) colors.passable else colors.alert,
        )
        is ScanState.Failed -> DetailRow(
            title = stringResource(R.string.sources_connect_failed),
            description = sourceFaultText(state.fault, state.detail),
            icon = R.drawable.ic_line_warning,
            iconTint = colors.error,
        )
        null -> if (held > 0) {
            DetailRow(
                title = androidx.compose.ui.res.pluralStringResource(R.plurals.sources_held_n, held, held),
                description = stringResource(R.string.sources_held_hint),
                icon = R.drawable.ic_line_check_circle,
                iconTint = colors.passable,
            )
        } else {
            DetailRow(
                title = stringResource(R.string.sources_not_scanned),
                description = stringResource(R.string.sources_not_scanned_hint),
                icon = R.drawable.ic_line_pending,
            )
        }
    }
}

/**
 * Step one of adding: which kind. Step two: that kind's fields — the same fields, filled in, when
 * [editing] a configured source.
 */
@Composable
private fun AddSourceDetail(
    type: SourceType?,
    editing: MediaSourceConfig?,
    onPickType: (SourceType) -> Unit,
    onPickLocal: () -> Unit,
    onBack: () -> Unit,
    name: String, onName: (String) -> Unit,
    uri: String, onUri: (String) -> Unit,
    user: String, onUser: (String) -> Unit,
    pass: String, onPass: (String) -> Unit,
    onBrowse: () -> Unit,
    onSave: (SourceType) -> Unit,
) {
    if (type == null) {
        DetailPane(title = stringResource(R.string.sources_add_title), subtitle = stringResource(R.string.sources_add_subtitle)) {
            // Network shares first: SMB is the usual answer, and the tip about it sits right under it.
            GroupLabel(stringResource(R.string.sources_group_share))
            ADDABLE.forEach { t ->
                DetailActionRow(
                    title = t.labelText(),
                    description = stringResource(t.blurb()),
                    icon = t.icon(),
                    onClick = { onPickType(t) },
                )
            }
            DetailNote(stringResource(R.string.sources_smb_tip))
            GroupLabel(stringResource(R.string.sources_group_server))
            SERVERS.forEach { t ->
                DetailActionRow(
                    title = t.labelText(),
                    description = stringResource(t.blurb()),
                    icon = t.icon(),
                    onClick = { onPickType(t) },
                )
            }
            GroupLabel(stringResource(R.string.source_local))
            DetailActionRow(
                title = stringResource(R.string.sources_local_folder),
                description = stringResource(R.string.sources_local_folder_hint),
                icon = R.drawable.ic_line_folder,
                onClick = onPickLocal,
            )
        }
        return
    }

    val hasSavedSecret = editing != null && !editing.password.isNullOrBlank()
    val problem = addressProblem(type, uri)
    val smbHostOnly = type == SourceType.SMB && uri.isNotBlank() && problem == null && !smbHasShare(uri)
    val canSave = problem == null && when (type) {
        SourceType.DLNA -> true
        SourceType.SMB -> smbHasShare(uri)
        SourceType.PLEX -> uri.isNotBlank() && (pass.isNotBlank() || hasSavedSecret)
        else -> uri.isNotBlank() && (!type.needsAccount || user.isNotBlank())
    }
    val saveLabel = stringResource(if (editing != null) R.string.sources_save_and_scan else R.string.sources_add_and_scan)

    DetailPane(
        title = if (editing != null) {
            stringResource(R.string.sources_edit_title, editing.name.ifBlank { type.labelText() })
        } else {
            stringResource(R.string.sources_add_kind, type.labelText())
        },
        subtitle = stringResource(type.blurb()),
        leadingActions = {
            PaneBackButton(
                label = stringResource(if (editing != null) R.string.det_back else R.string.sources_back_kinds),
                onClick = onBack,
            )
        },
        actions = {
            if (type == SourceType.SMB) {
                // Browsing is how a share gets picked: it leads while the address is only a host,
                // and steps aside for 添加并扫描 once a share is named.
                Button(
                    onClick = onBrowse,
                    enabled = uri.isNotBlank() && problem == null,
                    colors = if (smbHasShare(uri)) secondaryButtonColors() else ButtonDefaults.buttonColors(),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_line_folder),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) { Text(stringResource(R.string.sources_browse)) }
                if (smbHasShare(uri)) {
                    Button(onClick = { onSave(type) }, enabled = canSave) { Text(saveLabel) }
                }
            } else {
                Button(onClick = { onSave(type) }, enabled = canSave) { Text(saveLabel) }
            }
        },
    ) {
        LabelledField(
            label = stringResource(R.string.sources_name),
            description = stringResource(R.string.sources_name_hint, type.labelText()),
            value = name,
            onValueChange = onName,
            placeholder = type.namePlaceholder().takeIf { it != 0 }?.let { stringResource(it) },
        )
        LabelledField(
            label = stringResource(type.uriLabel()),
            description = stringResource(if (smbHostOnly) R.string.sources_smb_need_share else type.uriHint()),
            value = uri,
            onValueChange = onUri,
            placeholder = type.uriPlaceholder(),
            keyboardType = KeyboardType.Uri,
            autoCorrect = false,
            error = problem?.let { stringResource(it) },
        )
        if (type == SourceType.PLEX) {
            // Plex signs with a token, not an account: it goes where a password would.
            LabelledField(
                label = stringResource(R.string.sources_plex_token_label),
                description = stringResource(if (hasSavedSecret) R.string.sources_token_keep else R.string.sources_plex_token_hint),
                value = pass,
                onValueChange = onPass,
                placeholder = if (hasSavedSecret) stringResource(R.string.sources_saved_placeholder) else null,
                secret = true,
            )
        } else if (type == SourceType.NFS) {
            // NFS asks no password: the NAS trusts the device's address, and sees it as a user id.
            LabelledField(
                label = stringResource(R.string.sources_nfs_ids_label),
                description = stringResource(R.string.sources_nfs_ids_hint),
                value = user,
                onValueChange = onUser,
                keyboardType = KeyboardType.Uri,
                autoCorrect = false,
            )
        } else if (type != SourceType.DLNA) {
            LabelledField(
                label = stringResource(R.string.sources_username),
                description = stringResource(if (type.needsAccount) R.string.sources_server_user_hint else R.string.sources_anonymous_hint),
                value = user,
                onValueChange = onUser,
                autoCorrect = false,
            )
            LabelledField(
                label = stringResource(R.string.sources_password),
                description = stringResource(
                    when {
                        hasSavedSecret -> R.string.sources_password_keep
                        type.needsAccount -> R.string.sources_server_pass_hint
                        else -> R.string.sources_anonymous_hint
                    },
                ),
                value = pass,
                onValueChange = onPass,
                placeholder = if (hasSavedSecret) stringResource(R.string.sources_saved_placeholder) else null,
                secret = true,
            )
        }
        if (type == SourceType.SMB) DetailNote(stringResource(R.string.sources_browse_hint))
    }
}

@Composable
private fun LocalSourceDetail(
    state: ScanState?,
    held: Int,
    busy: Boolean,
    mediaRefused: Boolean,
    onScan: () -> Unit,
    onAllow: () -> Unit,
) {
    DetailPane(
        title = stringResource(R.string.sources_local_media),
        subtitle = stringResource(R.string.sources_local_desc),
        actions = {
            Button(
                onClick = onScan,
                enabled = !busy && state != ScanState.Scanning,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_line_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) { Text(stringResource(R.string.sources_scan_local)) }
        },
    ) {
        GroupLabel(stringResource(R.string.sources_status))
        ScanStatusRow(state, held)
        if (busy && state != ScanState.Scanning) DetailNote(stringResource(R.string.sources_scan_busy))
        if (mediaRefused) {
            DetailActionRow(
                title = stringResource(R.string.sources_local_no_permission),
                description = stringResource(R.string.sources_local_no_permission_hint),
                icon = R.drawable.ic_line_folder,
                onClick = onAllow,
            )
        }
        GroupLabel(stringResource(R.string.sources_scan_scope))
        DetailRow(
            title = stringResource(R.string.sources_local_folder),
            description = stringResource(R.string.sources_scan_scope_hint),
            icon = R.drawable.ic_line_folder,
        )
        DetailNote(stringResource(R.string.sources_local_note))
    }
}

@Composable
private fun ConfiguredSourceDetail(
    source: MediaSourceConfig,
    state: ScanState?,
    held: Int,
    onScan: () -> Unit,
    onEdit: () -> Unit,
    onBrowse: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val isSmb = source.type == SourceType.SMB
    SheetHook("edit") { onEdit() }
    SheetHook("remove") { confirmRemove = true }
    // An SMB source that failed for want of a share or a folder is fixed by browsing to one; that
    // action then sits under the failure, and is not repeated further down.
    val browseIsTheFix = isSmb && state is ScanState.Failed &&
        state.fault in setOf(SourceFault.NO_ADDRESS, SourceFault.NO_SHARE, SourceFault.NO_FOLDER)

    DetailPane(
        title = source.name.ifBlank { source.type.labelText() },
        subtitle = source.type.kindLine(),
        actions = {
            Button(
                onClick = onEdit,
                colors = secondaryButtonColors(),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_line_edit),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) { Text(stringResource(R.string.sources_edit)) }
            Button(
                onClick = onScan,
                enabled = state != ScanState.Scanning,
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_line_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) { Text(stringResource(R.string.sources_scan)) }
        },
    ) {
        GroupLabel(stringResource(R.string.sources_status))
        ScanStatusRow(state, held)
        // A failure points at the one thing that fixes it, right under the reason.
        if (state is ScanState.Failed) {
            when {
                browseIsTheFix ->
                    DetailActionRow(
                        title = stringResource(R.string.sources_browse),
                        description = stringResource(R.string.sources_browse_desc),
                        icon = R.drawable.ic_line_folder,
                        onClick = onBrowse,
                    )
                state.fault == SourceFault.AUTH -> DetailActionRow(
                    title = stringResource(R.string.sources_fix_account),
                    description = stringResource(R.string.sources_fix_account_hint),
                    icon = R.drawable.ic_line_edit,
                    onClick = onEdit,
                )
                else -> DetailActionRow(
                    title = stringResource(R.string.sources_fix_address),
                    description = stringResource(R.string.sources_fix_address_hint),
                    icon = R.drawable.ic_line_edit,
                    onClick = onEdit,
                )
            }
        }

        GroupLabel(stringResource(R.string.sources_connection))
        DetailRow(
            title = stringResource(R.string.sources_address),
            description = source.uri.ifBlank { stringResource(R.string.sources_autosearch) },
        )
        if (source.type == SourceType.PLEX) {
            DetailRow(
                title = stringResource(R.string.sources_token),
                description = if (source.password.isNullOrBlank()) stringResource(R.string.sources_token_missing) else stringResource(R.string.sources_token_saved),
            )
        } else if (source.type != SourceType.DLNA) {
            DetailRow(
                title = stringResource(R.string.sources_account),
                description = source.username?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sources_anonymous),
            )
        }
        if (isSmb && !browseIsTheFix) {
            DetailActionRow(
                title = stringResource(R.string.sources_browse),
                description = stringResource(R.string.sources_browse_desc),
                onClick = onBrowse,
            )
        }

        GroupLabel(stringResource(R.string.set_danger))
        DangerRow(
            title = stringResource(R.string.sources_remove),
            description = stringResource(R.string.sources_remove_hint),
            action = stringResource(R.string.sources_remove_action),
            onClick = { confirmRemove = true },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.sources_remove_q, source.name.ifBlank { source.type.labelText() })) },
            content = { Text(stringResource(R.string.sources_remove_body)) },
            buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { confirmRemove = false },
                        colors = secondaryButtonColors(),
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = { confirmRemove = false; onRemove() },
                        colors = dangerButtonColors(),
                    ) { Text(stringResource(R.string.remove)) }
                }
            },
        )
    }
}

/**
 * Why a source could not be reached, in the reader's language. [detail] is raw protocol text (a
 * path, an HTTP status) and is only ever shown where the sentence asks for it — or, for a cause
 * nobody recognised, after a sentence that says so.
 */
@Composable
fun sourceFaultText(fault: SourceFault, detail: String?): String = when (fault) {
    SourceFault.AUTH -> stringResource(R.string.fault_auth)
    SourceFault.NO_SHARE -> stringResource(R.string.fault_no_share)
    SourceFault.NO_FOLDER -> stringResource(R.string.fault_no_folder, detail ?: "")
    SourceFault.NO_ADDRESS -> stringResource(R.string.fault_no_address)
    SourceFault.UNREACHABLE -> stringResource(R.string.fault_unreachable)
    SourceFault.SERVER -> stringResource(R.string.fault_server, detail ?: "")
    SourceFault.HOST_CHANGED -> stringResource(R.string.fault_host_changed)
    SourceFault.NFS_REFUSED -> stringResource(R.string.fault_nfs_refused)
    SourceFault.UNKNOWN -> detail?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
        ?.let { stringResource(R.string.fault_unknown_detail, it) }
        ?: stringResource(R.string.fault_unknown)
}

/** The size of something on disk, in the reader's own units ("1.8 GB", "940 MB"). */
@Composable
internal fun fileSizeText(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The key [SourcesScreen] counts every local folder's files under: they are one source on the page. */
private const val LOCAL_HELD = "local:"
