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

            for ((index, record) in records.withIndex()) {

                // --- Extract Core Data ---
                val towerNum = getSafeByName(record, "Tower No.")
                val lineName = getSafeByName(record, "Line Name")
                val circuit = getSafeByName(record, "CKT")
                val latStr = getSafeByName(record, "Lat.")
                val longStr = getSafeByName(record, "Long.")

                // --- Extract New Extenders (Matched to Sample.csv) ---
                val phase = getSafeByName(record, "Phase")
                val side = getSafeByName(record, "Side")
                val capturedDate = getSafeByName(record, "Captured Date")
                val capturedTime = getSafeByName(record, "Captured Time")

                // --- Extract Environment & Load ---
                val ambTemp = getSafeByName(record, "Ambeint Temp.") // Note: Keeping typo matched to CSV
                val humidity = getSafeByName(record, "Humidity")
                val emissivity = getSafeByName(record, "Emissivity")
                val loadValCkt3 = getSafeByName(record, "Load Data CKT3")
                val loadValCkt4 = getSafeByName(record, "Load Data CKT4")

                // --- Extract Fault Data ---
                val faultTemp = getSafeByName(record, "Fault Temp.")
                val riseTemp = getSafeByName(record, "Rise Temp.")

                // 👇 NEW: Extract the Fault Description column
                val faultDesc = getSafeByName(record, "Fault Description")

                // --- EXTRACTION AND CLEANUP FOR REPORT TYPE ---
                val rawReportType = getSafeByName(record, "report_Type").ifBlank { "tower" }
                val rawFaultStatus = getSafeByName(record, "Fault").ifBlank { "normal" }


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

                        // Load Data (Using CKT3 as general load if needed, plus specifics)
                        loadValue = loadValCkt3,
                        loadDataCkt3 = loadValCkt3,
                        loadDataCkt4 = loadValCkt4,

                        // Faults
                        faultDescription = faultDesc,
                        faultTemp = faultTemp,
                        riseTemp = riseTemp,

                        // The New Report Type Field
                        reportType = rawReportType,
                        faultStatus = rawFaultStatus,

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