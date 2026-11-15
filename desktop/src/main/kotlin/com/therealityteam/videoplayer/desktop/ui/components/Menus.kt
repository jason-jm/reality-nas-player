package com.therealityteam.videoplayer.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import kotlinx.coroutines.launch

/** One line of a menu. */
sealed interface MenuEntry {
    data class Item(
        val label: String,
        val icon: ImageVector? = null,
        val checked: Boolean? = null,
        val shortcut: String? = null,
        val enabled: Boolean = true,
        val danger: Boolean = false,
        val detail: String? = null,
        val onClick: () -> Unit,
    ) : MenuEntry

    data class Submenu(val label: String, val icon: ImageVector? = null, val entries: List<MenuEntry>) : MenuEntry
    data class Header(val label: String) : MenuEntry
    data object Separator : MenuEntry
}

class MenuBuilder {
    val entries = mutableListOf<MenuEntry>()

    fun item(
        label: String, icon: ImageVector? = null, shortcut: String? = null, enabled: Boolean = true,
        danger: Boolean = false, detail: String? = null, onClick: () -> Unit,
    ) { entries += MenuEntry.Item(label, icon, null, shortcut, enabled, danger, detail, onClick) }

    fun check(label: String, checked: Boolean, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
        entries += MenuEntry.Item(label, null, checked, null, enabled, false, detail, onClick)
    }

    fun submenu(label: String, icon: ImageVector? = null, build: MenuBuilder.() -> Unit) {
        entries += MenuEntry.Submenu(label, icon, MenuBuilder().apply(build).entries)
    }

    fun header(label: String) { entries += MenuEntry.Header(label) }
    fun separator() { if (entries.isNotEmpty() && entries.last() != MenuEntry.Separator) entries += MenuEntry.Separator }
}

fun menu(build: MenuBuilder.() -> Unit): List<MenuEntry> = MenuBuilder().apply(build).entries

/**
 * A dropdown under the composable it is declared in (its parent's bounds are the anchor), flipped
 * above when there is no room below. Arrow keys move, Return picks, Escape closes.
 */
@Composable
fun DropdownMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>, alignEnd: Boolean = false, above: Boolean = false) {
    if (!expanded) return
    Popup(
        popupPositionProvider = AnchorBelow(alignEnd, above),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) { MenuPanel(entries, onDismiss) }
}

/** A context menu at [at] (relative to the composable it is declared in). */
@Composable
fun ContextMenuPopup(at: Offset?, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    if (at == null) return
    Popup(
        popupPositionProvider = AtPoint(IntOffset(at.x.toInt(), at.y.toInt())),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) { MenuPanel(entries, onDismiss) }
}

/** Right-click (or ctrl-click on a Mac) anywhere in [content] opens [entries]. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun WithContextMenu(entries: () -> List<MenuEntry>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var at by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier.onPointerEvent(PointerEventType.Press) { e ->
            val ctrlClick = e.keyboardModifiers.isCtrlPressed && com.therealityteam.videoplayer.desktop.platform.Host.isMac
            if (e.buttons.isSecondaryPressed || ctrlClick) {
                at = e.changes.first().position
                e.changes.forEach { it.consume() }
            }
        },
    ) {
        content()
        if (at != null) ContextMenuPopup(at, { at = null }, remember(at) { entries() })
    }
}

@Composable
private fun MenuPanel(entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val c = Aura.colors
    val focus = remember { FocusRequester() }
    val actionable = entries.withIndex().filter { (_, e) -> e is MenuEntry.Item && e.enabled || e is MenuEntry.Submenu }.map { it.index }
    var highlighted by remember { mutableIntStateOf(-1) }
    var openSub by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .padding(6.dp)
            .shadow(18.dp, AuraShapes.card)
            .clip(AuraShapes.card)
            .background(c.raised)
            .border(1.dp, c.stroke, AuraShapes.card)
            .widthIn(min = 200.dp, max = 360.dp)
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(5.dp)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.Escape -> { onDismiss(); true }
                    Key.DirectionDown -> {
                        highlighted = actionable.firstOrNull { it > highlighted } ?: actionable.firstOrNull() ?: -1; true
                    }
                    Key.DirectionUp -> {
                        highlighted = actionable.lastOrNull { it < highlighted } ?: actionable.lastOrNull() ?: -1; true
                    }
                    Key.DirectionRight -> { if (entries.getOrNull(highlighted) is MenuEntry.Submenu) openSub = highlighted; true }
                    Key.Enter, Key.Spacebar -> {
                        when (val e = entries.getOrNull(highlighted)) {
                            is MenuEntry.Item -> { onDismiss(); e.onClick() }
                            is MenuEntry.Submenu -> openSub = highlighted
                            else -> {}
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        entries.forEachIndexed { i, e ->
            when (e) {
                is MenuEntry.Separator -> Divider(Modifier.padding(vertical = 5.dp, horizontal = 4.dp))
                is MenuEntry.Header -> Txt(e.label, Aura.type.micro, c.textTertiary, Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp))
                is MenuEntry.Item -> MenuRow(
                    label = e.label, icon = e.icon, checked = e.checked, trailing = e.shortcut, detail = e.detail,
                    enabled = e.enabled, danger = e.danger, highlighted = highlighted == i,
                    onHover = { highlighted = i; openSub = -1 },
                ) { onDismiss(); e.onClick() }
                is MenuEntry.Submenu -> Box {
                    MenuRow(
                        label = e.label, icon = e.icon, checked = null, trailing = null, detail = null,
                        enabled = true, danger = false, highlighted = highlighted == i || openSub == i, submenu = true,
                        onHover = { highlighted = i; openSub = i },
                    ) { openSub = i }
                    if (openSub == i) {
                        Popup(
                            popupPositionProvider = BesideParent,
                            onDismissRequest = { openSub = -1 },
                            properties = PopupProperties(focusable = false),
                        ) { MenuPanel(e.entries, onDismiss) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String, icon: ImageVector?, checked: Boolean?, trailing: String?, detail: String?,
    enabled: Boolean, danger: Boolean, highlighted: Boolean, submenu: Boolean = false,
    onHover: () -> Unit, onClick: () -> Unit,
) {
    val c = Aura.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    val textColor = when {
        !enabled -> c.textTertiary
        danger -> c.danger
        else -> c.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clip(AuraShapes.small)
            .background(if (highlighted && enabled) c.accentSoft else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            checked != null -> Box(Modifier.width(16.dp)) { if (checked) Icon(Lucide.Check, c.accent, 15.dp) }
            icon != null -> Icon(icon, if (danger) c.danger else c.textSecondary, 16.dp)
            else -> Spacer(Modifier.width(0.dp))
        }
        if (checked != null || icon != null) Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, Aura.type.callout, textColor, maxLines = 2)
            if (detail != null) Txt(detail, Aura.type.caption, c.textTertiary, maxLines = 2)
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            Txt(trailing, Aura.type.caption, c.textTertiary)
        }
        if (submenu) {
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.ChevronRight, c.textTertiary, 14.dp)
        }
    }
}

private class AnchorBelow(val alignEnd: Boolean, val preferAbove: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var x = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val y = when {
            preferAbove && above >= 0 -> above
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

private class AtPoint(val point: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var x = anchorBounds.left + point.x
        var y = anchorBounds.top + point.y
        if (x + popupContentSize.width > windowSize.width) x = (x - popupContentSize.width).coerceAtLeast(0)
        if (y + popupContentSize.height > windowSize.height) y = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

private object BesideParent : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        var x = anchorBounds.right
        if (x + popupContentSize.width > windowSize.width) x = (anchorBounds.left - popupContentSize.width).coerceAtLeast(0)
        val y = anchorBounds.top.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)) - 11
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

/**
 * A panel of controls (sliders, lists, segmented choices) anchored to the composable it is
 * declared in — the player's menus. Opens above the anchor when [above], below otherwise.
 */
