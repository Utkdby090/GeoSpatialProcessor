package com.geospatial.processing.domain.usecase

import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.GeoRecord
import com.geospatial.processing.domain.model.RecordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets

class CsvImportService(private val repository: GeoRepository) {

    private val logger = LoggerFactory.getLogger(CsvImportService::class.java)

    suspend fun importCsvFile(file: File, onProgress: (Float) -> Unit): Int = withContext(Dispatchers.IO) {
        var count = 0
        logger.info("Starting CSV import from file: {}", file.absolutePath)
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
            val total = records.size
            if (total == 0) {
                logger.warn("Aborting import: CSV file is empty or missing data rows.")
                return@withContext 0
            }

            logger.info("Found {} records in CSV. Beginning database insertion.", total)

            // 1. Define standard headers to ignore when extracting dynamic circuits.
            // Converted to lowercase for case-insensitive matching.
            val standardHeaders = setOf(
                "sr. no.", "tower no.", "line name", "ckt", "lat.", "long.",
                "phase", "side", "captured date", "captured time", "ambeint temp.",
                "humidity", "emissivity", "fault temp.", "rise temp.", "fault description",
                "fault description ", // Trailing space catch
                "report_type", "fault", "company name", "direction"
            )

            for ((index, record) in records.withIndex()) {

                // --- Extract Core Data ---
                val towerNum = getSafeByName(record, "Tower No.")
                val lineName = getSafeByName(record, "Line Name")
                val circuit = getSafeByName(record, "CKT")
                val latStr = getSafeByName(record, "Lat.")
                val longStr = getSafeByName(record, "Long.")

                // --- Extract New Extenders ---
                val phase = getSafeByName(record, "Phase")
                val side = getSafeByName(record, "Side")
                val capturedDate = getSafeByName(record, "Captured Date")
                val capturedTime = getSafeByName(record, "Captured Time")

                // --- Extract Environment ---
                val ambTemp = getSafeByName(record, "Ambeint Temp.")
                val humidity = getSafeByName(record, "Humidity")
                val emissivity = getSafeByName(record, "Emissivity")

                // --- Extract Fault Data ---
                val faultTemp = getSafeByName(record, "Fault Temp.")
                val riseTemp = getSafeByName(record, "Rise Temp.")
                val faultDesc = getSafeByName(record, "Fault Description")

                // --- EXTRACTION AND CLEANUP FOR REPORT TYPE ---
                val rawReportType = getSafeByName(record, "report_Type").ifBlank { "tower" }
                val rawFaultStatus = getSafeByName(record, "Fault").ifBlank { "normal" }
                val companyNameStr = getSafeByName(record, "Company Name")
                val directionStr = getSafeByName(record, "Direction")

                // --- 2. DYNAMIC CIRCUIT EXTRACTION ---
                val dynamicMap = mutableMapOf<String, String>()
                record.toMap().forEach { (header, value) ->
                    val cleanHeader = header.trim()
                    // If the header is NOT in our standard list, it is dynamic load data
                    if (cleanHeader.isNotEmpty() && !standardHeaders.contains(cleanHeader.lowercase())) {
                        dynamicMap[cleanHeader] = value.trim()
                    }
                }

                if (lineName.isNotBlank() && towerNum.isNotBlank()) {
                    val newRecord = GeoRecord(
                        id = 0,
                        lineName = lineName,
                        towerNumber = towerNum,
                        circuit = circuit,
                        latitude = latStr.toDoubleOrNull() ?: 0.0,
                        longitude = longStr.toDoubleOrNull() ?: 0.0,

                        // Extenders
                        phase = phase,
                        side = side,
                        capturedDate = capturedDate,
                        capturedTime = capturedTime,

                        // Parameters
                        humidity = humidity,
                        emissivity = emissivity,
                        ambientTemp = ambTemp,

                        // --- Pass the dynamic circuits map here ---
                        dynamicCircuits = dynamicMap,

                        // Faults
                        faultDescription = faultDesc,
                        faultTemp = faultTemp,
                        riseTemp = riseTemp,

                        // Report Type & Meta
                        reportType = rawReportType,
                        faultStatus = rawFaultStatus,
                        companyName = companyNameStr,
                        direction = directionStr,

                        // Media
                        thermalImage = null,
                        visualImage = null,
                        towerImage = null,
                        extraImage = null,

                        status = RecordStatus.DRAFT
                    )

                    repository.saveRecord(newRecord)
                    count++
                }

                if (total > 0) {
                    onProgress(((index + 1).toFloat() / total.toFloat()))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("CSV Import Error: ${e.message}")
        }

        if (count > 0) onProgress(1.0f)
        return@withContext count
    }

    private fun getSafeByName(record: CSVRecord, columnName: String): String {
        return try {
            if (record.isMapped(columnName)) {
                record.get(columnName)?.trim() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}