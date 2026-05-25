

package com.geospatial.processing.utils

import java.io.File
import javax.swing.JFileChooser

object FileUtils {
    fun pickDirectory(dialogTitle: String, currentDir: File? = null): File? {
        val chooser = JFileChooser().apply {
            this.dialogTitle = dialogTitle
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            currentDir?.let { currentDirectory = it }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }
}