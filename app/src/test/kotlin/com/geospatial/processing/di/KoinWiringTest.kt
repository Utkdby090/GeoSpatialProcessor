package com.geospatial.processing.di

import com.geospatial.processing.auth.AuthState
import com.geospatial.processing.auth.LicenseBanner
import com.geospatial.processing.auth.LicenseGate
import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.core.plugin.telecom.TelecomKeys
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.ui.WorkbenchViewModel
import com.geospatial.processing.ui.navigation.AppNavigator
import com.geospatial.processing.ui.navigation.AppViewModel
import com.geospatial.processing.ui.navigation.WorkspaceStore
import com.geospatial.processing.ui.workspace.DashboardViewModel
import com.geospatial.processing.utils.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The real Koin modules, with only the OS-touching parts (licence store, preferences) replaced. */
@OptIn(ExperimentalCoroutinesApi::class)
class KoinWiringTest : KoinTest {

    private val root: File = Files.createTempDirectory("koin-test").toFile()

    private val testOverrides = module {
        single<LicenseGate> {
            object : LicenseGate {
                override fun evaluateOnStartup() = AuthState.Authorized
                override fun activate(key: String) = false
                override fun banner() = LicenseBanner("test", false)
            }
        }
        single<WorkspaceStore> {
            object : WorkspaceStore {
                override fun lastWorkspace(): File? = null
                override fun save(workspaceDir: File) {}
            }
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        startKoin {
            allowOverride(true)
            modules(allModules + testOverrides)
        }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun `app-level graph resolves`() {
        val app = get<AppViewModel>()
        assertSame(app, get<AppNavigator>(), "the navigator must be the same AppViewModel instance")
        get<DashboardViewModel> { parametersOf(root) }
    }

    @Test
    fun `a project scope provides the workbench and closes the database with the scope`() = runBlocking {
        val projectDir = checkNotNull(ProjectManager.createNewProject(root, "Scoped", "com.geo.telecom"))

        val project = get<ProjectOpener>().open(projectDir)
        val scope = checkNotNull(project.scope)
        val session = scope.get<ProjectSession>()
        val repository = scope.get<AssetRepository>()
        assertSame(repository, scope.get<AssetRepository>(), "repository is scoped to the project")
        assertEquals(TelecomKeys.PLUGIN_ID, scope.get<DomainPlugin>().pluginId, "plugin comes from project.json")

        repository.save(Asset(pluginId = TelecomKeys.PLUGIN_ID, position = 0, latitude = 0.0, longitude = 0.0,
            properties = mapOf(TelecomKeys.TOWER_NUMBER to "T-1")))
        val workbench = scope.get<WorkbenchViewModel>()
        workbench.initialLoad.join()
        assertEquals(listOf("T-1"), workbench.state.value.records.map { it.property(TelecomKeys.TOWER_NUMBER) })

        project.close()
        assertTrue(session.isClosed)
        assertTrue(scope.closed)
        assertTrue(projectDir.renameTo(File(root, "Renamed")), "database must be released after close")
    }

    @Test
    fun `each opening of a project gets a fresh scope and session`() {
        val projectDir = checkNotNull(ProjectManager.createNewProject(root, "Twice", "com.geo.telecom"))
        val opener = get<ProjectOpener>()

        val first = opener.open(projectDir)
        val firstSession = checkNotNull(first.scope).get<ProjectSession>()
        first.close()

        val second = opener.open(projectDir)
        val secondSession = checkNotNull(second.scope).get<ProjectSession>()
        assertNotSame(firstSession, secondSession)
        assertFalse(secondSession.isClosed)
        second.close()
    }
}
