package com.geospatial.processing.core.plugin

import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetDraft
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.domain.thermal.ThermalOverrides
import com.geospatial.processing.domain.thermal.ThermalStats
import java.io.File
import com.geospatial.processing.domain.report.ReportOptions
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

/**
 * One image slot on the form and the report, e.g. id "THERMAL", label "Thermal Image".
 * [isThermal] marks a slot that holds radiometric images, which get a temperature readout in the form.
 */
data class ImageSlotDef(val id: String, val label: String, val isThermal: Boolean = false)

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

    /** How urgent [asset]'s finding is, from its properties (e.g. the temperature rise). Industries without a rating keep [Severity.NONE]. */
    fun classify(asset: Asset): Severity = Severity.NONE

    /** Scene settings (emissivity, ambient temperature…) from the form values, which refine the temperatures in thermal images. */
    fun thermalOverrides(properties: Map<String, String>): ThermalOverrides = ThermalOverrides()

    /** Property values to fill in from what a thermal analysis found (e.g. the fault temperature); empty = nothing to fill. */
    fun thermalFindings(stats: ThermalStats, properties: Map<String, String>): Map<String, String> = emptyMap()
}

interface CsvImportStrategy {
    /** Reads [file]; [onProgress] receives 0..1. Rows that can't become an asset are skipped. */
    fun parse(file: File, onProgress: (Float) -> Unit = {}): List<AssetDraft>

    /**
     * Like [parse], but also says which rows were skipped and why, and gives a reason when nothing could be read,
     * so the user is told instead of seeing an import that silently found no towers. Strategies that don't report
     * this keep the default, which wraps [parse].
     */
    fun read(file: File, onProgress: (Float) -> Unit = {}): CsvImportResult = CsvImportResult(parse(file, onProgress))
}

/**
 * What reading a CSV produced. [skippedRows] counts rows that could not become an asset; [skippedDetails] describes the
 * first few of them. [problem] is set when the file could not be used at all (unreadable, or without the needed columns).
 */
data class CsvImportResult(
    val drafts: List<AssetDraft>,
    val skippedRows: Int = 0,
    val skippedDetails: List<String> = emptyList(),
    val problem: String? = null,
)

interface ReportStrategy {
    /** Path of the asset's PDF inside the bulk-export ZIP, e.g. "Tower_Reports/FAULT_Tower_76_0_Report.pdf". */
    fun entryName(asset: Asset): String

    /**
     * Writes one PDF for [asset] to [out] (without closing it).
     * [images] holds the image bytes per slot id (null = no image); [previous]/[next] are the
     * sequence labels of the neighbouring assets, for the navigator.
     */
    fun writePdf(asset: Asset, images: Map<String, ByteArray?>, previous: String?, next: String?, out: OutputStream)

    /**
     * Like [writePdf], but with the project's chosen template and branding. Strategies that don't support
     * templates keep their one layout (this default ignores [options]).
     */
    fun writePdf(
        asset: Asset, images: Map<String, ByteArray?>, previous: String?, next: String?,
        options: ReportOptions, out: OutputStream,
    ) = writePdf(asset, images, previous, next, out)
}
