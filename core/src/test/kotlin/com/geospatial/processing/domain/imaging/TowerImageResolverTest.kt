package com.geospatial.processing.domain.imaging

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TowerImageResolverTest {

    private fun tempRoot(): File = Files.createTempDirectory("tir").toFile().apply { deleteOnExit() }

    private fun folderWith(root: File, tower: String, vararg names: String): File =
        File(root, tower).apply { mkdirs(); names.forEach { File(this, it).writeText("x") } }

    @Test
    fun `tokenizer splits on separators, camelCase and digit boundaries`() {
        assertEquals(listOf("repair", "sleeve", "01"), TowerImageResolver.tokenize("Repair_Sleeve_01.jpg"))
        assertEquals(listOf("76", "0", "thermal"), TowerImageResolver.tokenize("76_0thermal.JPG"))
        assertEquals(listOf("thermal", "image"), TowerImageResolver.tokenize("ThermalImage.png"))
        assertEquals(listOf("ir", "image"), TowerImageResolver.tokenize("IRImage.jpg"))
        assertEquals(listOf("dji", "20240101120000", "0001", "t"), TowerImageResolver.tokenize("DJI_20240101120000_0001_T.JPG"))
    }

    @Test
    fun `words that merely contain 'ir' are not thermal`() {
        for (name in listOf("repair_sleeve.jpg", "pair.jpg", "first_view.jpg", "dir_tower.jpg", "wire_joint.jpg")) {
            assertFalse(ImageSlot.THERMAL in TowerImageResolver.classify(name, "tower"), name)
        }
    }

    @Test
    fun `real thermal names are recognised`() {
        for (name in listOf("thermal.jpg", "76_0_IR.jpg", "IR_001.jpg", "Thermal1.jpg", "DJI_0001_T.JPG", "radiometric.jpg")) {
            assertEquals(setOf(ImageSlot.THERMAL), TowerImageResolver.classify(name, "tower"), name)
        }
    }

    @Test
    fun `sleeve photos named repair now reach the structure slot (v1 bug)`() {
        val root = tempRoot()
        folderWith(root, "T12", "repair_sleeve.jpg", "thermal.jpg", "zoom.jpg", "overview.jpg")
        val slots = TowerImageResolver.resolve(root.path, "T12", "repair_sleeve")
        assertEquals("thermal.jpg", slots[ImageSlot.THERMAL]?.name)
        assertEquals("repair_sleeve.jpg", slots[ImageSlot.STRUCTURE]?.name)
        assertEquals("zoom.jpg", slots[ImageSlot.RGB_ZOOM]?.name)
        assertEquals("overview.jpg", slots[ImageSlot.LOCATION]?.name)
    }

    @Test
    fun `DJI suffix convention fills all slots`() {
        val root = tempRoot()
        folderWith(root, "45", "DJI_0001_T.JPG", "DJI_0001_Z.JPG", "DJI_0001_W.JPG", "DJI_0002_V.JPG", "site.jpg")
        val slots = TowerImageResolver.resolve(root.path, "45", "tower")
        assertEquals("DJI_0001_T.JPG", slots[ImageSlot.THERMAL]?.name)
        assertEquals("DJI_0001_Z.JPG", slots[ImageSlot.RGB_ZOOM]?.name)
        assertEquals("DJI_0001_W.JPG", slots[ImageSlot.STRUCTURE]?.name)
        assertEquals("site.jpg", slots[ImageSlot.LOCATION]?.name)
    }

    @Test
    fun `a file never fills two slots`() {
        val root = tempRoot()
        folderWith(root, "9", "tower_zoom.jpg")
        val slots = TowerImageResolver.resolve(root.path, "9", "tower")
        assertEquals(1, slots.size)
    }

    @Test
    fun `tower numbers with slashes map to underscore folders`() {
        val root = tempRoot()
        folderWith(root, "76_0", "thermal.jpg")
        val slots = TowerImageResolver.resolve(root.path, "76/0", "tower")
        assertEquals("thermal.jpg", slots[ImageSlot.THERMAL]?.name)
    }

    @Test
    fun `tower numbers cannot escape the image root`() {
        val root = tempRoot()
        val outside = File(root.parentFile, "outside-${System.nanoTime()}").apply { mkdirs(); deleteOnExit() }
        assertNull(TowerImageResolver.findTowerFolder(File(root, "sub").apply { mkdirs() }.path, "../${outside.name}"))
        assertTrue(TowerImageResolver.resolve(root.path, "..", "tower").isEmpty())
    }

    @Test
    fun `results are deterministic regardless of directory order`() {
        val root = tempRoot()
        folderWith(root, "1", "b_thermal.jpg", "a_thermal.jpg")
        assertEquals("a_thermal.jpg", TowerImageResolver.resolve(root.path, "1", "tower")[ImageSlot.THERMAL]?.name)
    }
}
