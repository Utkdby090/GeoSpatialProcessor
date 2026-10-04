package com.geospatial.processing.domain.thermal

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.assertNotNull

/** Shared access to DJI's sample R-JPEGs (`<repo>/dji/dataset`, not committed) and to the bundled SDK. */
internal object DjiSamples {

    val root = File("../dji/dataset")

    fun files(camera: String): List<File> =
        File(root, camera).listFiles { f -> f.extension.equals("jpg", true) }.orEmpty().sortedBy { it.name }

    /** Skips the calling test unless the bundled SDK can run here; fails if it should have loaded but did not. */
    fun requireSdk() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"), "the SDK is bundled for 64-bit Windows only")
        assertNotNull(DjiThermalSdk.library, "bundled SDK should load: ${DjiThermalSdk.unavailableReason}")
    }

    /** Skips the calling test unless [camera] has samples, then returns the first one's bytes. */
    fun firstBytes(camera: String = "M4T"): ByteArray {
        requireSdk()
        val file = files(camera).firstOrNull()
        assumeTrue(file != null, "no $camera samples in ${root.absolutePath}")
        return file!!.readBytes()
    }

    /** One value per pixel, so two frames can be compared exactly. */
    fun FloatArray.checksum(): Long = fold(17L) { acc, v -> acc * 31 + v.toRawBits() }
}
