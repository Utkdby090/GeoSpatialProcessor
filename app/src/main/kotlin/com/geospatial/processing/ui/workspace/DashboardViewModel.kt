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
            projectDir.renameTo(File(projectDir.parentFile, ".deleted_${projectDir.name}"))
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
            ?.sortedByDescending { it.lastModified() }
            ?.map { ProjectSummary(it, it.name, it.absolutePath, it.lastModified()) }
            ?: emptyList()
        _state.update { it.copy(projects = projects) }
    }

    fun openProject(projectDir: File) {
        navigator.openProject(projectDir)
    }

    /** Returns true if the project was created and opened (the wizard should close). */
    fun createProject(pluginId: String, projectName: String): Boolean {
        val newProjectDir = ProjectManager.createNewProject(workspaceDir, projectName, pluginId)
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

    fun renameProject(projectDir: File, newName: String) {
        val newDir = File(projectDir.parentFile, newName)
        if (projectDir.name != newName && !newDir.exists()) projectDir.renameTo(newDir)
        refresh()
    }

    fun deleteProject(projectDir: File, moveToTrash: Boolean) {
        try {
            trash.remove(projectDir, moveToTrash)
        } catch (e: Exception) {
            log.error("Could not remove project {}", projectDir, e)
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
