package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.*
import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.imaging.ImageSlot
import com.geospatial.processing.domain.imaging.TowerImageResolver
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.thermal.ThermalOverrides
import com.geospatial.processing.domain.thermal.ThermalStats
import java.io.File
import java.util.Locale

/** Electric grid / transmission line inspection: towers, mid-spans, repair sleeves and earth-wire joints. */
class TelecomPlugin : DomainPlugin {
    override val pluginId = K.PLUGIN_ID
    override val displayName = "Electric Grid Inspection"
    override val description = "Process 765kV transmission lines, mid-spans, and hardware fittings."
    override val iconName = "settings" // We'll map this to a native Compose icon in the UI

    override fun getPropertySchema(asset: Asset): List<PropertyDefinition> {
        val location = "Location & Temporal Data"
        val environment = "Environmental & Load Data"
        val fault = "Fault Analysis"

        // Extra CSV columns (per-circuit load data), three per row, in CSV column order.
        val loadFields = asset.properties.keys.filter { it.startsWith(K.LOAD_PREFIX) }
            .mapIndexed { i, key ->
                PropertyDefinition(key, key.removePrefix(K.LOAD_PREFIX), FieldType.TEXT, group = environment, row = 1 + i / 3)
            }

        return listOf(
            PropertyDefinition(K.LINE_NAME, "Line Name", FieldType.TEXT, isRequired = true, group = location, row = 0),
            PropertyDefinition(K.TOWER_NUMBER, "Tower No", FieldType.TEXT, isRequired = true, group = location, row = 0, weight = 0.5f),
            PropertyDefinition(K.CIRCUIT, "Circuit", FieldType.TEXT, group = location, row = 0, weight = 0.5f),
            PropertyDefinition(K.PHASE, "Phase", FieldType.TEXT, group = location, row = 1),
            PropertyDefinition(K.SIDE, "Side", FieldType.TEXT, group = location, row = 1),
            PropertyDefinition(K.DIRECTION, "Direction", FieldType.TEXT, group = location, row = 1),
            PropertyDefinition(K.CAPTURED_DATE, "Captured Date", FieldType.TEXT, group = location, row = 2),
            PropertyDefinition(K.CAPTURED_TIME, "Captured Time", FieldType.TEXT, group = location, row = 2),
            PropertyDefinition(CoreFields.LATITUDE, "Latitude", FieldType.NUMBER, isRequired = true, group = location, row = 2),
            PropertyDefinition(CoreFields.LONGITUDE, "Longitude", FieldType.NUMBER, isRequired = true, group = location, row = 2),

            PropertyDefinition(K.HUMIDITY, "Humidity (%)", FieldType.TEXT, group = environment, row = 0),
            PropertyDefinition(K.EMISSIVITY, "Emissivity", FieldType.TEXT, group = environment, row = 0),
            PropertyDefinition(K.AMBIENT_TEMP, "Amb. Temp (°C)", FieldType.TEXT, group = environment, row = 0),
        ) + loadFields + listOf(
            PropertyDefinition(K.FAULT_DESCRIPTION, "Fault Description", FieldType.TEXT, group = fault, row = 0),
            PropertyDefinition(K.FAULT_TEMP, "Fault Temp (°C)", FieldType.TEXT, group = fault, row = 1),
            PropertyDefinition(K.RISE_TEMP, "Rise Temp (°C)", FieldType.TEXT, group = fault, row = 1),
        )
    }

    override fun imageSlots(asset: Asset): List<ImageSlotDef> {
        val kind = ReportKind.of(asset)
        return listOf(
            ImageSlotDef(K.SLOT_LOCATION, "Location"),
            ImageSlotDef(K.SLOT_THERMAL, if (kind == ReportKind.MID_SPAN) "THERMAL Image" else "Thermal Image", isThermal = true),
            ImageSlotDef(K.SLOT_STRUCTURE, kind.structureLabel),
            ImageSlotDef(K.SLOT_RGB_ZOOM, "RGB Image"),
        )
    }

