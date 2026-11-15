package com.therealityteam.videoplayer.ui.theme

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.ButtonColors
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme

/**
 * The button roles every page shares. A view has at most one primary — the default [ButtonDefaults]
 * colours, PICO's dark pill — and everything beside it is secondary.
 *
 * Secondary is a light fill under dark text. Both colours have to be given: `buttonColors(containerColor
 * = …)` alone keeps the primary's white label, which disappears on the light fill.
 */
@Composable
fun secondaryButtonColors(): ButtonColors = PicoTheme.colorScheme.let {
    ButtonDefaults.buttonColors(containerColor = it.fillSecondary, contentColor = it.labelPrimary)
}

/** [secondaryButtonColors] for a round icon button. */
@Composable
fun secondaryIconButtonColors(): ButtonColors = PicoTheme.colorScheme.let {
    IconButtonDefaults.iconButtonColors(containerColor = it.fillSecondary, contentColor = it.labelPrimary)
}

/** An action that deletes or clears something: the scheme's error colour under its light label. */
@Composable
fun dangerButtonColors(): ButtonColors = PicoTheme.colorScheme.let {
    ButtonDefaults.buttonColors(containerColor = it.error, contentColor = it.labelPrimaryLight)
}
