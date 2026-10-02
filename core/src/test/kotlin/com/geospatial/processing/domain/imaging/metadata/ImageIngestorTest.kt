package com.geospatial.processing.domain.imaging.metadata

import com.geospatial.processing.domain.model.Asset
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ImageIngestorTest {

    private fun asset(lat: Double = 0.0, lon: Double = 0.0, capturedAt: Instant? = null) =
        Asset(pluginId = "p", position = 0, latitude = lat, longitude = lon, capturedAt = capturedAt)

    private fun img(name: String, meta: ImageMeta) = IngestedImage(File(name), meta)

    private val visual = ImageMeta(latitude = 28.6, longitude = 77.2, capturedAt = Instant.parse("2024-05-01T10:00:00Z"))
    private val thermal = ImageMeta(
        latitude = 28.61, longitude = 77.21, capturedAt = Instant.parse("2024-05-01T09:59:00Z"), isThermalHint = true,
    )

    @Test
    fun `fills a missing position from the visible image and the earliest capture time`() {
        val result = ImageIngestor.enrich(asset(), listOf(img("t.jpg", thermal), img("v.jpg", visual)))

        assertEquals(28.6, result.latitude)
        assertEquals(77.2, result.longitude)
        assertEquals(Instant.parse("2024-05-01T09:59:00Z"), result.capturedAt)
    }

    @Test
    fun `falls back to the thermal image when it is the only one with GPS`() {
        val result = ImageIngestor.enrich(asset(), listOf(img("t.jpg", thermal), img("v.jpg", ImageMeta())))

        assertEquals(28.61, result.latitude)
    }

    @Test
    fun `never overwrites coordinates or capture time that are already set`() {
        val original = asset(lat = 10.0, lon = 20.0, capturedAt = Instant.EPOCH)

        assertSame(original, ImageIngestor.enrich(original, listOf(img("v.jpg", visual))))
    }

    @Test
    fun `images without metadata change nothing`() {
        val original = asset()

        assertSame(original, ImageIngestor.enrich(original, listOf(img("v.jpg", ImageMeta()))))
        assertNull(ImageIngestor.enrich(original, emptyList()).capturedAt)
    }
}
