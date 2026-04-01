package com.geospatial.processing.domain.usecase

import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.*
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PdfGenerationService(private val repository: GeoRepository) {

    private val logger = LoggerFactory.getLogger(PdfGenerationService::class.java)


    private val primaryColor = Color(33, 47, 60)       // Dark Slate Header
    private val sectionColor = Color(41, 128, 185)     // Professional Blue Partitions
    private val secondaryColor = Color(242, 244, 244)  // Very Light Gray for labels
    private val borderColor = Color(189, 195, 199)     // Soft border lines

    private val titleFont = Font(Font.HELVETICA, 18f, Font.BOLD, primaryColor)
    private val headerFont = Font(Font.HELVETICA, 11f, Font.BOLD, Color.WHITE)
    private val sectionFont = Font(Font.HELVETICA, 10f, Font.BOLD, Color.WHITE)
    private val labelFont = Font(Font.HELVETICA, 9f, Font.BOLD, Color.DARK_GRAY)
    private val valueFont = Font(Font.HELVETICA, 9f, Font.NORMAL, Color.BLACK)

    suspend fun generateBulkZipReport(
        destZipFile: File,
        rootDir: String,
        onProgress: (Int, Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        logger.info("Starting bulk ZIP export to: ${destZipFile.absolutePath}")

        val records = repository.getAllRecords()
        val total = records.size
        if (total == 0) return@withContext 0

        var processedCount = 0

        try {
            ZipOutputStream(FileOutputStream(destZipFile)).use { zipOut ->
                // --- NEW: IN-MEMORY GROUPING BY REPORT TYPE ---
                val groupedRecords = records.groupBy { it.reportType }

                // Iterate through each group (Tower Faults vs Mid Spans)
                for ((type, recordsGroup) in groupedRecords) {

                    // Define the sub-folder name inside the ZIP
                    val folderName = if (type == "mid_span") "Mid_Span_Reports" else "Tower_Fault_Reports"

                    for (record in recordsGroup) {
                        val safeTowerName = record.towerNumber.replace("[\\\\/:*?\"<>|]".toRegex(), "_")

                        // NEW: Inject the folder path directly into the ZipEntry
                        val entryName = "$folderName/Tower_${safeTowerName}_Report.pdf"

                        zipOut.putNextEntry(ZipEntry(entryName))
                        writeSingleTowerPdf(record, rootDir, zipOut)
                        zipOut.closeEntry()

                        processedCount++
                        onProgress(processedCount, total) // Update progress bar accurately
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error exporting ZIP generation", e)
        }
        return@withContext total
    }

    private fun writeSingleTowerPdf(record: GeoRecord, rootDir: String, outputStream: OutputStream) {
        val document = Document(PageSize.A4)
        // Reduced margins to allow for much larger image sizes
        document.setMargins(20f, 20f, 20f, 20f)

        // --- NEW: DYNAMIC REPORT CONFIGURATION ---
        val isMidSpan = record.reportType == "mid_span"

        val documentTitleText = if (isMidSpan) {
            "MID-SPAN THERMAL FAULT REPORT"
        } else {
            "TOWER THERMAL FAULT REPORT"
        }

        // Mapped exactly to your 4 PDF quadrants
        val labelSlot1 = "Location"
        val labelSlot2 = if (isMidSpan) "THERMAL Image" else "Thermal Image"
        val labelSlot3 = if (isMidSpan) "SPAN Image" else "Tower Image"
        val labelSlot4 = "RGB Image"
        // -----------------------------------------

        try {
            val writer = PdfWriter.getInstance(document, outputStream)
            writer.isCloseStream = false // Protects the ZIP stream

            document.open()

            // --- 1. DOCUMENT TITLE ---

            val title = Paragraph(documentTitleText, titleFont).apply {
                alignment = Element.ALIGN_CENTER
                setSpacingAfter(8f)
            }
            document.add(title)

            // --- 2. MASTER RECORD HEADER ---
            val headerTable = PdfPTable(1).apply {
                widthPercentage = 100f
                setSpacingAfter(10f)
            }
            val headerString = "LINE: ${record.lineName.uppercase()}  |  TOWER: ${record.towerNumber}  |  CKT: ${record.circuit}"
            headerTable.addCell(PdfPCell(Phrase(headerString, headerFont)).apply {
                backgroundColor = primaryColor
                setPadding(8f)
                horizontalAlignment = Element.ALIGN_CENTER
                verticalAlignment = Element.ALIGN_MIDDLE
            })
            document.add(headerTable)

            // --- 3. MASSIVE IMAGES GRID (RESTORED TO TOP) ---
            val imagesTable = PdfPTable(2).apply {
                widthPercentage = 100f
                setSpacingAfter(10f)
            }

            // APPLIED DYNAMIC LABELS HERE
            addImageCell(imagesTable, labelSlot1, getImageBytes(record.resolveVisualImage(rootDir)))  // "Location" gets the Map/Visual Image
            addImageCell(imagesTable, labelSlot2, getImageBytes(record.resolveThermalImage(rootDir))) // "Thermal Image" gets the IR Image
            addImageCell(imagesTable, labelSlot3, getImageBytes(record.resolveTowerImage(rootDir)))   // "Tower Image"
            addImageCell(imagesTable, labelSlot4, getImageBytes(record.resolveExtraImage(rootDir)))   // "RGB Image"

            document.add(imagesTable)

            // --- 4. THE METRICS GRID (MOVED TO BOTTOM) ---
            val metricsTable = PdfPTable(4).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.2f, 2.0f, 1.2f, 2.0f))
                setSpacingAfter(10f)
            }

            // PARTITION 1: Location & Timestamp
            metricsTable.addCell(createSectionHeader("LOCATION & CAPTURE DETAILS", 4))
            addMetricCell(metricsTable, "Date Captured", record.capturedDate ?: "N/A")
            addMetricCell(metricsTable, "Time Captured", record.capturedTime ?: "N/A")
            addMetricCell(metricsTable, "Coordinates", "${record.latitude}, ${record.longitude}")
            addMetricCell(metricsTable, "Direction", record.direction ?: "N/A")
            addMetricCell(metricsTable, "Phase", record.phase ?: "N/A")
            addMetricCell(metricsTable, "Side", record.side ?: "N/A")

            // PARTITION 2: Environmental & Load
            metricsTable.addCell(createSectionHeader("ENVIRONMENTAL & LOAD PARAMETERS", 4))
            addMetricCell(metricsTable, "Ambient Temp", formatWithUnit(record.ambientTemp, "°C"))
            addMetricCell(metricsTable, "Humidity", formatWithUnit(record.humidity, "%"))
            addMetricCell(metricsTable, "Emissivity", record.emissivity)
            addMetricCell(metricsTable, "General Load", formatWithUnit(record.loadValue, "A"))
            addMetricCell(metricsTable, "Load CKT3", formatWithUnit(record.loadDataCkt3 ?: "", "A"))
            addMetricCell(metricsTable, "Load CKT4", formatWithUnit(record.loadDataCkt4 ?: "", "A"))

            // PARTITION 3: Fault Analysis
            metricsTable.addCell(createSectionHeader("FAULT ANALYSIS", 4))
            addMetricCell(metricsTable, "Fault Temp", formatWithUnit(record.faultTemp, "°C"))
            addMetricCell(metricsTable, "Rise Temp", formatWithUnit(record.riseTemp ?: "", "°C"))

            // Fault description spans the whole bottom row
            metricsTable.addCell(createLabelCell("Description"))
            metricsTable.addCell(createValueCell(record.faultDescription).apply { colspan = 3 })

            document.add(metricsTable)

            // --- 5. FOOTER ---
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val footer = Paragraph("Generated by GeoSpatial Processor on: $timestamp", Font(Font.HELVETICA, 8f, Font.ITALIC, Color.GRAY)).apply {
                alignment = Element.ALIGN_RIGHT
            }
            document.add(footer)

        } finally {
            if (document.isOpen) document.close()
        }
    }

    // --- HELPER FUNCTIONS ---
    private fun formatWithUnit(value: String, unit: String): String {
        if (value.isBlank() || value.equals("NA", ignoreCase = true)) return "N/A"
        return "$value $unit"
    }

    private fun getImageBytes(source: ImageSource): ByteArray? {
        return try {
            when (source) {
                is ImageSource.FromBlob -> source.bytes
                is ImageSource.FromFile -> source.file.readBytes()
                is ImageSource.Missing -> null
            }
        } catch (e: Exception) {
            logger.warn("Failed to read image bytes: {}", e.message)
            null
        }
    }

    private fun createSectionHeader(title: String, colSpan: Int): PdfPCell {
        return PdfPCell(Phrase(title, sectionFont)).apply {
            colspan = colSpan
            backgroundColor = sectionColor
            setPadding(4f)
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
            borderColor = this@PdfGenerationService.borderColor
        }
    }

    private fun addMetricCell(table: PdfPTable, label: String, value: String) {
        table.addCell(createLabelCell(label))
        table.addCell(createValueCell(value))
    }

    private fun createLabelCell(text: String) = PdfPCell(Phrase(text, labelFont)).apply {
        backgroundColor = secondaryColor
        borderColor = this@PdfGenerationService.borderColor
        setPadding(4f)
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun createValueCell(text: String) = PdfPCell(Phrase(if (text.isBlank()) "N/A" else text, valueFont)).apply {
        borderColor = this@PdfGenerationService.borderColor
        setPadding(4f)
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun addImageCell(table: PdfPTable, label: String, imageBytes: ByteArray?) {
        val cell = PdfPCell().apply {
            borderColor = this@PdfGenerationService.borderColor
            setPadding(4f)
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
        }
        val captionFont = Font(Font.HELVETICA, 10f, Font.BOLD, primaryColor)

        if (imageBytes != null) {
            try {
                val img = Image.getInstance(imageBytes).apply {
                    scaleToFit(220f, 175f)
                    alignment = Element.ALIGN_CENTER
                }
                cell.addElement(img)
                cell.addElement(Paragraph(label, captionFont).apply {
                    alignment = Element.ALIGN_CENTER
                    setSpacingBefore(4f)
                })
            } catch (e: Exception) {
                cell.addElement(Paragraph("Error loading $label", captionFont))
            }
        } else {
            cell.addElement(Paragraph("No Image Provided\n($label)", captionFont).apply {
                alignment = Element.ALIGN_CENTER
            })
        }
        table.addCell(cell)
    }
}