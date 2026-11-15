package com.therealityteam.videoplayer.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

/**
 * What a page shows when it has nothing to list: a glyph, what is going on, and — where there is
 * one — the way out, as a button. Centre it in the space the list would have taken.
 *
 * [loading] puts the system spinner in the glyph's place, for "nothing yet" rather than "nothing".
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    message: String? = null,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = PicoTheme.colorScheme
    Column(
        modifier = modifier.widthIn(max = 480.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            loading -> CircularProgressIndicator()
            iconRes != null -> Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(48.dp),
            )
        }
        Text(
            text = title,
            style = PicoTheme.typography.headlineMedium,
            color = colors.labelPrimary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                style = PicoTheme.typography.bodyMediumMultiline,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Column(modifier = Modifier.padding(top = 12.dp)) { action() }
        }
    }
}
