package com.geospatial.processing.data.database

import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.GeoRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectSessionTest {

    private val root: File = Files.createTempDirectory("session-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun projectDir(name: String) = File(root, name).apply { mkdirs() }

    private fun record(tower: String) = GeoRecord(lineName = "Line", towerNumber = tower, latitude = 1.0, longitude = 2.0)

    @Test
    fun `open creates project db with tables`() = runBlocking {
        val dir = projectDir("A")
        ProjectSession.open(dir).use { session ->
            assertTrue(File(dir, ProjectDatabaseFiles.PROJECT_DB).isFile)
            assertEquals(emptyList(), GeoRepository(session.database).getAllRecords())
        }
    }

    @Test
    fun `two open projects never see each other's records`() = runBlocking {
        val a = ProjectSession.open(projectDir("A"))
        val b = ProjectSession.open(projectDir("B"))
        try {
            GeoRepository(a.database).saveRecord(record("A-1"))
            GeoRepository(b.database).saveRecord(record("B-1"))
            GeoRepository(b.database).saveRecord(record("B-2"))

            assertEquals(listOf("A-1"), GeoRepository(a.database).getAllRecords().map { it.towerNumber })
            assertEquals(listOf("B-1", "B-2"), GeoRepository(b.database).getAllRecords().map { it.towerNumber })
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun `records survive close and reopen`() = runBlocking {
        val dir = projectDir("A")
        ProjectSession.open(dir).use { GeoRepository(it.database).saveRecord(record("T-1")) }
        ProjectSession.open(dir).use { session ->
            assertEquals(listOf("T-1"), GeoRepository(session.database).getAllRecords().map { it.towerNumber })
        }
    }

    @Test
    fun `closing releases the database so the folder can be renamed`() = runBlocking {
        val dir = projectDir("A")
        val session = ProjectSession.open(dir)
        GeoRepository(session.database).saveRecord(record("T-1"))
        session.close()
        session.close() // idempotent

        assertTrue(session.isClosed)
        assertTrue(dir.renameTo(File(root, "A-renamed")), "project folder still locked after close()")
    }

    @Test
    fun `a closed session can no longer be used`() {
        val session = ProjectSession.open(projectDir("A"))
        session.close()
        assertFailsWith<Exception> { runBlocking { GeoRepository(session.database).getAllRecords() } }
    }

    @Test
    fun `workspace session creates metadata folder`() {
        // workspace.db itself is created lazily by SQLite on first use; it has no tables yet.
        WorkspaceSession.open(root).use {
            assertTrue(File(root, ".metadata").isDirectory)
        }
    }
}
