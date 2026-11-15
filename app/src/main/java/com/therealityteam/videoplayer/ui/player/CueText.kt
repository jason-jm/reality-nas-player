package com.therealityteam.videoplayer.ui.player

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType

/**
 * A cue's text as the engine styled it: ASS style colours, bold / italic / underline /
 * strike-through and relative sizes arrive as Android spans; this carries them into Compose.
 * With [keepColours] off the file's colours are dropped (the user's colour applies) while the
 * weights and slants stay.
 */
internal fun CharSequence.toAnnotated(keepColours: Boolean): AnnotatedString {
    val spanned = this as? Spanned ?: return AnnotatedString(toString())
    return buildAnnotatedString {
        append(spanned.toString())
        for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val style = when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                }
                is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                is ForegroundColorSpan -> if (keepColours) SpanStyle(color = Color(span.foregroundColor)) else null
                is RelativeSizeSpan -> SpanStyle(fontSize = TextUnit(span.sizeChange, TextUnitType.Em))
                else -> null
            }
            if (style != null) addStyle(style, start, end)
        }
    }
}
