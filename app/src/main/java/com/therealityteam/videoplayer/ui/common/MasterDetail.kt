package com.therealityteam.videoplayer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.LocalDisableAlpha
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SideNavigation
import com.pico.spatial.ui.design.SideNavigationItem
import com.pico.spatial.ui.design.SideNavigationSection
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextField
import com.pico.spatial.ui.design.TitleAlignment
import com.pico.spatial.ui.design.TitleBar
import com.pico.spatial.ui.design.windows.Sheet
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.disableSpatialHoverEffect
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.tooltip
import com.therealityteam.videoplayer.R
import com.therealityteam.videoplayer.platform.DeepLink
import com.therealityteam.videoplayer.ui.theme.dangerButtonColors
import com.therealityteam.videoplayer.ui.theme.secondaryIconButtonColors
import com.pico.spatial.ui.design.R as DesignR

/**
 * Two-pane (master–detail) chrome shared by 来源 and 设置.
 *
 * Both pages used to be one long scrolling column of controls, which made every option look
 * equally important and pushed the primary action off the bottom of the window. The shape that
 * fits them — a set of named things on the left, the selected thing's own controls on the right —
 * is the same shape the home screen already uses (sidebar + poster wall), so these pages now read
 * as part of the same app rather than as forms bolted onto it.
 *
 * The master column is PICO's SideNavigation at the width its guidelines give a 1280dp window —
 * the same 316dp as the home screen's — so the detail pane never reflows while the user moves the
 * selection, and a source row has room for a name over a URI.
 */
private val MasterWidth = 316.dp

/** Detail-pane content is capped so lines of prose stay readable on a 1280dp-wide window. */
private val DetailMaxWidth = 720.dp

/**
 * [SideNavigationItem]'s own corner (the design system's RadiusHuge). The row clips to it before
 * the hover effect and the click, so the highlight and the press tint follow the pill instead of
 * filling the row's square bounds.
 */
private val MasterEntryShape = RoundedCornerShape(24.dp)

@Composable
fun MasterDetailScaffold(
    title: String,
    onBack: () -> Unit,
    master: @Composable ColumnScope.() -> Unit,
    detail: @Composable () -> Unit,
    masterFooter: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.width(MasterWidth).fillMaxHeight()) {
            // 返回 and the page's name head the navigation, as the search field heads the home one.
            SideNavigation(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                header = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val back = stringResource(R.string.det_back)
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.tooltip(text = back),
                            colors = secondaryIconButtonColors(),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pc_back),
                                contentDescription = back,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        // No style of its own: SideNavigation sets its header in the template's page
                        // title style. It used to be forced down to 16sp — smaller than the heading of
                        // whatever the detail pane showed beside it.
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                },
                content = master,
            )
            // The footer action stays put under the scrolling list, inset like the list's rows.
            masterFooter?.let {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), content = it)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}

/** A named group of [MasterEntry] rows in the master column. */
@Composable
fun MasterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SideNavigationSection(
        modifier = Modifier.fillMaxWidth(),
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        content = content,
    )
}

/**
 * One selectable row in the master column. [SideNavigationItem] carries the selected/unselected
 * container colors, its width and padding, and its own controller haptics; the clip, hover and the
 * press indication are added here because it takes its click from the caller's modifier.
 */
