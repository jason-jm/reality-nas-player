package com.therealityteam.videoplayer.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes

enum class ButtonKind { Primary, Secondary, Ghost, Danger }

/** A push button: 32 dp tall, text with an optional leading icon. */
@Composable
fun AuraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 32.dp,
) {
    val source = remember { MutableInteractionSource() }
    val c = Aura.colors
    val (fill, content, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.accent, c.onAccent, Color.Transparent)
        ButtonKind.Secondary -> Triple(c.surface, c.text, c.strokeStrong)
        ButtonKind.Ghost -> Triple(Color.Transparent, c.text, Color.Transparent)
        ButtonKind.Danger -> Triple(c.danger.copy(alpha = 0.14f), c.danger, c.danger.copy(alpha = 0.35f))
    }
    val wash = interactionWash(source)
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .height(height)
            .clip(AuraShapes.control)
            .background(fill)
            .background(if (enabled) wash else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else border, AuraShapes.control)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.clickCursor() else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) content else content.copy(alpha = 0.4f)
        if (icon != null) {
            Icon(icon, tint, 16.dp)
            Spacer(Modifier.width(7.dp))
        }
        Txt(text, Aura.type.bodyStrong, tint)
    }
}

/**
 * A square icon-only button with a tooltip. [description] is what a screen reader says for it —
 * the tooltip's words unless given (a button whose tooltip is hidden at times still has a name).
 */
@Composable
fun IconButton(
    icon: ImageVector,
    tooltip: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    tint: Color = Aura.colors.text,
    selected: Boolean = false,
    enabled: Boolean = true,
    onPicture: Boolean = false,
    description: String? = tooltip,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val wash = if (onPicture) {
        val a by animateColorAsState(if (hovered) Color(0x33FFFFFF) else Color.Transparent, tween(120))
        a
    } else interactionWash(source, selected)
    val button = @Composable {
        Box(
            modifier
                .size(size)
                .clip(AuraShapes.control)
                .background(wash)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .then(if (enabled) Modifier.clickCursor() else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, if (!enabled) tint.copy(alpha = 0.35f) else if (selected) Aura.colors.accent else tint, iconSize)
        }
    }
    if (tooltip != null) Tooltip(tooltip) { button() } else button()
}

/** A tooltip after half a second of hovering. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(
                Modifier
                    .shadow(8.dp, AuraShapes.small)
                    .clip(AuraShapes.small)
                    .background(Aura.colors.raised)
                    .border(1.dp, Aura.colors.stroke, AuraShapes.small)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) { Txt(text, Aura.type.caption, Aura.colors.text) }
        },
        delayMillis = 550,
        tooltipPlacement = TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.BottomCenter,
            offset = DpOffset(0.dp, 6.dp),
        ),
    ) { content() }
}

/** A single-line text field in the theme: 32 dp, an optional leading icon, a focus ring. */
@Composable
fun AuraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    password: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    height: androidx.compose.ui.unit.Dp = 32.dp,
    fill: Color = Aura.colors.surface,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val c = Aura.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = source,
        textStyle = Aura.type.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier.height(height),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxHeight()
                    .clip(AuraShapes.control)
                    .background(fill)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.stroke, AuraShapes.control)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, c.textTertiary, 15.dp)
                    Spacer(Modifier.width(7.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, Aura.type.body, c.textTertiary)
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

/** An on/off switch, named for screen readers by [label] — by default the [SettingRow] it sits in. */
@Composable
fun AuraSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, label: String? = LocalControlLabel.current) {
    val c = Aura.colors
    val track by animateColorAsState(if (checked) c.accent else c.strokeStrong, tween(150))
    val knob by animateDpAsState(if (checked) 18.dp else 2.dp, tween(150))
    Box(
        Modifier
            .size(38.dp, 22.dp)
            .clip(AuraShapes.pill)
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .clickCursor(),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(18.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * A slider over [range]; [onChange] while dragging, [onDone] on release. [steps] > 0 snaps.
 * Reports a fraction under the pointer to [onHover] for previews (the seek bar's frame preview).
 */
@Composable
fun AuraSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onDone: (() -> Unit)? = null,
    trackHeight: androidx.compose.ui.unit.Dp = 4.dp,
    activeColor: Color = Aura.colors.accent,
    trackColor: Color = Aura.colors.strokeStrong,
    knobSize: androidx.compose.ui.unit.Dp = 14.dp,
    showKnob: Boolean = true,
    label: String? = LocalControlLabel.current,
) {
    var width by remember { mutableStateOf(1) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    fun toValue(x: Float): Float {
        val f = (x / width).coerceIn(0f, 1f)
        var v = range.start + f * (range.endInclusive - range.start)
        if (steps > 0) {
            val step = (range.endInclusive - range.start) / steps
            v = range.start + Math.round((v - range.start) / step) * step
        }
        return v.coerceIn(range.start, range.endInclusive)
    }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier
            .heightIn(min = 20.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .hoverable(source)
            .clickCursor()
            // For screen readers: its name, where it stands, and a way to move it.
            .semantics {
                if (label != null) contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range, if (steps > 0) steps - 1 else 0)
                setProgress { target -> onChange(target.coerceIn(range)); onDone?.invoke(); true }
            }
            .pointerInput(range, steps) {
                detectTapGestures { onChange(toValue(it.x)); onDone?.invoke() }
            }
            .pointerInput(range, steps) {
                detectDragGestures(
                    onDragStart = { onChange(toValue(it.x)) },
                    onDragEnd = { onDone?.invoke() },
                    onDragCancel = { onDone?.invoke() },
                ) { change, _ -> onChange(toValue(change.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (hovered) trackHeight + 2.dp else trackHeight
        Box(Modifier.fillMaxWidth().height(h).clip(AuraShapes.pill).background(trackColor))
        Box(Modifier.fillMaxWidth(fraction).height(h).clip(AuraShapes.pill).background(activeColor))
        if (showKnob || hovered) {
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - knobSize / 2)
                    .size(knobSize)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/** Mutually exclusive choices side by side, like a macOS segmented control. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Share the row's whole width equally — for a narrow panel, where natural widths overflow. */
    fill: Boolean = false,
) {
    val c = Aura.colors
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(30.dp)
            .clip(AuraShapes.control)
            .background(c.hover)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val source = remember { MutableInteractionSource() }
            val wash = interactionWash(source)
            Box(
                Modifier
                    .fillMaxHeight()
                    .then(if (fill) Modifier.weight(1f) else Modifier.widthIn(min = 56.dp))
                    .clip(AuraShapes.small)
                    .background(if (on) c.raised else wash)
                    .clickable(interactionSource = source, indication = null) { onSelect(value) }
                    .clickCursor()
                    .padding(horizontal = if (fill) 4.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) { Txt(label, Aura.type.callout, if (on) c.text else c.textSecondary, maxLines = 1) }
        }
    }
}

/**
 * A labelled row in a settings list: title and description on the left, a control on the right.
 * The control is named by the title for screen readers ([LocalControlLabel]).
 */
@Composable
fun SettingRow(title: String, description: String? = null, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 24.dp)) {
            Txt(title, Aura.type.bodyStrong)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Txt(description, Aura.type.callout, Aura.colors.textSecondary, maxLines = 4)
            }
        }
        CompositionLocalProvider(LocalControlLabel provides title) { control() }
    }
}

/** The words a control without its own is known by to screen readers: the row it sits in. */
val LocalControlLabel = compositionLocalOf<String?> { null }

/** Keyboard focus that shows: a focusable box whose ring appears only when focused. */
fun Modifier.focusRing(source: MutableInteractionSource): Modifier = focusable(interactionSource = source)
