package com.therealityteam.videoplayer.desktop.platform

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar

/**
 * The Dock icon's own menu — a right-click on it: what you were watching, to carry on in one
 * click, and the ways in, a file or an address. macOS keeps such a menu for every app; elsewhere
 * nothing is set.
 */
object DockMenu {
    private val taskbar: Taskbar? =
        runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().takeIf { it.isSupported(Taskbar.Feature.MENU) } else null }.getOrNull()
    private var shown: List<String>? = null

    /** One line of the menu: its words and what it does, or a heading when [run] is null. */
    class Entry(val label: String, val run: (() -> Unit)?)

    /** Set the menu; groups of entries are separated. Repeats of what is shown change nothing. */
    fun set(groups: List<List<Entry>>) {
        val t = taskbar ?: return
        val labels = groups.flatMap { g -> g.map { it.label } + "—" }
        if (labels == shown) return
        shown = labels
        val menu = PopupMenu()
        groups.filter { it.isNotEmpty() }.forEachIndexed { i, g ->
            if (i > 0) menu.addSeparator()
            for (e in g) {
                menu.add(MenuItem(e.label).apply {
                    val run = e.run
                    if (run == null) isEnabled = false else addActionListener { runCatching(run) }
                })
            }
        }
        runCatching { t.menu = menu }
    }

    /** The menu as set, for checks: each line, a heading's marked. */
    internal fun describe(): List<String> {
        val menu = taskbar?.let { runCatching { it.menu }.getOrNull() } ?: return emptyList()
        return (0 until menu.itemCount).map { i -> menu.getItem(i).let { if (it.label == "-") "———" else it.label + if (it.isEnabled) "" else "  — heading" } }
    }
}
