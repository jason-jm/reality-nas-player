package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.LocalSnackbarHostState
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.model.MediaSourceConfig
import com.therealityteam.videoplayer.data.model.SourceType
import com.therealityteam.videoplayer.data.source.SmbEntry
import com.therealityteam.videoplayer.data.source.SmbSource
import com.therealityteam.videoplayer.data.source.SourceFault
import com.therealityteam.videoplayer.data.source.faultOf
import com.therealityteam.videoplayer.di.AURA_SESSION_SCOPE_ID
import com.therealityteam.videoplayer.ui.common.DetailActionRow
import com.therealityteam.videoplayer.ui.common.DetailNote
import com.therealityteam.videoplayer.ui.common.DetailPane
import com.therealityteam.videoplayer.ui.common.DetailRow
import com.therealityteam.videoplayer.ui.common.EmptyState
import com.therealityteam.videoplayer.ui.common.GroupLabel
import com.therealityteam.videoplayer.ui.common.LabelledField
import com.therealityteam.videoplayer.ui.common.PaneBackButton
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import com.pico.spatial.ui.design.R as DesignR

/**
 * Walks an SMB host the way a file manager does — shares, then folders — so a source is chosen
 * by pointing at it instead of by typing `smb://host/share/some/deep/path`.
 *
 * Credentials are asked for only when the server actually refuses: the first listing is tried
 * anonymously (or with the account of a source already on this host), and the login fields appear
 * in place if that comes back as an access error. Many home NAS boxes list their shares to guests,
 * and asking everyone for a password first would be a step most people did not need. A saved
 * password is used but never shown: the field stays empty and says it holds one.
 *
 * Adding a folder does not leave the browser. Somebody adding 电影, 剧集 and 动画 from one NAS is
 * doing one job, not three, so the row for a folder already added reads 已添加 and the walk
 * continues from where it was. 完成 ([onDone]) then lands on the last folder added.
 *
 * The back button at the bar's start climbs: a folder to its parent, a share to the share list,
 * and the share list to wherever the walk began ([onBack], named by [backLabel]).
 */
