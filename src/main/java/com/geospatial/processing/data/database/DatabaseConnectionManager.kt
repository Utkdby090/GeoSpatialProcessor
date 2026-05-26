package com.geospatial.processing.data.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
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
            // Placeholder for workspace schema tracking
        }
    }

    // Connects to an individual project's database
    fun connectToProject(projectDir: File) {
        val dbFile = File(projectDir, "project.db")
        connectAndInitialize(dbFile) {
            // 1. Keep the old tables initializing so your current MainScreen doesn't crash
            com.geospatial.processing.config.DatabaseConfig.init()

            // 2. Initializes the dynamic JSON table from your merged branch!
            SchemaUtils.create(DynamicAssetsTable)
        }
    }

    // Safely drops the current connection and establishes a new SQLite one
    private fun connectAndInitialize(dbFile: File, schemaInit: () -> Unit) {

        // --- 1. BULLETPROOF DISCONNECT ---
        // Instead of asking Exposed for the "current" transaction, we explicitly
        // give it our saved Database object and tell it to unregister it.
        currentDb?.let { oldDb ->
            try {
                TransactionManager.closeAndUnregister(oldDb)
            } catch (e: Exception) {
                // Safe to ignore closing errors
            }
        }

        // --- 2. SETUP NEW CONNECTION ---
        val newDb = Database.connect(
            url = "jdbc:sqlite:${dbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )
        currentDb = newDb

        // Set SQLite specific PRAGMAs for performance
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // --- 3. EXPLICIT TRANSACTION ---
        // By passing 'newDb' directly into the transaction block, we guarantee
        // Exposed won't get confused about which database to write to.
        transaction(newDb) {
            schemaInit()
        }
    }
}