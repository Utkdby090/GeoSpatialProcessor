package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.ReportStrategy
import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.domain.report.ReportOptions
import com.geospatial.processing.domain.report.parseHexColor
import com.lowagie.text.*
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.awt.Color
import java.io.OutputStream

/** One-page inspection report per tower/span (OpenPDF). Layout ported unchanged from PdfGenerationService. */
class TelecomReport(private val plugin: TelecomPlugin) : ReportStrategy {

    private val primaryColor = Color(33, 47, 60)       // Dark Slate Header
    private val sectionColor = Color(41, 128, 185)     // Professional Blue Partitions
    private val secondaryColor = Color(242, 244, 244)  // Very Light Gray for labels
    private val borderColor = Color(189, 195, 199)     // Soft border lines

    private val titleFont = Font(Font.HELVETICA, 18f, Font.BOLD, primaryColor)
    private val headerFont = Font(Font.HELVETICA, 11f, Font.BOLD, Color.WHITE)
    private val sectionFont = Font(Font.HELVETICA, 10f, Font.BOLD, Color.WHITE)
    private val labelFont = Font(Font.HELVETICA, 9f, Font.BOLD, Color.DARK_GRAY)
    private val valueFont = Font(Font.HELVETICA, 9f, Font.NORMAL, Color.BLACK)

    override fun entryName(asset: Asset): String {
        val type = TelecomPlugin.reportType(asset)
        val (folderName, filePrefix) = when {
            type.contains("mid", ignoreCase = true) -> "Mid_Span_Reports" to "MidSpan"
            type.contains("sleeve", ignoreCase = true) -> "Repair_Sleeve_Reports" to "Repair_Sleeve"
            type.contains("earth", ignoreCase = true) -> "Earth_Wire_Joint_Reports" to "Earth_Wire_Joint"
            else -> "Tower_Reports" to "Tower"
        }
        val safeTowerName = asset.property(K.TOWER_NUMBER).replace("[\\\\/:*?\"<>|]".toRegex(), "_")
        val faultStatusPrefix = if (TelecomPlugin.isFault(asset)) "FAULT" else "NORMAL"
        return "$folderName/${faultStatusPrefix}_${filePrefix}_${safeTowerName}_Report.pdf"
    }

    override fun writePdf(asset: Asset, images: Map<String, ByteArray?>, previous: String?, next: String?, out: OutputStream) =
        writePdf(asset, images, previous, next, ReportOptions(), out)

