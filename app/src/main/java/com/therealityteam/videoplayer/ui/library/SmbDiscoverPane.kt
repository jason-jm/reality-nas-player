package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.data.source.DiscoveredHost
import com.therealityteam.videoplayer.data.source.SmbDiscovery
import com.therealityteam.videoplayer.ui.common.DetailActionRow
import com.therealityteam.videoplayer.ui.common.DetailNote
import com.therealityteam.videoplayer.ui.common.DetailPane
import com.therealityteam.videoplayer.ui.common.EmptyState
import com.therealityteam.videoplayer.ui.common.GroupLabel
import com.therealityteam.videoplayer.ui.common.LabelledField
import com.therealityteam.videoplayer.ui.common.PaneBackButton
import com.therealityteam.videoplayer.ui.theme.secondaryButtonColors
import kotlinx.coroutines.flow.collect
import org.koin.core.context.GlobalContext

/** A dotted IPv4 address — four numbers from 0 to 255 — the only thing a subnet sweep can start from. */
private fun isIpv4(text: String): Boolean {
    val parts = text.trim().split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255 }
}

/**
 * Finds the SMB servers on the network and lets the user pick one, so that adding a share
 * starts from "which of these NAS boxes" rather than from an empty address field.
 *
 * The scan covers the headset's own network plus the network of every SMB source already
 * configured — so the second share on a known NAS needs no typing at all. When the NAS is
 * somewhere neither of those reaches (a different VLAN, the far side of a tunnel), the user
 * can point the scan at another network by giving any address on it; that is one line instead
 * of a full `smb://host/share/path`. Typing the address in full stays available underneath, and
 * the back button at the bar's start ([onBack]) returns to the choice of source kind.
 */
@Composable
fun SmbDiscoverPane(
    knownHosts: List<String>,
    onPickHost: (String) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    val discovery = remember { GlobalContext.get().get<SmbDiscovery>() }
    val colors = PicoTheme.colorScheme

    val found: SnapshotStateList<DiscoveredHost> = remember { mutableListOf<DiscoveredHost>().toMutableStateList() }
    var scanning by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    var extraHint by rememberSaveable { mutableStateOf("") }
    var hints by remember { mutableStateOf(knownHosts) }
    var showHintField by rememberSaveable { mutableStateOf(false) }

    val networks = remember(hints, round) { runCatching { discovery.plannedNetworks(hints) }.getOrDefault(emptyList()) }

    LaunchedEffect(round, hints) {
        scanning = true
        found.clear()
        val started = android.os.SystemClock.elapsedRealtime()
        runCatching {
            discovery.discover(hints).collect { host ->
                if (found.none { it.address == host.address }) found.add(host)
            }
        }
        scanning = false
        android.util.Log.i(
            "AuraSmb",
            "discovery over ${networks.joinToString { it.removeSuffix(".*") + ".x" }}: ${found.joinToString { "${it.label} (${it.address})" }.ifEmpty { "nothing" }} " +
                "in ${android.os.SystemClock.elapsedRealtime() - started} ms",
        )
    }

    // Only a dotted address can be swept; anything else is said to be wrong where it was typed.
    val hintProblem = extraHint.isNotBlank() && !isIpv4(extraHint)

    DetailPane(
        title = stringResource(R.string.smb_find_servers),
        subtitle = when {
            scanning && networks.isEmpty() -> stringResource(R.string.smb_preparing)
            // A planned network is its first three octets; written as "192.168.1.x" it reads as one.
            scanning -> stringResource(
                R.string.smb_scanning_nets,
                networks.joinToString(stringResource(R.string.det_name_sep)) { it.removeSuffix(".*") + ".x" },
            )
            found.isEmpty() -> stringResource(R.string.smb_none_found)
            else -> androidx.compose.ui.res.pluralStringResource(R.plurals.smb_found_n, found.size, found.size)
        },
        leadingActions = {
            PaneBackButton(label = stringResource(R.string.sources_back_kinds), onClick = onBack)
        },
        actions = {
            Button(
                onClick = { round++ },
                enabled = !scanning,
                colors = secondaryButtonColors(),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_line_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            ) { Text(stringResource(R.string.smb_search_again)) }
        },
    ) {
        if (scanning) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.smb_asking),
                    style = PicoTheme.typography.bodyMedium,
                    color = colors.labelSecondary,
                )
            }
        }

        if (found.isNotEmpty()) {
            GroupLabel(stringResource(R.string.smb_on_this_network))
            found.forEach { host ->
                DetailActionRow(
                    title = host.label,
                    description = host.name?.let { host.address } ?: stringResource(R.string.smb_a_server),
                    icon = R.drawable.ic_line_nas,
                    onClick = { onPickHost(host.address) },
                )
            }
        }

        if (!scanning && found.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.smb_none_here),
                message = stringResource(R.string.smb_none_here_hint),
                iconRes = R.drawable.ic_line_nas,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        GroupLabel(stringResource(R.string.smb_cant_find))
        if (showHintField) {
            LabelledField(
                label = stringResource(R.string.smb_other_subnet),
                description = stringResource(R.string.smb_other_subnet_hint),
                value = extraHint,
                onValueChange = { extraHint = it },
                placeholder = "192.168.1.100",
                keyboardType = KeyboardType.Uri,
                autoCorrect = false,
                error = if (hintProblem) stringResource(R.string.smb_subnet_invalid) else null,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        hints = (hints + extraHint.trim()).distinct()
                        showHintField = false
                    },
                    enabled = extraHint.isNotBlank() && !hintProblem && !scanning,
                ) { Text(stringResource(R.string.smb_search_subnet)) }
                Button(
                    onClick = { showHintField = false },
                    colors = secondaryButtonColors(),
                ) { Text(stringResource(R.string.smb_cancel)) }
            }
        } else {
            DetailActionRow(
                title = stringResource(R.string.smb_other_subnet_more),
                description = stringResource(R.string.smb_other_subnet_when),
                icon = R.drawable.ic_subnet_search,
                onClick = { showHintField = true },
            )
        }
        DetailActionRow(
            title = stringResource(R.string.smb_type_address),
            description = stringResource(R.string.smb_type_address_hint),
            icon = R.drawable.ic_line_edit,
            onClick = onManual,
        )
        DetailNote(stringResource(R.string.smb_how_it_works))
    }
}
