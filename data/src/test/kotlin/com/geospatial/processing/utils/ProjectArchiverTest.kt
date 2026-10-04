package com.geospatial.processing.utils

import com.geospatial.processing.utils.ProjectArchiver.ImportResult
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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

    // ---- export: the previous file is never lost, and the archive never contains itself ----

    private fun project(workspace: File, name: String = "Line_A") = File(workspace, name).apply {
        mkdirs()
        File(this, "project.json").writeText("{}")
        File(this, "images").mkdirs()
        File(this, "images/t1.jpg").writeText("img")
    }

    @Test
    fun `exporting over an existing file replaces it completely and leaves no temporary file`() {
        val ws = tempDir("ws")
        val p = project(ws)
        val out = tempDir("out")
        val geox = File(out, "Line_A.geox").apply { writeText("old export") }

        assertTrue(ProjectArchiver.exportProject(p, geox))

        ZipFile(geox).use { z -> assertTrue(z.getEntry("Line_A/project.json") != null) }
        assertEquals(listOf("Line_A.geox"), out.list()!!.toList())
    }

    @Test
    fun `a failed export reports false and leaves no temporary file or damaged destination`() {
        val ws = tempDir("ws")
        val p = project(ws)
        val out = tempDir("out")
        // A non-empty folder where the archive should go: packing works, the final move cannot.
        val blocked = File(out, "Line_A.geox").apply { mkdirs(); File(this, "keep.txt").writeText("keep") }

        assertFalse(ProjectArchiver.exportProject(p, blocked))

        assertEquals("keep", File(blocked, "keep.txt").readText())
        assertEquals(listOf("Line_A.geox"), out.list()!!.toList(), "no temp archive left behind")
    }

    @Test
    fun `exporting into the project folder itself does not pack the archive into itself`() {
        val ws = tempDir("ws")
        val p = project(ws)
        val geox = File(p, "backup.geox")

        assertTrue(ProjectArchiver.exportProject(p, geox))
        assertTrue(ProjectArchiver.exportProject(p, geox), "and again over the first export")

        ZipFile(geox).use { z ->
            assertEquals(setOf("Line_A/project.json", "Line_A/images/t1.jpg"), z.entries().toList().map { it.name }.toSet())
        }
    }

    @Test
    fun `a destination folder that does not exist yet is created`() {
        val p = project(tempDir("ws"))
        val geox = File(tempDir("out"), "new/sub/Line_A.geox")

        assertTrue(ProjectArchiver.exportProject(p, geox))
        assertTrue(geox.isFile)
    }

    @Test
    fun `names with spaces and non-English letters survive a round trip`() {
        val ws = tempDir("ws")
        val p = project(ws, "Línea Ñandú 线路")
        File(p, "images/ü.jpg").writeText("x")
        val geox = File(tempDir("out"), "x.geox")
        assertTrue(ProjectArchiver.exportProject(p, geox))

        val result = ProjectArchiver.importProject(geox, tempDir("ws2"))

        assertIs<ImportResult.Success>(result)
        assertEquals("Línea Ñandú 线路", result.projectDir.name)
        assertEquals("x", File(result.projectDir, "images/ü.jpg").readText())
    }

    @Test
    fun `live database side files and OS junk are not packed`() {
        val ws = tempDir("ws")
        val p = project(ws)
        listOf("project.db-wal", "project.db-shm", "project.db-journal", "Thumbs.db", ".DS_Store", "desktop.ini", "x.tmp").forEach { File(p, it).writeText("junk") }
        val geox = File(tempDir("out"), "x.geox")

        assertTrue(ProjectArchiver.exportProject(p, geox))

        ZipFile(geox).use { z -> assertEquals(setOf("Line_A/project.json", "Line_A/images/t1.jpg"), z.entries().toList().map { it.name }.toSet()) }
    }

    // ---- import ----

    @Test
    fun `leftover staging folders of interrupted imports are cleaned up, fresh ones are left alone`() {
        val ws = tempDir("ws")
        val stale = File(ws, ".import-old").apply { mkdirs(); File(this, "half.bin").writeText("x"); setLastModified(System.currentTimeMillis() - 3 * 24 * 3600 * 1000L) }
        val fresh = File(ws, ".import-running").apply { mkdirs() }

        val result = ProjectArchiver.importProject(zipOf("A/project.json" to "{}"), ws)

        assertIs<ImportResult.Success>(result)
        assertFalse(stale.exists(), "a day-old staging folder is removed")
        assertTrue(fresh.exists(), "a recent one may belong to a running import")
        assertEquals(setOf("A", ".import-running"), ws.list()!!.toSet())
    }

    @Test
    fun `a failed import leaves the workspace exactly as it was`() {
        val ws = tempDir("ws")
        File(ws, "Existing").mkdirs()
        val before = ws.list()!!.toSet()

        ProjectArchiver.importProject(zipOf("A/notes.txt" to "no project file"), ws)
        ProjectArchiver.importProject(zipOf("A/project.json" to "{}", "B/project.json" to "{}"), ws)
        ProjectArchiver.importProject(zipOf("A/project.json" to "{}", "A/../../evil.txt" to "x"), ws)
        ProjectArchiver.importProject(File(ws, "missing.geox"), ws)

        assertEquals(before, ws.list()!!.toSet())
        assertFalse(File(ws.parentFile, "evil.txt").exists())
    }

    @Test
    fun `an archive whose root folder name is unsafe is refused`() {
        val ws = tempDir("ws")
        for (root in listOf(".hidden", "..", "a:b", "a*b", "x|y")) {
            val result = ProjectArchiver.importProject(zipOf("$root/project.json" to "{}"), ws)
            assertIs<ImportResult.Failure>(result, root)
        }
        assertEquals(emptyList(), ws.list()!!.toList())
    }

    @Test
    fun `an empty archive and one with backslash paths are handled`() {
        val ws = tempDir("ws")
        val empty = File.createTempFile("empty", ".geox").also { ZipOutputStream(FileOutputStream(it)).use { } }
        assertIs<ImportResult.Failure>(ProjectArchiver.importProject(empty, ws))

        val windowsStyle = zipOf("Proj\\project.json" to "{}", "Proj\\images\\a.jpg" to "img")
        val result = ProjectArchiver.importProject(windowsStyle, ws)
        assertIs<ImportResult.Success>(result)
        assertEquals("img", File(result.projectDir, "images/a.jpg").readText())
    }
}