@Composable
fun SmbBrowsePane(
    host: String,
    initialUsername: String?,
    initialPassword: String?,
    backLabel: String,
    onBack: () -> Unit,
    onDone: (addedId: String?) -> Unit,
) {
    val smb = remember { GlobalContext.get().get<SmbSource>() }
    val vm = remember { GlobalContext.get().getScope(AURA_SESSION_SCOPE_ID).get<LibraryViewModel>() }
    val sources by vm.sources.collectAsState()
    val colors = PicoTheme.colorScheme
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val savedPassword = initialPassword.orEmpty()
    var username by remember { mutableStateOf(initialUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    // What the last listing was actually made with, so editing the fields does not re-list until
    // the user says so; [attempt] lets the same account be tried again after a network hiccup.
    var credentials by remember { mutableStateOf(initialUsername.orEmpty() to savedPassword) }
    var attempt by remember { mutableIntStateOf(0) }
    var needsAuth by remember { mutableStateOf(false) }
    var signInTried by remember { mutableStateOf(false) }
    var authRefused by remember { mutableStateOf(false) }
    var lastAdded by remember { mutableStateOf<String?>(null) }

    var share by remember { mutableStateOf<String?>(null) } // null = listing the host's shares
    var path by remember { mutableStateOf("") }             // folder path inside the share
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Pair<SourceFault, String?>?>(null) }

    LaunchedEffect(share, path, credentials, attempt) {
        loading = true
        error = null
        entries = emptyList()
        val (user, pass) = credentials
        val result = runCatching {
            val s = share
            if (s == null) smb.listShares(host, user.ifBlank { null }, pass.ifBlank { null })
            else smb.browse(host, s, path, user.ifBlank { null }, pass.ifBlank { null })
        }
        result
            .onSuccess { entries = it; needsAuth = false; authRefused = false }
            .onFailure {
                val fault = faultOf(it)
                if (fault == SourceFault.AUTH) {
                    // The first refusal is the server asking for an account; a refusal after the
                    // user has signed in is the account being wrong — that one is said out loud.
                    authRefused = signInTried
                    needsAuth = true
                } else {
                    error = fault to it.message
                }
            }
        loading = false
    }

    val currentUri = "smb://$host" + (share?.let { "/$it" } ?: "") + (if (path.isNotBlank()) "/$path" else "")
    val alreadyAdded = sources.any { it.uri.trimEnd('/').equals(currentUri.trimEnd('/'), ignoreCase = true) }
    val atRoot = share == null

    DetailPane(
        title = when {
            path.isNotBlank() -> path.substringAfterLast('/')
            share != null -> share!!
            else -> host
        },
        subtitle = currentUri,
        leadingActions = {
            PaneBackButton(
                label = when {
                    path.isNotBlank() -> stringResource(R.string.smb_up)
                    share != null -> stringResource(R.string.smb_back_shares)
                    else -> backLabel
                },
                onClick = {
                    when {
                        path.isNotBlank() -> path = path.substringBeforeLast('/', "")
                        share != null -> share = null
                        else -> onBack()
                    }
                },
            )
        },
        actions = {
            if (!atRoot && !needsAuth) {
                Button(
                    onClick = {
                        val cfg = MediaSourceConfig(
                            id = "smb_${currentUri.hashCode().toUInt().toString(16)}",
                            type = SourceType.SMB,
                            name = share + (if (path.isNotBlank()) "/$path" else ""),
                            uri = currentUri,
                            username = credentials.first.ifBlank { null },
                            password = credentials.second.ifBlank { null },
                        )
                        vm.addSource(cfg)
                        lastAdded = cfg.id
                        val message = context.getString(R.string.sources_added_toast, cfg.name)
                        scope.launch { snackbar.show(message = message) }
                    },
                    enabled = !alreadyAdded,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(if (alreadyAdded) R.drawable.ic_line_check else R.drawable.ic_line_plus),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                ) { Text(stringResource(if (alreadyAdded) R.string.smb_added else R.string.smb_add_this)) }
            }
            Button(
                onClick = { onDone(lastAdded) },
                colors = secondaryButtonColors(),
            ) { Text(stringResource(R.string.smb_done)) }
        },
    ) {
        if (needsAuth) {
            SignInForm(
                username = username,
                onUsername = { username = it },
                password = password,
                onPassword = { password = it },
                hasSavedPassword = savedPassword.isNotEmpty(),
                signingIn = loading,
                refused = authRefused && !loading,
                networkError = error?.takeIf { !loading },
                onSignIn = {
                    signInTried = true
                    credentials = username.trim() to password.ifEmpty { savedPassword }
                    attempt++
                },
            )
            return@DetailPane
        }

        if (loading) {
            EmptyState(
                title = stringResource(R.string.smb_reading),
                loading = true,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        error?.let { (fault, detail) ->
            DetailRow(
                title = stringResource(R.string.smb_cannot_open),
                description = sourceFaultText(fault, detail),
                icon = R.drawable.ic_line_warning,
                iconTint = colors.error,
            )
            DetailActionRow(
                title = stringResource(R.string.md_retry),
                icon = R.drawable.ic_line_refresh,
                onClick = { attempt++ },
            )
        }

        val folders = entries.filter { it.isDirectory || it.isShare }
        val videos = entries.filterNot { it.isDirectory || it.isShare }

        if (folders.isNotEmpty()) {
            GroupLabel(stringResource(if (atRoot) R.string.smb_shares else R.string.smb_folders))
            folders.forEach { entry ->
                val entryUri = "$currentUri/${entry.name}"
                val added = sources.any { it.uri.trimEnd('/').equals(entryUri, ignoreCase = true) }
                DetailActionRow(
                    title = entry.name,
                    description = if (added) stringResource(R.string.smb_added_as_source) else null,
                    icon = if (entry.isShare) R.drawable.ic_line_nas else R.drawable.ic_line_folder,
                    trailingIcon = if (added) R.drawable.ic_line_check else DesignR.drawable.ic_sui_settinglistitem_trail_arrow,
                    onClick = {
                        if (entry.isShare) {
                            share = entry.name
                            path = ""
                        } else {
                            path = if (path.isBlank()) entry.name else "$path/${entry.name}"
                        }
                    },
                )
            }
        }

        if (videos.isNotEmpty()) {
            // A handful of names confirms "this is the right folder"; the scan reads all of them.
            GroupLabel(stringResource(R.string.smb_videos_here, videos.size))
            videos.take(MAX_LISTED_VIDEOS).forEach { entry ->
                DetailRow(title = entry.name, icon = R.drawable.ic_line_video)
            }
            if (videos.size > MAX_LISTED_VIDEOS) {
                DetailNote(androidx.compose.ui.res.pluralStringResource(R.plurals.smb_more_videos, videos.size - MAX_LISTED_VIDEOS, videos.size - MAX_LISTED_VIDEOS))
            }
        }

        if (!loading && error == null && entries.isEmpty()) {
            EmptyState(
                title = stringResource(if (atRoot) R.string.smb_no_shares else R.string.smb_folder_empty),
                message = stringResource(if (atRoot) R.string.smb_try_account else R.string.smb_nothing_inside),
                iconRes = R.drawable.ic_line_folder,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        if (!atRoot) {
            DetailNote(stringResource(R.string.smb_add_hint))
        }
    }
}

/**
 * The account form the pane turns into when the server refuses a guest. It says so while it signs
 * in, and says why when it could not: a refused account under the password, a network failure
 * above the fields — the two need different fixes.
 */
@Composable
private fun ColumnScope.SignInForm(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    hasSavedPassword: Boolean,
    signingIn: Boolean,
    refused: Boolean,
    networkError: Pair<SourceFault, String?>?,
    onSignIn: () -> Unit,
) {
    val colors = PicoTheme.colorScheme
    GroupLabel(stringResource(R.string.smb_need_login))
    DetailRow(
        title = stringResource(R.string.smb_no_anon),
        description = stringResource(R.string.smb_fill_creds),
        icon = R.drawable.ic_line_warning,
        iconTint = colors.alert,
    )
    networkError?.let { (fault, detail) ->
        DetailRow(
            title = stringResource(R.string.smb_cannot_open),
            description = sourceFaultText(fault, detail),
            icon = R.drawable.ic_line_warning,
            iconTint = colors.error,
        )
    }
    LabelledField(
        label = stringResource(R.string.smb_username),
        value = username,
        onValueChange = onUsername,
        autoCorrect = false,
        enabled = !signingIn,
    )
    LabelledField(
        label = stringResource(R.string.smb_password),
        value = password,
        onValueChange = onPassword,
        placeholder = if (hasSavedPassword) stringResource(R.string.smb_saved_password) else null,
        secret = true,
        enabled = !signingIn,
        error = if (refused) stringResource(R.string.fault_auth) else null,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onSignIn,
            enabled = username.isNotBlank() && !signingIn,
        ) { Text(stringResource(R.string.smb_sign_in)) }
        if (signingIn) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.smb_signing_in),
                style = PicoTheme.typography.bodyMedium,
                color = colors.labelSecondary,
            )
        }
    }
}

/** Ten filenames are plenty to confirm "this is the right folder"; the scan reads all of them. */
private const val MAX_LISTED_VIDEOS = 10
