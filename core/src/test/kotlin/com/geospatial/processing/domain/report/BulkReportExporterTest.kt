package com.geospatial.processing.domain.report

import com.geospatial.processing.domain.model.FakePlugin
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.domain.model.RecordStatus.DRAFT
import com.geospatial.processing.domain.model.RecordStatus.READY
import com.geospatial.processing.domain.model.asset
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BulkReportExporterTest {

    private val dir: File = Files.createTempDirectory("export").toFile()
    private val plugin = FakePlugin()
    private val noImages: (com.geospatial.processing.domain.model.Asset) -> Map<String, ImageSource> =
        { mapOf("A" to ImageSource.FromBlob(byteArrayOf(1)), "B" to ImageSource.Missing) }

    @Test
    fun `exports only READY assets and returns how many were written`() {
        val assets = listOf(
            asset("T1", 0, READY, "folder" to "Tower"),
            asset("T2", 1, DRAFT, "folder" to "Tower"),
            asset("S1", 2, READY, "folder" to "Span"),
            asset("T3", 3, READY, "folder" to "Tower"),
        )
        val zip = File(dir, "out.zip")
        val progress = mutableListOf<Pair<Int, Int>>()

        val count = BulkReportExporter(plugin).export(assets, noImages, zip) { c, t -> progress += c to t }

        assertEquals(3, count)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        ZipFile(zip).use { z ->
            // Grouped by folder, first-seen order: Tower first, then Span.
            assertEquals(listOf("Tower/T1.pdf", "Tower/T3.pdf", "Span/S1.pdf"), z.entries().toList().map { it.name })
            assertEquals("pdf:T1:1", z.getInputStream(z.getEntry("Tower/T1.pdf")).readBytes().decodeToString())
        }
    }

    @Test
    fun `previous and next come from the full list, not only READY assets`() {
        val assets = listOf(asset("T1", 0, DRAFT), asset("T2", 1, READY), asset("T3", 2, DRAFT))

        BulkReportExporter(plugin).export(assets, noImages, File(dir, "out.zip"))

        assertEquals(listOf(Triple<String, String?, String?>("T2", "T1", "T3")), plugin.written)
    }

    @Test
    fun `nothing READY writes no file`() {
        val zip = File(dir, "none.zip")
        assertEquals(0, BulkReportExporter(plugin).export(listOf(asset("T1")), noImages, zip))
        assertFalse(zip.exists())
    }

    @Test
    fun `a failure deletes the partial zip`() {
        val zip = File(dir, "broken.zip")
        val failing: (com.geospatial.processing.domain.model.Asset) -> Map<String, ImageSource> = { error("boom") }

        assertFailsWith<IllegalStateException> {
            BulkReportExporter(plugin).export(listOf(asset("T1", 0, READY)), failing, zip)
        }
        assertFalse(zip.exists())
    }
}
