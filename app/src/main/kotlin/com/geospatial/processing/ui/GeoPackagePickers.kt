package com.geospatial.processing.ui

import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

private fun chooser(title: String) = JFileChooser().apply {
    dialogTitle = title
    fileFilter = FileNameExtensionFilter("GeoPackage", "gpkg")
}

fun pickGeoPackageFile(): File? {
    val parentFrame = JFrame().apply { iconImage = getAwtAppIcon() }
    val chooser = chooser("Import GeoPackage")
    val result = chooser.showOpenDialog(parentFrame)
    parentFrame.dispose()
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Asks where to save, adds `.gpkg` when missing, and confirms before replacing an existing file. */
fun saveGeoPackageFile(suggestedName: String): File? {
    val parentFrame = JFrame().apply { iconImage = getAwtAppIcon() }
    val chooser = chooser("Export GeoPackage").apply { selectedFile = File("$suggestedName.gpkg") }
    val result = chooser.showSaveDialog(parentFrame)
    parentFrame.dispose()
    if (result != JFileChooser.APPROVE_OPTION) return null
    val picked = chooser.selectedFile
    val file = if (picked.name.endsWith(".gpkg", ignoreCase = true)) picked else File(picked.parentFile, "${picked.name}.gpkg")
    if (file.exists()) {
        val answer = JOptionPane.showConfirmDialog(null, "${file.name} already exists. Replace it?", "Export GeoPackage", JOptionPane.YES_NO_OPTION)
        if (answer != JOptionPane.YES_OPTION) return null
    }
    return file
}
