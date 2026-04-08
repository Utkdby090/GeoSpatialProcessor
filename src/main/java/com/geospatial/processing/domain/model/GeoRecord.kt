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

    // --- Location Extenders ---
    val phase: String? = null,
    val side: String? = null,
    val direction: String? = null,

    // --- Time Data ---
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
    val reportType: String = "tower",

    // --- NEW: Fault Status Mapping ---
    // Defaults to Normal so UI and logic won't break if column is missing
    val faultStatus: String = "Normal"
) {

    // --- NEW: Dynamic Title Resolution ---

    // Checks the new column, but falls back to checking reportType for older CSV formats
    val isFault: Boolean
        get() = faultStatus.contains("fault", ignoreCase = true) ||
                reportType.contains("fault", ignoreCase = true) ||
    (faultDescription.isNotBlank() && !faultDescription.equals("NA", ignoreCase = true))

    // The single source of truth for the PDF Title and UI Header
    val resolvedReportTitle: String
        get() {
            val type = reportType.lowercase()
            return when {
                type.contains("midspan") || type.contains("mid_span") ->
                    if (isFault) "MidSpan Fault report" else "MidSpan no fault report"

                type.contains("repairsleeve") || type.contains("repair_sleeve") ->
                    if (isFault) "Repair Sleeve Fault report" else "repairSleeve no fault report"

                else -> // Defaults to Tower
                    if (isFault) "Tower Fault Report" else "Tower no Fault report"
            }
        }


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
        if (extraImage != null) {
            if (extraImage.isEmpty()) return ImageSource.Missing
            return ImageSource.FromBlob(extraImage)
        }
        val folder = File(rootDir, towerNumber)
        if (!folder.exists() || !folder.isDirectory) return ImageSource.Missing

        // Looks specifically for "Zoom", "Visual", or "RGB", but strictly IGNORES "Thermal"
        val file = folder.listFiles()?.firstOrNull {
            val name = it.name.lowercase()
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                    !name.contains("thermal") && !name.contains("ir") &&
                    (name.contains("zoom") || name.contains("rgb") || name.contains("visual") || name.contains("supp"))
        }
        return if (file != null) ImageSource.FromFile(file) else ImageSource.Missing
    }


    // 3. TOWER / SPAN / SLEEVE IMAGE (Bottom Left)
    fun resolveTowerImage(rootDir: String): ImageSource {
        if (towerImage != null) {
            if (towerImage.isEmpty()) return ImageSource.Missing
            return ImageSource.FromBlob(towerImage)
        }
        val folder = File(rootDir, towerNumber)
        if (!folder.exists() || !folder.isDirectory) return ImageSource.Missing

        val type = reportType.lowercase()
        val isMidSpan = type.contains("midspan") || type.contains("mid_span")
        val isSleeve = type.contains("sleeve") || type.contains("repair_sleeve") // <-- NEW CHECK

        // Dynamically looks for "Span", "Sleeve", or "Tower"
        val file = folder.listFiles()?.firstOrNull {
            val name = it.name.lowercase()
            (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                    !name.contains("thermal") && !name.contains("ir") && !name.contains("zoom") &&
                    when {
                        isMidSpan -> name.contains("span") || name.contains("mid")
                        isSleeve -> name.contains("sleeve") || name.contains("repair") // <-- NEW FILTER
                        else -> name.contains("tower") || name.contains("wide") || name.contains("structure")
                    }
        }
        return if (file != null) ImageSource.FromFile(file) else ImageSource.Missing
    }

    // 4. EXTRA / VISUAL SLOT (Top Left - Usually mapped to Location Map)
    fun resolveVisualImage(rootDir: String): ImageSource {
        if (visualImage != null) {
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