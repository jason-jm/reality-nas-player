package com.therealityteam.videoplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.videoplayer.R

/**
 * Compact scan / match progress: a label ("刮削 12/340") over a Spatial linear bar. Shared by the
 * library's title bar and the sources screen so progress is visible from both.
 */
@Composable
fun LibraryTaskProgress(progress: TaskProgress, modifier: Modifier = Modifier) {
    LibraryTaskProgress(
        label = stringResource(progress.phase.label),
        done = progress.done,
        total = progress.total,
        modifier = modifier,
    )
}

/**
 * The same for any library task that counts [done] of [total] — AI 识别 included, so every task the
 * library runs is reported in the one place.
 *
 * While the task cannot yet say how much there is ([total] <= 0, the discovery phase) the label
 * sits beside the system spinner. It used to sit over a bar held at 0, which looks exactly like a
 * task that has stalled; SpatialUI 6.1.9's LinearProgressIndicator has no indeterminate mode.
 */
@Composable
fun LibraryTaskProgress(label: String, done: Int, total: Int, modifier: Modifier = Modifier) {
    val text = taskProgressText(label, done, total)
    if (total <= 0) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(progressSize = CircularProgressDefaults.Small)
            TaskLabel(text)
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TaskLabel(text)
            LinearProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "刮削 12/340", or "刮削中…" while the total is not known yet. */
@Composable
fun taskProgressText(label: String, done: Int, total: Int): String =
    if (total <= 0) stringResource(R.string.task_busy_ind, label)
    else stringResource(R.string.task_busy_n, label, done, total)

/** [taskProgressText] for a [TaskProgress]. */
@Composable
fun taskProgressText(progress: TaskProgress): String =
    taskProgressText(stringResource(progress.phase.label), progress.done, progress.total)

@Composable
private fun TaskLabel(text: String) {
    Text(
        text = text,
        style = PicoTheme.typography.bodySmall,
        color = PicoTheme.colorScheme.labelSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
