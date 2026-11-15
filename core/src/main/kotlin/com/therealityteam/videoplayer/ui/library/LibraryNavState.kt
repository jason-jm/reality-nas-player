package com.therealityteam.videoplayer.ui.library

/**
 * Library navigation + scroll state that must outlive the library window being closed and
 * reopened (e.g. when a video plays in its own window). Held in the long-lived session scope,
 * not in composition, so returning from the player restores the detail page the user was on and
 * both the poster-wall and detail-list scroll positions — instead of snapping back to the top.
 */
class LibraryNavState {
    var view: LibSection = LibSection.ALL
    var query: String = ""
    var sort: LibSort = LibSort.RECENT
    var filter: WallFilter = WallFilter()
    var selectedKey: String? = null

    // Poster-wall scroll.
    var gridIndex: Int = 0
    var gridOffset: Int = 0

    // Detail episode/file-list scroll, tied to the group it belongs to so a *different* title
    // opens at the top while the one we played from is restored.
    var detailKey: String? = null
    var detailIndex: Int = 0
    var detailOffset: Int = 0
}
