package com.geospatial.processing.domain.thermal

import com.geospatial.processing.domain.imaging.metadata.ExifFixtures
import com.geospatial.processing.domain.thermal.DjiSamples.checksum
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour of [DjiDecoder] on good, odd and hostile input, using the SDK bundled in the jar (the production path). */
class DjiDecoderTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/thermal/$name")) { "missing test resource $name" }.use { it.readBytes() }

    // ---- recognising files ----

    @Test
    fun `the engine routes a DJI image to the DJI decoder and a FLIR image to the FLIR decoder`() {
        val dji = DjiSamples.firstBytes()
        val flir = resource("ax8-radiometric.jpg")
        val engine = ThermalEngine()

        assertEquals("DJI radiometric JPEG", engine.decoderFor(dji)?.name)
        assertEquals(FlirDecoder().name, engine.decoderFor(flir)?.name)
        assertFalse(DjiDecoder().canDecode(flir), "FLIR must not be claimed by the DJI decoder")
        assertFalse(FlirDecoder().canDecode(dji), "DJI must not be claimed by the FLIR decoder")
    }

    @Test
    fun `a plain photo is not a thermal image`() {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "jpg", out)
        val jpeg = out.toByteArray()
        val engine = ThermalEngine()

        assertNull(engine.decoderFor(jpeg))
        assertNull(engine.decodeOrNull(jpeg))
        assertFailsWith<ThermalDecodeException> { engine.decode(jpeg) }
    }

    // ---- hostile or broken input ----

    @Test
    fun `empty input gives a readable error without touching the native library`() {
        val e = assertFailsWith<ThermalDecodeException> { DjiDecoder().decode(ByteArray(0)) }
        assertContains(e.message!!, "empty")
    }

    @Test
    fun `non-image bytes give a readable error`() {
        DjiSamples.requireSdk()
        for (bytes in listOf(ByteArray(1), ByteArray(64), ByteArray(4096) { 0xFF.toByte() }, "not a picture".toByteArray())) {
            val e = assertFailsWith<ThermalDecodeException>("size ${bytes.size}") { DjiDecoder().decode(bytes) }
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `a JPEG with no payload gives a readable error`() {
        DjiSamples.requireSdk()
        val soiEoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertFailsWith<ThermalDecodeException> { DjiDecoder().decode(soiEoi) }
    }

    @Test
    fun `a truncated R-JPEG fails cleanly or still decodes, but never crashes`() {
        val bytes = DjiSamples.firstBytes()
        for (fraction in listOf(0.0, 0.001, 0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99)) {
            val cut = bytes.copyOf((bytes.size * fraction).toInt())
            val outcome = runCatching { DjiDecoder().decode(cut) }
            outcome.exceptionOrNull()?.let { assertTrue(it is ThermalDecodeException, "cut at $fraction threw ${it::class.simpleName}: ${it.message}") }
        }
        // one byte short of the whole file
        runCatching { DjiDecoder().decode(bytes.copyOf(bytes.size - 1)) }.exceptionOrNull()?.let { assertTrue(it is ThermalDecodeException) }
    }

    // ---- scene settings ----

    @Test
    fun `settings are read from the file when nothing is overridden`() {
        val p = DjiDecoder().decode(DjiSamples.firstBytes()).params
        assertEquals(0.95, p.emissivity, 0.01)
        assertTrue(p.distanceM > 0)
        assertTrue(p.humidityPct in 0.0..100.0)
    }

    @Test
    fun `each override reaches the SDK`() {
        val bytes = DjiSamples.firstBytes()
        val base = DjiDecoder().decode(bytes)

        val cases = mapOf(
            "emissivity" to (ThermalOverrides(emissivity = 0.6) to { f: ThermalFrame -> f.params.emissivity }),
            "distance" to (ThermalOverrides(distanceM = 40.0) to { f: ThermalFrame -> f.params.distanceM }),
            "humidity" to (ThermalOverrides(humidityPct = 80.0) to { f: ThermalFrame -> f.params.humidityPct }),
            "reflected" to (ThermalOverrides(reflectedTempC = 35.0) to { f: ThermalFrame -> f.params.reflectedTempC }),
            "ambient" to (ThermalOverrides(atmosphericTempC = 30.0) to { f: ThermalFrame -> f.params.atmosphericTempC }),
        )
        val expected = mapOf("emissivity" to 0.6, "distance" to 40.0, "humidity" to 80.0, "reflected" to 35.0, "ambient" to 30.0)
        for ((name, pair) in cases) {
            val frame = DjiDecoder().decode(bytes, pair.first)
            assertEquals(expected.getValue(name), pair.second(frame), 1e-3, "$name was applied")
            assertEquals(base.width, frame.width)
        }
    }

    @Test
    fun `emissivity and reflected temperature change the temperatures in the physically right direction`() {
        val bytes = DjiSamples.firstBytes("H20T") // a scene well above ambient: lower emissivity must read hotter
        DjiSamples.requireSdk()
        val low = DjiDecoder().decode(bytes, ThermalOverrides(emissivity = 0.5)).stats()!!.max
        val high = DjiDecoder().decode(bytes, ThermalOverrides(emissivity = 0.95)).stats()!!.max
        assertTrue(low > high, "emissivity 0.5 gave $low, 0.95 gave $high")
    }

    @Test
    fun `out-of-range settings are clamped to what the SDK allows instead of failing`() {
        val bytes = DjiSamples.firstBytes()
        val frame = DjiDecoder().decode(
            bytes,
            ThermalOverrides(emissivity = 7.0, humidityPct = 400.0, distanceM = 1e9, reflectedTempC = 1e6, atmosphericTempC = -1e6),
        )
        val p = frame.params
        assertTrue(p.emissivity in 0.0..1.0, "emissivity ${p.emissivity}")
        assertTrue(p.humidityPct in 0.0..100.0, "humidity ${p.humidityPct}")
        assertTrue(p.distanceM < 1e9 && p.distanceM > 0, "distance ${p.distanceM}")
        assertTrue(p.reflectedTempC < 1e6 && p.atmosphericTempC > -1e6)
        assertNotNull(frame.stats())

        val low = DjiDecoder().decode(bytes, ThermalOverrides(emissivity = -3.0, humidityPct = -5.0, distanceM = -10.0)).params
        assertTrue(low.emissivity > 0.0 && low.humidityPct >= 0.0 && low.distanceM >= 0.0, "$low")
        assertTrue(low.distanceM >= 1.0 - 1e-6, "distance is not below the SDK's minimum: ${low.distanceM}")
    }

    @Test
    fun `NaN and infinite settings are ignored`() {
        val bytes = DjiSamples.firstBytes()
        val base = DjiDecoder().decode(bytes)
        val frame = DjiDecoder().decode(
            bytes,
            ThermalOverrides(emissivity = Double.NaN, distanceM = Double.POSITIVE_INFINITY, humidityPct = Double.NEGATIVE_INFINITY, reflectedTempC = Double.NaN),
        )
        assertEquals(base.tempsC.checksum(), frame.tempsC.checksum())
        assertEquals(base.params.emissivity, frame.params.emissivity)
        assertEquals(base.params.distanceM, frame.params.distanceM)
    }

    @Test
    fun `an override does not leak into the next decode`() {
        val bytes = DjiSamples.firstBytes()
        val first = DjiDecoder().decode(bytes)
        DjiDecoder().decode(bytes, ThermalOverrides(emissivity = 0.5, distanceM = 80.0))
        val again = DjiDecoder().decode(bytes)

        assertEquals(first.tempsC.checksum(), again.tempsC.checksum())
        assertEquals(first.params.emissivity, again.params.emissivity)
    }

    @Test
    fun `decoding is deterministic and does not change its input`() {
        val bytes = DjiSamples.firstBytes()
        val copy = bytes.copyOf()
        val a = DjiDecoder().decode(bytes)
        val b = DjiDecoder().decode(bytes)

        assertEquals(a.tempsC.checksum(), b.tempsC.checksum())
        assertTrue(bytes.contentEquals(copy), "input bytes were modified")
    }

    @Test
    fun `native calibration constants are reported as unknown rather than made up`() {
        val p = DjiDecoder().decode(DjiSamples.firstBytes()).params
        assertTrue(p.planckB.isNaN() && p.planckR1.isNaN() && p.planckO.isNaN())
    }

    // ---- concurrency and resources ----

    @Test
    fun `parallel decodes with different settings all give the same answer as a sequential decode`() {
        DjiSamples.requireSdk()
        val inputs = listOf("M4T", "H20T", "H30T", "M3T").mapNotNull { DjiSamples.files(it).firstOrNull()?.readBytes() }
        org.junit.jupiter.api.Assumptions.assumeTrue(inputs.isNotEmpty())
        val settings = listOf(ThermalOverrides(), ThermalOverrides(emissivity = 0.7), ThermalOverrides(distanceM = 30.0, humidityPct = 70.0))

        // Sequential truth.
        val truth = inputs.indices.flatMap { i -> settings.indices.map { s -> (i to s) to DjiDecoder().decode(inputs[i], settings[s]).tempsC.checksum() } }.toMap()

        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 96).map { n ->
                Callable {
                    val i = n % inputs.size
                    val s = (n / inputs.size) % settings.size
                    (i to s) to DjiDecoder().decode(inputs[i], settings[s]).tempsC.checksum()
                }
            }
            val results = pool.invokeAll(jobs, 3, TimeUnit.MINUTES).map { it.get() }
            for ((key, sum) in results) assertEquals(truth.getValue(key), sum, "image/settings $key differed under concurrency")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a failing decode does not poison later decodes`() {
        val bytes = DjiSamples.firstBytes()
        val good = DjiDecoder().decode(bytes).tempsC.checksum()
        repeat(20) {
            runCatching { DjiDecoder().decode(bytes.copyOf(bytes.size / 3)) }
            runCatching { DjiDecoder().decode(ByteArray(100)) }
        }
        assertEquals(good, DjiDecoder().decode(bytes).tempsC.checksum())
    }

    @Test
    fun `repeated decoding keeps the heap and the native handles under control`() {
        val bytes = DjiSamples.firstBytes()
        val rt = Runtime.getRuntime()
        repeat(10) { DjiDecoder().decode(bytes) } // warm up
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()

        repeat(60) { DjiDecoder().decode(bytes) }
        System.gc()
        val growth = (rt.totalMemory() - rt.freeMemory()) - before

        // Each frame is ~1.3 MB; if frames or handles were retained 60 of them would be ~200 MB.
        assertTrue(growth < 25L * 1024 * 1024, "heap grew by ${growth / 1024 / 1024} MB over 60 decodes")
    }

    // ---- error mapping ----

    @Test
    fun `a file that does not start like a JPEG is rejected before the native library sees it`() {
        val whole = DjiSamples.firstBytes()
        val e = assertFailsWith<ThermalDecodeException> { DjiDecoder().decode(whole.copyOfRange(1, whole.size)) }
        assertContains(e.message!!, "not a JPEG")
    }

    @Test
    fun `real R-JPEGs may carry data after the JPEG end marker, so the end of the file is not checked`() {
        val ends = listOf("M4T", "H30T", "M3T").mapNotNull { DjiSamples.files(it).firstOrNull()?.readBytes() }
        org.junit.jupiter.api.Assumptions.assumeTrue(ends.isNotEmpty())
        // At least one real sample does not end in FFD9; the decoder must accept it (guards against re-adding an EOI check).
        assertTrue(ends.any { !(it[it.size - 2] == 0xFF.toByte() && it[it.size - 1] == 0xD9.toByte()) }, "samples all end in EOI, test premise gone")
        ends.forEach { DjiDecoder().decode(it) }
    }

    @Test
    fun `trailing zero padding after the end marker is tolerated`() {
        val bytes = DjiSamples.firstBytes()
        val base = DjiDecoder().decode(bytes).tempsC.checksum()
        assertEquals(base, DjiDecoder().decode(bytes + ByteArray(37)).tempsC.checksum())
    }

    // ---- recognising cameras ----

    @Test
    fun `the Mavic 2 Enterprise Advanced is recognised although its model name matches no keyword`() {
        val bytes = DjiSamples.firstBytes("M2EA")
        assertEquals("DJI radiometric JPEG", ThermalEngine().decoderFor(bytes)?.name)
        assertNotNull(ThermalEngine().decode(bytes).stats())
    }

    @Test
    fun `a DJI photo that is not radiometric is refused`() {
        DjiSamples.requireSdk()
        val photo = ExifFixtures.jpeg(make = "DJI", model = "FC3582") // a visible-light camera: make is DJI, no thermal hint
        assertFalse(DjiDecoder().canDecode(photo))
        assertNull(ThermalEngine().decoderFor(photo))
    }

    @Test
    fun `a thermal-looking model from another make is not claimed by the DJI decoder`() {
        val notDji = ExifFixtures.jpeg(make = "Acme", model = "M4T")
        assertFalse(DjiDecoder().canDecode(notDji))
    }

    // ---- SDK quirks found by probing every setting at the edge of its advertised range ----

    @Test
    fun `an emissivity the SDK refuses to measure with falls back to the camera's value instead of failing`() {
        val bytes = DjiSamples.firstBytes("M4T") // the M4T fails to measure at its own minimum of 0.1
        val base = DjiDecoder().decode(bytes)
        val frame = DjiDecoder().decode(bytes, ThermalOverrides(emissivity = 0.1))

        assertEquals(base.tempsC.checksum(), frame.tempsC.checksum())
        assertEquals(base.params.emissivity, frame.params.emissivity, "the reported emissivity is the one actually used")
    }

    @Test
    fun `one refused setting does not discard the other settings`() {
        val bytes = DjiSamples.firstBytes("M4T")
        val base = DjiDecoder().decode(bytes)
        val frame = DjiDecoder().decode(bytes, ThermalOverrides(emissivity = 0.1, distanceM = 50.0, humidityPct = 80.0))

        assertEquals(base.params.emissivity, frame.params.emissivity, "refused one reverted")
        assertEquals(50.0, frame.params.distanceM, 1e-3, "distance kept")
        assertEquals(80.0, frame.params.humidityPct, 1e-3, "humidity kept")
        assertNotEquals(base.tempsC.checksum(), frame.tempsC.checksum(), "the accepted settings still change the picture")
    }

    @Test
    fun `a distance the SDK refuses to apply falls back too`() {
        val bytes = DjiSamples.firstBytes("XTS") // the XT S rejects a distance of exactly 1 m
        val base = DjiDecoder().decode(bytes)
        val frame = DjiDecoder().decode(bytes, ThermalOverrides(distanceM = 1.0))
        assertNotNull(frame.stats())
        assertTrue(frame.params.distanceM == 1.0 || frame.params.distanceM == base.params.distanceM)
    }

    @Test
    fun `a setting the camera cannot change is left alone, not forced to zero`() {
        val bytes = DjiSamples.firstBytes("H20T") // reports an ambient range of 0..0: not adjustable
        val base = DjiDecoder().decode(bytes)
        val frame = DjiDecoder().decode(bytes, ThermalOverrides(atmosphericTempC = 25.0))

        assertEquals(base.params.atmosphericTempC, frame.params.atmosphericTempC)
        assertEquals(base.tempsC.checksum(), frame.tempsC.checksum())
    }

    @Test
    fun `the settings at both ends of every advertised range never make a decode fail`() {
        DjiSamples.requireSdk()
        val cameras = listOf("M4T", "H20T", "H20N", "H30T", "M2EA", "M30T", "M3T", "M3TD", "XTS")
        val extremes = listOf(
            ThermalOverrides(emissivity = 0.1), ThermalOverrides(emissivity = 1.0),
            ThermalOverrides(distanceM = 1.0), ThermalOverrides(distanceM = 300.0),
            ThermalOverrides(humidityPct = 0.0), ThermalOverrides(humidityPct = 100.0),
            ThermalOverrides(reflectedTempC = -40.0), ThermalOverrides(reflectedTempC = 500.0),
            ThermalOverrides(atmosphericTempC = -40.0), ThermalOverrides(atmosphericTempC = 80.0),
            ThermalOverrides(emissivity = 0.1, distanceM = 1.0, humidityPct = 0.0, reflectedTempC = -40.0, atmosphericTempC = -40.0),
            ThermalOverrides(emissivity = 1.0, distanceM = 300.0, humidityPct = 100.0, reflectedTempC = 500.0, atmosphericTempC = 80.0),
        )
        for (camera in cameras) {
            val bytes = DjiSamples.files(camera).firstOrNull()?.readBytes() ?: continue
            for (o in extremes) {
                val frame = try {
                    DjiDecoder().decode(bytes, o)
                } catch (e: ThermalDecodeException) {
                    throw AssertionError("$camera with $o: ${e.message}", e)
                }
                assertNotNull(frame.stats(), "$camera with $o")
            }
        }
    }
}
