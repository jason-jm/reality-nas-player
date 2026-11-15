package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Chip
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.menu.Menu
import com.pico.spatial.ui.design.menu.MenuItem
import com.pico.spatial.ui.design.menu.rememberMenuPositionProvider
import com.pico.spatial.ui.design.windows.popup.HorizontalPlacement
import com.pico.spatial.ui.design.windows.popup.VerticalPlacement
import com.therealityteam.videoplayer.R
import kotlinx.coroutines.delay

/**
 * The wall's sort control: a chip that names the current order and opens a menu of the five.
 * One entry per order, each with the glyph that says what it is (SKYBOX's rule: an option
 * without an icon is a list, not a control), the current one marked.
 *
 * [sort] is the order the wall is actually in — 历史 reads 最近添加 as 最近观看, and the chip says so.
 * Regular (40dp) rather than the chip's Small default: it is the page's only control in its bar.
 */
@Composable
fun SortChip(
    sort: LibSort,
    onSelect: (LibSort) -> Unit,
    modifier: Modifier = Modifier,
    /** Automation: open the menu as if the chip were tapped (`-e aura.sort open`); [onOpened] consumes it. */
    openRequested: Boolean = false,
    onOpened: () -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openRequested) {
        if (!openRequested) return@LaunchedEffect
        delay(400)
        open = true
        android.util.Log.i("AuraSort", "sort menu opened by request")
        onOpened() // consumed only now: clearing first re-keys this effect and cancels the open
    }
    Box(modifier = modifier) {
        Chip(
            label = { Text(stringResource(sort.label)) },
            onClick = { open = true },
            chipSize = ChipsDefaults.Regular,
            leadingIcon = { Icon(painter = painterResource(sort.icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        if (open) {
            Menu(
                onDismissRequest = { open = false },
                positionProvider = rememberMenuPositionProvider(
                    horizontalPlacement = HorizontalPlacement.alignEnd(),
                    verticalPlacement = VerticalPlacement.below(offset = 8.dp),
                ),
                modifier = Modifier.width(300.dp),
            ) {
                LibSort.entries.forEach { s ->
                    MenuItem(
                        title = { Text(stringResource(s.label)) },
                        subtitle = { Text(stringResource(s.hint)) },
                        leadingIcon = { Icon(painter = painterResource(s.icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
                        trailingIcon = if (s == sort) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.sort_current),
                                    modifier = Modifier.size(20.dp),
                                    tint = PicoTheme.colorScheme.interaction,
                                )
                            }
                        } else null,
                        onClick = {
                            onSelect(s)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
