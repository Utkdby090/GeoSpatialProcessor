package com.geospatial.processing.ui.workspace

import com.geospatial.processing.core.plugin.telecom.TelecomPlugin
import com.geospatial.processing.ui.navigation.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val root: File = Files.createTempDirectory("dashboard-test").toFile()
    private val workspace = File(root, "workspace").apply { mkdirs() }

    private class RecordingNavigator : AppNavigator {
        val opened = mutableListOf<File>()
        override fun openProject(projectDir: File): Boolean { opened += projectDir; return true }
        override fun closeProject() {}
    }

    private class FakeLegacySource(var pending: Boolean = false, var result: File? = null) : LegacyImportSource {
        var dismissed = false
        override fun hasPendingData() = pending && !dismissed
        override fun dismissForever() { dismissed = true }
        override suspend fun migrateInto(workspaceDir: File) = result
    }

    private val navigator = RecordingNavigator()
    private val legacySource = FakeLegacySource()
    private val legacyOffer = LegacyImportOffer(legacySource)

    private fun newViewModel() = DashboardViewModel(
        workspace, navigator, listOf(TelecomPlugin()), legacyOffer,
        io = Dispatchers.Unconfined,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    @Test
    fun `lists project folders but hides metadata and soft-deleted ones`() {
        File(workspace, "Alpha").mkdirs()
        File(workspace, ".metadata").mkdirs()
        File(workspace, ".deleted_Old").mkdirs()
        File(workspace, "notes.txt").writeText("not a project")

        assertEquals(listOf("Alpha"), newViewModel().state.value.projects.map { it.name })
    }

    @Test
    fun `creating a project scaffolds it and opens it`() {
        val vm = newViewModel()

        assertTrue(vm.createProject("com.geo.telecom", "Line 7"))

        val dir = File(workspace, "Line_7")
        assertTrue(File(dir, "project.json").isFile)
        assertTrue(File(dir, "project.db").isFile)
        assertEquals(listOf(dir), navigator.opened)
        assertEquals(listOf("Line_7"), vm.state.value.projects.map { it.name })
    }

    @Test
    fun `creating a duplicate project shows a notice and keeps the wizard open`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Dup")
        navigator.opened.clear()

        assertFalse(vm.createProject("com.geo.telecom", "Dup"))
        assertEquals(emptyList(), navigator.opened)
        assertNotNull(vm.state.value.notice)

        vm.dismissNotice()
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `rename and soft delete`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Old")

        vm.renameProject(File(workspace, "Old"), "New")
        assertEquals(listOf("New"), vm.state.value.projects.map { it.name })

        vm.deleteProject(File(workspace, "New"), moveToTrash = false)
        assertEquals(emptyList(), vm.state.value.projects)
        assertTrue(File(workspace, ".deleted_New").isDirectory, "soft delete keeps the data")
    }

    @Test
    fun `export adds the geox extension and import opens a copy`() = runBlocking {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Exported")
        val project = File(workspace, "Exported")

        vm.exportProject(project, File(root, "backup")).join()
        val geox = File(root, "backup.geox")
        assertTrue(geox.isFile)
        assertEquals("Export complete", vm.state.value.notice?.title)

        navigator.opened.clear()
        vm.importGeox(geox).join()
        val imported = navigator.opened.single()
        assertTrue(imported.isDirectory)
        assertTrue(imported != project, "import must never overwrite the existing project")
    }

    @Test
    fun `importing a non-geox file shows a failure notice`() = runBlocking {
        val vm = newViewModel()
        val bogus = File(root, "bogus.geox").apply { writeText("not a zip") }

        vm.importGeox(bogus).join()

        assertEquals("Import failed", vm.state.value.notice?.title)
        assertEquals(emptyList(), navigator.opened)
    }

    @Test
    fun `legacy offer - not now hides it for the rest of the session`() {
        legacySource.pending = true
        val vm = newViewModel()
        assertNotNull(vm.state.value.legacyOffer)

        vm.snoozeLegacyOffer()

        assertNull(vm.state.value.legacyOffer)
        assertNull(newViewModel().state.value.legacyOffer, "a new dashboard in the same session must not ask again")
        assertFalse(legacySource.dismissed)
    }

    @Test
    fun `legacy offer - don't ask again dismisses permanently`() {
        legacySource.pending = true
        val vm = newViewModel()

        vm.dismissLegacyOfferForever()

        assertTrue(legacySource.dismissed)
        assertNull(vm.state.value.legacyOffer)
    }

    @Test
    fun `legacy import success opens the new project`() = runBlocking {
        val imported = File(workspace, "Legacy_Import").apply { mkdirs() }
        legacySource.pending = true
        legacySource.result = imported
        val vm = newViewModel()

        vm.importLegacyData().join()

        assertNull(vm.state.value.legacyOffer)
        assertEquals(listOf(imported), navigator.opened)
    }

    @Test
    fun `legacy import failure keeps the dialog with an error`() = runBlocking {
        legacySource.pending = true
        legacySource.result = null
        val vm = newViewModel()

        vm.importLegacyData().join()

        val offer = assertNotNull(vm.state.value.legacyOffer)
        assertFalse(offer.isWorking)
        assertNotNull(offer.error)
        assertEquals(emptyList(), navigator.opened)
    }

    @Test
    fun `geox destination keeps or adds the extension`() {
        assertEquals("a.geox", DashboardViewModel.geoxDestination(File("a")).name)
        assertEquals("a.GEOX", DashboardViewModel.geoxDestination(File("a.GEOX")).name)
        assertEquals("a.zip.geox", DashboardViewModel.geoxDestination(File("a.zip")).name)
    }
}
