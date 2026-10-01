package com.geospatial.processing.data.database

import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.legacy.LegacyGeoData
import com.geospatial.processing.data.repository.AssetRows
import com.geospatial.processing.data.table.AuditLogs
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager

class ProjectMigrationException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Upgrades a project database to the current schema (tracked in SQLite's `PRAGMA user_version`).
 *
 * v3 (Phase 1): `geo_data` rows (fixed tower columns + image BLOBs) become `assets`, and every image
 * BLOB is written to <project>/images/<assetId>/. Safety:
 *  1. The database is copied to `project.db.pre-v3.bak` first (never deleted).
 *  2. Conversion, the table rename (`geo_data` → `geo_data_legacy`, kept for reference) and the
 *     version bump happen in ONE transaction.
 *  3. If anything fails, the transaction rolls back and the image files written so far are deleted,
 *     so the project is exactly as before; [ProjectMigrationException] is thrown.
 */
object ProjectMigrator {

    const val SCHEMA_VERSION = 3
    private val log = LoggerFactory.getLogger(ProjectMigrator::class.java)

    /** [dbFile] is the SQLite file behind [db]; [projectDir] receives the extracted images. */
    fun migrate(db: Database, dbFile: File, projectDir: File) {
        val version = transaction(db) { userVersion() }
        if (version >= SCHEMA_VERSION) return

        if (!transaction(db) { tableExists(LegacyGeoData.TABLE) }) {
            transaction(db) { exec("PRAGMA user_version = $SCHEMA_VERSION", explicitStatementType = StatementType.OTHER) }
            return
        }

        val backup = backup(dbFile)
        log.info("Migrating {} to schema v{} (backup: {})", dbFile, SCHEMA_VERSION, backup.name)

        val imageStore = ImageStore(projectDir)
        val written = mutableListOf<String>()
        try {
            transaction(db) {
                val rows = LegacyGeoData.readAll(this)
                val assets = rows.map { row ->
                    LegacyGeoData.toAsset(row) { assetId, slot, bytes ->
                        imageStore.write(assetId, slot, bytes).also { written += it }
                    }
                }
                assets.forEach { AssetRows.upsert(it) }
                exec("ALTER TABLE ${LegacyGeoData.TABLE} RENAME TO ${LegacyGeoData.TABLE}_legacy", explicitStatementType = StatementType.ALTER)
                AuditLogs.insert {
                    it[action] = "SCHEMA_MIGRATION"
                    it[details] = "Migrated ${assets.size} records to schema v$SCHEMA_VERSION (backup ${backup.name})"
                }
                exec("PRAGMA user_version = $SCHEMA_VERSION", explicitStatementType = StatementType.OTHER)
            }
        } catch (e: Exception) {
            written.forEach { imageStore.delete(it) }
            File(projectDir, ImageStore.IMAGES_DIR).listFiles()?.filter { it.isDirectory && it.list().isNullOrEmpty() }?.forEach { it.delete() }
            throw ProjectMigrationException("Could not upgrade ${projectDir.name}; the project was left unchanged.", e)
        }
    }

    /** Consistent copy of the database next to it (VACUUM INTO works while other connections are idle). */
    private fun backup(dbFile: File): File {
        var target = File(dbFile.parentFile, "${dbFile.name}.pre-v$SCHEMA_VERSION.bak")
        var n = 2
        while (target.exists()) target = File(dbFile.parentFile, "${dbFile.name}.pre-v$SCHEMA_VERSION.${n++}.bak")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.prepareStatement("VACUUM INTO ?").use { st ->
                st.setString(1, target.absolutePath)
                st.execute()
            }
        }
        return target
    }

    private fun Transaction.userVersion(): Int =
        exec("PRAGMA user_version") { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0

    private fun Transaction.tableExists(name: String): Boolean =
        exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name'") { rs -> rs.next() } ?: false
}
