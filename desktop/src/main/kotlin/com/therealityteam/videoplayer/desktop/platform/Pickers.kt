package com.therealityteam.videoplayer.desktop.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * The system's own open dialogs: Finder's on a Mac (for folders too, through the AWT switch that
 * turns FileDialog into a folder chooser), the Windows chooser elsewhere.
 */
object Pickers {

    fun chooseFolder(owner: Frame?, title: String): File? {
        if (Host.isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val d = FileDialog(owner, title, FileDialog.LOAD)
                d.isVisible = true
                val dir = d.directory ?: return null
                val name = d.file ?: return null
                return File(dir, name).takeIf { it.isDirectory }
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        systemLook()
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    fun chooseFiles(owner: Frame?, title: String): List<File> {
        if (Host.isMac) {
            val d = FileDialog(owner, title, FileDialog.LOAD).apply { isMultipleMode = true }
            d.isVisible = true
            return d.files?.toList().orEmpty()
        }
        systemLook()
        val chooser = JFileChooser().apply {
            dialogTitle = title
            isMultiSelectionEnabled = true
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
    }

    private var looked = false

    private fun systemLook() {
        if (looked) return
        looked = true
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }
}