    override fun writePdf(
        asset: Asset, images: Map<String, ByteArray?>, previous: String?, next: String?,
        options: ReportOptions, out: OutputStream,
    ) {
        val template = options.template
        val branding = options.branding
        val accent = parseHexColor(branding.accentColor)?.let { Color(it) } ?: sectionColor
        val document = Document(PageSize.A4)
        document.setMargins(20f, 20f, 20f, 20f)

        val kind = ReportKind.of(asset)
        val slotLabels = plugin.imageSlots(asset).associate { it.id to it.label }
        fun label(slot: String) = slotLabels.getValue(slot)
        fun p(key: String) = asset.property(key)

        try {
            val writer = PdfWriter.getInstance(document, out)
            writer.isCloseStream = false

            document.open()

            // --- 1. DOCUMENT TITLE ---
            val title = Paragraph(TelecomPlugin.reportTitle(asset).uppercase(), titleFont).apply {
                alignment = Element.ALIGN_CENTER
                setSpacingAfter(8f)
            }
            val logo = branding.logo?.let { runCatching { Image.getInstance(it).apply { scaleToFit(90f, 40f) } }.getOrNull() }
            if (logo == null) {
                document.add(title)
            } else {
                // Logo on the left, title centred in the remaining space.
                val titleTable = PdfPTable(floatArrayOf(1.2f, 4f, 1.2f)).apply { widthPercentage = 100f; setSpacingAfter(8f) }
                titleTable.addCell(PdfPCell(logo, false).apply { border = Rectangle.NO_BORDER; verticalAlignment = Element.ALIGN_MIDDLE })
                titleTable.addCell(PdfPCell().apply {
                    border = Rectangle.NO_BORDER
                    verticalAlignment = Element.ALIGN_MIDDLE
                    addElement(title.apply { setSpacingAfter(0f) })
                })
                titleTable.addCell(PdfPCell(Phrase("")).apply { border = Rectangle.NO_BORDER })
                document.add(titleTable)
            }

            // --- 2. MASTER RECORD HEADER ---
            val headerTable = PdfPTable(1).apply {
                widthPercentage = 100f
                setSpacingAfter(10f)
            }
            val headerString = "LINE: ${p(K.LINE_NAME).uppercase()}  |  TOWER: ${p(K.TOWER_NUMBER)}  |  CKT: ${p(K.CIRCUIT)}"
            headerTable.addCell(PdfPCell(Phrase(headerString, headerFont)).apply {
                backgroundColor = primaryColor
                setPadding(8f)
                horizontalAlignment = Element.ALIGN_CENTER
                verticalAlignment = Element.ALIGN_MIDDLE
            })
            document.add(headerTable)

            if (template.showSeverity) document.add(severityBanner(asset.severity))

            // --- 3. IMAGES GRID ---
            val imagesTable = PdfPTable(2).apply {
                widthPercentage = 100f
                setSpacingAfter(10f)
            }
            if (template.showImages) {
            addImageCell(imagesTable, label(K.SLOT_LOCATION), images[K.SLOT_LOCATION])
            addImageCell(imagesTable, label(K.SLOT_THERMAL), images[K.SLOT_THERMAL])
            if (kind == ReportKind.TOWER) {
                addTowerImageWithNavigatorCell(
                    imagesTable, label(K.SLOT_STRUCTURE), images[K.SLOT_STRUCTURE], p(K.TOWER_NUMBER), previous, next
                )
            } else {
                addImageCell(imagesTable, label(K.SLOT_STRUCTURE), images[K.SLOT_STRUCTURE])
            }
            addImageCell(imagesTable, label(K.SLOT_RGB_ZOOM), images[K.SLOT_RGB_ZOOM])
            document.add(imagesTable)
            }

            // --- 4. THE METRICS GRID ---
            val metricsTable = PdfPTable(4).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(1.2f, 2.0f, 1.2f, 2.0f))
                setSpacingAfter(10f)
            }

            // PARTITION 1: Location & Timestamp
            metricsTable.addCell(createSectionHeader("LOCATION & CAPTURE DETAILS", 4, accent))
            addMetricCell(metricsTable, "Date Captured", p(K.CAPTURED_DATE).ifEmpty { "N/A" })
            addMetricCell(metricsTable, "Time Captured", p(K.CAPTURED_TIME).ifEmpty { "N/A" })
            addMetricCell(metricsTable, "Coordinates", "${asset.latitude}, ${asset.longitude}")
            if (kind == ReportKind.TOWER) addMetricCell(metricsTable, "Direction", p(K.DIRECTION).ifEmpty { "N/A" })
            else addMetricCell(metricsTable, "", "")
            addMetricCell(metricsTable, "Phase", p(K.PHASE).ifEmpty { "N/A" })
            addMetricCell(metricsTable, "Side", p(K.SIDE).ifEmpty { "N/A" })

            // PARTITION 2: Environmental & Load
            metricsTable.addCell(createSectionHeader("ENVIRONMENTAL & LOAD PARAMETERS", 4, accent))
            addMetricCell(metricsTable, "Ambient Temp", formatWithUnit(p(K.AMBIENT_TEMP), "°C"))
            addMetricCell(metricsTable, "Humidity", formatWithUnit(p(K.HUMIDITY), "%"))
            // Emissivity completes the half-row, so we add a blank spacer to finish the row neatly.
            addMetricCell(metricsTable, "Emissivity", p(K.EMISSIVITY))
            addMetricCell(metricsTable, "", "")

