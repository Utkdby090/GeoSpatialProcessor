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

    // 1. Initialize the Logger for this specific class
    private val logger = LoggerFactory.getLogger(PdfGenerationService::class.java)
    private val primaryColor = Color(33, 47, 60)
    private val secondaryColor = Color(236, 240, 241)
    private val borderColor = Color(189, 195, 199)


    private val titleFont = Font(Font.HELVETICA, 20f, Font.BOLD, primaryColor)
    private val headerFont = Font(Font.HELVETICA, 12f, Font.BOLD, Color.WHITE)
    private val labelFont = Font(Font.HELVETICA, 9f, Font.BOLD, Color.DARK_GRAY)
    private val valueFont = Font(Font.HELVETICA, 9f, Font.NORMAL, Color.BLACK)

    /**
     * NEW: High-Performance Bulk ZIP Exporter
     * Streams individual PDFs directly into a ZIP file without writing temp files to disk.
     */
    suspend fun generateBulkZipReport(
        destZipFile: File,
        rootDir: String,
        onProgress: (Int, Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        logger.info("Starting bulk ZIP export to: ${destZipFile.absolutePath}")
        logger.debug("Scanning root directory: {}", rootDir)

        val records = repository.getAllRecords().filter { it.isDynamicallyReady(rootDir) }
        val total = records.size
        if (total == 0) return@withContext 0

        // Open a single streaming pipeline to the ZIP file

        try {
            ZipOutputStream(FileOutputStream(destZipFile)).use { zipOut ->
                records.forEachIndexed { index, record ->

                    // 1. Create a safe file name for this specific tower
                    val safeTowerName = record.towerNumber.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                    val entryName = "Tower_${safeTowerName}_Report.pdf"

                    // 2. Open a new slot in the ZIP file
                    zipOut.putNextEntry(ZipEntry(entryName))

                    // 3. Render the PDF directly into that slot
                    writeSingleTowerPdf(record, rootDir, zipOut)

                    // 4. Close the slot (flushes memory)
                    zipOut.closeEntry()

                    logger.info("Successfully exported {} records to ZIP.", total)

                    // 5. Report progress back to the UI
                    onProgress(index + 1, total)
                }
            }
        } catch (e: Exception) {
            logger.error("Error exporting ZIP generation", e)
        }

        return@withContext total
    }

    /**
     * Extracted PDF drawing logic for a SINGLE tower.
     * Writes directly to the provided OutputStream (which is our ZIP stream).
     */
    private fun writeSingleTowerPdf(record: GeoRecord, rootDir: String, outputStream: OutputStream) {
        val document = Document(PageSize.A4)
        document.setMargins(36f, 36f, 36f, 36f)

        try {
            val writer = PdfWriter.getInstance(document, outputStream)
            // CRITICAL: Prevent OpenPDF from closing our ZIP stream when it finishes this document!
            writer.isCloseStream = false

            document.open()

            // --- DOCUMENT TITLE ---
            val title = Paragraph("TOWER INSPECTION REPORT", titleFont)
            title.alignment = Element.ALIGN_CENTER
            title.setSpacingAfter(25f)
            document.add(title)

            // --- RECORD HEADER ---
            val headerTable = PdfPTable(1).apply { widthPercentage = 100f }
            val headerCell = PdfPCell(Phrase("LINE: ${record.lineName.uppercase()}  |  TOWER: ${record.towerNumber}  |  CIRCUIT: ${record.circuit}", headerFont)).apply {
                backgroundColor = primaryColor
                setPadding(8f)
                verticalAlignment = Element.ALIGN_MIDDLE
            }
            headerTable.addCell(headerCell)
            document.add(headerTable)

            // --- IMAGES GRID ---
            val imagesTable = PdfPTable(2).apply {
                widthPercentage = 100f
                setSpacingBefore(10f)
                setSpacingAfter(15f)
            }

            addImageCell(imagesTable, "Thermal Analysis", getImageBytes(record.resolveThermalImage(rootDir)))
            addImageCell(imagesTable, "Visual (RGB)", getImageBytes(record.resolveVisualImage(rootDir)))
            addImageCell(imagesTable, "Full Tower Structure", getImageBytes(record.resolveTowerImage(rootDir)))
            addImageCell(imagesTable, "Supplementary Detail", getImageBytes(record.resolveExtraImage(rootDir)))
            document.add(imagesTable)

            // --- METRICS ---
            val metricsTable = PdfPTable(4).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.5f, 2.5f, 1.5f, 2.5f))
                setSpacingAfter(30f)
            }

            addMetricCell(metricsTable, "Coordinates", "${record.latitude}, ${record.longitude}")
            addMetricCell(metricsTable, "Load Value", formatWithUnit(record.loadValue, "A"))
            addMetricCell(metricsTable, "Ambient Temp", formatWithUnit(record.ambientTemp, "°C"))
            addMetricCell(metricsTable, "Humidity", formatWithUnit(record.humidity, "%"))
            addMetricCell(metricsTable, "Emissivity", record.emissivity)
            addMetricCell(metricsTable, "Fault Temp", formatWithUnit(record.faultTemp, "°C"))

            val faultLabelCell = createLabelCell("Fault Analysis")
            metricsTable.addCell(faultLabelCell)
            val faultValueCell = createValueCell(record.faultDescription).apply { colspan = 3 }
            metricsTable.addCell(faultValueCell)
            document.add(metricsTable)

            // --- FOOTER ---
            val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            val footer = Paragraph("\nGenerated on: $timestamp", Font(Font.HELVETICA, 8f, Font.ITALIC, Color.GRAY))
            footer.alignment = Element.ALIGN_RIGHT
            document.add(footer)

        } finally {
            // Close the document (flushes data to the ZipOutputStream), but writer.isCloseStream = false protects the ZIP
            if (document.isOpen) {
                document.close()
            }
        }
    }

    // --- HELPER FUNCTIONS ---
    private fun formatWithUnit(value: String, unit: String): String {
        if (value.isBlank() || value.equals("NA", ignoreCase = true)) return value
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
            null }
    }

    private fun addMetricCell(table: PdfPTable, label: String, value: String) {
        table.addCell(createLabelCell(label))
        table.addCell(createValueCell(value))
    }

    private fun createLabelCell(text: String) = PdfPCell(Phrase(text, labelFont)).apply {
        backgroundColor = secondaryColor
        borderColor = this@PdfGenerationService.borderColor
        setPadding(6f)
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun createValueCell(text: String) = PdfPCell(Phrase(if (text.isBlank()) "N/A" else text, valueFont)).apply {
        borderColor = this@PdfGenerationService.borderColor
        setPadding(6f)
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun addImageCell(table: PdfPTable, label: String, imageBytes: ByteArray?) {
        val cell = PdfPCell().apply {
            borderColor = this@PdfGenerationService.borderColor
            setPadding(5f)
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
            fixedHeight = 180f
        }
        val captionFont = Font(Font.HELVETICA, 9f, Font.BOLD, primaryColor)

        if (imageBytes != null) {
            try {
                val img = Image.getInstance(imageBytes).apply {
                    scaleToFit(220f, 140f)
                    alignment = Element.ALIGN_CENTER
                }
                cell.addElement(img)
                val caption = Paragraph(label, captionFont).apply {
                    alignment = Element.ALIGN_CENTER
                    setSpacingBefore(5f)
                }
                cell.addElement(caption)
            } catch (e: Exception) {
                cell.addElement(Paragraph("Error loading $label", captionFont))
            }
        } else {
            val missingText = Paragraph("No Image Provided\n($label)", captionFont).apply { alignment = Element.ALIGN_CENTER }
            cell.addElement(missingText)
        }
        table.addCell(cell)
    }
}