package com.geospatial.processing.data.legacy

import com.geospatial.processing.data.database.ProjectMigrator
import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.Severity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaColumnMigrationTest {

    private val projectDir: File = Files.createTempDirectory("v4").toFile()
    private val dbFile = File(projectDir, "project.db")

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun sql(vararg statements: String) =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            statements.forEach { conn.createStatement().use { st -> st.execute(it) } }
        }

    /** A project exactly as v3 left it: assets without captured_at, user_version 3. */
    private fun createV3Project(asset: Asset) {
        ProjectSession.open(projectDir).use { s -> runBlocking { AssetRepository(s.database).save(asset) } }
        sql("ALTER TABLE assets DROP COLUMN captured_at", "ALTER TABLE assets DROP COLUMN severity", "PRAGMA user_version = 3")
    }

    private val asset = Asset(id = "a1", pluginId = "p", position = 0, latitude = 1.5, longitude = 2.5, properties = mapOf("k" to "v"))

    @Test
    fun `v3 project gets the new column, keeps its data and a backup is made`() {
        createV3Project(asset)

        val loaded = ProjectSession.open(projectDir).use { s -> runBlocking { AssetRepository(s.database).getAll() } }

        assertEquals(listOf(asset), loaded)
        assertNull(loaded.single().capturedAt)
        assertEquals(ProjectMigrator.SCHEMA_VERSION, LegacyFixtures.userVersion(dbFile))
        assertTrue(File(projectDir, "project.db.pre-v4.bak").exists())
        assertFalse(File(projectDir, "project.db.pre-v5.bak").exists(), "one backup covers the whole chain")
        assertEquals(Severity.NONE, loaded.single().severity)
    }

    @Test
    fun `capture time survives a save and reload after the migration`() {
        createV3Project(asset)
        val taken = Instant.parse("2024-05-01T10:20:30Z")

        val loaded = ProjectSession.open(projectDir).use { s ->
            runBlocking {
                val repo = AssetRepository(s.database)
                repo.save(asset.copy(capturedAt = taken))
                repo.getAll()
            }
        }

        assertEquals(taken, loaded.single().capturedAt)
    }

    @Test
    fun `migrating twice changes nothing`() {
        createV3Project(asset)
        ProjectSession.open(projectDir).close()
        ProjectSession.open(projectDir).close()

        assertFalse(File(projectDir, "project.db.pre-v4.2.bak").exists())
        assertEquals(ProjectMigrator.SCHEMA_VERSION, LegacyFixtures.userVersion(dbFile))
    }

    @Test
    fun `new projects need no backup`() {
        ProjectSession.open(projectDir).close()

        assertFalse(File(projectDir, "project.db.pre-v4.bak").exists())
        assertEquals(ProjectMigrator.SCHEMA_VERSION, LegacyFixtures.userVersion(dbFile))
    }

    @Test
    fun `v4 project gets the severity column with its own backup`() {
        ProjectSession.open(projectDir).use { s -> runBlocking { AssetRepository(s.database).save(asset) } }
        sql("ALTER TABLE assets DROP COLUMN severity", "PRAGMA user_version = 4")

        val loaded = ProjectSession.open(projectDir).use { s -> runBlocking { AssetRepository(s.database).getAll() } }

        assertEquals(listOf(asset), loaded)
        assertEquals(ProjectMigrator.SCHEMA_VERSION, LegacyFixtures.userVersion(dbFile))
        assertTrue(File(projectDir, "project.db.pre-v5.bak").exists())
        assertFalse(File(projectDir, "project.db.pre-v4.bak").exists())
    }

    @Test
    fun `severity is stored and read back, unknown names count as none`() {
        val loaded = ProjectSession.open(projectDir).use { s ->
            runBlocking {
                val repo = AssetRepository(s.database)
                repo.save(asset.copy(severity = Severity.HIGH))
                repo.save(asset.copy(id = "a2", position = 1))
                repo.getAll()
            }
        }
        assertEquals(listOf(Severity.HIGH, Severity.NONE), loaded.map { it.severity })

        sql("UPDATE assets SET severity = 'FROM_THE_FUTURE' WHERE id = 'a1'")
        val again = ProjectSession.open(projectDir).use { s -> runBlocking { AssetRepository(s.database).getAll() } }
        assertEquals(Severity.NONE, again.first().severity)
    }
}
