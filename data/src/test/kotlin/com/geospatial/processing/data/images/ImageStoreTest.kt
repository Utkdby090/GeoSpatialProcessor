package com.geospatial.processing.data.images

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageStoreTest {

    private val projectDir: File = Files.createTempDirectory("store").toFile()
    private val store = ImageStore(projectDir)

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    @Test
    fun `writes under images, with the extension taken from the content`() {
        assertEquals("images/a1/THERMAL.jpg", store.write("a1", "THERMAL", jpeg))
        assertEquals("images/a1/RGB_ZOOM.png", store.write("a1", "RGB_ZOOM", png))
        assertEquals("images/a1/LOCATION.img", store.write("a1", "LOCATION", byteArrayOf(1, 2, 3)))
        assertContentEquals(jpeg, File(projectDir, "images/a1/THERMAL.jpg").readBytes())
    }

    @Test
    fun `rewriting a slot overwrites the file`() {
        store.write("a1", "THERMAL", jpeg)
        val newer = jpeg + byteArrayOf(9)
        store.write("a1", "THERMAL", newer)
        assertContentEquals(newer, store.file("images/a1/THERMAL.jpg")!!.readBytes())
    }

    @Test
    fun `writing leaves no temporary files, however often a slot is rewritten`() {
        repeat(50) { n -> store.write("a1", "THERMAL", jpeg + n.toByte()) }
        store.writeOriginal("a1", "THERMAL", jpeg)
        repeat(20) { n -> store.writeOriginal("a1", "THERMAL", jpeg + n.toByte()) }

        val names = File(projectDir, "images/a1").list()!!.sorted()
        assertEquals(listOf("THERMAL.jpg", "THERMAL.orig.jpg"), names)
    }

    @Test
    fun `concurrent writes to one slot leave one complete image, never a mix or a fragment`() {
        val versions = (0 until 8).map { v -> ByteArray(200_000) { (v + 1).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 64).map { n -> pool.submit { runCatching { store.write("a1", "THERMAL", versions[n % versions.size]) } } }
            jobs.forEach { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val written = File(projectDir, "images/a1/THERMAL.jpg").readBytes()
        assertTrue(versions.any { it.contentEquals(written) }, "the file is exactly one of the versions that were written")
        assertEquals(listOf("THERMAL.jpg"), File(projectDir, "images/a1").list()!!.toList(), "no temp files left")
    }

    @Test
    fun `a new original of another file type replaces the old one, and the old one survives until then`() {
        store.writeOriginal("a1", "THERMAL", jpeg)
        store.writeOriginal("a1", "THERMAL", png)

        assertEquals(listOf("THERMAL.orig.png"), File(projectDir, "images/a1").list()!!.toList())
        assertContentEquals(png, store.originalFile("a1", "THERMAL")!!.readBytes())
    }

    @Test
    fun `an original of one slot is not touched when another slot is written`() {
        store.writeOriginal("a1", "THERMAL", jpeg)
        store.writeOriginal("a1", "RGB_ZOOM", png)

        assertContentEquals(jpeg, store.originalFile("a1", "THERMAL")!!.readBytes())
        assertContentEquals(png, store.originalFile("a1", "RGB_ZOOM")!!.readBytes())
    }

    @Test
    fun `a failure to write is reported with the path and leaves the previous image alone`() {
        store.write("a1", "THERMAL", jpeg)
        // A folder where the image file should go makes the replacement impossible.
        File(projectDir, "images/a2").mkdirs()
        File(projectDir, "images/a2/THERMAL.jpg").mkdirs()
        File(projectDir, "images/a2/THERMAL.jpg/inside").writeText("x")

        val e = assertFailsWith<java.io.IOException> { store.write("a2", "THERMAL", jpeg) }

        assertTrue("THERMAL.jpg" in e.message!!)
        assertEquals(listOf("THERMAL.jpg"), File(projectDir, "images/a2").list()!!.toList(), "no temp file left behind")
        assertContentEquals(jpeg, File(projectDir, "images/a1/THERMAL.jpg").readBytes())
    }

    @Test
    fun `refuses paths outside the images folder`() {
        assertNull(store.file("../project.db"))
        assertNull(store.file("images/../../etc/passwd"))
        assertFailsWith<IllegalArgumentException> { store.write("../x", "THERMAL", jpeg) }
    }

    @Test
    fun `delete asset and delete all`() {
        store.write("a1", "THERMAL", jpeg)
        store.write("a2", "THERMAL", jpeg)

        store.deleteAsset("a1")
        assertFalse(File(projectDir, "images/a1").exists())
        assertTrue(File(projectDir, "images/a2/THERMAL.jpg").exists())

        store.deleteAll()
        assertTrue(File(projectDir, "images").list()!!.isEmpty())
    }
}
