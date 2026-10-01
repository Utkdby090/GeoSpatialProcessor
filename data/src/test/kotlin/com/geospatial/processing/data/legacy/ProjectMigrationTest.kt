package com.geospatial.processing.data.legacy

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.data.database.ProjectMigrationException
import com.geospatial.processing.data.database.ProjectMigrator
import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.RecordStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectMigrationTest {

    private val projectDir: File = Files.createTempDirectory("migration").toFile()
    private val dbFile = File(projectDir, "project.db")

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun openAndLoad() = ProjectSession.open(projectDir).use { session ->
        runBlocking { AssetRepository(session.database).getAll() }
    }

    @Test
    fun `old project is converted on first open, with every field and image`() {
        LegacyFixtures.createLegacyProjectDb(dbFile)

        val assets = openAndLoad()

        assertEquals(listOf("76_0", "77_0"), assets.map { it.property(K.TOWER_NUMBER) })
        val full = assets[0]
        assertEquals(K.PLUGIN_ID, full.pluginId)
        assertEquals(RecordStatus.DRAFT, full.status)
        assertEquals(12.5, full.latitude)
        assertEquals(77.5, full.longitude)
        assertEquals(
            mapOf(
                K.LINE_NAME to "Line A", K.TOWER_NUMBER to "76_0", K.CIRCUIT to "1",
                K.PHASE to "R", K.SIDE to "Left", K.DIRECTION to "North",
                K.CAPTURED_DATE to "2026-01-05", K.CAPTURED_TIME to "10:30",
                K.HUMIDITY to "40", K.EMISSIVITY to "0.95", K.AMBIENT_TEMP to "31",
                K.FAULT_DESCRIPTION to "Hot joint", K.FAULT_TEMP to "85", K.RISE_TEMP to "54",
                K.REPORT_TYPE to "tower_fault", K.FAULT_STATUS to "Fault", K.COMPANY_NAME to "Acme Grid",
                K.LOAD_PREFIX + "Load CKT1" to "120A", K.LOAD_PREFIX + "Load CKT2" to "98A",
            ),
            full.properties
        )

        // BLOBs became files; the empty BLOB (user cleared the slot) stays cleared; null = no entry.
        assertEquals(setOf(K.SLOT_THERMAL, K.SLOT_STRUCTURE, K.SLOT_LOCATION), full.images.keys)
        assertEquals(AssetImage.Cleared, full.images[K.SLOT_LOCATION])
        val thermal = File(projectDir, full.images.getValue(K.SLOT_THERMAL).relativePath!!)
        assertEquals("images/${full.id}/THERMAL.jpg", full.images.getValue(K.SLOT_THERMAL).relativePath)
        assertContentEquals(LegacyFixtures.JPEG, thermal.readBytes())

        val minimal = assets[1]
        assertEquals(RecordStatus.READY, minimal.status)
        assertNull(minimal.properties[K.PHASE], "null legacy columns are not invented")
        assertEquals(emptyMap(), minimal.images)
    }

    @Test
    fun `migration keeps a backup and the old table, and runs only once`() {
        LegacyFixtures.createLegacyProjectDb(dbFile)
        openAndLoad()

        val backup = File(projectDir, "project.db.pre-v3.bak")
        assertTrue(backup.isFile)
        assertTrue("geo_data" in LegacyFixtures.tables(backup), "backup is the untouched old database")
        val tables = LegacyFixtures.tables(dbFile)
        assertTrue("geo_data_legacy" in tables && "geo_data" !in tables)
        assertEquals(ProjectMigrator.SCHEMA_VERSION, LegacyFixtures.userVersion(dbFile))

        // Second open: nothing is converted again and no second backup appears.
        assertEquals(2, openAndLoad().size)
        assertFalse(File(projectDir, "project.db.pre-v3.2.bak").exists())
    }

    @Test
    fun `a failed migration leaves the project unchanged`() {
        LegacyFixtures.createLegacyProjectDb(dbFile)
        File(projectDir, "images").writeText("a FILE where the images folder should be") // makes image writes fail

        assertFailsWith<ProjectMigrationException> { ProjectSession.open(projectDir) }

        val tables = LegacyFixtures.tables(dbFile)
        assertTrue("geo_data" in tables, "old table must still be there")
        assertFalse("geo_data_legacy" in tables)
        assertEquals(0, LegacyFixtures.userVersion(dbFile))

        // Fix the cause and open again: the migration now succeeds.
        File(projectDir, "images").delete()
        assertEquals(2, openAndLoad().size)
    }

    @Test
    fun `new projects are simply stamped with the current version`() {
        assertEquals(emptyList(), openAndLoad())
        assertEquals(ProjectMigrator.SCHEMA_VERSION, LegacyFixtures.userVersion(dbFile))
        assertFalse(File(projectDir, "project.db.pre-v3.bak").exists())
    }

    @Test
    fun `load column json from Gson is parsed, junk is ignored`() {
        assertEquals(mapOf("Load CKT1" to "120A"), LegacyGeoData.parseLoadColumns("""{"Load CKT1":"120A"}"""))
        assertEquals(emptyMap(), LegacyGeoData.parseLoadColumns("not json"))
        assertEquals(emptyMap(), LegacyGeoData.parseLoadColumns(null))
    }
}
