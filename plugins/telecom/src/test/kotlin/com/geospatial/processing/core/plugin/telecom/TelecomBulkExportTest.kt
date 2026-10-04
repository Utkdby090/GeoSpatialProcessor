package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.domain.report.BulkReportExporter
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real telecom report through the real exporter: the ZIP must not depend on the number of threads building PDFs. */
class TelecomBulkExportTest {

    private val dir: File = Files.createTempDirectory("telecom-export").toFile()
    private val plugin = TelecomPlugin()

    private fun jpeg(seed: Int): ByteArray = ByteArrayOutputStream().also {
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color((seed * 37) % 256, (seed * 91) % 256, (seed * 53) % 256)
        g.fillRect(0, 0, 320, 240)
        g.dispose()
        ImageIO.write(img, "jpg", it)
    }.toByteArray()

    private fun tower(i: Int, tower: String = "T$i", type: String = "tower", circuit: String = "1", fault: Boolean = i % 4 == 0) = Asset(
        pluginId = K.PLUGIN_ID, position = i, status = RecordStatus.READY, latitude = 12.0 + i / 1000.0, longitude = 77.0,
        severity = if (fault) Severity.HIGH else Severity.NONE,
        properties = mapOf(
            K.TOWER_NUMBER to tower, K.LINE_NAME to "Line ${i % 5}", K.CIRCUIT to circuit, K.REPORT_TYPE to if (fault) "${type}_fault" else type,
            K.FAULT_STATUS to if (fault) "Fault" else "normal", K.FAULT_DESCRIPTION to "Observation number $i",
            K.LOAD_PREFIX + "Load CKT$circuit" to "${100 + i}A",
        ),
    )

    private fun images(a: Asset): Map<String, ImageSource> =
        plugin.imageSlots(a).withIndex().associate { (n, slot) -> slot.id to ImageSource.FromBlob(jpeg(a.position * 4 + n)) }

    /** Every entry's name with the text and page count of its PDF, so differing layouts or lost content show up. */
    private fun readBack(zip: File): List<Triple<String, Int, String>> = ZipFile(zip).use { z ->
        z.entries().toList().map { e ->
            val reader = PdfReader(z.getInputStream(e).readBytes())
            Triple(e.name, reader.numberOfPages, (1..reader.numberOfPages).joinToString("\n") { PdfTextExtractor(reader).getTextFromPage(it) })
        }
    }

    @Test
    fun `parallel export gives the same reports in the same order as a single thread`() {
        val types = listOf("tower", "mid_span", "repair_sleeve", "earth_wire_joint")
        val assets = (0 until 48).map { tower(it, type = types[it % types.size]) }

        val single = File(dir, "single.zip")
        val parallel = File(dir, "parallel.zip")
        assertEquals(48, BulkReportExporter(plugin).export(assets, ::images, single, parallelism = 1))
        assertEquals(48, BulkReportExporter(plugin).export(assets, ::images, parallel, parallelism = 6))

        val expected = readBack(single)
        val actual = readBack(parallel)
        assertEquals(48, expected.size)
        assertEquals(expected.map { it.first }, actual.map { it.first }, "same entries in the same order")
        for ((e, a) in expected.zip(actual)) {
            assertEquals(1, a.second, "${a.first} is one page")
            assertEquals(e.third, a.third, "${a.first} has the same content")
        }
    }

    @Test
    fun `several rows for one tower each get a report instead of failing the whole export`() {
        // Two circuits and three phases of tower 76: the same tower number, type and fault status.
        val assets = listOf(
            tower(0, tower = "76", circuit = "1", fault = true),
            tower(1, tower = "76", circuit = "1", fault = true),
            tower(2, tower = "76", circuit = "2", fault = true),
            tower(3, tower = "77", circuit = "1", fault = false),
        )
        val zip = File(dir, "dupes.zip")

        assertEquals(4, BulkReportExporter(plugin).export(assets, ::images, zip))

        val names = readBack(zip).map { it.first }
        assertEquals(4, names.toSet().size, "unique names: $names")
        assertEquals(
            listOf(
                "Tower_Reports/FAULT_Tower_76_Report.pdf",
                "Tower_Reports/FAULT_Tower_76_Report_2.pdf",
                "Tower_Reports/FAULT_Tower_76_Report_3.pdf",
                "Tower_Reports/NORMAL_Tower_77_Report.pdf",
            ),
            names,
        )
    }

    @Test
    fun `awkward tower numbers and missing images still produce a valid report each`() {
        val assets = listOf(
            tower(0, tower = "76/0"), tower(1, tower = "A:B*C?"), tower(2, tower = "x".repeat(150)), tower(3, tower = "ÄÖÜ-1"),
        )
        val zip = File(dir, "odd.zip")

        assertEquals(4, BulkReportExporter(plugin).export(assets, { emptyMap() }, zip, parallelism = 3))

        val back = readBack(zip)
        assertEquals(4, back.size)
        assertTrue(back.all { it.second >= 1 }, "every entry is a readable PDF") // very long unbroken numbers may wrap onto a second page
    }

    @Test
    fun `every path separator in a tower number is replaced, so a report can never be written outside its folder`() {
        for (number in listOf("76/0", "76\\0", "a:b", "a*b", "a?b", "a\"b", "a<b>c", "a|b", "..\\..\\evil")) {
            val name = plugin.getReportStrategy().entryName(tower(0, tower = number))
            val file = name.substringAfter('/')
            assertTrue(file.none { it in "/\\:*?\"<>|" }, "$number -> $name")
            assertEquals("Tower_Reports", name.substringBefore('/'))
        }
    }

    @Test
    fun `a load column with an enormous circuit number does not abort the export, and circuits are listed by number`() {
        val a = tower(0).copy(
            properties = tower(0).properties + mapOf(
                K.LOAD_PREFIX + "Load CKT99999999999999999999" to "1A",
                K.LOAD_PREFIX + "Load CKT10" to "10A",
                K.LOAD_PREFIX + "Load CKT2" to "2A",
            ),
        )
        val zip = File(dir, "ckt.zip")

        assertEquals(1, BulkReportExporter(plugin).export(listOf(a), ::images, zip))

        val text = readBack(zip).single().third
        assertTrue(text.indexOf("LOAD CKT2") in 0 until text.indexOf("LOAD CKT10"), "CKT2 comes before CKT10: numeric order")
    }
}
