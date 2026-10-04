package com.geospatial.processing.domain.thermal

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every sample image of every camera family DJI ships, held to the numbers DJI's own `dji_irp.exe` produced for it
 * (`dji-reference-stats.csv`, generated with `-a measure --measurefmt float32`; rounded to 4 decimals).
 * This is the ground truth: it proves the JNA struct layouts, the buffer handling and the resolution handling.
 */
class DjiReferenceTest {

    private data class Reference(val camera: String, val file: String, val pixels: Int, val min: Double, val max: Double, val mean: Double)

    private val references: List<Reference> by lazy {
        val csv = checkNotNull(javaClass.getResourceAsStream("/thermal/dji-reference-stats.csv")) { "missing reference csv" }
            .bufferedReader().readLines().drop(1).filter { it.isNotBlank() }
        csv.map { line ->
            val c = line.split(',')
            Reference(c[0], c[1], c[2].toInt(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
        }
    }

    @Test
    fun `reference table covers every camera family`() {
        assertEquals(49, references.size)
        assertEquals(
            setOf("H20N", "H20T", "H30T", "M2EA", "M30T", "M3T", "M3TD", "M4T", "XTS"),
            references.map { it.camera }.toSet(),
        )
    }

    @TestFactory
    fun `every sample matches DJI's reference tool`(): List<DynamicTest> = references.map { ref ->
        DynamicTest.dynamicTest("${ref.camera}/${ref.file}") {
            DjiSamples.requireSdk()
            val file = File(DjiSamples.root, "${ref.camera}/${ref.file}")
            assumeTrue(file.isFile, "sample not present: $file")

            val frame = ThermalEngine().decode(file.readBytes())
            val stats = assertNotNull(frame.stats())

            assertEquals(ref.pixels, frame.width * frame.height, "pixel count")
            assertEquals(ref.pixels, stats.pixelCount, "every pixel has a reading")
            // The reference was printed with 4 decimals; float32 mean accumulation may differ in the 5th.
            assertEquals(ref.min, stats.min.toDouble(), 6e-4, "min")
            assertEquals(ref.max, stats.max.toDouble(), 6e-4, "max")
            assertEquals(ref.mean, stats.mean.toDouble(), 6e-4, "mean")
        }
    }

    @Test
    fun `the hottest and coldest pixel positions are inside the frame and agree with the reading`() {
        DjiSamples.requireSdk()
        val file = DjiSamples.files("M4T").firstOrNull()
        assumeTrue(file != null)
        val frame = ThermalEngine().decode(file!!.readBytes())
        val stats = frame.stats()!!

        assertEquals(stats.max, frame.temperatureAt(stats.maxAt.x, stats.maxAt.y))
        assertEquals(stats.min, frame.temperatureAt(stats.minAt.x, stats.minAt.y))
        assertTrue(stats.maxAt.x in 0 until frame.width && stats.maxAt.y in 0 until frame.height)
    }

    @Test
    fun `region statistics work on a DJI frame`() {
        DjiSamples.requireSdk()
        val file = DjiSamples.files("H30T").firstOrNull() // the 1280x1024 sensor: a different size to the common 640x512
        assumeTrue(file != null)
        val frame = ThermalEngine().decode(file!!.readBytes())
        assertEquals(1280, frame.width)
        assertEquals(1024, frame.height)

        val whole = frame.stats()!!
        val corner = frame.stats(Region(0, 0, 9, 9))!!
        assertEquals(100, corner.pixelCount)
        assertTrue(corner.min >= whole.min && corner.max <= whole.max)
        // A region around the hottest pixel must contain that maximum.
        val around = frame.stats(Region(whole.maxAt.x - 2, whole.maxAt.y - 2, whole.maxAt.x + 2, whole.maxAt.y + 2))!!
        assertEquals(whole.max, around.max)
    }
}
