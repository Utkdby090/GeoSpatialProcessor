package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.domain.report.ReportBranding
import com.geospatial.processing.domain.report.ReportOptions
import com.geospatial.processing.domain.report.ReportTemplate
import com.geospatial.processing.domain.report.parseHexColor
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelecomReportTemplateTest {

    private val strategy = TelecomPlugin().getReportStrategy()

    private val tower = Asset(
        pluginId = K.PLUGIN_ID, position = 0, latitude = 12.5, longitude = 77.5, severity = Severity.HIGH,
        properties = mapOf(K.TOWER_NUMBER to "76_0", K.LINE_NAME to "Line A", K.CIRCUIT to "1", K.COMPANY_NAME to "Data Co"),
    )

    private fun png(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun render(options: ReportOptions?, images: Map<String, ByteArray?> = emptyMap()): Pair<String, ByteArray> {
        val out = ByteArrayOutputStream()
        if (options == null) strategy.writePdf(tower, images, null, null, out)
        else strategy.writePdf(tower, images, null, null, options, out)
        val bytes = out.toByteArray()
        val reader = PdfReader(bytes)
        assertEquals(1, reader.numberOfPages)
        return PdfTextExtractor(reader).getTextFromPage(1) to bytes
    }

    @Test
    fun `the standard template is exactly the old report`() {
        val (old, _) = render(null)
        val (standard, _) = render(ReportOptions())

        assertEquals(old, standard)
        assertFalse(standard.contains("SEVERITY"))
    }

    @Test
    fun `detailed and summary show the severity banner and rating`() {
        for (template in listOf(ReportTemplate.DETAILED, ReportTemplate.SUMMARY)) {
            val (text, _) = render(ReportOptions(template))
            assertTrue(text.contains("SEVERITY: HIGH"), "$template banner")
            assertTrue(text.contains("Severity") && text.contains("High"), "$template fault analysis row")
        }
    }

    @Test
    fun `summary leaves the images out`() {
        val images = mapOf(K.SLOT_THERMAL to png(), K.SLOT_LOCATION to png())
        val (standardText, standard) = render(ReportOptions(ReportTemplate.STANDARD), images)
        val (summaryText, summary) = render(ReportOptions(ReportTemplate.SUMMARY), images)

        assertTrue(summary.size < standard.size, "no embedded images")
        assertTrue(standardText.contains("Thermal Image"), "standard captions the thermal slot")
        assertFalse(summaryText.contains("Thermal Image"))
        assertTrue(summaryText.contains("FAULT ANALYSIS"))
    }

    @Test
    fun `branding company replaces the one in the data, and a blank one keeps it`() {
        assertTrue(render(ReportOptions(branding = ReportBranding(companyName = "Acme Grid"))).first.contains("ACME GRID"))
        assertTrue(render(ReportOptions()).first.contains("DATA CO"))
    }

    @Test
    fun `a logo is drawn, and an unreadable logo or bad accent is ignored`() {
        val plain = render(ReportOptions()).second
        val withLogo = render(ReportOptions(branding = ReportBranding(logo = png(), accentColor = "#C0392B"))).second
        val broken = render(ReportOptions(branding = ReportBranding(logo = byteArrayOf(1, 2, 3), accentColor = "not a colour")))

        assertTrue(withLogo.size > plain.size)
        assertTrue(broken.first.contains("LINE: LINE A"), "still renders")
    }

    @Test
    fun `hex colours`() {
        assertEquals(0x2980B9, parseHexColor("#2980B9"))
        assertEquals(0x2980B9, parseHexColor(" 2980b9 "))
        assertNull(parseHexColor("#FFF"))
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor(""))
    }

    @Test
    fun `unknown stored template names fall back to standard`() {
        assertEquals(ReportTemplate.STANDARD, ReportTemplate.fromName("FUTURE_THING"))
        assertEquals(ReportTemplate.SUMMARY, ReportTemplate.fromName("SUMMARY"))
    }
}
