package com.geospatial.processing.data.database

import com.geospatial.processing.data.table.AuditLogs
import com.geospatial.processing.data.table.GeoDataTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

/**
 * Owns the two database connections the app ever needs:
 *  - the WORKSPACE database (.metadata/workspace.db) while the dashboard is shown
 *  - the PROJECT database (<project>/project.db) while a project is open
 *
 * Fixes from v1:
 *  - No more call into DatabaseConfig.init(), which silently connected the global
 *    ~/.geospatial/geospatial_v14.db and made it Exposed's default database. That is why
 *    every project used to share the same records and .geox exports had empty project.db files.
 *  - No hardcoded encryption secret. Project databases are plain SQLite: the data belongs to the
 *    customer, travels between machines in .geox files and will become a GeoPackage in Phase 2.
 *  - Repositories receive the project Database explicitly ([project]) instead of relying on
 *    "whichever database was connected last".
 */
object DatabaseConnectionManager {

    @Volatile private var workspaceDb: Database? = null
    @Volatile private var projectDb: Database? = null

    /** Folder of the project that is currently open, or null on the dashboard. */
    @Volatile var currentProjectDir: File? = null
        private set

    /** The open project's database. Throws if no project is open – that is a programming error. */
    val project: Database
        get() = projectDb ?: error("No project is open")

    fun connectToWorkspace(workspaceDir: File) {
        closeProject()
        closeQuietly(workspaceDb)

        val metaDir = File(workspaceDir, ".metadata").apply { mkdirs() }
        val db = open(File(metaDir, "workspace.db"))
        workspaceDb = db
        TransactionManager.defaultDatabase = db
    }

    fun connectToProject(projectDir: File) {
        closeProject()

        val db = open(ProjectDatabaseFiles.consolidate(projectDir))
        transaction(db) {
            SchemaUtils.create(GeoDataTable, AuditLogs, DynamicAssetsTable)
        }
        projectDb = db
        currentProjectDir = projectDir
        // Safety net for any code that still uses a bare transaction { } – it now hits THIS project.
        TransactionManager.defaultDatabase = db
    }

    /** Releases the project database so its folder can be exported, moved or deleted. */
    fun closeProject() {
        closeQuietly(projectDb)
        projectDb = null
        currentProjectDir = null
        TransactionManager.defaultDatabase = workspaceDb
    }

    private fun open(dbFile: File): Database =
        Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC",
            setupConnection = { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                    st.execute("PRAGMA busy_timeout = 5000")
                }
            },
            databaseConfig = DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
            },
        )

    private fun closeQuietly(db: Database?) {
        if (db == null) return
        try { TransactionManager.closeAndUnregister(db) } catch (e: Exception) { /* already closed */ }
    }
}
