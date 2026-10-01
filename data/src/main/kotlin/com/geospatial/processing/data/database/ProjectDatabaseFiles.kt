package com.geospatial.processing.data.database

import java.io.File

/**
 * Decides which SQLite file holds a project's data.
 *
 * v2.0 (interim fix in AppRouter) stored records in <project>/local_geodata.db, while
 * connectToProject() also created an almost-empty <project>/project.db.
 * On first open after the upgrade, local_geodata.db becomes the one and only project.db.
 * The old project.db is kept as a backup, never deleted.
 */
object ProjectDatabaseFiles {
    const val PROJECT_DB = "project.db"
    const val INTERIM_DB = "local_geodata.db"
    private val SIDE_FILES = listOf("-journal", "-wal", "-shm")

    /**
     * Returns the database file to open. If a rename fails (e.g. file locked by another program),
     * falls back to opening local_geodata.db directly, so data is never hidden from the user.
     * Must be called while no connection to either file is open.
     */
    fun consolidate(projectDir: File): File {
        val interim = File(projectDir, INTERIM_DB)
        val target = File(projectDir, PROJECT_DB)
        if (!interim.isFile) return target

        if (target.exists()) {
            var backup = File(projectDir, "$PROJECT_DB.pre-v2.1.bak")
            var n = 2
            while (backup.exists()) backup = File(projectDir, "$PROJECT_DB.pre-v2.1.$n.bak").also { n++ }
            if (!target.renameTo(backup)) return interim
            SIDE_FILES.forEach { suffix ->
                val side = File(projectDir, PROJECT_DB + suffix)
                if (side.exists()) side.renameTo(File(projectDir, backup.name + suffix))
            }
        }

        if (!interim.renameTo(target)) return interim
        // Move SQLite side-files with the database so no pending transaction is lost.
        SIDE_FILES.forEach { suffix ->
            val side = File(projectDir, INTERIM_DB + suffix)
            if (side.exists()) side.renameTo(File(projectDir, PROJECT_DB + suffix))
        }
        return target
    }
}
