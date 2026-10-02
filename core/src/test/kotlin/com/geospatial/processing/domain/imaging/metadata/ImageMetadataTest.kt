package com.geospatial.processing.domain.imaging.metadata

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageMetadataTest {

    private val dir: File = Files.createTempDirectory("exif").toFile()
    private val delhi = ExifFixtures.Dms(28, 36, 0, true, 77, 12, 0, true)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    @Test
    fun `reads GPS, capture time and camera from EXIF`() {
        val meta = ImageMetadata.read(
            file("a.jpg", ExifFixtures.jpeg(make = "DJI", model = "FC3582", gps = delhi, dateOriginal = "2024:05:01 10:20:30"))
        )

        assertEquals(28.6, meta.latitude!!, 1e-6)
        assertEquals(77.2, meta.longitude!!, 1e-6)
        assertEquals(Instant.parse("2024-05-01T10:20:30Z"), meta.capturedAt)
        assertEquals("DJI", meta.make)
        assertEquals("FC3582", meta.model)
        assertTrue(meta.hasGps)
        assertFalse(meta.isThermalHint)
    }

    @Test
    fun `southern and western hemispheres give negative coordinates`() {
        val meta = ImageMetadata.read(file("s.jpg", ExifFixtures.jpeg(gps = delhi.copy(north = false, east = false))))

        assertEquals(-28.6, meta.latitude!!, 1e-6)
        assertEquals(-77.2, meta.longitude!!, 1e-6)
    }

    @Test
    fun `image without GPS has no position`() {
        val meta = ImageMetadata.read(file("n.jpg", ExifFixtures.jpeg(make = "DJI")))

        assertNull(meta.latitude)
        assertFalse(meta.hasGps)
    }

    @Test
    fun `thermal camera make or model is recognised`() {
        assertTrue(ImageMetadata.read(file("t.jpg", ExifFixtures.jpeg(make = "FLIR Systems", gps = delhi))).isThermalHint)
        assertTrue(ImageMetadata.read(file("m.jpg", ExifFixtures.jpeg(make = "DJI", model = "ZH20T"))).isThermalHint)
    }

    @Test
    fun `files that are not images, are empty or do not exist give empty metadata`() {
        assertEquals(ImageMeta(), ImageMetadata.read(file("x.jpg", "not an image".toByteArray())))
        assertEquals(ImageMeta(), ImageMetadata.read(file("e.jpg", ByteArray(0))))
        assertEquals(ImageMeta(), ImageMetadata.read(File(dir, "missing.jpg")))
    }
}
