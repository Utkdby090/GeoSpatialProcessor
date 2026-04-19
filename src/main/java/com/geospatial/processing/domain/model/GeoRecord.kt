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
   // val loadValue: String = "",
    //val loadDataCkt1: String? = null,
    //val loadDataCkt2: String? = null,
    //putting new dynamic headers values.
    val dynamicCircuits: Map<String, String> = emptyMap(),

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
    val faultStatus: String = "Normal",
    val companyName: String = ""
) {

    // --- NEW: Dynamic Title Resolution ---


    // Strict AND condition: BOTH columns must explicitly say "fault"
    val isFault: Boolean
        get() {
            // 1. Sanitize the inputs to prevent accidental spaces from the CSV breaking the logic
            val cleanStatus = faultStatus.trim()
            val cleanType = reportType.trim()

            // 2. Safely check for the word "fault" in both places
            val statusHasFault = cleanStatus.contains("fault", ignoreCase = true)
            val typeHasFault = cleanType.contains("fault", ignoreCase = true)

            // 3. The OR Logic Gate
            // If EITHER column says "fault", it correctly flags as a fault report.
            return statusHasFault && typeHasFault
        }
    // The single source of truth for the PDF Title and UI Header
    val resolvedReportTitle: String
        get() {
            val type = reportType.lowercase()
            return when {
                // Catches "midspan", "mid_span", "mid_span_fault", etc.
                type.contains("mid") ->
                    if (isFault) "Mid Span Fault report" else "Mid Span no fault report"

                // Catches "sleeve", "repair_sleeve", "sleeve_fault", etc.
                type.contains("sleeve") ->
                    if (isFault) "Sleeve Fault report" else "Sleeve no fault report"

                // Defaults to Tower
                else ->
                    if (isFault) "Tower Fault report" else "Tower no fault report"
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