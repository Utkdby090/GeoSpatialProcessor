package com.geospatial.processing.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.data.database.LegacyDatabaseMigrator
import com.geospatial.processing.ui.navigation.AppNavigator
import com.geospatial.processing.utils.ProjectArchiver
import com.geospatial.processing.utils.ProjectManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

data class ProjectSummary(val dir: File, val name: String, val path: String, val lastModified: Long)

data class Notice(val title: String, val message: String)

/** The "data from a previous version found" dialog. */
data class LegacyOfferState(val isWorking: Boolean = false, val error: String? = null)

data class DashboardUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val notice: Notice? = null,
    /** Null when the legacy-import dialog is not shown. */
    val legacyOffer: LegacyOfferState? = null,
)

/** Records saved by v1/v2.0 in the old shared database (see LegacyDatabaseMigrator). */
interface LegacyImportSource {
    fun hasPendingData(): Boolean
    fun dismissForever()
    /** Returns the new project folder, or null on failure. */
    suspend fun migrateInto(workspaceDir: File): File?
}

object MigratorLegacyImportSource : LegacyImportSource {
    override fun hasPendingData() = LegacyDatabaseMigrator.hasPendingLegacyData()
    override fun dismissForever() = LegacyDatabaseMigrator.dismiss()
    override suspend fun migrateInto(workspaceDir: File) = LegacyDatabaseMigrator.migrateInto(workspaceDir)
}

/** App-wide (Koin single): "Not now" hides the legacy offer for the rest of this app session. */
class LegacyImportOffer(val source: LegacyImportSource) {
    @Volatile var snoozedThisSession = false
    fun shouldOffer() = !snoozedThisSession && source.hasPendingData()
}

/** Removes a project folder from the dashboard without destroying data. */
fun interface ProjectTrash {
    fun remove(projectDir: File, moveToTrash: Boolean)
}

/** OS recycle bin when asked and supported; otherwise a soft delete (rename to a hidden ".deleted_" folder). */
object DesktopProjectTrash : ProjectTrash {
    override fun remove(projectDir: File, moveToTrash: Boolean) {
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (moveToTrash && desktop?.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) == true) {
            desktop.moveToTrash(projectDir)
        } else {
            // An earlier soft-deleted project of the same name may still be there (Windows cannot rename onto it).
            var target = File(projectDir.parentFile, ".deleted_${projectDir.name}")
            var n = 2
            while (target.exists()) target = File(projectDir.parentFile, ".deleted_${projectDir.name}_${n++}")
            if (!projectDir.renameTo(target)) {
                throw java.io.IOException("${projectDir.name} could not be moved. Is it open in another program?")
            }
        }
    }
}