@Composable
fun MasterEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    supporting: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    SideNavigationItem(
        selected = selected,
        modifier = Modifier
            // Air round the pill, as on the home screen's shelves ([SideRowGap]).
            .padding(vertical = SideRowGap / 2)
            .clip(MasterEntryShape)
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .spatialHoverEffect()
            .hoverDebug()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ),
        leading = icon?.let {
            {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailing = trailing?.let { { it() } },
        content = {
            Column {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = PicoTheme.typography.bodySmall,
                        color = PicoTheme.colorScheme.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

/**
 * The right-hand pane, as PICO's template builds a navigation page's content: a TitleBar naming
 * whatever the master column has selected — its actions at the bar's end, and a way back at its
 * start when the pane is a step inside a flow — over that thing's own controls. The controls
 * scroll independently of the master column, in a centred column.
 */
@Composable
fun DetailPane(
    title: String,
    subtitle: String? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    leadingActions: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TitleBar(
            title = {
                // The whole room between the actions, so the title is centred in it (Center alone set it at its start).
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = PicoTheme.typography.bodyMedium,
                            color = PicoTheme.colorScheme.labelTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            titleAlignment = if (actions != null) TitleAlignment.Center else TitleAlignment.CenterInBar, // never under its actions
            leadingActions = leadingActions ?: {},
            trailingActions = actions ?: {},
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 32.dp, end = 32.dp, bottom = 32.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = DetailMaxWidth),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * The round "go back a step" button for a [DetailPane]'s leading actions — the flows inside a pane
 * (finding a server, walking its folders, editing a source) step back here rather than through a
 * list row, and the window's own back button still leaves the page.
 */
@Composable
fun PaneBackButton(label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.tooltip(text = label),
        colors = secondaryIconButtonColors(),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_pc_back),
            contentDescription = label,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A caption above a run of rows, e.g. 「网络共享」, aligned with the rows' own content — in the same
 * title role as the master column's section names, so a group reads as a group and not a footnote.
 */
@Composable
fun GroupLabel(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.titleSmall,
        color = PicoTheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp).semantics { heading() },
    )
}

/** A row's leading glyph, at the size every row uses; [tint] marks a state (passed, warning, error). */
@Composable
private fun RowIcon(@DrawableRes icon: Int, tint: Color? = null) {
    if (tint != null) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    } else {
        Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

/** The system spinner in a row icon's place, for a row whose state is "working on it". */
@Composable
private fun RowSpinner() {
    Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progressSize = CircularProgressDefaults.Small)
    }
}

/**
 * A settings/detail row card: title, explanation, and the control that changes it on the right.
 * [ListItem] supplies the card surface, the 60dp minimum height, its own type roles (the title in
 * body-large, the explanation in body-medium) and the spatial hover effect.
 *
 * A row with nothing to press opts out of the hover: ListItem always carries it, and an inert row
 * that lights up under the user's gaze reads as a button that does nothing. A row with a trailing
 * control keeps it, because switching the effect off covers the whole subtree, the control included.
 */
@Composable
fun DetailRow(
    title: String,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    trailing: @Composable (() -> Unit)? = null,
    iconTint: Color? = null,
    busy: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier
            .fillMaxWidth()
            .then(if (trailing == null) Modifier.disableSpatialHoverEffect(true) else Modifier),
        supportingContent = description?.let { { Text(it) } },
        leadingContent = when {
            busy -> { { RowSpinner() } }
            icon != null -> { { RowIcon(icon, iconTint) } }
            else -> null
        },
        trailingContent = trailing?.let { { it() } },
    )
}

/**
 * The same card, but the whole row is the control — a step into something ("add one of these",
 * "language ›"), or one choice of several when [selected] is given, in which case the chosen row
 * carries the sheet's check and the others nothing. [value] shows the current setting beside the
 * arrow, the way a settings list shows what a row is set to before it is opened.
 */
@Composable
fun DetailActionRow(
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = DesignR.drawable.ic_sui_settinglistitem_trail_arrow,
    value: String? = null,
    selected: Boolean? = null,
    iconTint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val press = if (selected != null) {
        Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }
    val trailingGlyph = when (selected) {
        null -> trailingIcon
        true -> DesignR.drawable.ic_sui_sheet_selected
        false -> null
    }
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .then(press)
            .controllerHapticFeedback(interactionSource = interaction),
        supportingContent = description?.let { { Text(it) } },
        leadingContent = icon?.let { { RowIcon(it, iconTint) } },
        trailingContent = if (value == null && trailingGlyph == null) null else {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (value != null) {
                        Text(
                            text = value,
                            style = PicoTheme.typography.bodyMedium,
                            color = PicoTheme.colorScheme.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                    }
                    if (trailingGlyph != null) {
                        Icon(
                            painter = painterResource(trailingGlyph),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * An on/off row. The whole row is the switch — a look anywhere on it and a pinch flips it — because
 * the switch by itself is a small target at a metre; the [Switch] only shows the state.
 */
@Composable
fun DetailSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else LocalDisableAlpha.current)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .controllerHapticFeedback(interactionSource = interaction),
        supportingContent = description?.let { { Text(it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, interactionSource = interaction)
        },
    )
}

/**
 * A row that deletes or clears something: what it does, what it costs, and a small trigger in the
 * error colour whose label ends in "…" because it only opens the confirmation. Every page's
 * destructive action has this one shape, under the one 「危险操作」 heading.
 */
@Composable
fun DangerRow(
    title: String,
    action: String,
    onClick: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    @DrawableRes icon: Int? = R.drawable.ic_line_delete,
) {
    DetailRow(
        title = title,
        description = description,
        icon = icon,
        iconTint = PicoTheme.colorScheme.error,
        busy = busy,
        trailing = {
            Button(
                onClick = onClick,
                enabled = enabled && !busy,
                size = ButtonDefaults.Small,
                colors = dangerButtonColors(),
            ) { Text(action) }
        },
    )
}

/**
 * A labelled text input with its own explanation — the form primitive for both pages.
 *
 * [secret] masks what is typed (passwords, tokens, API keys) and adds a show/hide button; [error]
 * turns the field to its error state with the reason under it. Addresses and keys are typed with
 * autocorrect off: a keyboard "correcting" a host name or a key breaks it.
 */
@Composable
fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String? = null,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = true,
    error: String? = null,
    enabled: Boolean = true,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).alpha(if (enabled) 1f else LocalDisableAlpha.current),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = PicoTheme.typography.bodyLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = PicoTheme.typography.bodyMedium,
                    color = PicoTheme.colorScheme.labelTertiary,
                )
            }
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it) } },
            trailingContent = if (secret) {
                { RevealButton(revealed = revealed, field = label, onToggle = { revealed = !revealed }) }
            } else {
                null
            },
            supportingText = error?.let { { Text(it) } },
            enabled = enabled,
            isError = error != null,
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = autoCorrect && !secret,
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
            ),
        )
    }
}

