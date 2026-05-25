package com.geospatial.processing.data.database

import com.geospatial.processing.config.DatabaseConfig // Assuming this is your current init file
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

object DatabaseConnectionManager {
    var currentDb: Database? = null
        private set

    // Connects to the master workspace database (which tracks projects)
    fun connectToWorkspace(workspaceDir: File) {
        val metaDir = File(workspaceDir, ".metadata")
        if (!metaDir.exists()) metaDir.mkdirs()

        val dbFile = File(metaDir, "workspace.db")
        connectAndInitialize(dbFile) {
            // Placeholder for workspace schema
        }
    }

    // Connects to an individual project's database
    fun connectToProject(projectDir: File) {
        val dbFile = File(projectDir, "project.db")
        connectAndInitialize(dbFile) {
            // Initialize your EXISTING database tables for this specific project folder
            DatabaseConfig.init()
        }
    }

    // Safely drops the current connection and establishes a new SQLite one
    private fun connectAndInitialize(dbFile: File, schemaInit: () -> Unit) {
        // Close existing connection if any
        try {
            TransactionManager.currentOrNull()?.connection?.close()
        } catch (e: Exception) { e.printStackTrace() }

        // Setup new connection
        currentDb = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )

        // Set SQLite specific PRAGMAs for performance
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        transaction {
            schemaInit()
        }
    }
}