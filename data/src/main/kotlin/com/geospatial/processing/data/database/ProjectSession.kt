package com.geospatial.processing.data.database

import com.geospatial.processing.data.table.AuditLogs
import com.geospatial.processing.data.table.GeoDataTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.Closeable
import java.io.File
import java.sql.Connection

/**
 * One open project: its folder and its single database (<project>/project.db).
 *
 * Replaces the global DatabaseConnectionManager. Whoever opens a session owns it and must close it
 * (the app does this through a Koin scope per open project), so there is never a "current database"
 * that code can silently fall back to. Pass [database] explicitly to repositories.
 */
class ProjectSession private constructor(
    val projectDir: File,
    val database: Database,
) : Closeable {

    @Volatile var isClosed = false
        private set

    override fun close() {
        if (isClosed) return
        isClosed = true
        SqliteDatabases.closeQuietly(database)
    }

    companion object {
        /**
         * Opens [projectDir]'s database, consolidating a v2.0 local_geodata.db first
         * (see [ProjectDatabaseFiles]) and creating any missing tables.
         * Must not be called while another session on the same folder is open.
         */
        fun open(projectDir: File): ProjectSession {
            val db = SqliteDatabases.open(ProjectDatabaseFiles.consolidate(projectDir))
            try {
                transaction(db) {
                    SchemaUtils.create(GeoDataTable, AuditLogs, DynamicAssetsTable)
                }
            } catch (e: Exception) {
                SqliteDatabases.closeQuietly(db)
                throw e
            }
            return ProjectSession(projectDir, db)
        }
    }
}

/** The workspace-level database (<workspace>/.metadata/workspace.db). It has no tables yet. */
class WorkspaceSession private constructor(
    val workspaceDir: File,
    val database: Database,
) : Closeable {

    override fun close() = SqliteDatabases.closeQuietly(database)

    companion object {
        fun open(workspaceDir: File): WorkspaceSession {
            val metaDir = File(workspaceDir, ".metadata").apply { mkdirs() }
            return WorkspaceSession(workspaceDir, SqliteDatabases.open(File(metaDir, "workspace.db")))
        }
    }
}

/** Connection settings shared by every SQLite file the app opens. */
internal object SqliteDatabases {
    fun open(dbFile: File): Database =
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

    fun closeQuietly(db: Database) {
        try { TransactionManager.closeAndUnregister(db) } catch (e: Exception) { /* already closed */ }
    }
}
