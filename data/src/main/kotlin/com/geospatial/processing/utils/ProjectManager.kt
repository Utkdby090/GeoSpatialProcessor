package com.geospatial.processing.utils

import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.domain.model.ProjectConfig
import com.geospatial.processing.domain.model.ReportSettings
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

        // 4. Create project.db with its tables. The project is NOT left open: whoever wants to
        //    work with it opens its own ProjectSession.
        ProjectSession.open(projectDir).close()

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

    private val logoExtensions = setOf("png", "jpg", "jpeg")

    /**
     * Saves [settings] in project.json. A [newLogo] is copied to `branding/logo.<ext>` (replacing any earlier one) and
     * its relative path stored; with no [newLogo], a blank `settings.logoPath` removes the logo and anything else keeps it.
     * Returns what was stored.
     * @throws IllegalArgumentException when the project has no readable project.json or the logo isn't PNG/JPEG
     */
    fun saveReportSettings(projectDir: File, settings: ReportSettings, newLogo: File? = null): ReportSettings {
        val config = requireNotNull(readProjectConfig(projectDir)) { "${projectDir.name} has no readable project.json" }
        val brandingDir = File(projectDir, "branding")

        var logoPath = settings.logoPath
        if (newLogo != null) {
            val ext = newLogo.extension.lowercase()
            require(ext in logoExtensions) { "The logo must be a PNG or JPEG image." }
            brandingDir.mkdirs()
            brandingDir.listFiles { f -> f.nameWithoutExtension == "logo" }?.forEach { it.delete() }
            newLogo.copyTo(File(brandingDir, "logo.$ext"), overwrite = true)
            logoPath = "branding/logo.$ext"
        } else if (logoPath.isBlank()) {
            brandingDir.listFiles { f -> f.nameWithoutExtension == "logo" }?.forEach { it.delete() }
        }

        val stored = settings.copy(logoPath = logoPath)
        val updated = config.copy(report = stored, lastModified = System.currentTimeMillis())
        File(projectDir, "project.json").writeText(jsonFormatter.encodeToString(updated))
        return stored
    }

    /** The project's logo file bytes, or null when none is set or it can't be read. */
    fun readLogo(projectDir: File, settings: ReportSettings): ByteArray? {
        if (settings.logoPath.isBlank()) return null
        val file = File(projectDir, settings.logoPath).canonicalFile
        // project.json could come from a .geox: never read outside the project folder.
        if (!file.path.startsWith(projectDir.canonicalPath + File.separator)) return null
        return runCatching { file.readBytes() }.getOrNull()
    }
}