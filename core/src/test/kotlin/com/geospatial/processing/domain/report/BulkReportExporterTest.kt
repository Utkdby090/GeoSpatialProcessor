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

    @Test
    fun `a failure leaves an existing file at the destination untouched`() {
        val zip = File(dir, "keep.zip").apply { writeText("previous export") }
        val failing: (com.geospatial.processing.domain.model.Asset) -> Map<String, ImageSource> = { error("boom") }

        assertFailsWith<IllegalStateException> { BulkReportExporter(plugin).export(listOf(asset("T1", 0, READY)), failing, zip) }

        assertEquals("previous export", zip.readText())
        assertEquals(listOf("keep.zip"), dir.list()!!.toList(), "no temp file left behind")
    }

    @Test
    fun `towers that would get the same report name are kept apart instead of failing the export`() {
        // Several phases / circuits of one tower produce the same entry name.
        val assets = listOf(
            asset("T1", 0, READY, "folder" to "Tower"),
            asset("T1", 1, READY, "folder" to "Tower"),
            asset("T1", 2, READY, "folder" to "Tower"),
            asset("T2", 3, READY, "folder" to "Tower"),
        )
        val zip = File(dir, "dupes.zip")

        assertEquals(4, BulkReportExporter(plugin).export(assets, noImages, zip))

        ZipFile(zip).use { z ->
            assertEquals(listOf("Tower/T1.pdf", "Tower/T1_2.pdf", "Tower/T1_3.pdf", "Tower/T2.pdf"), z.entries().toList().map { it.name })
        }
    }

    @Test
    fun `names that differ only by case are kept apart, because Windows extracts them to one file`() {
        val assets = listOf(asset("t1", 0, READY, "folder" to "Tower"), asset("T1", 1, READY, "folder" to "Tower"))
        val zip = File(dir, "case.zip")

        BulkReportExporter(plugin).export(assets, noImages, zip)

        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertEquals(2, names.map { it.lowercase() }.toSet().size, "unique ignoring case: $names")
        }
    }

    @Test
    fun `a name without an extension or in the root gets its suffix at the end`() {
        val plain = object : FakePlugin() {}
        val assets = listOf(asset("a", 0, READY, "folder" to "x"), asset("a", 1, READY, "folder" to "x"))
        // FakePlugin names are "<folder>/<name>.pdf"; the suffix must go before ".pdf", never after the folder dot.
        val zip = File(dir, "suffix.zip")
        BulkReportExporter(plain).export(assets, noImages, zip)
        ZipFile(zip).use { z -> assertEquals(listOf("x/a.pdf", "x/a_2.pdf"), z.entries().toList().map { it.name }) }
    }

    @Test
    fun `the result is identical whatever the number of threads, and in the same order`() {
        val assets = (0 until 120).map { asset("T$it", it, READY, "folder" to if (it % 3 == 0) "Span" else "Tower") }
        fun run(threads: Int): List<Pair<String, String>> {
            val zip = File(dir, "p$threads.zip")
            assertEquals(120, BulkReportExporter(FakePlugin()).export(assets, noImages, zip, parallelism = threads))
            return ZipFile(zip).use { z -> z.entries().toList().map { it.name to z.getInputStream(it).readBytes().decodeToString() } }
        }
        val single = run(1)
        assertEquals(single, run(4))
        assertEquals(single, run(8))
    }

    @Test
    fun `progress is reported once per report, in order, from a single thread`() {
        val assets = (0 until 40).map { asset("T$it", it, READY, "folder" to "Tower") }
        val threads = mutableSetOf<Thread>()
        val seen = mutableListOf<Int>()

        BulkReportExporter(FakePlugin()).export(assets, noImages, File(dir, "prog.zip"), parallelism = 4) { c, t ->
            threads += Thread.currentThread(); seen += c; assertEquals(40, t)
        }

        assertEquals((1..40).toList(), seen)
        assertEquals(1, threads.size)
    }

    @Test
    fun `a failure inside a worker is reported as itself, the export stops and nothing is left behind`() {
        val assets = (0 until 30).map { asset("T$it", it, READY, "folder" to "Tower") }
        val zip = File(dir, "worker.zip")
        val failOn7: (com.geospatial.processing.domain.model.Asset) -> Map<String, ImageSource> = {
            if (it.property("name") == "T7") throw IllegalArgumentException("bad image folder") else noImages(it)
        }

        val e = assertFailsWith<IllegalArgumentException> {
            BulkReportExporter(FakePlugin()).export(assets, failOn7, zip, parallelism = 4)
        }

        assertEquals("bad image folder", e.message)
        assertFalse(zip.exists())
        assertEquals(emptyList(), dir.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `an unreadable image shows as missing and does not fail the export`() {
        val gone = ImageSource.FromFile(File(dir, "does-not-exist.jpg"))
        val zip = File(dir, "missing.zip")

        val n = BulkReportExporter(plugin).export(listOf(asset("T1", 0, READY)), { mapOf("A" to gone, "B" to ImageSource.Missing) }, zip)

        assertEquals(1, n)
        ZipFile(zip).use { z -> assertEquals("pdf:T1:0", z.getInputStream(z.entries().nextElement()).readBytes().decodeToString()) }
    }

    @Test
    fun `the destination folder is created when it does not exist`() {
        val zip = File(dir, "new/sub/out.zip")
        assertEquals(1, BulkReportExporter(plugin).export(listOf(asset("T1", 0, READY, "folder" to "Tower")), noImages, zip))
        assertEquals(true, zip.isFile)
    }
}
