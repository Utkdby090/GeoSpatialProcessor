package com.geospatial.processing.core.plugin

import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetDraft
import java.io.File
import java.io.OutputStream

// Defines the types of UI inputs we can generate
enum class FieldType { TEXT, NUMBER, DROPDOWN, BOOLEAN }

/**
 * One field of the asset form (e.g. "Tower No." or "Blade Length").
 *
 * Layout: fields are shown by [group] (in schema order), then by [row] inside the group;
 * fields on the same row share the width by [weight].
 * The keys [CoreFields.LATITUDE] and [CoreFields.LONGITUDE] edit the asset's coordinates.
 */
data class PropertyDefinition(
    val key: String,             // Property key (e.g., "towerNumber")
    val label: String,           // UI Label (e.g., "Tower No.")
    val type: FieldType,
    val isRequired: Boolean = false,
    val dropdownOptions: List<String> = emptyList(), // Used if type == DROPDOWN
    val group: String = "",
    val row: Int = 0,
    val weight: Float = 1f,
)

/** Form keys for the coordinates, which are core [Asset] fields rather than properties. */
object CoreFields {
    const val LATITUDE = "core.latitude"
    const val LONGITUDE = "core.longitude"
}

/** One image slot on the form and the report, e.g. id "THERMAL", label "Thermal Image". */
data class ImageSlotDef(val id: String, val label: String)

/** How an asset is named and classified in lists, the form and reports. */
data class AssetPresentation(
    /** Main line in the asset list, e.g. "Tower 76_0". */
    val listTitle: String,
    /** Second line in the asset list. */
    val listSubtitle: String,
    /** Short label used by the previous/next sequence navigator, e.g. "76_0". */
    val sequenceLabel: String,
    /** Report title, e.g. "Tower Fault report". */
    val reportTitle: String,
    val isFault: Boolean,
    /** Whether the form/report shows the previous/next navigator next to the structure image. */
    val showsSequenceNavigator: Boolean,
)

// The Base Interface every industry module must implement
interface DomainPlugin {
    val pluginId: String         // e.g., "com.geo.telecom"
    val displayName: String      // e.g., "Telecom Grid Inspection"
    val description: String      // e.g., "Process 765kV transmission lines and mid-spans."
    val iconName: String         // Points to a vector asset

    /** The form fields for [asset], including fields that only exist for this asset (e.g. extra CSV columns). */
    fun getPropertySchema(asset: Asset): List<PropertyDefinition>

    /** The image slots shown for [asset], in display order (two per row). */
    fun imageSlots(asset: Asset): List<ImageSlotDef>

    fun present(asset: Asset): AssetPresentation

    /** Images for [asset] found in the user's image folder, keyed by slot id. */
    fun resolveFolderImages(rootDir: String, asset: Asset): Map<String, File>

    /** How to read this industry's CSV. */
    fun getCsvImportStrategy(): CsvImportStrategy

    /** How to draw this industry's PDF report. */
    fun getReportStrategy(): ReportStrategy
}

interface CsvImportStrategy {
    /** Reads [file]; [onProgress] receives 0..1. Rows that can't become an asset are skipped. */
    fun parse(file: File, onProgress: (Float) -> Unit = {}): List<AssetDraft>
}

interface ReportStrategy {
    /** Path of the asset's PDF inside the bulk-export ZIP, e.g. "Tower_Reports/FAULT_Tower_76_0_Report.pdf". */
    fun entryName(asset: Asset): String

    /**
     * Writes one PDF for [asset] to [out] (without closing it).
     * [images] holds the image bytes per slot id (null = no image); [previous]/[next] are the
     * sequence labels of the neighbouring assets, for the navigator.
     */
    fun writePdf(asset: Asset, images: Map<String, ByteArray?>, previous: String?, next: String?, out: OutputStream)
}
