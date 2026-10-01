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