@Composable
fun Popover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    above: Boolean = true,
    alignEnd: Boolean = false,
    width: androidx.compose.ui.unit.Dp = 340.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val c = Aura.colors
    Popup(
        popupPositionProvider = AnchorBelow(alignEnd, preferAbove = above),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(8.dp)
                .shadow(22.dp, AuraShapes.panel)
                .clip(AuraShapes.panel)
                .background(c.raised)
                .border(1.dp, c.stroke, AuraShapes.panel)
                .width(width)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { k ->
                    if (k.type == KeyEventType.KeyDown && k.key == Key.Escape) { onDismiss(); true } else false
                }
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}

/** A heading inside a popover. */
@Composable
fun PopoverHeader(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, Aura.type.micro, Aura.colors.textTertiary, Modifier.weight(1f))
        if (trailing != null) Txt(trailing, Aura.type.micro, Aura.colors.textTertiary)
    }
}

/** A choosable row inside a popover: a check when chosen, a title, an optional detail line and badge. */
@Composable
fun PopoverRow(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
    badge: String? = null,
    maxTitleLines: Int = 2,
    /** How much of [title] is shared with its neighbours, drawn dim; the rest is drawn strong. */
    sharedPrefix: Int = 0,
) {
    val c = Aura.colors
    val source = remember { MutableInteractionSource() }
    val wash = interactionWash(source, false)
    // The chosen row is in sight when the list opens — episode 80 of a long queue, the subtitle
    // track far down a list — with a row or two of its neighbours around it: asked for once the
    // row has been laid out, as a popup's rows are only after its first frame.
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var brought by remember { mutableStateOf(false) }
    val air = 90f * LocalDensity.current.density
    Row(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .onSizeChanged { size ->
                if (checked && !brought && size.height > 0) {
                    brought = true
                    scope.launch {
                        withFrameNanos { }
                        runCatching { bring.bringIntoView(Rect(0f, -air, size.width.toFloat(), size.height + air)) }
                    }
                }
            }
            .padding(horizontal = 6.dp)
            .clip(AuraShapes.control)
            .background(wash)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp)) { if (checked) Icon(Lucide.Check, c.accent, 15.dp) }
        Spacer(Modifier.width(8.dp))
        if (badge != null) {
            Box(Modifier.clip(AuraShapes.small).background(c.hover).padding(horizontal = 5.dp, vertical = 1.dp)) {
                Txt(badge, Aura.type.micro, c.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            if (sharedPrefix in 1 until title.length) {
                // What tells it apart from the rows around it, read at a glance.
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(color = c.textTertiary)) { append(title.substring(0, sharedPrefix)) }
                    withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.SemiBold)) { append(title.substring(sharedPrefix)) }
                }
                BasicText(text, style = Aura.type.callout, maxLines = maxTitleLines, overflow = TextOverflow.Ellipsis)
            } else {
                Txt(title, Aura.type.callout, c.text, maxLines = maxTitleLines)
            }
            if (!detail.isNullOrBlank()) Txt(detail, Aura.type.caption, c.textTertiary, maxLines = 2)
        }
    }
}
