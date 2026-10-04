package com.geospatial.processing.ui.navigation

import com.geospatial.processing.auth.AuthState
import com.geospatial.processing.auth.LicenseBanner
import com.geospatial.processing.auth.LicenseGate
import com.geospatial.processing.data.database.WorkspaceSession
import com.geospatial.processing.di.OpenProject
import com.geospatial.processing.di.ProjectOpener
import com.geospatial.processing.domain.model.ProjectConfig
import com.geospatial.processing.utils.ProjectManager
import com.geospatial.processing.utils.WorkspacePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File

/** Which screen the main window shows. */
sealed interface AppScreen {
    data object WorkspaceSelection : AppScreen
    data class Dashboard(val workspaceDir: File) : AppScreen
    data class Workbench(val workspaceDir: File, val project: OpenProject) : AppScreen
}

/** Navigation entry points used by the screen ViewModels. */
interface AppNavigator {
    /** Opens [projectDir] in the workbench. Returns false if it is not a valid project. */
    fun openProject(projectDir: File): Boolean
    fun closeProject()
}

/** Remembers the last workspace between launches. */
interface WorkspaceStore {
    fun lastWorkspace(): File?
    fun save(workspaceDir: File)
}

object PrefsWorkspaceStore : WorkspaceStore {
    override fun lastWorkspace(): File? = WorkspacePrefs.getLastWorkspace()
    override fun save(workspaceDir: File) = WorkspacePrefs.saveWorkspace(workspaceDir.absolutePath)
}

/**
 * Application-level state: the licence/trial gate and navigation between
 * workspace selection → project dashboard → workbench.
 *
 * Lives as long as the app (a Koin single), so it is a plain class rather than an androidx ViewModel.
 * It owns the open workspace and project sessions and closes them on navigation and [shutdown].
 */
class AppViewModel(
    private val licenseGate: LicenseGate,
    private val workspaceStore: WorkspaceStore,
    private val projectOpener: ProjectOpener,
    private val readProjectConfig: (File) -> ProjectConfig? = ProjectManager::readProjectConfig,
    private val openWorkspace: (File) -> Closeable = WorkspaceSession::open,
    /** Whether the remembered workspace is still there; tests replace it because they use made-up folder names. */
    private val workspaceExists: (File) -> Boolean = File::isDirectory,
) : AppNavigator {

    private val log = LoggerFactory.getLogger(AppViewModel::class.java)

    private val _auth = MutableStateFlow(licenseGate.evaluateOnStartup())
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _banner = MutableStateFlow<LicenseBanner?>(null)
    /** Null until the app is authorized (the banner is only shown in the main window). */
    val banner: StateFlow<LicenseBanner?> = _banner.asStateFlow()

    private var workspaceSession: Closeable? = null

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.WorkspaceSelection)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    init {
        if (_auth.value == AuthState.Authorized) onAuthorized()
    }

    /** Lock screen: try a licence key. Returns true if it unlocked the app. */
    fun submitLicenseKey(key: String): Boolean {
        if (!licenseGate.activate(key)) return false
        _auth.value = AuthState.Authorized
        onAuthorized()
        return true
    }

    private fun onAuthorized() {
        _banner.value = licenseGate.banner()
        // A remembered workspace that is gone (an unplugged drive, a deleted folder) sends the user to pick one, instead
        // of an empty dashboard that cannot create or open anything.
        workspaceStore.lastWorkspace()?.takeIf(workspaceExists)?.let { enterWorkspace(it) }
    }

    /** Opens [workspaceDir], creating it if needed. Returns false (staying where it was) when that is not possible. */
    fun selectWorkspace(workspaceDir: File): Boolean {
        if (!workspaceDir.exists() && !workspaceDir.mkdirs()) {
            log.warn("Could not create workspace folder {}", workspaceDir)
            return false
        }
        if (!enterWorkspace(workspaceDir)) return false
        workspaceStore.save(workspaceDir) // only a workspace that opened is remembered for the next start
        return true
    }

    private fun enterWorkspace(workspaceDir: File): Boolean {
        closeOpenProject()
        workspaceSession?.close()
        workspaceSession = null
        return try {
            workspaceSession = openWorkspace(workspaceDir)
            _screen.value = AppScreen.Dashboard(workspaceDir)
            true
        } catch (e: Exception) {
            log.error("Could not open workspace {}", workspaceDir, e)
            _screen.value = AppScreen.WorkspaceSelection
            false
        }
    }

    override fun openProject(projectDir: File): Boolean {
        val workspaceDir = when (val current = _screen.value) {
            is AppScreen.Dashboard -> current.workspaceDir
            is AppScreen.Workbench -> current.workspaceDir
            AppScreen.WorkspaceSelection -> return false
        }
        if (readProjectConfig(projectDir) == null) {
            log.warn("Invalid project (missing or unreadable project.json): {}", projectDir)
            return false
        }
        closeOpenProject()
        val project = try {
            projectOpener.open(projectDir)
        } catch (e: Exception) {
            log.error("Could not open project database in {}", projectDir, e)
            _screen.value = AppScreen.Dashboard(workspaceDir)
            return false
        }
        _screen.value = AppScreen.Workbench(workspaceDir, project)
        return true
    }

    /** Closes the project database (so the folder can be exported/renamed) and returns to the dashboard. */
    override fun closeProject() {
        val current = _screen.value as? AppScreen.Workbench ?: return
        current.project.close()
        _screen.value = AppScreen.Dashboard(current.workspaceDir)
    }

    private fun closeOpenProject() {
        (_screen.value as? AppScreen.Workbench)?.project?.close()
    }

    /** Called when the app exits: closes the project and workspace databases cleanly. */
    fun shutdown() {
        closeOpenProject()
        workspaceSession?.close()
        workspaceSession = null
    }
}
