package com.geospatial.processing.data.images

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImageStoreOriginalTest {

    private val projectDir: File = Files.createTempDirectory("store").toFile()
    private val store = ImageStore(projectDir)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 0, 9)

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    @Test
    fun `original is kept next to the display image`() {
        val display = store.write("a1", "THERMAL", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9))
        store.writeOriginal("a1", "THERMAL", jpeg)

        assertEquals("images/a1/THERMAL.jpg", display)
        assertEquals("THERMAL.orig.jpg", store.originalFile("a1", "THERMAL")!!.name)
        assertContentEquals(jpeg, store.originalFile("a1", "THERMAL")!!.readBytes())
        assertEquals(2, File(projectDir, "images/a1").listFiles()!!.size)
    }

    @Test
    fun `a new original replaces the old one even with another extension`() {
        store.writeOriginal("a1", "THERMAL", jpeg)
        store.writeOriginal("a1", "THERMAL", png)

        assertEquals("THERMAL.orig.png", store.originalFile("a1", "THERMAL")!!.name)
        assertEquals(1, File(projectDir, "images/a1").listFiles()!!.size)
    }

    @Test
    fun `originals of other slots and assets are not returned`() {
        store.writeOriginal("a1", "THERMAL", jpeg)

        assertNull(store.originalFile("a1", "LOCATION"))
        assertNull(store.originalFile("a2", "THERMAL"))
    }

    @Test
    fun `deleting an original or the whole asset removes it`() {
        store.writeOriginal("a1", "THERMAL", jpeg)
        store.deleteOriginal("a1", "THERMAL")
        assertNull(store.originalFile("a1", "THERMAL"))

        store.writeOriginal("a1", "THERMAL", jpeg)
        store.deleteAsset("a1")
        assertNull(store.originalFile("a1", "THERMAL"))
    }

    @Test
    fun `unsafe names are rejected`() {
        assertNull(store.originalFile("../x", "THERMAL"))
        assertFailsWith<IllegalArgumentException> { store.writeOriginal("a1", "../THERMAL", jpeg) }
    }
}
