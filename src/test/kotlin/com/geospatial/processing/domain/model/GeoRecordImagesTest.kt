package com.geospatial.processing.domain.model

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeoRecordImagesTest {

    private fun rootWith(tower: String, vararg names: String): File =
        Files.createTempDirectory("img").toFile().also { root ->
            File(root, tower).apply { mkdirs(); names.forEach { File(this, it).writeText("x") } }
        }

    private fun record(tower: String = "T1", thermal: ByteArray? = null) =
        GeoRecord(lineName = "L", towerNumber = tower, latitude = 0.0, longitude = 0.0, thermalImage = thermal)

    @Test
    fun `manual upload beats folder file`() {
        val root = rootWith("T1", "thermal.jpg")
        assertIs<ImageSource.FromBlob>(record(thermal = byteArrayOf(1)).resolveThermalImage(root.path))
    }

    @Test
    fun `cleared slot stays empty even if a folder file exists`() {
        val root = rootWith("T1", "thermal.jpg")
        assertIs<ImageSource.Missing>(record(thermal = ByteArray(0)).resolveThermalImage(root.path))
    }

    @Test
    fun `folder files fill all four slots and record becomes ready`() {
        val root = rootWith("T1", "thermal.jpg", "zoom.jpg", "tower.jpg", "overview.jpg")
        val r = record()
        assertEquals("tower.jpg", (r.resolveTowerImage(root.path) as ImageSource.FromFile).file.name)
        assertTrue(r.isDynamicallyReady(root.path))
    }

    @Test
    fun `missing location image keeps record in draft`() {
        val root = rootWith("T1", "thermal.jpg", "zoom.jpg", "tower.jpg")
        assertFalse(record().isDynamicallyReady(root.path))
    }
}
