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
    /**
     * Imports CSV data with a Progress Callback.
     * Stripped of image processing for lightning-fast text-only imports.
     * @param file The CSV file to import.
     * @param onProgress Lambda that receives a float from 0.0 to 1.0.
     * @return Count of successfully imported records.
     */
    suspend fun importCsvFile(file: File, onProgress: (Float) -> Unit): Int = withContext(Dispatchers.IO) {
        var count = 0
        logger.info("Starting CSV import from file: {}", file.absolutePath)
        try {
            // 1. Parse the file
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

            // Get all records to calculate progress
            val records = parser.records
            val total = records.size
            if (total == 0) {
                // 3. Log a warning if the user uploads an empty file
                logger.warn("Aborting import: CSV file is empty or missing data rows.")

            }

            logger.info("Found {} records in CSV. Beginning database insertion.", total)

            // 2. Loop through records
            for ((index, record) in records.withIndex()) {

                // --- Extract Data Fields based on mapped CSV indices ---
                // 0: Sr. No. (Ignored)
                val towerNum = getSafeByName(record, "Tower No.")
                val lineName = getSafeByName(record, "Line Name")
                val circuit = getSafeByName(record, "CKT")
                val latStr = getSafeByName(record, "Lat.")
                val longStr = getSafeByName(record, "Long.")
                val ambTemp = getSafeByName(record, "Ambeint Temp.")
                val faultTemp = getSafeByName(record, "Fault Temp.")
                val humidity = getSafeByName(record, "Humidity")
                val emissivity = getSafeByName(record, "Emissivity")
                val loadVal = getSafeByName(record, "Load Data CKT3")

                // --- Create Record ---
                if (lineName.isNotBlank() && towerNum.isNotBlank()) {

                    val newRecord = GeoRecord(
                        id = 0, // 0 = New Record
                        lineName = lineName,
                        towerNumber = towerNum,
                        circuit = circuit,
                        latitude = latStr.toDoubleOrNull() ?: 0.0,
                        longitude = longStr.toDoubleOrNull() ?: 0.0,

                        humidity = humidity,
                        emissivity = emissivity,
                        ambientTemp = ambTemp,
                        loadValue = loadVal,

                        faultDescription = "", // No specific description column in this sample
                        faultTemp = faultTemp,

                        // ALL IMAGES START AS NULL (No manual overrides yet)
                        // The app will use the hybrid architecture to resolve local files dynamically.
                        thermalImage = null,
                        visualImage = null,
                        towerImage = null,
                        extraImage = null,

                        // Status defaults to DRAFT.
                        // Actual readiness is evaluated during UI render or PDF export when checking ImageSource.
                        status = RecordStatus.DRAFT
                    )

                    repository.saveRecord(newRecord)
                    count++
                }

                // --- Report Progress ---
                if (total > 0) {
                    onProgress(((index + 1) / total).toFloat())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("CSV Import Error: ${e.message}")

        }

        // Ensure progress hits 100% at the end
        if (count > 0) onProgress(1.0f)

        return@withContext count
    }

    /** Helper: Get column value safely */
    private fun getSafe(record: CSVRecord, index: Int): String {
        return if (index < record.size()) record.get(index) else ""
    }


    /** * Helper: Gets the column value safely using the exact Column Header Name.
     * This prevents data from shifting if the CSV column order changes.
     */
    private fun getSafeByName(record: CSVRecord, columnName: String): String {
        return try {
            // Checks if the CSV actually has this header before trying to grab it
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