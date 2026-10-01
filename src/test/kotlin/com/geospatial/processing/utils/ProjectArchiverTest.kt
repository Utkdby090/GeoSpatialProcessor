package com.geospatial.processing.utils

import com.geospatial.processing.utils.ProjectArchiver.ImportResult
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectArchiverTest {

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("test", ".geox")
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            entries.forEach { (name, body) ->
                zos.putNextEntry(ZipEntry(name)); zos.write(body.toByteArray()); zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `round trip export and import`() {
        val workspace = tempDir("ws")
        val project = File(workspace, "Line_A").apply { mkdirs() }
        File(project, "project.json").writeText("{}")
        File(project, "images").mkdirs()
        File(project, "images/t1.jpg").writeText("img")
        File(project, "project.db-wal").writeText("live sqlite side file")

        val geox = File(tempDir("out"), "Line_A.geox")
        assertTrue(ProjectArchiver.exportProject(project, geox))

        val target = tempDir("ws2")
        val result = ProjectArchiver.importProject(geox, target)
        assertIs<ImportResult.Success>(result)
        assertEquals("img", File(result.projectDir, "images/t1.jpg").readText())
        assertFalse(File(result.projectDir, "project.db-wal").exists(), "WAL file must not be exported")
    }

    @Test
    fun `zip-slip entries are rejected and nothing is written`() {
        val workspace = tempDir("ws")
        val victim = File(workspace.parentFile, "pwned-${System.nanoTime()}.txt")
        val evil = zipOf(
            "Proj/project.json" to "{}",
            "Proj/../../${victim.name}" to "owned",
        )
        val result = ProjectArchiver.importProject(evil, workspace)
        assertIs<ImportResult.Failure>(result)
        assertFalse(victim.exists())
        assertTrue(workspace.listFiles().isNullOrEmpty(), "staging folder must be cleaned up")
    }

    @Test
    fun `absolute and multi-root archives are rejected`() {
        val workspace = tempDir("ws")
        assertIs<ImportResult.Failure>(ProjectArchiver.importProject(zipOf("/etc/x" to "a"), workspace))
        assertIs<ImportResult.Failure>(
            ProjectArchiver.importProject(zipOf("A/project.json" to "{}", "B/x" to "b"), workspace)
        )
    }

    @Test
    fun `archive without project json is rejected`() {
        val result = ProjectArchiver.importProject(zipOf("Proj/readme.txt" to "hi"), tempDir("ws"))
        assertIs<ImportResult.Failure>(result)
    }

    @Test
    fun `existing project is never overwritten`() {
        val workspace = tempDir("ws")
        File(workspace, "Proj").mkdirs()
        File(workspace, "Proj/project.json").writeText("ORIGINAL")

        val result = ProjectArchiver.importProject(zipOf("Proj/project.json" to "NEW"), workspace)
        assertIs<ImportResult.Success>(result)
        assertEquals("Proj_2", result.projectDir.name)
        assertEquals("ORIGINAL", File(workspace, "Proj/project.json").readText())
    }

    @Test
    fun `entries whose names start with the root name are not mangled`() {
        val result = ProjectArchiver.importProject(
            zipOf("A/project.json" to "{}", "A/Abc.txt" to "keep"), tempDir("ws")
        )
        assertIs<ImportResult.Success>(result)
        assertEquals("keep", File(result.projectDir, "Abc.txt").readText())
    }
}
