package com.geospatial.processing.domain.model

import java.io.File

enum class RecordStatus {
    DRAFT,      // Missing data or images (Orange Icon)
    READY,      // All required fields present (Green Icon)
    ARCHIVED    // Processed and exported (Gray Icon)
}

data class GeoRecord(
    val id: Int = 0,
    val lineName: String,
    val towerNumber: String = "",
    val circuit: String = "",
    val latitude: Double,
    val longitude: Double,

    // --- NEW: Location Extenders ---
    val phase: String? = null,
    val side: String? = null,
    val direction: String? = null,

    // --- NEW: Time Data ---
    val capturedDate: String? = null,
    val capturedTime: String? = null,

    // --- Parameters ---
    val humidity: String = "",
    val emissivity: String = "",
    val ambientTemp: String = "",

    // --- Load Data ---
    val loadValue: String = "",
    val loadDataCkt3: String? = null,
    val loadDataCkt4: String? = null,

    // --- Faults ---
    val faultDescription: String = "",
    val faultTemp: String = "",
    val riseTemp: String? = null,

    // --- Images ---
    val thermalImage: ByteArray? = null,
    val visualImage: ByteArray? = null,
    val towerImage: ByteArray? = null,
    val extraImage: ByteArray? = null,

    val status: RecordStatus = RecordStatus.DRAFT,

    // Defaulted to match our PDF Service logic
    val reportType: String = "tower_fault"
) {


    // 1. THERMAL IMAGE (Top Right)
    fun resolveThermalImage(rootDir: String): ImageSource {
        if (thermalImage != null) {
            if (thermalImage.isEmpty()) return ImageSource.Missing
            return ImageSource.FromBlob(thermalImage)
        }
        val folder = File(rootDir, towerNumber)
        if (!folder.exists() || !folder.isDirectory) return ImageSource.Missing

        // Looks specifically for "Thermal" or "IR"
        val file = folder.listFiles()?.firstOrNull {
            val name = it.name.lowercase()
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                    (name.contains("thermal") || name.contains("ir"))
        }
        return if (file != null) ImageSource.FromFile(file) else ImageSource.Missing
    }

    // 2. RGB / ZOOM IMAGE (Bottom Right)
    fun resolveExtraImage(rootDir: String): ImageSource {
        if (extraImage != null) { // <--- ADD THIS BRACE
            if (extraImage.isEmpty()) return ImageSource.Missing
            return ImageSource.FromBlob(extraImage)
        }
        val folder = File(rootDir, towerNumber)
        if (!folder.exists() || !folder.isDirectory) return ImageSource.Missing

        // Looks specifically for "Zoom", "Visual", or "RGB", but strictly IGNORES "Thermal"
        val file = folder.listFiles()?.firstOrNull {
            val name = it.name.lowercase()
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                    !name.contains("thermal") && !name.contains("ir") && // <-- The Negative Filter
                    (name.contains("zoom") || name.contains("rgb") || name.contains("visual") || name.contains("supp"))
        }
        return if (file != null) ImageSource.FromFile(file) else ImageSource.Missing
    }

    // 3. TOWER / SPAN IMAGE (Bottom Left)
    fun resolveTowerImage(rootDir: String): ImageSource {
        if (towerImage != null) { // <--- ADD THIS BRACE
            if (towerImage.isEmpty()) return ImageSource.Missing
            return ImageSource.FromBlob(towerImage)
        }
        val folder = File(rootDir, towerNumber)
        if (!folder.exists() || !folder.isDirectory) return ImageSource.Missing

        val isMidSpan = reportType == "mid_span"

        // Dynamically looks for "Span" if Mid-Span, or "Tower"/"Wide" if Tower Fault
        val file = folder.listFiles()?.firstOrNull {
            val name = it.name.lowercase()
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                    !name.contains("thermal") && !name.contains("ir") && !name.contains("zoom") && // <-- Negative Filters
                    if (isMidSpan) {
                        name.contains("span") || name.contains("mid")
                    } else {
                        name.contains("tower") || name.contains("wide") || name.contains("structure")
                    }
        }
        return if (file != null) ImageSource.FromFile(file) else ImageSource.Missing
    }

    // 4. EXTRA / VISUAL SLOT (Top Left - Usually mapped to Location Map)
    fun resolveVisualImage(rootDir: String): ImageSource {
        if (visualImage != null) { // <--- ADD THIS BRACE
            if (visualImage.isEmpty()) return ImageSource.Missing
            return ImageSource.FromBlob(visualImage)
        }
        val folder = File(rootDir, towerNumber)
        if (!folder.exists() || !folder.isDirectory) return ImageSource.Missing

        // Grabs whatever is left that isn't Thermal, Zoom, or Tower/Span
        val file = folder.listFiles()?.firstOrNull {
            val name = it.name.lowercase()
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                    !name.contains("thermal") && !name.contains("zoom") && !name.contains("tower") && !name.contains("span") && !name.contains("ir")
        }
        return if (file != null) ImageSource.FromFile(file) else ImageSource.Missing
    }
}