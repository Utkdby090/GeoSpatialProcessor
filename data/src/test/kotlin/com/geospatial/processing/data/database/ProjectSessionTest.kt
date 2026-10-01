package com.geospatial.processing.data.database

import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.Asset
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

    private fun asset(tower: String, position: Int = 0) =
        Asset(pluginId = "test", position = position, latitude = 1.0, longitude = 2.0, properties = mapOf("tower" to tower))

    private fun towers(session: ProjectSession) = runBlocking {
        AssetRepository(session.database).getAll().map { it.property("tower") }
    }

    @Test
    fun `open creates project db with tables`() {
        val dir = projectDir("A")
        ProjectSession.open(dir).use { session ->
            assertTrue(File(dir, ProjectDatabaseFiles.PROJECT_DB).isFile)
            assertEquals(emptyList(), towers(session))
        }
    }

    @Test
    fun `two open projects never see each other's records`() = runBlocking {
        val a = ProjectSession.open(projectDir("A"))
        val b = ProjectSession.open(projectDir("B"))
        try {
            AssetRepository(a.database).save(asset("A-1"))
            AssetRepository(b.database).save(asset("B-1", 0))
            AssetRepository(b.database).save(asset("B-2", 1))

            assertEquals(listOf("A-1"), towers(a))
            assertEquals(listOf("B-1", "B-2"), towers(b))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun `records survive close and reopen`() = runBlocking {
        val dir = projectDir("A")
        ProjectSession.open(dir).use { AssetRepository(it.database).save(asset("T-1")) }
        ProjectSession.open(dir).use { session -> assertEquals(listOf("T-1"), towers(session)) }
    }

    @Test
    fun `closing releases the database so the folder can be renamed`() = runBlocking {
        val dir = projectDir("A")
        val session = ProjectSession.open(dir)
        AssetRepository(session.database).save(asset("T-1"))
        session.close()
        session.close() // idempotent

        assertTrue(session.isClosed)
        assertTrue(dir.renameTo(File(root, "A-renamed")), "project folder still locked after close()")
    }

    @Test
    fun `a closed session can no longer be used`() {
        val session = ProjectSession.open(projectDir("A"))
        session.close()
        assertFailsWith<Exception> { towers(session) }
    }

    @Test
    fun `workspace session creates metadata folder`() {
        // workspace.db itself is created lazily by SQLite on first use; it has no tables yet.
        WorkspaceSession.open(root).use {
            assertTrue(File(root, ".metadata").isDirectory)
        }
    }
}
