package com.therealityteam.videoplayer.desktop.ui.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aura's look on a desktop: a cinema-dark room by default (the posters are the colour), a light
 * one for those whose system is light, and one accent — a violet glow, the "aura" — for what is
 * selected, playing or asked of the reader. Nothing here is Material: desktop density, hover and
 * focus instead of ripples, the system's own font.
 */
@Immutable
data class AuraColors(
    val isDark: Boolean,
    /** The window itself. */
    val background: Color,
    /** The sidebar, a step off the background. */
    val sidebar: Color,
    /** Cards, panels, fields. */
    val surface: Color,
    /** Menus, popovers, dialogs — above everything else. */
    val raised: Color,
    /** A row or button under the pointer. */
    val hover: Color,
    /** …and while pressed. */
    val pressed: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    /** The accent as a wash behind a selected row. */
    val accentSoft: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    /** Behind a dialog. */
    val scrim: Color,
    /** Text drawn over pictures (posters, video). */
    val onPicture: Color,
)

val DarkColors = AuraColors(
    isDark = true,
    background = Color(0xFF0F1014),
    sidebar = Color(0xFF15171C),
    surface = Color(0xFF1B1E24),
    raised = Color(0xFF24272F),
    hover = Color(0x14FFFFFF),
    pressed = Color(0x22FFFFFF),
    stroke = Color(0x17FFFFFF),
    strokeStrong = Color(0x2EFFFFFF),
    text = Color(0xFFF2F3F5),
    textSecondary = Color(0xFFA7ACB6),
    textTertiary = Color(0xFF6C717D),
    accent = Color(0xFF8F7DFF),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x2E8F7DFF),
    danger = Color(0xFFFF6166),
    warning = Color(0xFFF2B84B),
    success = Color(0xFF3DD68C),
    scrim = Color(0x99000000),
    onPicture = Color(0xFFFFFFFF),
)

val LightColors = AuraColors(
    isDark = false,
    background = Color(0xFFF5F6F8),
    sidebar = Color(0xFFECEEF2),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFFFFFFF),
    hover = Color(0x0D000000),
    pressed = Color(0x18000000),
    stroke = Color(0x14000000),
    strokeStrong = Color(0x29000000),
    text = Color(0xFF15171C),
    textSecondary = Color(0xFF5A606C),
    textTertiary = Color(0xFF8C919B),
    accent = Color(0xFF6B55F5),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x226B55F5),
    danger = Color(0xFFE5484D),
    warning = Color(0xFFC98A0B),
    success = Color(0xFF1FA46A),
    scrim = Color(0x66000000),
    onPicture = Color(0xFFFFFFFF),
)

/** The type ramp, sized for a desk: reading distance, not arm's length. */
@Immutable
data class AuraType(
    val display: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val callout: TextStyle,
    val caption: TextStyle,
    val micro: TextStyle,
)

private val base = TextStyle(fontFamily = FontFamily.Default)

val AuraTypography = AuraType(
    display = base.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    title1 = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    title2 = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    headline = base.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    body = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodyStrong = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    callout = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
    caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
    micro = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

object AuraShapes {
    val small = RoundedCornerShape(6.dp)
    val control = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(10.dp)
    val panel = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(percent = 50)
}

val LocalAuraColors = staticCompositionLocalOf { DarkColors }
val LocalAuraType = staticCompositionLocalOf { AuraTypography }

object Aura {
    val colors: AuraColors @Composable get() = LocalAuraColors.current
    val type: AuraType @Composable get() = LocalAuraType.current
}

@Composable
fun AuraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalAuraColors provides colors,
        LocalAuraType provides AuraTypography,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accentSoft),
        LocalScrollbarStyle provides ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 7.dp,
            shape = AuraShapes.pill,
            hoverDurationMillis = 250,
            unhoverColor = colors.text.copy(alpha = 0.16f),
            hoverColor = colors.text.copy(alpha = 0.34f),
        ),
        content = content,
    )
}