    override fun present(asset: Asset): AssetPresentation {
        val towerNumber = asset.property(K.TOWER_NUMBER)
        val lineName = asset.property(K.LINE_NAME)
        return AssetPresentation(
            listTitle = "Tower $towerNumber",
            listSubtitle = if (lineName.isNotBlank()) lineName else "Circuit: ${asset.property(K.CIRCUIT)}",
            sequenceLabel = towerNumber,
            reportTitle = reportTitle(asset),
            isFault = isFault(asset),
            showsSequenceNavigator = ReportKind.of(asset) == ReportKind.TOWER,
        )
    }

    /** Emissivity, ambient temperature (also used as the reflected temperature) and humidity typed into the form. */
    override fun thermalOverrides(properties: Map<String, String>): ThermalOverrides {
        val ambient = number(properties[K.AMBIENT_TEMP])
        return ThermalOverrides(
            emissivity = number(properties[K.EMISSIVITY])?.takeIf { it > 0.0 && it <= 1.0 },
            atmosphericTempC = ambient,
            reflectedTempC = ambient,
            humidityPct = number(properties[K.HUMIDITY])?.takeIf { it in 0.0..100.0 },
        )
    }

    /** The hottest reading is the fault temperature; its rise is measured against the ambient temperature. */
    override fun thermalFindings(stats: ThermalStats, properties: Map<String, String>): Map<String, String> {
        val findings = linkedMapOf(K.FAULT_TEMP to "%.1f".format(Locale.ROOT, stats.max))
        number(properties[K.AMBIENT_TEMP])?.let { findings[K.RISE_TEMP] = "%.1f".format(Locale.ROOT, stats.max - it) }
        return findings
    }

    private fun number(text: String?): Double? = text?.trim()?.replace(',', '.')?.toDoubleOrNull()

    override fun resolveFolderImages(rootDir: String, asset: Asset): Map<String, File> =
        TowerImageResolver.resolve(rootDir, asset.property(K.TOWER_NUMBER), reportType(asset))
            .mapKeys { (slot, _) -> slotId(slot) }

    override fun getCsvImportStrategy(): CsvImportStrategy = TelecomCsvImport()

    override fun getReportStrategy(): ReportStrategy = TelecomReport(this)

    companion object {
        fun reportType(asset: Asset): String = asset.property(K.REPORT_TYPE).ifBlank { "tower" }

        /** A fault report only when BOTH the fault status and the report type say "fault". */
        fun isFault(asset: Asset): Boolean =
            asset.property(K.FAULT_STATUS).trim().contains("fault", ignoreCase = true) &&
                reportType(asset).trim().contains("fault", ignoreCase = true)

        /** The single source of truth for the PDF title and the form's classification field. */
        fun reportTitle(asset: Asset): String {
            val type = reportType(asset).lowercase()
            val fault = isFault(asset)
            return when {
                type.contains("earth") || type.contains("wire") ->
                    if (fault) "Earth Wire Joint Fault report" else "Earth Wire Joint report"
                type.contains("mid") -> if (fault) "Mid Span Fault report" else "Mid Span no fault report"
                type.contains("sleeve") -> if (fault) "Sleeve Fault report" else "Sleeve no fault report"
                else -> if (fault) "Tower Fault report" else "Tower no fault report"
            }
        }

        private fun slotId(slot: ImageSlot) = when (slot) {
            ImageSlot.LOCATION -> K.SLOT_LOCATION
            ImageSlot.THERMAL -> K.SLOT_THERMAL
            ImageSlot.STRUCTURE -> K.SLOT_STRUCTURE
            ImageSlot.RGB_ZOOM -> K.SLOT_RGB_ZOOM
        }
    }
}

/** Which structure an inspection is about. Mirrors the checks the form and PDF always used. */
internal enum class ReportKind(val structureLabel: String) {
    TOWER("Tower Image"), MID_SPAN("SPAN Image"), SLEEVE("SLEEVE Image"), EARTH_WIRE("EARTH WIRE Image");

    companion object {
        fun of(asset: Asset): ReportKind {
            val type = TelecomPlugin.reportType(asset).lowercase()
            return when {
                type.contains("mid") -> MID_SPAN
                type.contains("sleeve") -> SLEEVE
                type.contains("earth") -> EARTH_WIRE
                else -> TOWER
            }
        }
    }
}
