package com.geospatial.processing.domain.model

import java.io.File

sealed class ImageSource {
    data class FromBlob(val bytes: ByteArray) : ImageSource()
    data class FromFile(val file: File) : ImageSource()
    object Missing : ImageSource()
}

// --- HELPER: FUZZY FILE SCANNER ---
private fun findImageByKeyword(towerDir: File, keyword: String): File? {
    if (!towerDir.exists() || !towerDir.isDirectory) return null

    // Scans the folder for the first file that contains the keyword and is an image
    return towerDir.listFiles()?.firstOrNull { file ->
        val name = file.name.lowercase()
        name.contains(keyword.lowercase()) &&
                (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
    }
}

// --- RESOLUTION FUNCTIONS ---
fun GeoRecord.resolveThermalImage(rootDir: String): ImageSource {
    if (this.thermalImage != null) return ImageSource.FromBlob(this.thermalImage)
    val towerDir = File(rootDir, this.towerNumber.trim())
    val localFile = findImageByKeyword(towerDir, "thermal") // Fuzzy match
    return if (localFile != null) ImageSource.FromFile(localFile) else ImageSource.Missing
}

fun GeoRecord.resolveVisualImage(rootDir: String): ImageSource {
    if (this.visualImage != null) return ImageSource.FromBlob(this.visualImage)
    val towerDir = File(rootDir, this.towerNumber.trim())
    val localFile = findImageByKeyword(towerDir, "visual") // Fuzzy match
    return if (localFile != null) ImageSource.FromFile(localFile) else ImageSource.Missing
}

fun GeoRecord.resolveTowerImage(rootDir: String): ImageSource {
    if (this.towerImage != null) return ImageSource.FromBlob(this.towerImage)
    val towerDir = File(rootDir, this.towerNumber.trim())
    val localFile = findImageByKeyword(towerDir, "tower") // Fuzzy match
    return if (localFile != null) ImageSource.FromFile(localFile) else ImageSource.Missing
}

fun GeoRecord.resolveExtraImage(rootDir: String): ImageSource {
    if (this.extraImage != null) return ImageSource.FromBlob(this.extraImage)
    val towerDir = File(rootDir, this.towerNumber.trim())
    val localFile = findImageByKeyword(towerDir, "extra") // Fuzzy match
    return if (localFile != null) ImageSource.FromFile(localFile) else ImageSource.Missing
}

// --- NEW: DYNAMIC READINESS CHECK ---
// The Tree View and PDF Generator will use this to see if the record is actually complete.
fun GeoRecord.isDynamicallyReady(rootDir: String): Boolean {
    val hasThermal = resolveThermalImage(rootDir) !is ImageSource.Missing
    val hasVisual = resolveVisualImage(rootDir) !is ImageSource.Missing
    val hasTower = resolveTowerImage(rootDir) !is ImageSource.Missing
    val hasExtra = resolveExtraImage(rootDir) !is ImageSource.Missing

    // You can also add checks for text fields here if needed (e.g., this.lineName.isNotBlank())
    return hasThermal && hasVisual && hasTower && hasExtra
}