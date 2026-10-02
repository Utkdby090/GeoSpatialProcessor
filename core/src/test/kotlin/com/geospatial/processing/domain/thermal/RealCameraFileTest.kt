package com.geospatial.processing.domain.thermal

import com.geospatial.processing.domain.imaging.metadata.ImageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files that really came out of a camera (unlike [FlirFixtures], which follows our own reading of the format).
 *
 * `ax8-radiometric.jpg` is a FLIR AX8 image whose burned-in overlay says max 25.5, min 24.3, avg 25.1 °C,
 * which is the ground truth the decoder is held to.
 */
class RealCameraFileTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/thermal/$name")) { "missing test resource $name" }.use { it.readBytes() }

    private val ax8 = resource("ax8-radiometric.jpg")

    @Test
    fun `AX8 image decodes to the temperatures the camera printed on it`() {
        val frame = ThermalEngine().decode(ax8)
        val stats = frame.stats()!!

        assertEquals(80, frame.width)
        assertEquals(60, frame.height)
        assertEquals(4800, stats.pixelCount, "every pixel has a reading")
        assertEquals(25.5, stats.max.toDouble(), 0.1)
        assertEquals(24.3, stats.min.toDouble(), 0.15)
        assertEquals(25.1, stats.mean.toDouble(), 0.15)
    }

    @Test
    fun `camera settings are read from the file`() {
        val p = FlirDecoder().decode(ax8).params

        assertEquals(0.95, p.emissivity, 1e-3)
        assertEquals(1.0, p.distanceM, 1e-3)
        assertEquals(20.0, p.reflectedTempC, 0.01)
        assertEquals(50.0, p.humidityPct, 1e-3)
        assertEquals(-7142.0, p.planckO)
    }

    @Test
    fun `form emissivity changes the reading of the real file`() {
        val stored = FlirDecoder().decode(ax8).stats()!!.max
        val lower = FlirDecoder().decode(ax8, ThermalOverrides(emissivity = 0.8)).stats()!!.max

        // The scene is close to the reflected temperature, so the shift is small but must go the right way and stay sane.
        assertTrue(lower != stored && lower in 20f..35f, "was $lower vs $stored")
    }

    @Test
    fun `metadata identifies the camera as thermal`() {
        val meta = ImageMetadata.read(ax8.inputStream())

        assertEquals("FLIR AX8", meta.model)
        assertTrue(meta.isThermalHint)
    }

    @Test
    fun `a FLIR-branded screenshot without radiometric data is recognised as plain`() {
        val screenshot = resource("flir-screenshot-not-radiometric.jpg")
        val engine = ThermalEngine()

        assertFalse(FlirDecoder().canDecode(screenshot))
        assertNull(engine.decoderFor(screenshot))
        assertNull(engine.decodeOrNull(screenshot))
        assertFailsWith<ThermalDecodeException> { engine.decode(screenshot) }
    }
}
