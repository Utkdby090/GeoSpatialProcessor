package com.geospatial.processing.utils

import com.geospatial.processing.domain.model.ReportSettings
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReportSettingsTest {

    private val workspace = Files.createTempDirectory("ws").toFile()
    private val project: File = ProjectManager.createNewProject(workspace, "Line_A", "com.geo.telecom")!!

    private fun logo(name: String, bytes: ByteArray) = File(workspace, name).apply { writeBytes(bytes) }

    @Test
    fun `a project without report settings gets the defaults`() {
        // A project.json written before report settings existed.
        File(project, "project.json").writeText("""{"projectName":"Line_A","pluginId":"com.geo.telecom","createdAt":1,"lastModified":2}""")

        assertEquals(ReportSettings(), ProjectManager.readProjectConfig(project)!!.report)
    }

    @Test
    fun `settings and the logo are saved in the project`() {
        val stored = ProjectManager.saveReportSettings(
            project, ReportSettings("SUMMARY", "Acme", "#112233"), logo("my logo.PNG", byteArrayOf(1, 2, 3)),
        )

        assertEquals("branding/logo.png", stored.logoPath)
        val read = ProjectManager.readProjectConfig(project)!!
        assertEquals(stored, read.report)
        assertEquals("Line_A", read.projectName)
        assertContentEquals(byteArrayOf(1, 2, 3), ProjectManager.readLogo(project, stored))
    }

    @Test
    fun `a new logo replaces the old one even with another extension`() {
        val first = ProjectManager.saveReportSettings(project, ReportSettings(), logo("a.png", byteArrayOf(1)))
        ProjectManager.saveReportSettings(project, first, logo("b.jpg", byteArrayOf(2)))

        assertEquals(listOf("logo.jpg"), File(project, "branding").list()!!.toList())
        assertContentEquals(byteArrayOf(2), ProjectManager.readLogo(project, ProjectManager.readProjectConfig(project)!!.report))
    }

    @Test
    fun `keeping the path keeps the logo, blanking it removes it`() {
        val withLogo = ProjectManager.saveReportSettings(project, ReportSettings(), logo("a.png", byteArrayOf(1)))

        val kept = ProjectManager.saveReportSettings(project, withLogo.copy(companyName = "X"))
        assertNotNull(ProjectManager.readLogo(project, kept))

        val removed = ProjectManager.saveReportSettings(project, kept.copy(logoPath = ""))
        assertNull(ProjectManager.readLogo(project, removed))
        assertFalse(File(project, "branding/logo.png").exists())
    }

    @Test
    fun `only png and jpeg logos are accepted`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectManager.saveReportSettings(project, ReportSettings(), logo("a.exe", byteArrayOf(1)))
        }
        assertEquals(ReportSettings(), ProjectManager.readProjectConfig(project)!!.report)
    }

    @Test
    fun `a logo path pointing outside the project is never read`() {
        File(workspace, "secret.txt").writeText("secret")

        assertNull(ProjectManager.readLogo(project, ReportSettings(logoPath = "../secret.txt")))
        assertNull(ProjectManager.readLogo(project, ReportSettings(logoPath = "branding/missing.png")))
    }

    @Test
    fun `saving needs a project`() {
        assertFailsWith<IllegalArgumentException> { ProjectManager.saveReportSettings(File(workspace, "nope"), ReportSettings()) }
    }
}
