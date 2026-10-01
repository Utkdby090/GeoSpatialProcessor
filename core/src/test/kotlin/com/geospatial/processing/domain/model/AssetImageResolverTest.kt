package com.geospatial.processing.domain.model

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssetImageResolverTest {

    private val projectDir: File = Files.createTempDirectory("project").toFile()
    private val root: File = Files.createTempDirectory("images").toFile()
    private val resolver = AssetImageResolver(FakePlugin(), projectDir)

    private fun folderImages(name: String, vararg slots: String) =
        File(root, name).apply { mkdirs(); slots.forEach { File(this, "$it.jpg").writeText("folder") } }

    private fun manualImage(relativePath: String) =
        File(projectDir, relativePath).apply { parentFile.mkdirs(); writeText("manual") }

    @Test
    fun `manual image beats the folder image`() {
        folderImages("T1", "A")
        manualImage("images/x/A.jpg")
        val a = asset("T1").copy(images = mapOf("A" to AssetImage("images/x/A.jpg")))

        val source = assertIs<ImageSource.FromFile>(resolver.resolve(a, root.path).getValue("A"))
        assertEquals("manual", source.file.readText())
    }

    @Test
    fun `cleared slot stays empty even if the folder has an image`() {
        folderImages("T1", "A")
        val a = asset("T1").copy(images = mapOf("A" to AssetImage.Cleared))

        assertIs<ImageSource.Missing>(resolver.resolve(a, root.path).getValue("A"))
    }

    @Test
    fun `missing manual file falls back to the folder`() {
        folderImages("T1", "A")
        val a = asset("T1").copy(images = mapOf("A" to AssetImage("images/gone/A.jpg")))

        val source = assertIs<ImageSource.FromFile>(resolver.resolve(a, root.path).getValue("A"))
        assertEquals("folder", source.file.readText())
    }

    @Test
    fun `asset is ready only when every slot has an image`() {
        folderImages("T1", "A", "B")
        folderImages("T2", "A")

        assertTrue(resolver.isReady(asset("T1"), root.path))
        assertFalse(resolver.isReady(asset("T2"), root.path))
    }
}
