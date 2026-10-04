package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.CsvImportResult
import com.geospatial.processing.core.plugin.CsvImportStrategy
import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.map.Coordinates
import com.geospatial.processing.domain.model.AssetDraft
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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

    override fun parse(file: File, onProgress: (Float) -> Unit): List<AssetDraft> = read(file, onProgress).drafts

    override fun read(file: File, onProgress: (Float) -> Unit): CsvImportResult {
        logger.info("Starting CSV import from file: {}", file.absolutePath)
        val text = try {
            decode(file.readBytes())
        } catch (e: IOException) {
            logger.error("CSV could not be read", e)
            return CsvImportResult(emptyList(), problem = "The file could not be read: ${e.message}")
        }
        if (text.isBlank()) return CsvImportResult(emptyList(), problem = "The CSV file is empty.")

        val format = CSVFormat.DEFAULT.builder()
            .setDelimiter(detectDelimiter(text))
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setAllowMissingColumnNames(true) // Excel often leaves empty header cells after the last real column
            .setTrim(true)
            .build()

        // Closing the parser releases the file; without it Windows keeps the CSV locked until the next garbage collection.
        val (headerNames, records) = try {
            CSVParser.parse(StringReader(text), format).use { parser ->
                parser.headerNames.toList() to parser.records
            }
        } catch (e: Exception) {
            logger.error("CSV Import Error", e)
            return CsvImportResult(emptyList(), problem = "The CSV could not be read: ${e.message}")
        }

        val headers = headerNames.map { it.trim() }
        // Per-circuit load data: every column that is not a standard one, in the order of the file.
        val loadColumns = headers.withIndex()
            .map { (index, name) -> name to index }
            .filter { (name, _) -> name.isNotEmpty() && name.lowercase() !in STANDARD_HEADERS }
        val present = headers.map { it.lowercase() }.toSet()
        val missing = REQUIRED_HEADERS.filter { it.lowercase() !in present }
        if (missing.isNotEmpty()) {
            val found = headers.filter { it.isNotEmpty() }.take(8).joinToString(", ").ifEmpty { "none" }
            return CsvImportResult(emptyList(), problem = "The CSV has no ${missing.joinToString(" and ") { "\"$it\"" }} column. Columns found: $found.")
        }
        if (records.isEmpty()) {
            logger.warn("Aborting import: CSV file is empty or missing data rows.")
            return CsvImportResult(emptyList(), problem = "The CSV has a header row but no data rows.")
        }
        logger.info("Found {} records in CSV.", records.size)

        val drafts = ArrayList<AssetDraft>(records.size)
        val skipped = ArrayList<String>()
        var skippedCount = 0
        var lastReported = -1
        for ((index, record) in records.withIndex()) {
            if (record.all { it.isBlank() }) continue // a row of empty cells, as spreadsheets leave at the end of a sheet
            val draft = toDraft(record, loadColumns)
            if (draft != null) {
                drafts += draft
            } else {
                skippedCount++
                if (skipped.size < MAX_DETAILS) skipped += "row ${index + 2}: no line name or tower number" // row 1 is the header
            }
            // One update per percent: the caller turns each into a UI state change.
            val percent = ((index + 1) * 100L / records.size).toInt()
            if (percent != lastReported) {
                lastReported = percent
                onProgress((index + 1).toFloat() / records.size)
            }
        }
        if (drafts.isNotEmpty()) onProgress(1.0f)
        return CsvImportResult(drafts, skippedCount, skipped)
    }

    /** Null when the row has no line name or tower number. */
    private fun toDraft(record: CSVRecord, loadColumns: List<Pair<String, Int>>): AssetDraft? {
        val properties = linkedMapOf<String, String>()
        columns.forEach { (column, key) -> properties[key] = getSafeByName(record, column) }
        properties[K.REPORT_TYPE] = getSafeByName(record, "report_Type").ifBlank { "tower" }
        properties[K.FAULT_STATUS] = getSafeByName(record, "Fault").ifBlank { "normal" }

        if (properties[K.LINE_NAME].isNullOrBlank() || properties[K.TOWER_NUMBER].isNullOrBlank()) return null

        // Dynamic circuits: any header NOT in the standard list is per-circuit load data (a short row just has fewer).
        for ((name, index) in loadColumns) {
            if (index < record.size()) properties[K.LOAD_PREFIX + name] = record.get(index).trim()
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

        /** A row without these cannot become a tower, so a file without the columns is reported instead of silently empty. */
        private val REQUIRED_HEADERS = listOf("Tower No.", "Line Name")

        private const val MAX_DETAILS = 10

        private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

        /**
         * Text of a CSV as Excel and survey tools write it: UTF-8 (with or without a byte-order mark), UTF-16 with a mark,
         * or the legacy Windows "ANSI" encoding that Excel's plain "CSV" export uses (where "°" is a single byte).
         * Reading ANSI as UTF-8 would silently turn every "°" into a replacement character and lose the position.
         */
        internal fun decode(bytes: ByteArray): String {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
            return when {
                starts(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
                starts(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
                starts(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
                else -> try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: CharacterCodingException) {
                    String(bytes, WINDOWS_1252)
                }
            }
        }

        /**
         * The separator of a CSV: spreadsheets in many countries write ";" because "," is their decimal mark, and tools
         * sometimes write tabs. Judged on the header row, ignoring separators inside quotes; a comma when nothing else wins.
         */
        internal fun detectDelimiter(text: String): Char {
            val header = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
            val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
            var quoted = false
            for (c in header) {
                if (c == '"') quoted = !quoted
                else if (!quoted && c in counts) counts[c] = counts.getValue(c) + 1
            }
            val best = counts.maxByOrNull { it.value }!!
            return if (best.value == 0 || best.value <= counts.getValue(',')) ',' else best.key
        }
    }
}
