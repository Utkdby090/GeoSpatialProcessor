package com.geospatial.processing.data.database

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectDatabaseFilesTest {

    private fun project(): File = Files.createTempDirectory("proj").toFile()

    @Test
    fun `new project uses project db`() {
        val dir = project()
        assertEquals("project.db", ProjectDatabaseFiles.consolidate(dir).name)
    }

    @Test
    fun `interim database becomes project db and old one is backed up`() {
        val dir = project()
        File(dir, "local_geodata.db").writeText("REAL RECORDS")
        File(dir, "project.db").writeText("empty schema")

        val result = ProjectDatabaseFiles.consolidate(dir)

        assertEquals("project.db", result.name)
        assertEquals("REAL RECORDS", result.readText())
        assertFalse(File(dir, "local_geodata.db").exists())
        assertEquals("empty schema", File(dir, "project.db.pre-v2.1.bak").readText())
    }

    @Test
    fun `pending journal moves with the database`() {
        val dir = project()
        File(dir, "local_geodata.db").writeText("db")
        File(dir, "local_geodata.db-journal").writeText("journal")

        ProjectDatabaseFiles.consolidate(dir)

        assertEquals("journal", File(dir, "project.db-journal").readText())
    }

    @Test
    fun `running twice is harmless`() {
        val dir = project()
        File(dir, "local_geodata.db").writeText("REAL RECORDS")
        ProjectDatabaseFiles.consolidate(dir)
        val second = ProjectDatabaseFiles.consolidate(dir)
        assertEquals("REAL RECORDS", second.readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".bak") })
    }
}
