package com.geospatial.processing.domain.model

import java.io.File

sealed class ImageSource {
    data class FromBlob(val bytes: ByteArray) : ImageSource()
    data class FromFile(val file: File) : ImageSource()
    object Missing : ImageSource()
}

// NOTE: v1 also declared resolveThermalImage/resolveVisualImage/... as extension functions here.
// GeoRecord declares member functions with the same names, and in Kotlin members always win,
// so those extensions were dead code that never ran. They have been removed to avoid confusion.

/** A record is READY when all four report image slots are filled (manual upload or folder match). */
fun GeoRecord.isDynamicallyReady(rootDir: String): Boolean {
    val slots = resolveAllImages(rootDir)
    return slots.values.none { it is ImageSource.Missing }
}
