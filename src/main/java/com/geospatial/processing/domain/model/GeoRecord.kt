package com.geospatial.processing.domain.model

import com.geospatial.processing.domain.imaging.ImageSlot
import com.geospatial.processing.domain.imaging.TowerImageResolver

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
                // if the report type is earth
                type.contains("earth") || type.contains("wire") ->
                    if (isFault) "Earth Wire Joint Fault report" else "Earth Wire Joint report"
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


    /**
     * Resolves all four report images with ONE folder scan (see TowerImageResolver).
     * Manual uploads (DB blobs) take priority; an empty blob means "user cleared this slot".
     */
    fun resolveAllImages(rootDir: String): Map<ImageSlot, ImageSource> {
        val folderMatches by lazy { TowerImageResolver.resolve(rootDir, towerNumber, reportType) }

        fun pick(blob: ByteArray?, slot: ImageSlot): ImageSource = when {
            blob != null && blob.isEmpty() -> ImageSource.Missing
            blob != null -> ImageSource.FromBlob(blob)
            else -> folderMatches[slot]?.let { ImageSource.FromFile(it) } ?: ImageSource.Missing
        }

        return mapOf(
            ImageSlot.THERMAL to pick(thermalImage, ImageSlot.THERMAL),       // top right
            ImageSlot.RGB_ZOOM to pick(extraImage, ImageSlot.RGB_ZOOM),       // bottom right
            ImageSlot.STRUCTURE to pick(towerImage, ImageSlot.STRUCTURE),     // bottom left
            ImageSlot.LOCATION to pick(visualImage, ImageSlot.LOCATION),      // top left
        )
    }

    // Kept for existing callers (DetailView, PdfGenerationService). Same slots as before.

    // 1. THERMAL IMAGE (Top Right)
    fun resolveThermalImage(rootDir: String): ImageSource = resolveAllImages(rootDir).getValue(ImageSlot.THERMAL)

    // 2. RGB / ZOOM IMAGE (Bottom Right)
    fun resolveExtraImage(rootDir: String): ImageSource = resolveAllImages(rootDir).getValue(ImageSlot.RGB_ZOOM)

    // 3. TOWER / SPAN / SLEEVE IMAGE (Bottom Left)
    fun resolveTowerImage(rootDir: String): ImageSource = resolveAllImages(rootDir).getValue(ImageSlot.STRUCTURE)

    // 4. EXTRA / VISUAL SLOT (Top Left - Usually mapped to Location Map)
    fun resolveVisualImage(rootDir: String): ImageSource = resolveAllImages(rootDir).getValue(ImageSlot.LOCATION)
}