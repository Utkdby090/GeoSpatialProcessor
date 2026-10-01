package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.CoreFields
import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.model.Asset
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
