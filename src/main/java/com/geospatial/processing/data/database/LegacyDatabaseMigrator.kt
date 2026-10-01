package com.geospatial.processing.data.database

import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.utils.ProjectManager
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.io.File

/**
 * One-time rescue of data written by v1/v2.0, which stored ALL records of ALL projects in
 * ~/.geospatial/geospatial_v14.db (encrypted with a key compiled into the app).
 *
 * The records are copied into a new project called "Legacy Import". The old file is NOT deleted;
 * a ".migrated" marker stops the prompt from appearing again.
 *
 * REMOVE THIS CLASS (and the key below) once all customers have upgraded. The key only unlocks
 * data that was already exposed by v1 – nothing new is ever written with it.
 */
object LegacyDatabaseMigrator {

    private val legacyFile = File(System.getProperty("user.home"), ".geospatial/geospatial_v14.db")
    private val markerFile = File(legacyFile.parentFile, "geospatial_v14.db.migrated")

    // Same secret v1 used. Kept only to read old data; see class doc.
    private const val LEGACY_KEY = "Enterprise_Geo_Secret_99!!"

    fun hasPendingLegacyData(): Boolean = legacyFile.isFile && !markerFile.exists()

    /** Lets the user dismiss the offer permanently without importing. */
    fun dismiss() {
        markerFile.parentFile?.mkdirs()
        markerFile.writeText("dismissed")
    }

    /**
     * Copies every legacy record into a new project in [workspaceDir].
     * Returns the new project folder (left OPEN as the current project), or null on failure.
     */
    suspend fun migrateInto(workspaceDir: File): File? {
        if (!legacyFile.isFile) return null

        val legacyDb = Database.connect(
            url = "jdbc:sqlite:${legacyFile.absolutePath}?password=$LEGACY_KEY",
            driver = "org.sqlite.JDBC",
        )
        return try {
            val records = GeoRepository(legacyDb).getAllRecords()

            val projectDir = ProjectManager.createNewProject(workspaceDir, uniqueName(workspaceDir), "com.geo.telecom")
                ?: return null
            val target = GeoRepository(DatabaseConnectionManager.project)
            records.forEach { target.saveRecord(it.copy(id = 0)) }
            target.logAuditAction("LEGACY_IMPORT", "Imported ${records.size} records from ${legacyFile.name}")

            markerFile.writeText("migrated to ${projectDir.absolutePath}")
            projectDir
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { TransactionManager.closeAndUnregister(legacyDb) } catch (e: Exception) { }
        }
    }

    private fun uniqueName(workspaceDir: File): String {
        var name = "Legacy Import"
        var n = 2
        while (File(workspaceDir, name.replace(Regex("[^a-zA-Z0-9_-]"), "_")).exists()) {
            name = "Legacy Import $n"; n++
        }
        return name
    }
}