/** State and actions of the project dashboard for one workspace. */
class DashboardViewModel(
    val workspaceDir: File,
    private val navigator: AppNavigator,
    val availablePlugins: List<DomainPlugin>,
    private val legacyOffer: LegacyImportOffer,
    private val trash: ProjectTrash = DesktopProjectTrash,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(DashboardViewModel::class.java)

    private val _state = MutableStateFlow(
        DashboardUiState(legacyOffer = if (legacyOffer.shouldOffer()) LegacyOfferState() else null)
    )
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        // Folders starting with a dot are hidden: ".metadata" and soft-deleted ".deleted_*" projects.
        val projects = workspaceDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { dir -> ProjectSummary(dir, dir.name, dir.absolutePath, lastActivity(dir)) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
        _state.update { it.copy(projects = projects) }
    }

    /**
     * When the project was last worked on. A folder's own time only changes when files are added or removed in it, so
     * editing towers (which rewrites project.db) would never move a project up the "recent" list; the database's time does.
     */
    private fun lastActivity(projectDir: File): Long =
        maxOf(projectDir.lastModified(), File(projectDir, "project.db").lastModified(), File(projectDir, "project.json").lastModified())

    fun openProject(projectDir: File) {
        navigator.openProject(projectDir)
    }

    /** Returns true if the project was created and opened (the wizard should close). */
    fun createProject(pluginId: String, projectName: String): Boolean {
        if (projectName.isBlank()) {
            showNotice("Project not created", "Give the project a name.")
            return false
        }
        val newProjectDir = try {
            ProjectManager.createNewProject(workspaceDir, projectName, pluginId)
        } catch (e: Exception) {
            log.error("Could not create project {}", projectName, e)
            showNotice("Project not created", "The project could not be created in ${workspaceDir.absolutePath}: ${e.message}")
            return false
        }
        if (newProjectDir == null) {
            showNotice("Project not created", "A project folder for \"$projectName\" already exists in this workspace.")
            return false
        }
        refresh()
        navigator.openProject(newProjectDir)
        return true
    }

    fun importGeox(geoxFile: File): Job = viewModelScope.launch {
        when (val result = withContext(io) { ProjectArchiver.importProject(geoxFile, workspaceDir) }) {
            is ProjectArchiver.ImportResult.Success -> {
                refresh()
                navigator.openProject(result.projectDir)
            }
            is ProjectArchiver.ImportResult.Failure -> showNotice("Import failed", result.reason)
        }
    }

    /** Exports [projectDir]; the file always gets the .geox extension, even if the user didn't type it. */
    fun exportProject(projectDir: File, chosenFile: File): Job = viewModelScope.launch {
        val destination = geoxDestination(chosenFile)
        val ok = withContext(io) { ProjectArchiver.exportProject(projectDir, destination) }
        if (ok) showNotice("Export complete", "Project saved to:\n${destination.absolutePath}")
        else showNotice(
            "Export failed",
            "The project could not be exported to ${destination.absolutePath}. " +
                "Check that the location is writable and has enough free space."
        )
    }

    /** Renames the project folder; returns whether it was renamed. The reason it was not is shown as a notice. */
    fun renameProject(projectDir: File, newName: String): Boolean {
        val name = newName.trim()
        val renamed = when {
            name == projectDir.name -> false // nothing to do, nothing to report
            // The name becomes a folder inside the workspace: no separators, "..", reserved or illegal characters.
            !ProjectManager.isValidFolderName(name) -> {
                showNotice("Project not renamed", "\"$newName\" cannot be used as a project name. Avoid \\ / : * ? \" < > | and names starting with a dot.")
                false
            }
            File(projectDir.parentFile, name).exists() -> {
                showNotice("Project not renamed", "A project called \"$name\" already exists in this workspace.")
                false
            }
            !projectDir.renameTo(File(projectDir.parentFile, name)) -> {
                showNotice("Project not renamed", "\"${projectDir.name}\" could not be renamed. Is it open in another program?")
                false
            }
            else -> true
        }
        refresh()
        return renamed
    }

    fun deleteProject(projectDir: File, moveToTrash: Boolean) {
        try {
            trash.remove(projectDir, moveToTrash)
        } catch (e: Exception) {
            log.error("Could not remove project {}", projectDir, e)
            showNotice("Project not removed", "\"${projectDir.name}\" could not be removed: ${e.message ?: "unknown error"}")
        }
        refresh()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // --- Legacy import offer ---

    fun importLegacyData(): Job = viewModelScope.launch {
        _state.update { it.copy(legacyOffer = LegacyOfferState(isWorking = true)) }
        val projectDir = legacyOffer.source.migrateInto(workspaceDir)
        if (projectDir != null) {
            _state.update { it.copy(legacyOffer = null) }
            refresh()
            navigator.openProject(projectDir)
        } else {
            _state.update {
                it.copy(
                    legacyOffer = LegacyOfferState(
                        error = "The import could not be completed. Your original data is untouched. " +
                            "You can try again or choose \"Not now\"."
                    )
                )
            }
        }
    }

    fun snoozeLegacyOffer() {
        legacyOffer.snoozedThisSession = true
        _state.update { it.copy(legacyOffer = null) }
    }

    fun dismissLegacyOfferForever() {
        legacyOffer.source.dismissForever()
        _state.update { it.copy(legacyOffer = null) }
    }

    private fun showNotice(title: String, message: String) =
        _state.update { it.copy(notice = Notice(title, message)) }

    companion object {
        fun geoxDestination(chosen: File): File =
            if (chosen.extension.equals("geox", ignoreCase = true)) chosen
            else File(chosen.parentFile, chosen.name + ".geox")
    }
}
