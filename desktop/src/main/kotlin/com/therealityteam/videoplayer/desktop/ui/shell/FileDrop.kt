package com.therealityteam.videoplayer.desktop.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Captions
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Upload
import com.therealityteam.videoplayer.data.source.VIDEO_EXTENSIONS
import com.therealityteam.videoplayer.desktop.i18n.LocalStrings
import com.therealityteam.videoplayer.desktop.ui.components.Icon
import com.therealityteam.videoplayer.desktop.ui.components.Txt
import com.therealityteam.videoplayer.desktop.ui.theme.Aura
import com.therealityteam.videoplayer.desktop.ui.theme.AuraShapes
import java.io.File
import java.net.URI

/** What a set of files dragged in from Finder or Explorer amounts to. */
data class Dropped(val videos: List<File>, val subtitles: List<File>, val folders: List<File>) {
    val isEmpty get() = videos.isEmpty() && subtitles.isEmpty() && folders.isEmpty()

    companion object {
        /** Subtitle files mpv reads on its own — text and pictures. */
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "idx", "sup", "sub", "smi")

        fun of(files: List<File>): Dropped = Dropped(
            videos = files.filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }.sortedBy { it.name.lowercase() },
            // A VobSub .sub is named by its .idx; take the .sub alone only when there is no .idx.
            subtitles = files.filter { f ->
                val ext = f.extension.lowercase()
                f.isFile && ext in SUBTITLE_EXTENSIONS &&
                    !(ext == "sub" && files.any { it.extension.equals("idx", true) && it.nameWithoutExtension == f.nameWithoutExtension })
            },
            folders = files.filter { it.isDirectory },
        )
    }
}

/**
 * The window as a drop target: files dragged over it dim the page under a hint of what letting go
 * will do ([hint] decides, from what is being dragged when the system says), and [onDrop] gets them.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FileDropArea(
    hint: (Dropped?) -> DropHint,
    onDrop: (Dropped) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var over by remember { mutableStateOf<DropHint?>(null) }
    val currentHint by rememberUpdatedState(hint)
    val currentDrop by rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            private fun files(e: DragAndDropEvent): List<File>? = runCatching {
                (e.dragData() as? DragData.FilesList)?.readFiles()?.map { File(URI(it)) }
            }.getOrNull()

            override fun onEntered(event: DragAndDropEvent) { over = currentHint(files(event)?.let(Dropped::of)) }
            override fun onExited(event: DragAndDropEvent) { over = null }
            override fun onEnded(event: DragAndDropEvent) { over = null }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = null
                val dropped = Dropped.of(files(event) ?: return false)
                if (dropped.isEmpty) return false
                currentDrop(dropped)
                return true
            }
        }
    }
    Box(
        modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { e -> runCatching { e.dragData() is DragData.FilesList }.getOrDefault(false) },
            target = target,
        ),
    ) {
        content()
        AnimatedVisibility(over != null, enter = fadeIn(tween(120)), exit = fadeOut(tween(160))) {
            val h = over ?: DropHint.Generic
            DropOverlay(h)
        }
    }
}

/** What letting go will do, as the overlay says it. */
enum class DropHint { Play, AddToLibrary, AddSubtitles, Generic }

@Composable
internal fun DropOverlay(hint: DropHint) {
    val c = Aura.colors
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding(18.dp)) {
        Box(
            Modifier.fillMaxSize().border(2.dp, c.accent.copy(alpha = 0.85f), AuraShapes.card)
                .background(c.accent.copy(alpha = 0.08f), AuraShapes.card),
            contentAlignment = Alignment.Center,
        ) {
            val (icon, key) = when (hint) {
                DropHint.Play -> Lucide.Play to "d_drop_play"
                DropHint.AddToLibrary -> Lucide.FolderPlus to "d_drop_add"
                DropHint.AddSubtitles -> Lucide.Captions to "d_drop_subtitle"
                DropHint.Generic -> Lucide.Upload to "d_drop_generic"
            }
            // On a panel of its own: whatever poster is under the middle of the window, it reads.
            Column(
                Modifier.widthIn(max = 520.dp).shadow(24.dp, AuraShapes.card).clip(AuraShapes.card)
                    .background(Color(0xF0181820)).padding(horizontal = 36.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(icon, Color.White, 30.dp)
                }
                Spacer(Modifier.height(14.dp))
                Txt(s[key], Aura.type.title2, Color.White, maxLines = 2, align = TextAlign.Center)
            }
        }
    }
}
