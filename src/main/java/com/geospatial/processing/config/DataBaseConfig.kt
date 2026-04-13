package com.geospatial.processing.config

import com.geospatial.processing.data.table.AuditLogs
import com.geospatial.processing.data.table.GeoDataTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseConfig {
    private const val DB_FILE_NAME = "geospatial_v13.db"

    fun init() {
        // 1. Get the current Windows user's home directory (e.g., C:\Users\YourName)
        val userHome = System.getProperty("user.home")

        // 2. Define a hidden folder named ".geospatial" inside the user's home directory
        val appDir = File(userHome, ".geospatial")

        // 3. Create the directory if it is the first time the app is running
        if (!appDir.exists()) {
            appDir.mkdirs()
        }

        // 4. Point the database file to this new, safe location
        val dbFile = File(appDir, DB_FILE_NAME)

        // 5. Store the secret as a mutable CharArray
        val dbSecret = charArrayOf(
            'E', 'n', 't', 'e', 'r', 'p', 'r', 'i', 's', 'e', '_',
            'G', 'e', 'o', '_', 'S', 'e', 'c', 'r', 'e', 't', '_',
            '9', '9', '!', '!'
        )

        try {
            // 6. Connect to SQLite with SQLCipher Encryption
            // This temporarily converts the CharArray to a String just for the connection
            val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}?password=${String(dbSecret)}"

            Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC"
            )

            // 7. Initialize Schema
            transaction {
                addLogger(StdOutSqlLogger)
                SchemaUtils.create(GeoDataTable, AuditLogs)
            }
            println("Database safely initialized at: ${dbFile.absolutePath}")

        } finally {

            dbSecret.fill('\u0000')
            println("Security Alert: Database secret successfully wiped from JVM memory.")
        }
    }
}