/**
 * Show or hide a masked field's text; named on hover, since it is only an eye — and, to whatever reads the
 * page, by its [field]: two keys' eyes were both just 「显示」.
 */
@Composable
private fun RevealButton(revealed: Boolean, field: String, onToggle: () -> Unit) {
    val label = stringResource(if (revealed) R.string.md_secret_hide else R.string.md_secret_show)
    val named = stringResource(if (revealed) R.string.md_secret_hide_named else R.string.md_secret_show_named, field)
    IconButton(
        onClick = onToggle,
        modifier = Modifier.tooltip(text = label),
        colors = secondaryIconButtonColors(),
        size = IconButtonDefaults.Min,
    ) {
        Icon(
            painter = painterResource(if (revealed) R.drawable.ic_password_hide else R.drawable.ic_password_show),
            contentDescription = named,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Explanatory prose at the foot of a pane — quiet, but in the multi-line body role, so a paragraph
 * read at a metre has the line height it needs.
 */
@Composable
fun DetailNote(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.bodyMediumMultiline,
        color = PicoTheme.colorScheme.labelTertiary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
    )
}

/**
 * Pick one of several values in a [Sheet]: the rows scroll, the chosen one carries the sheet's
 * check, and choosing closes it. The sheet's own close button is the way out without choosing.
 */
@Composable
fun <T> ChoiceSheet(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(
        onDismissRequest = onDismiss,
        title = { Text(title) },
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 560.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (value, label) ->
                DetailActionRow(
                    title = label,
                    selected = value == selected,
                    onClick = { onChoose(value) },
                )
            }
        }
    }
}

/**
 * Automation hook (`-e aura.detail_sheet <name>`): opens the named sheet or confirmation once, as
 * its tap would — the pickers and dialogs behind a tap that no screenshot run can make.
 */
@Composable
fun SheetHook(name: String, open: () -> Unit) {
    val request by DeepLink.detailSheetRequest.collectAsState()
    LaunchedEffect(request) {
        if (request.equals(name, ignoreCase = true)) {
            open()
            DeepLink.detailSheetRequest.value = null
        }
    }
}

/** Empty state for the detail pane when the master column has nothing selected. */
@Composable
fun DetailPlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = PicoTheme.typography.bodyLarge,
            color = PicoTheme.colorScheme.labelTertiary,
        )
    }
}
