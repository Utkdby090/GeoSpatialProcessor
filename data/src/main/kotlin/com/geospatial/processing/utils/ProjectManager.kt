package com.geospatial.processing.utils

import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.domain.model.ProjectConfig
import com.geospatial.processing.domain.model.ReportSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

object ProjectManager {

    private val log = LoggerFactory.getLogger(ProjectManager::class.java)

    private val jsonFormatter = Json { prettyPrint = true }

    // \p{M}: combining marks (vowel signs and viramas in Hindi, accents written as separate characters) are part of words.
    private val unsafeFolderChars = Regex("[^\\p{L}\\p{M}\\p{N}_-]")
    private const val MAX_FOLDER_NAME = 80

    /** Device names Windows refuses as file or folder names, with or without an extension. */
    private val windowsReservedNames = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    /**
     * The folder name for a project called [projectName]: letters (any script), digits, "_" and "-"; everything else
     * becomes "_". Names Windows reserves ("CON", "NUL"...) get a "_" in front, and very long names are cut.
     * Empty when [projectName] is blank, which no folder can be made for.
     */
    fun safeFolderName(projectName: String): String {
        val trimmed = projectName.trim()
        if (trimmed.isEmpty()) return ""
        var name = trimmed.replace(unsafeFolderChars, "_").take(MAX_FOLDER_NAME)
        if (name.uppercase() in windowsReservedNames) name = "_$name"
        return name
    }

    /**
     * True when [name] can be used as it is for a project folder inside a workspace: no path separators or characters
     * Windows forbids, not "." / ".." or hidden (the dashboard hides names starting with a dot), no trailing dot or space,
     * not a reserved device name, and not so long that paths break.
     */
    fun isValidFolderName(name: String): Boolean =
        ProjectArchiver.isSafeFolderName(name) &&
            name == name.trim() && !name.endsWith(".") &&
            name.length <= MAX_FOLDER_NAME &&
            name.substringBefore('.').uppercase() !in windowsReservedNames

    /**
     * Scaffolds a brand new project directory with all required files.
     * Returns null when [projectName] is blank or a project folder of that name already exists.
     * @throws java.io.IOException when the folder cannot be created or written; nothing is left behind in that case
     */
    fun createNewProject(workspaceDir: File, projectName: String, pluginId: String): File? {
        // 1. Sanitize the project name for the OS folder creation
        val safeFolderName = safeFolderName(projectName)
        if (safeFolderName.isEmpty()) return null
        val projectDir = File(workspaceDir, safeFolderName)
        if (projectDir.exists()) return null

        try {
            // 2. Create the physical directory structure
            check(projectDir.mkdirs()) { "Could not create the folder ${projectDir.absolutePath}" }
            File(projectDir, "images").mkdirs()
            File(projectDir, "exports").mkdirs()

            // 3. Generate the project.json descriptor file
            val config = ProjectConfig(
                projectName = projectName,
                pluginId = pluginId,
                createdAt = System.currentTimeMillis(),
                lastModified = System.currentTimeMillis()
            )
            SafeFiles.writeText(File(projectDir, "project.json"), jsonFormatter.encodeToString(config))

            // 4. Create project.db with its tables. The project is NOT left open: whoever wants to
            //    work with it opens its own ProjectSession.
            ProjectSession.open(projectDir).close()
        } catch (e: Exception) {
            // A folder with a missing project.json or database would block this name for good ("already exists").
            projectDir.deleteRecursively()
            throw if (e is java.io.IOException) e else java.io.IOException("Could not create the project: ${e.message}", e)
        }
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
            log.warn("{} is not a readable project descriptor", configFile, e)
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
        var keep: File? = null
        if (newLogo != null) {
            val ext = newLogo.extension.lowercase()
            require(ext in logoExtensions) { "The logo must be a PNG or JPEG image." }
            // The new logo is in place before any old one is removed: a failed copy must not cost the project its logo.
            keep = File(brandingDir, "logo.$ext")
            SafeFiles.writeBytes(keep, newLogo.readBytes())
            logoPath = "branding/logo.$ext"
        } else if (logoPath.isBlank()) {
            keep = null
        } else {
            keep = File(projectDir, logoPath).takeIf { it.isFile } // an unchanged logo stays as it is
        }

        val stored = settings.copy(logoPath = logoPath)
        val updated = config.copy(report = stored, lastModified = System.currentTimeMillis())
        SafeFiles.writeText(File(projectDir, "project.json"), jsonFormatter.encodeToString(updated))
        if (newLogo != null || logoPath.isBlank()) {
            // Now that project.json points at the new logo (or at none), earlier logos of another file type can go.
            brandingDir.listFiles { f -> f.nameWithoutExtension == "logo" && f != keep && !f.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
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