            // Dynamic circuits, sorted by the number in "Load CKT3" / "CKT4".
            val sortedCircuits = asset.properties.filterKeys { it.startsWith(K.LOAD_PREFIX) }.entries.sortedBy { entry ->
                Regex("CKT\\s*(\\d+)", RegexOption.IGNORE_CASE).find(entry.key)?.groupValues?.get(1)?.toInt() ?: 0
            }
            sortedCircuits.forEach { (key, value) ->
                addMetricCell(metricsTable, key.removePrefix(K.LOAD_PREFIX).uppercase(), value)
            }
            // An odd number of circuits leaves the 4-column grid two cells short; pad it.
            if (sortedCircuits.size % 2 != 0) addMetricCell(metricsTable, "", "")

            // PARTITION 3: Fault Analysis
            metricsTable.addCell(createSectionHeader("FAULT ANALYSIS", 4, accent))
            addMetricCell(metricsTable, "Fault Temp", formatWithUnit(p(K.FAULT_TEMP), "°C"))
            addMetricCell(metricsTable, "Rise Temp", formatWithUnit(p(K.RISE_TEMP), "°C"))
            if (template.showSeverity) {
                addMetricCell(metricsTable, "Severity", asset.severity.label)
                addMetricCell(metricsTable, "", "")
            }
            // Fault description spans the whole bottom row
            metricsTable.addCell(createLabelCell("Description"))
            metricsTable.addCell(createValueCell(p(K.FAULT_DESCRIPTION)).apply { colspan = 3 })
            document.add(metricsTable)

            // --- 5. FOOTER ---
            val company = branding.companyName.ifBlank { p(K.COMPANY_NAME) }
            val displayCompany = if (company.isNotBlank()) company.uppercase() else "GEOSPATIAL PROCESSING"
            document.add(Paragraph("Report created by : $displayCompany", Font(Font.HELVETICA, 10f, Font.BOLD, Color.GRAY)).apply {
                alignment = Element.ALIGN_RIGHT
            })
        } finally {
            if (document.isOpen) document.close()
        }
    }

    // --- HELPER FUNCTIONS ---
    private fun formatWithUnit(value: String, unit: String): String {
        if (value.isBlank() || value.equals("NA", ignoreCase = true)) return "N/A"
        return "$value $unit"
    }

    private fun severityBanner(severity: Severity): PdfPTable {
        val color = when (severity) {
            Severity.NONE -> Color(107, 114, 128)
            Severity.LOW -> Color(202, 138, 4)
            Severity.MEDIUM -> Color(234, 88, 12)
            Severity.HIGH -> Color(220, 38, 38)
            Severity.CRITICAL -> Color(127, 29, 29)
        }
        return PdfPTable(1).apply {
            widthPercentage = 100f
            setSpacingAfter(10f)
            addCell(PdfPCell(Phrase("SEVERITY: ${severity.label.uppercase()}", headerFont)).apply {
                backgroundColor = color
                setPadding(5f)
                horizontalAlignment = Element.ALIGN_CENTER
                border = Rectangle.NO_BORDER
            })
        }
    }

    private fun createSectionHeader(title: String, colSpan: Int, accent: Color = sectionColor): PdfPCell =
        PdfPCell(Phrase(title, sectionFont)).apply {
            colspan = colSpan
            backgroundColor = accent
            setPadding(4f)
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
            borderColor = this@TelecomReport.borderColor
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
        borderColor = this@TelecomReport.borderColor
        setPadding(4f)
        verticalAlignment = Element.ALIGN_MIDDLE
    }

    private fun createValueCell(text: String) = PdfPCell(Phrase(if (text.isBlank()) "N/A" else text, valueFont)).apply {
        borderColor = this@TelecomReport.borderColor
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
            borderColor = this@TelecomReport.borderColor
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
                imageCell.addElement(Image.getInstance(imageBytes).apply {
                    scaleToFit(210f, 170f)
                    alignment = Element.ALIGN_CENTER
                })
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
            borderColor = this@TelecomReport.borderColor
            setPadding(4f)
            horizontalAlignment = Element.ALIGN_CENTER
            verticalAlignment = Element.ALIGN_MIDDLE
        }
        val captionFont = Font(Font.HELVETICA, 10f, Font.BOLD, primaryColor)

        if (imageBytes != null) {
            try {
                cell.addElement(Image.getInstance(imageBytes).apply {
                    scaleToFit(220f, 175f)
                    alignment = Element.ALIGN_CENTER
                })
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
