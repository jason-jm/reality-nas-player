package com.therealityteam.videoplayer.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.therealityteam.videoplayer.data.model.MediaItem
import com.therealityteam.videoplayer.desktop.AppGraph
import com.therealityteam.videoplayer.ui.library.LibSection
import com.therealityteam.videoplayer.ui.library.LibSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pages the library window moves between. The player covers the whole window instead. */
sealed interface Page {
    data object Wall : Page
    data class Detail(val key: String) : Page
    data object Sources : Page
    data class FixMatch(val key: String) : Page
}

/** What the player has been asked to play: a file, the queue it belongs to, where to start. */
data class PlayRequest(val item: MediaItem, val queue: List<MediaItem>, val startMs: Long? = null, val title: String? = null)

/** A message at the bottom of the window for a few seconds. */
data class Toast(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

/**
 * Where the reader is in the app: the shelf, the search, the page stack, what is playing. One
 * instance per window, held for the window's life so that a trip to the player and back lands
 * exactly where it left — the same shelf, the same scroll, the same card.
 */
class AppState(val graph: AppGraph, val scope: CoroutineScope) {
    var section by mutableStateOf(LibSection.ALL)
        private set
    var query by mutableStateOf("")
    var moreShelves by mutableStateOf(false)

    private val stack = mutableStateListOf<Page>(Page.Wall)
    val page: Page get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var playing by mutableStateOf<PlayRequest?>(null)
        private set
    var settingsOpen by mutableStateOf(false)

    /** File › Open Address… is asking for one. */
    var addressOpen by mutableStateOf(false)

    /**
     * The window is up. On a Mac its close button only puts it away — Aura stays (a scan carries
     * on, the Dock icon brings the window back, ⌘Q quits) — so it can be down while the app runs.
     */
    var windowShown by mutableStateOf(true)
    var toast by mutableStateOf<Toast?>(null)
        private set

    /** The card the keyboard is on — kept across a trip to a title's page and back. */
    var selectedKey by mutableStateOf<String?>(null)

    /** The wall, for the keyboard: ↓ or Return in the search field moves into the results. */
    val wallFocus = FocusRequester()
    var selectFirstOnFocus by mutableStateOf(false)

    fun focusWall(selectFirst: Boolean) {
        selectFirstOnFocus = selectFirst
        runCatching { wallFocus.requestFocus() }
    }

    /** One scroll position per shelf, so switching shelves and coming back keeps your place. */
    private val wallScroll = HashMap<LibSection, LazyGridState>()
    fun wallState(section: LibSection): LazyGridState = wallScroll.getOrPut(section) { LazyGridState() }

    /**
     * The open card's page scroll, kept while a video plays (the library is not drawn then) so
     * the way back lands where the page was left — for that card only, as on the headset.
     */
    fun detailScroll(key: String): ScrollState =
        detailScroll?.takeIf { it.first == key }?.second ?: ScrollState(0).also { detailScroll = key to it }
    private var detailScroll: Pair<String, ScrollState>? = null

    val sort: LibSort
        get() = LibSort.entries.firstOrNull { it.name == graph.settings.value.librarySort } ?: LibSort.RECENT

    fun setSort(sort: LibSort) = graph.settings.update { it.copy(librarySort = sort.name) }

    fun show(section: LibSection) {
        this.section = section
        stack.clear()
        stack.add(Page.Wall)
    }

    fun go(page: Page) {
        if (stack.last() == page) return
        // A card opened afresh starts at its top; coming back to it (from the player, from
        // fixing its match) finds it where it was left.
        if (page is Page.Detail) detailScroll = null
        stack.add(page)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Swap the page on top for [page] — a title page whose card was re-keyed while it was open. */
    fun replaceTop(page: Page) {
        stack[stack.lastIndex] = page
    }

    fun play(item: MediaItem, queue: List<MediaItem> = listOf(item), startMs: Long? = null, title: String? = null) {
        playing = PlayRequest(item, queue.ifEmpty { listOf(item) }, startMs, title)
        windowShown = true
    }

    fun stopPlaying() {
        playing = null
    }

    fun toast(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val t = Toast(text, action, onAction)
        toast = t
        scope.launch {
            delay(if (action != null) 6_000 else 3_000)
            if (toast?.id == t.id) toast = null
        }
    }

    fun dismissToast() {
        toast = null
    }
}
