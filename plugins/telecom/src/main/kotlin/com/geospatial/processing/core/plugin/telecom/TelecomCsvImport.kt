package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.CsvImportStrategy
import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.map.Coordinates
import com.geospatial.processing.domain.model.AssetDraft
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets

/** Reads the drone-survey CSV (one row per tower/span). Ported from the former CsvImportService. */
class TelecomCsvImport : CsvImportStrategy {

    private val logger = LoggerFactory.getLogger(TelecomCsvImport::class.java)

    /** CSV column -> property key. Everything not listed here (and not in [STANDARD_HEADERS]) is load data. */
    private val columns = listOf(
        "Tower No." to K.TOWER_NUMBER,
        "Line Name" to K.LINE_NAME,
        "CKT" to K.CIRCUIT,
        "Phase" to K.PHASE,
        "Side" to K.SIDE,
        "Captured Date" to K.CAPTURED_DATE,
        "Captured Time" to K.CAPTURED_TIME,
        "Ambeint Temp." to K.AMBIENT_TEMP,   // sic: the survey software's spelling
        "Humidity" to K.HUMIDITY,
        "Emissivity" to K.EMISSIVITY,
        "Fault Temp." to K.FAULT_TEMP,
        "Rise Temp." to K.RISE_TEMP,
        "Fault Description" to K.FAULT_DESCRIPTION,
        "Company Name" to K.COMPANY_NAME,
        "Direction" to K.DIRECTION,
    )

    override fun parse(file: File, onProgress: (Float) -> Unit): List<AssetDraft> {
        logger.info("Starting CSV import from file: {}", file.absolutePath)
        val drafts = mutableListOf<AssetDraft>()
        try {
            val parser = CSVParser.parse(
                file,
                StandardCharsets.UTF_8,
                CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build()
            )
            val records = parser.records
            if (records.isEmpty()) {
                logger.warn("Aborting import: CSV file is empty or missing data rows.")
                return emptyList()
            }
            logger.info("Found {} records in CSV.", records.size)

            for ((index, record) in records.withIndex()) {
                toDraft(record)?.let { drafts += it }
                onProgress((index + 1).toFloat() / records.size)
            }
        } catch (e: Exception) {
            logger.error("CSV Import Error", e)
        }
        if (drafts.isNotEmpty()) onProgress(1.0f)
        return drafts
    }

    /** Null when the row has no line name or tower number. */
    private fun toDraft(record: CSVRecord): AssetDraft? {
        val properties = linkedMapOf<String, String>()
        columns.forEach { (column, key) -> properties[key] = getSafeByName(record, column) }
        properties[K.REPORT_TYPE] = getSafeByName(record, "report_Type").ifBlank { "tower" }
        properties[K.FAULT_STATUS] = getSafeByName(record, "Fault").ifBlank { "normal" }

        if (properties[K.LINE_NAME].isNullOrBlank() || properties[K.TOWER_NUMBER].isNullOrBlank()) return null

        // Dynamic circuits: any header NOT in the standard list is per-circuit load data.
        record.toMap().forEach { (header, value) ->
            val cleanHeader = header.trim()
            if (cleanHeader.isNotEmpty() && cleanHeader.lowercase() !in STANDARD_HEADERS) {
                properties[K.LOAD_PREFIX + cleanHeader] = value.trim()
            }
        }

        // Decimal or degrees/minutes/seconds; a missing or unreadable pair stays 0,0 ("no position yet").
        val position = Coordinates.parsePair(getSafeByName(record, "Lat."), getSafeByName(record, "Long."))
        return AssetDraft(
            latitude = position?.lat ?: 0.0,
            longitude = position?.lon ?: 0.0,
            properties = properties,
        )
    }

    private fun getSafeByName(record: CSVRecord, columnName: String): String = try {
        if (record.isMapped(columnName)) record.get(columnName)?.trim() ?: "" else ""
    } catch (e: Exception) {
        ""
    }

    companion object {
        /** Standard headers (lowercase); everything else is treated as dynamic load data. */
        internal val STANDARD_HEADERS = setOf(
            "sr. no.", "tower no.", "line name", "ckt", "lat.", "long.",
            "phase", "side", "captured date", "captured time", "ambeint temp.",
            "humidity", "emissivity", "fault temp.", "rise temp.", "fault description",
            "report_type", "fault", "company name", "direction"
        )
    }
}
