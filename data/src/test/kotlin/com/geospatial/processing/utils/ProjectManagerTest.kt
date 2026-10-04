package com.geospatial.processing.utils

import com.geospatial.processing.domain.model.ReportSettings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectManagerTest {

    private val workspace: File = Files.createTempDirectory("pm").toFile()

    @AfterTest
    fun cleanUp() {
        workspace.deleteRecursively()
    }

    private fun create(name: String) = ProjectManager.createNewProject(workspace, name, "com.geo.telecom")
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    // ---- folder names ----

    @Test
    fun `folder names keep letters of any script and digits, and replace the rest`() {
        assertEquals("My_Project_2", ProjectManager.safeFolderName("My Project 2"))
        assertEquals("Line-A_north", ProjectManager.safeFolderName("Line-A/north"))
        assertEquals("Été_2026", ProjectManager.safeFolderName("Été 2026"))
        assertEquals("प्रोजेक्ट", ProjectManager.safeFolderName("प्रोजेक्ट"), "Hindi, with its vowel signs and viramas, is kept as written")
        assertEquals("电力线路一", ProjectManager.safeFolderName("电力线路一"))
        assertEquals("café", ProjectManager.safeFolderName("café"), "an accent written as a separate character is kept")
    }

    @Test
    fun `different non-English names no longer collapse into the same folder`() {
        val a = ProjectManager.safeFolderName("线路甲")
        val b = ProjectManager.safeFolderName("线路乙")
        assertTrue(a != b, "$a vs $b")
    }

    @Test
    fun `reserved Windows names, blanks, path tricks and very long names are handled`() {
        assertEquals("_CON", ProjectManager.safeFolderName("CON"))
        assertEquals("_nul", ProjectManager.safeFolderName("nul"))
        assertEquals("_COM1", ProjectManager.safeFolderName("COM1"))
        assertEquals("", ProjectManager.safeFolderName(""))
        assertEquals("", ProjectManager.safeFolderName("   "))
        assertEquals("_____", ProjectManager.safeFolderName("..\\.."), "dots and separators are replaced")
        assertEquals(80, ProjectManager.safeFolderName("x".repeat(500)).length)
        assertTrue(ProjectManager.safeFolderName("a/b\\c:d*e?f\"g<h>i|j").none { it in "/\\:*?\"<>|" })
    }

    @Test
    fun `isValidFolderName accepts ordinary names and rejects anything that could leave the workspace or break Windows`() {
        for (ok in listOf("Line A", "Line_A-2", "Été", "v1.2 final", "A")) assertTrue(ProjectManager.isValidFolderName(ok), ok)
        for (bad in listOf("", " ", ".", "..", "..\\x", "../x", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a|b", ".hidden",
            "trailing.", "trailing ", " leading", "CON", "con.txt", "NUL", "COM3", "x".repeat(81), "tab\there", "nul\u0000")) {
            assertFalse(ProjectManager.isValidFolderName(bad), "'$bad' should be rejected")
        }
    }

    // ---- creating a project ----

    @Test
    fun `a new project has its folders, descriptor and database`() {
        val dir = assertNotNull(create("Line A"))

        assertEquals("Line_A", dir.name)
        assertTrue(File(dir, "images").isDirectory)
        assertTrue(File(dir, "exports").isDirectory)
        assertTrue(File(dir, "project.db").isFile)
        val config = assertNotNull(ProjectManager.readProjectConfig(dir))
        assertEquals("Line A", config.projectName, "the name the user typed is kept")
        assertEquals("com.geo.telecom", config.pluginId)
        assertEquals(emptyList(), dir.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `a blank name or an existing project gives null and changes nothing`() {
        assertNull(create(""))
        assertNull(create("   "))
        val first = assertNotNull(create("Line A"))
        File(first, "marker.txt").writeText("keep me")

        assertNull(create("Line A"))
        assertNull(create("Line/A"), "a different spelling of the same folder name")

        assertEquals("keep me", File(first, "marker.txt").readText())
        assertEquals(listOf("Line_A"), workspace.list()!!.toList())
    }

    @Test
    fun `a project whose creation fails leaves no folder behind that would block the name`() {
        // A file where the workspace should be makes every step impossible.
        val notAFolder = File(workspace, "file.txt").apply { writeText("x") }

        assertFailsWith<java.io.IOException> { ProjectManager.createNewProject(notAFolder, "Line A", "p") }

        assertEquals(listOf("file.txt"), workspace.list()!!.toList())
    }

    @Test
    fun `many projects created at once are all complete`() {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (1..24).map { n -> pool.submit<File?> { create("P$n") } }.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(24, results.filterNotNull().size)
            results.filterNotNull().forEach { assertNotNull(ProjectManager.readProjectConfig(it), it.name); assertTrue(File(it, "project.db").isFile) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- reading the descriptor ----

    @Test
    fun `an unreadable or missing descriptor is null, not an exception`() {
        val dir = assertNotNull(create("Line A"))
        File(dir, "project.json").writeText("{ this is not json")
        assertNull(ProjectManager.readProjectConfig(dir))
        File(dir, "project.json").delete()
        assertNull(ProjectManager.readProjectConfig(dir))
        assertNull(ProjectManager.readProjectConfig(File(workspace, "nothing")))
    }

    // ---- report settings and the logo ----

    @Test
    fun `saving settings rewrites the descriptor completely and leaves no temporary file`() {
        val dir = assertNotNull(create("Line A"))
        repeat(30) { n -> ProjectManager.saveReportSettings(dir, ReportSettings(companyName = "Company $n")) }

        assertEquals("Company 29", ProjectManager.readProjectConfig(dir)!!.report.companyName)
        assertEquals(emptyList(), dir.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test
    fun `a new logo replaces an old one of another type only after it is stored`() {
        val dir = assertNotNull(create("Line A"))
        val jpg = File(workspace, "a.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9)) }
        val pngFile = File(workspace, "b.png").apply { writeBytes(png) }

        ProjectManager.saveReportSettings(dir, ReportSettings(), jpg)
        assertEquals(listOf("logo.jpg"), File(dir, "branding").list()!!.toList())

        val stored = ProjectManager.saveReportSettings(dir, ReportSettings(logoPath = "branding/logo.jpg"), pngFile)

        assertEquals("branding/logo.png", stored.logoPath)
        assertEquals(listOf("logo.png"), File(dir, "branding").list()!!.toList(), "the old logo of another type is gone")
        assertContentEquals(png, ProjectManager.readLogo(dir, stored))
    }

    @Test
    fun `saving other settings keeps the logo, and a blank logo path removes it`() {
        val dir = assertNotNull(create("Line A"))
        val logo = File(workspace, "b.png").apply { writeBytes(png) }
        val withLogo = ProjectManager.saveReportSettings(dir, ReportSettings(), logo)

        val kept = ProjectManager.saveReportSettings(dir, withLogo.copy(companyName = "X"))
        assertTrue(File(dir, "branding/logo.png").isFile, "an unchanged logo is not touched")
        assertEquals("branding/logo.png", kept.logoPath)

        ProjectManager.saveReportSettings(dir, kept.copy(logoPath = ""))
        assertEquals(emptyList(), File(dir, "branding").list()!!.toList())
        assertNull(ProjectManager.readLogo(dir, ProjectManager.readProjectConfig(dir)!!.report))
    }

    @Test
    fun `a logo that is not PNG or JPEG is refused and the existing logo and settings are untouched`() {
        val dir = assertNotNull(create("Line A"))
        val good = ProjectManager.saveReportSettings(dir, ReportSettings(companyName = "Before"), File(workspace, "b.png").apply { writeBytes(png) })

        assertFailsWith<IllegalArgumentException> {
            ProjectManager.saveReportSettings(dir, good.copy(companyName = "After"), File(workspace, "x.gif").apply { writeBytes(byteArrayOf(1)) })
        }

        assertEquals("Before", ProjectManager.readProjectConfig(dir)!!.report.companyName)
        assertContentEquals(png, ProjectManager.readLogo(dir, good))
    }

    @Test
    fun `a missing logo file or a path out of the project is not read`() {
        val dir = assertNotNull(create("Line A"))
        File(workspace, "secret.png").writeBytes(png)

        assertNull(ProjectManager.readLogo(dir, ReportSettings(logoPath = "branding/none.png")))
        assertNull(ProjectManager.readLogo(dir, ReportSettings(logoPath = "../secret.png")))
        assertNull(ProjectManager.readLogo(dir, ReportSettings(logoPath = "")))
    }

    @Test
    fun `saving settings for a folder without a descriptor says so`() {
        val dir = File(workspace, "bare").apply { mkdirs() }
        assertFailsWith<IllegalArgumentException> { ProjectManager.saveReportSettings(dir, ReportSettings()) }
    }
}
