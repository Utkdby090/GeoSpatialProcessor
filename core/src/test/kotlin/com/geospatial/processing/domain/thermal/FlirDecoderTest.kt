package com.geospatial.processing.domain.thermal

import com.geospatial.processing.domain.imaging.metadata.ExifFixtures
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlirDecoderTest {

    // A 4×2 scene: one hot pixel (80 °C), the rest between 20 and 35 °C.
    private val temps = doubleArrayOf(20.0, 22.0, 25.0, 30.0, 35.0, 21.0, 80.0, 24.0)
    private val raw = IntArray(temps.size) { FlirFixtures.rawFor(temps[it]) }

    private fun jpeg(scene: FlirFixtures.Scene = FlirFixtures.Scene(), bigEndian: Boolean = false, chunks: Int = 1, shuffle: Boolean = false) =
        FlirFixtures.jpeg(4, 2, raw, scene, bigEndian = bigEndian, chunks = chunks, shuffleChunks = shuffle)

    private fun assertTemps(frame: ThermalFrame, expected: DoubleArray, tolerance: Double = 0.1) {
        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        expected.forEachIndexed { i, t -> assertEquals(t, frame.tempsC[i].toDouble(), tolerance, "pixel $i") }
    }

    @Test
    fun `black body at zero distance gives back the temperatures it was built from`() {
        assertTemps(FlirDecoder().decode(jpeg()), temps)
    }

    @Test
    fun `lower emissivity makes a hot object read hotter than its apparent temperature`() {
        val frame = FlirDecoder().decode(jpeg(FlirFixtures.Scene(emissivity = 0.9f, reflectedC = 20f)))

        val hot = frame.temperatureAt(2, 1)!!
        assertTrue(hot > 80f && hot < 95f, "hot pixel was $hot")
        // A pixel at the reflected temperature is unchanged by the emissivity correction.
        assertEquals(20.0, frame.tempsC[0].toDouble(), 0.5)
    }

    @Test
    fun `overrides from the form beat the values stored in the image`() {
        val file = jpeg(FlirFixtures.Scene(emissivity = 0.95f))

        val stored = FlirDecoder().decode(file).temperatureAt(2, 1)!!
        val overridden = FlirDecoder().decode(file, ThermalOverrides(emissivity = 0.7)).temperatureAt(2, 1)!!

        assertTrue(overridden > stored, "$overridden should exceed $stored")
        assertEquals(0.7, FlirDecoder().decode(file, ThermalOverrides(emissivity = 0.7)).params.emissivity)
    }

    @Test
    fun `distance and humidity absorb some radiation, so the same counts mean a hotter object`() {
        val near = FlirDecoder().decode(jpeg(FlirFixtures.Scene(distanceM = 1f))).temperatureAt(2, 1)!!
        val far = FlirDecoder().decode(jpeg(FlirFixtures.Scene(distanceM = 60f, humidity = 0.8f))).temperatureAt(2, 1)!!

        assertTrue(far > near, "far $far should exceed near $near")
    }

    @Test
    fun `big-endian files decode the same as little-endian ones`() {
        assertTemps(FlirDecoder().decode(jpeg(bigEndian = true)), temps)
    }

    @Test
    fun `thermal data split over several segments in any order is reassembled`() {
        assertTemps(FlirDecoder().decode(jpeg(chunks = 3, shuffle = true)), temps)
    }

    @Test
    fun `samples stored without byte swapping decode when told so`() {
        val file = FlirFixtures.jpeg(4, 2, raw, storeSwapped = false)

        assertTemps(FlirDecoder(swapBytes = false).decode(file), temps)
    }

    @Test
    fun `plain JPEGs and garbage are not radiometric`() {
        val plain = ExifFixtures.jpeg(make = "Canon")

        assertFalse(FlirDecoder().canDecode(plain))
        assertFalse(FlirDecoder().canDecode(ByteArray(0)))
        assertFalse(FlirDecoder().canDecode("hello".toByteArray()))
        assertFailsWith<ThermalDecodeException> { FlirDecoder().decode(plain) }
    }

    @Test
    fun `damaged FLIR data fails with a clear exception, never a crash`() {
        val good = jpeg()
        // Chop the file in the middle of the thermal data, and also corrupt the header of an intact file.
        val truncated = good.copyOf(good.size / 2)
        val corrupt = good.copyOf().also { it[2 + 4 + 8 + 1] = 'X'.code.toByte() } // inside the FFF magic

        assertFailsWith<ThermalDecodeException> { FlirDecoder().decode(truncated) }
        assertFailsWith<ThermalDecodeException> { FlirDecoder().decode(corrupt) }
    }

    @Test
    fun `engine picks the FLIR decoder, reports plain photos, and explains DJI files`() {
        val engine = ThermalEngine()

        assertEquals("FLIR radiometric JPEG", engine.decoderFor(jpeg())?.name)
        assertNotNull(engine.decodeOrNull(jpeg()))

        val plain = ExifFixtures.jpeg(make = "Canon")
        assertNull(engine.decoderFor(plain))
        assertNull(engine.decodeOrNull(plain))
        assertFailsWith<ThermalDecodeException> { engine.decode(plain) }

        val dji = ExifFixtures.jpeg(make = "DJI", model = "ZH20T")
        assertEquals("DJI radiometric JPEG", engine.decoderFor(dji)?.name)
        val e = assertFailsWith<ThermalDecodeException> { engine.decode(dji) }
        assertTrue("DJI" in e.message!!)
    }

    @Test
    fun `calibration returns NaN outside the curve instead of nonsense`() {
        val frame = FlirDecoder().decode(jpeg())
        val calibration = RadiometricCalibration(frame.params)

        assertTrue(calibration.tempC(0).isNaN())
        assertEquals(30.0, calibration.tempC(FlirFixtures.rawFor(30.0)).toDouble(), 0.1)
    }

    @Test
    fun `same file decodes to identical frames`() {
        assertContentEquals(FlirDecoder().decode(jpeg()).tempsC, FlirDecoder().decode(jpeg()).tempsC)
    }
}
