package com.geospatial.processing.utils

import com.geospatial.processing.data.database.DatabaseConnectionManager
import com.geospatial.processing.domain.model.ProjectConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object ProjectManager {

    private val jsonFormatter = Json { prettyPrint = true }

    /**
     * Scaffolds a brand new project directory with all required files.
     */
    fun createNewProject(workspaceDir: File, projectName: String, pluginId: String): File? {
        // 1. Sanitize the project name for the OS folder creation
        val safeFolderName = projectName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val projectDir = File(workspaceDir, safeFolderName)

        if (projectDir.exists()) {
            println("Error: A project with this folder name already exists.")
            return null
        }

        // 2. Create the physical directory structure
        projectDir.mkdirs()
        File(projectDir, "images").mkdirs()
        File(projectDir, "exports").mkdirs()

        // 3. Generate the project.json descriptor file
        val config = ProjectConfig(
            projectName = projectName,
            pluginId = pluginId,
            createdAt = System.currentTimeMillis(),
            lastModified = System.currentTimeMillis()
        )

        val configFile = File(projectDir, "project.json")
        configFile.writeText(jsonFormatter.encodeToString(config))

        // 4. Initialize the localized SQLite database
        DatabaseConnectionManager.connectToProject(projectDir)

        return projectDir
    }

    /**
     * Reads the project.json file to determine what type of project this is.
     */
    fun readProjectConfig(projectDir: File): ProjectConfig? {
        val configFile = File(projectDir, "project.json")
        if (!configFile.exists()) return null

        return try {
            Json.decodeFromString<ProjectConfig>(configFile.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}