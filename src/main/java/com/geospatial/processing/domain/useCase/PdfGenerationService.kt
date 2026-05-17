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

        val masterRecords = repository.getAllRecords()
        val readyRecords = masterRecords.filter { it.status == RecordStatus.READY }
        val total = readyRecords.size

        if (total == 0) {
            logger.warn("No READY records found to export.")
            return@withContext 0
        }

        var processedCount = 0

        try {
            ZipOutputStream(FileOutputStream(destZipFile)).use { zipOut ->
                val groupedRecords = readyRecords.groupBy { it.reportType }

                for ((type, recordsGroup) in groupedRecords) {

                    // The destructured Pair dynamically assigns the folder and the prefix
                    val (folderName, filePrefix) = when {
                        type.contains("mid", ignoreCase = true) -> Pair("Mid_Span_Reports", "MidSpan")
                        type.contains("sleeve", ignoreCase = true) -> Pair("Repair_Sleeve_Reports", "Repair_Sleeve")
                        type.contains("earth", ignoreCase = true) -> Pair("Earth_Wire_Joint_Reports", "Earth_Wire_Joint")
                        else -> Pair("Tower_Reports", "Tower")
                    }

                    for (record in recordsGroup) {
                        val masterIndex = masterRecords.indexOfFirst { it.id == record.id }
                        val prevItem = masterRecords.getOrNull(masterIndex - 1)?.towerNumber
                        val nextItem = masterRecords.getOrNull(masterIndex + 1)?.towerNumber

                        val safeTowerName = record.towerNumber.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                        val faultStatusPrefix = if (record.isFault) "FAULT" else "NORMAL"

                        // Using the dynamic filePrefix variable instead of hardcoded "Tower"
                        val entryName = "$folderName/${faultStatusPrefix}_${filePrefix}_${safeTowerName}_Report.pdf"

                        zipOut.putNextEntry(ZipEntry(entryName))
                        writeSingleTowerPdf(record, rootDir, zipOut, prevItem, nextItem)
                        zipOut.closeEntry()

                        processedCount++
                        onProgress(processedCount, total)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error exporting ZIP generation", e)
        }
        return@withContext total
    }

    private fun writeSingleTowerPdf(
        record: GeoRecord,
        rootDir: String,
        outputStream: OutputStream,
        prevItem: String?,
        nextItem: String?
    ) {
        val document = Document(PageSize.A4)
        document.setMargins(20f, 20f, 20f, 20f)

        val type = record.reportType.lowercase()
        val isMidSpan = type.contains("mid")
        val isSleeve = type.contains("sleeve")
        val isEarthWire = type.contains("earth")

        val documentTitleText = record.resolvedReportTitle.uppercase()

        val labelSlot1 = "Location"
        val labelSlot2 = if (isMidSpan) "THERMAL Image" else "Thermal Image"
        val labelSlot3 = when {
            isMidSpan -> "SPAN Image"
            isSleeve -> "SLEEVE Image"
            isEarthWire -> "EARTH WIRE Image"
            else -> "Tower Image"
        }
        val labelSlot4 = "RGB Image"

        try {
            val writer = PdfWriter.getInstance(document, outputStream)
            writer.isCloseStream = false

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

            // --- 3. IMAGES GRID ---
            val imagesTable = PdfPTable(2).apply {
                widthPercentage = 100f
                setSpacingAfter(10f)
            }

            // Top Row Images
            addImageCell(imagesTable, labelSlot1, getImageBytes(record.resolveVisualImage(rootDir)))
            addImageCell(imagesTable, labelSlot2, getImageBytes(record.resolveThermalImage(rootDir)))

            // Bottom Left: Conditional Navigator Logic
            val slot3ImageBytes = getImageBytes(record.resolveTowerImage(rootDir))
            if (isMidSpan || isSleeve || isEarthWire ) {
                addImageCell(imagesTable, labelSlot3, slot3ImageBytes)
            } else {
                addTowerImageWithNavigatorCell(
                    imagesTable,
                    labelSlot3,
                    slot3ImageBytes,
                    record.towerNumber,
                    prevItem,
                    nextItem
                )
            }

            // Bottom Right Image
            addImageCell(imagesTable, labelSlot4, getImageBytes(record.resolveExtraImage(rootDir)))

            document.add(imagesTable)

            // --- 4. THE METRICS GRID ---
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

            if (isMidSpan || isSleeve || isEarthWire) {
                addMetricCell(metricsTable, "", "")
            } else {
                addMetricCell(metricsTable, "Direction", record.direction ?: "N/A")
            }

            addMetricCell(metricsTable, "Phase", record.phase ?: "N/A")
            addMetricCell(metricsTable, "Side", record.side ?: "N/A")

            // PARTITION 2: Environmental & Load
            metricsTable.addCell(createSectionHeader("ENVIRONMENTAL & LOAD PARAMETERS", 4))
            addMetricCell(metricsTable, "Ambient Temp", formatWithUnit(record.ambientTemp, "°C"))
            addMetricCell(metricsTable, "Humidity", formatWithUnit(record.humidity, "%"))

            // Emissivity completes the half-row, so we add a blank spacer to finish the row neatly.
            addMetricCell(metricsTable, "Emissivity", record.emissivity)
            addMetricCell(metricsTable, "", "")

            // --- NEW: DYNAMIC CIRCUITS LOOP (BULLETPROOF SORTING) ---
            var circuitCount = 0

            // This extracts the actual number from strings like "Load CKT3" or "CKT4"
            // and sorts them mathematically, ensuring perfect sequential order every time.
            val sortedCircuits = record.dynamicCircuits.entries.sortedBy { entry ->
                val match = Regex("CKT\\s*(\\d+)", RegexOption.IGNORE_CASE).find(entry.key)
                match?.groupValues?.get(1)?.toInt() ?: 0
            }

            sortedCircuits.forEach { (headerName, headerValue) ->
                addMetricCell(metricsTable, headerName.uppercase(), headerValue)
                circuitCount++
            }

            // If an odd number of dynamic circuits were added, the 4-column grid is missing 2 cells.
            // This spacer prevents the table from breaking or misaligning.
            if (circuitCount % 2 != 0) {
                addMetricCell(metricsTable, "", "")
            }

            // PARTITION 3: Fault Analysis
            metricsTable.addCell(createSectionHeader("FAULT ANALYSIS", 4))
            addMetricCell(metricsTable, "Fault Temp", formatWithUnit(record.faultTemp, "°C"))
            addMetricCell(metricsTable, "Rise Temp", formatWithUnit(record.riseTemp ?: "", "°C"))

            // Fault description spans the whole bottom row
            metricsTable.addCell(createLabelCell("Description"))
            metricsTable.addCell(createValueCell(record.faultDescription).apply { colspan = 3 })

            document.add(metricsTable)

            // --- 5. FOOTER ---
            val displayCompany = if (record.companyName.isNotBlank()) record.companyName.uppercase() else "GEOSPATIAL PROCESSING"
            val footerText = "Report created by : $displayCompany"
            val footer = Paragraph(footerText, Font(Font.HELVETICA, 10f, Font.BOLD, Color.GRAY)).apply {
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
        if (label.isBlank()) {
            table.addCell(PdfPCell(Phrase("")).apply { border = Rectangle.NO_BORDER })
            table.addCell(PdfPCell(Phrase("")).apply { border = Rectangle.NO_BORDER })
        } else {
            table.addCell(createLabelCell(label))
            table.addCell(createValueCell(value))
        }
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

    // --- SPECIALIZED NAVIGATOR IMAGE BUILDER ---
    private fun addTowerImageWithNavigatorCell(
        table: PdfPTable,
        label: String,
        imageBytes: ByteArray?,
        currentItem: String,
        prevItem: String?,
        nextItem: String?
    ) {
        val outerCell = PdfPCell().apply {
            borderColor = this@PdfGenerationService.borderColor
            setPadding(4f)
            verticalAlignment = Element.ALIGN_MIDDLE
        }

        val nestedTable = PdfPTable(2).apply {
            widthPercentage = 100f
            setWidths(floatArrayOf(4.2f, 0.8f))
        }

        val imageCell = PdfPCell().apply {
            border = Rectangle.NO_BORDER
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
        }
        val captionFont = Font(Font.HELVETICA, 10f, Font.BOLD, primaryColor)

        if (imageBytes != null) {
            try {
                val img = Image.getInstance(imageBytes).apply {
                    scaleToFit(210f, 170f)
                    alignment = Element.ALIGN_CENTER
                }
                imageCell.addElement(img)
                imageCell.addElement(Paragraph(label, captionFont).apply {
                    alignment = Element.ALIGN_CENTER
                    setSpacingBefore(4f)
                })
            } catch (e: Exception) {
                imageCell.addElement(Paragraph("Error loading image", captionFont))
            }
        } else {
            imageCell.addElement(Paragraph("No Image Provided\n($label)", captionFont).apply {
                alignment = Element.ALIGN_CENTER
            })
        }
        nestedTable.addCell(imageCell)

        val navCell = PdfPCell().apply {
            border = Rectangle.NO_BORDER
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
            backgroundColor = secondaryColor
            setPadding(8f)
        }

        val grayFont = Font(Font.HELVETICA, 8f, Font.NORMAL, Color.DARK_GRAY)
        val boldFont = Font(Font.HELVETICA, 11f, Font.BOLD, primaryColor)

        if (prevItem != null) {
            navCell.addElement(Paragraph(prevItem, grayFont).apply { alignment = Element.ALIGN_CENTER })
            navCell.addElement(Paragraph("▲", grayFont).apply { alignment = Element.ALIGN_CENTER })
        } else {
            navCell.addElement(Paragraph(" \n ", grayFont))
        }

        navCell.addElement(Paragraph(" ", grayFont))

        navCell.addElement(Paragraph(currentItem, boldFont).apply { alignment = Element.ALIGN_CENTER })
        navCell.addElement(Paragraph(" ", grayFont))

        if (nextItem != null) {
            navCell.addElement(Paragraph("▼", grayFont).apply { alignment = Element.ALIGN_CENTER })
            navCell.addElement(Paragraph(nextItem, grayFont).apply { alignment = Element.ALIGN_CENTER })
        } else {
            navCell.addElement(Paragraph(" \n ", grayFont))
        }

        nestedTable.addCell(navCell)
        outerCell.addElement(nestedTable)
        table.addCell(outerCell)
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