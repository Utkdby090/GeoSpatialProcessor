package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.domain.thermal.ThermalStats
import com.geospatial.processing.domain.thermal.ThermalOverrides
import com.geospatial.processing.domain.thermal.Pixel
import com.geospatial.processing.core.plugin.CoreFields
import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.Severity
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelecomPluginTest {

    private val plugin = TelecomPlugin()
    private val dir: File = Files.createTempDirectory("telecom").toFile()

    private fun asset(vararg props: Pair<String, String>) =
        Asset(pluginId = K.PLUGIN_ID, position = 0, latitude = 12.5, longitude = 77.5, properties = mapOf(*props))

    // --- CSV import -----------------------------------------------------------------------------

    @Test
    fun `csv rows become drafts with properties, coordinates and load columns`() {
        val csv = File(dir, "survey.csv").apply {
            writeText(
                "Sr. No.,Tower No.,Line Name,CKT,Lat.,Long.,Phase,Ambeint Temp.,report_Type,Fault,Load CKT1,Load CKT2\n" +
                    "1,76_0,Line A,1,12.5,77.5,R,31,tower_fault,Fault,120A,98A\n" +
                    "2,,Line A,1,0,0,R,31,tower,,1A,2A\n" +            // no tower number -> skipped
                    "3,77_0,Line A,2,bad,77.6,Y,30,,,110A,90A\n"
            )
        }
        val progress = mutableListOf<Float>()

        val drafts = plugin.getCsvImportStrategy().parse(csv) { progress += it }

        assertEquals(2, drafts.size)
        val first = drafts[0]
        assertEquals(12.5, first.latitude)
        assertEquals("76_0", first.properties[K.TOWER_NUMBER])
        assertEquals("31", first.properties[K.AMBIENT_TEMP])
        assertEquals("tower_fault", first.properties[K.REPORT_TYPE])
        assertEquals("120A", first.properties[K.LOAD_PREFIX + "Load CKT1"])
        assertFalse(first.properties.keys.any { it.contains("Sr. No.") }, "standard headers are not load data")

        val second = drafts[1]
        assertEquals(0.0, second.latitude, "unparseable coordinates default to 0")
        assertEquals("tower", second.properties[K.REPORT_TYPE], "blank report type defaults to tower")
        assertEquals("normal", second.properties[K.FAULT_STATUS])
        assertEquals(1.0f, progress.last())
    }

    @Test
    fun `csv coordinates may be degrees-minutes-seconds and letter-marked columns are put the right way round`() {
        val csv = File(dir, "dms.csv").apply {
            writeText(
                "Tower No.,Line Name,Lat.,Long.\n" +
                    "76_0,Line A,\"27°30'00\"\"N\",\"73°54'00\"\"E\"\n" +
                    "76_1,Line A,73 54 E,27 30 N\n"
            )
        }

        val drafts = plugin.getCsvImportStrategy().parse(csv) {}

        drafts.forEach {
            assertEquals(27.5, it.latitude, 1e-9)
            assertEquals(73.9, it.longitude, 1e-9)
        }
    }

    // --- Presentation / titles ------------------------------------------------------------------

    @Test
    fun `report titles follow report type and fault status`() {
        fun title(type: String, fault: String) = plugin.present(asset(K.REPORT_TYPE to type, K.FAULT_STATUS to fault)).reportTitle
        assertEquals("Tower Fault report", title("tower_fault", "Fault"))
        assertEquals("Tower no fault report", title("tower_fault", "Normal"), "both columns must say fault")
        assertEquals("Tower no fault report", title("tower", "Fault"))
        assertEquals("Mid Span Fault report", title("mid_span_fault", "fault"))
        assertEquals("Sleeve no fault report", title("repair_sleeve", "normal"))
        assertEquals("Earth Wire Joint report", title("earth_wire", "normal"))
        assertEquals("Tower no fault report", title("", "normal"), "blank type is a tower")
    }

    @Test
    fun `list labels and navigator`() {
        val tower = plugin.present(asset(K.TOWER_NUMBER to "76_0", K.LINE_NAME to "Line A"))
        assertEquals("Tower 76_0", tower.listTitle)
        assertEquals("Line A", tower.listSubtitle)
        assertEquals("76_0", tower.sequenceLabel)
        assertTrue(tower.showsSequenceNavigator)

        val span = plugin.present(asset(K.REPORT_TYPE to "mid_span", K.CIRCUIT to "2"))
        assertEquals("Circuit: 2", span.listSubtitle)
        assertFalse(span.showsSequenceNavigator)
    }

    @Test
    fun `schema has every field plus this asset's load columns`() {
        val schema = plugin.getPropertySchema(asset(K.LOAD_PREFIX + "Load CKT1" to "120A"))
        val keys = schema.map { it.key }
        assertTrue(keys.containsAll(listOf(K.LINE_NAME, K.TOWER_NUMBER, K.FAULT_DESCRIPTION, CoreFields.LATITUDE)))
        assertEquals("Load CKT1", schema.single { it.key == K.LOAD_PREFIX + "Load CKT1" }.label)
        assertEquals(setOf(K.LINE_NAME, K.TOWER_NUMBER, CoreFields.LATITUDE, CoreFields.LONGITUDE),
            schema.filter { it.isRequired }.map { it.key }.toSet())
    }

    @Test
    fun `image slot labels depend on report type`() {
        assertEquals(listOf("Location", "Thermal Image", "Tower Image", "RGB Image"),
            plugin.imageSlots(asset()).map { it.label })
        assertEquals("THERMAL Image", plugin.imageSlots(asset(K.REPORT_TYPE to "mid_span"))[1].label)
        assertEquals("SLEEVE Image", plugin.imageSlots(asset(K.REPORT_TYPE to "repair_sleeve"))[2].label)
    }

    @Test
    fun `folder images map onto slot ids`() {
        File(dir, "76_0").apply { mkdirs(); listOf("thermal.jpg", "zoom.jpg", "tower.jpg", "overview.jpg").forEach { File(this, it).writeText("x") } }

        val found = plugin.resolveFolderImages(dir.path, asset(K.TOWER_NUMBER to "76_0"))

        assertEquals(setOf(K.SLOT_THERMAL, K.SLOT_RGB_ZOOM, K.SLOT_STRUCTURE, K.SLOT_LOCATION), found.keys)
        assertEquals("tower.jpg", found.getValue(K.SLOT_STRUCTURE).name)
    }

    // --- PDF ------------------------------------------------------------------------------------

    @Test
    fun `entry names group by report type and flag faults`() {
        val report = plugin.getReportStrategy()
        assertEquals("Tower_Reports/FAULT_Tower_76_0_Report.pdf",
            report.entryName(asset(K.TOWER_NUMBER to "76/0", K.REPORT_TYPE to "tower_fault", K.FAULT_STATUS to "Fault")), "path-unsafe characters in the tower number become _")
        assertEquals("Mid_Span_Reports/NORMAL_MidSpan_12_Report.pdf",
            report.entryName(asset(K.TOWER_NUMBER to "12", K.REPORT_TYPE to "mid_span")))
    }

    @Test
    fun `writes a one-page PDF with the tower data`() {
        val out = ByteArrayOutputStream()
        val a = asset(
            K.TOWER_NUMBER to "76_0", K.LINE_NAME to "Line A", K.CIRCUIT to "1",
            K.FAULT_DESCRIPTION to "Hot joint", K.LOAD_PREFIX + "Load CKT1" to "120A",
        )

        plugin.getReportStrategy().writePdf(a, mapOf(K.SLOT_THERMAL to null), previous = "75_0", next = "77_0", out = out)

        val reader = PdfReader(out.toByteArray())
        assertEquals(1, reader.numberOfPages)
        val text = PdfTextExtractor(reader).getTextFromPage(1)
        assertTrue(text.contains("TOWER NO FAULT REPORT"))
        assertTrue(text.contains("LINE: LINE A"))
        assertTrue(text.contains("Hot joint"))
        assertTrue(text.contains("LOAD CKT1"))
        assertTrue(text.contains("75_0") && text.contains("77_0"), "navigator shows neighbours")
    }

    // --- thermal ----------------------------------------------------------------------------------

    @Test
    fun `only the thermal slot is marked thermal`() {
        val thermal = plugin.imageSlots(asset()).filter { it.isThermal }

        assertEquals(listOf(K.SLOT_THERMAL), thermal.map { it.id })
    }

    @Test
    fun `form values become thermal scene settings, ignoring junk and out-of-range numbers`() {
        val ok = plugin.thermalOverrides(mapOf(K.EMISSIVITY to "0,92", K.AMBIENT_TEMP to " 31.5 ", K.HUMIDITY to "55"))
        assertEquals(0.92, ok.emissivity)
        assertEquals(31.5, ok.atmosphericTempC)
        assertEquals(31.5, ok.reflectedTempC)
        assertEquals(55.0, ok.humidityPct)

        val bad = plugin.thermalOverrides(mapOf(K.EMISSIVITY to "1.7", K.AMBIENT_TEMP to "warm", K.HUMIDITY to "140"))
        assertEquals(ThermalOverrides(), bad)
        assertEquals(ThermalOverrides(), plugin.thermalOverrides(emptyMap()))
    }

    @Test
    fun `thermal findings fill the fault temperature and the rise over ambient`() {
        val stats = ThermalStats(20f, 78.46f, 30f, Pixel(0, 0), Pixel(3, 2), 100)

        assertEquals(
            mapOf(K.FAULT_TEMP to "78.5", K.RISE_TEMP to "47.5"),
            plugin.thermalFindings(stats, mapOf(K.AMBIENT_TEMP to "31")),
        )
        // Without a usable ambient temperature there is no rise to report.
        assertEquals(mapOf(K.FAULT_TEMP to "78.5"), plugin.thermalFindings(stats, emptyMap()))
    }

    // --- severity ---------------------------------------------------------------------------------

    private fun severityOf(vararg props: Pair<String, String>) = plugin.classify(asset(*props))

    @Test
    fun `severity follows the temperature rise at each threshold`() {
        assertEquals(Severity.NONE, severityOf(K.RISE_TEMP to "0.9"))
        assertEquals(Severity.LOW, severityOf(K.RISE_TEMP to "1"))
        assertEquals(Severity.LOW, severityOf(K.RISE_TEMP to "3.9"))
        assertEquals(Severity.MEDIUM, severityOf(K.RISE_TEMP to "4"))
        assertEquals(Severity.HIGH, severityOf(K.RISE_TEMP to "16"))
        assertEquals(Severity.CRITICAL, severityOf(K.RISE_TEMP to "40"))
        assertEquals(Severity.CRITICAL, severityOf(K.RISE_TEMP to "75,5"))
        assertEquals(Severity.NONE, severityOf(K.RISE_TEMP to "-3"))
    }

    @Test
    fun `without a rise it is worked out from fault and ambient temperature`() {
        assertEquals(Severity.HIGH, severityOf(K.FAULT_TEMP to "60", K.AMBIENT_TEMP to "40"))
        // The rise field wins when both are present.
        assertEquals(Severity.LOW, severityOf(K.RISE_TEMP to "2", K.FAULT_TEMP to "90", K.AMBIENT_TEMP to "30"))
    }

    @Test
    fun `no usable temperatures means none for a normal report and medium for a fault`() {
        assertEquals(Severity.NONE, severityOf())
        assertEquals(Severity.NONE, severityOf(K.RISE_TEMP to "n/a", K.FAULT_TEMP to "60"))
        assertEquals(Severity.MEDIUM, severityOf(K.FAULT_STATUS to "Fault", K.REPORT_TYPE to "tower_fault"))
    }

    @Test
    fun `a report marked as fault is at least low even when the rise is tiny`() {
        assertEquals(Severity.LOW, severityOf(K.FAULT_STATUS to "Fault", K.REPORT_TYPE to "tower_fault", K.RISE_TEMP to "0.2"))
    }

    @Test
    fun `thresholds can be tuned and must be ordered`() {
        val strict = TelecomPlugin(SeverityThresholds(low = 0.5, medium = 2.0, high = 5.0, critical = 10.0))

        assertEquals(Severity.CRITICAL, strict.classify(asset(K.RISE_TEMP to "10")))
        assertFailsWith<IllegalArgumentException> { SeverityThresholds(low = 5.0, medium = 2.0) }
    }
}
