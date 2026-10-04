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
    fun `rename refuses names that could leave the workspace or are not allowed, and says why`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Keep")
        val project = File(workspace, "Keep")

        for (bad in listOf("..\\Escaped", "../Escaped", "a/b", "a:b", ".hidden", "CON", "x".repeat(200), "")) {
            vm.dismissNotice()
            val renamed = vm.renameProject(project, bad)
            assertFalse(renamed, "'$bad'")
            if (bad.isNotEmpty()) assertEquals("Project not renamed", vm.state.value.notice?.title, "'$bad' gets an explanation")
            assertTrue(project.isDirectory, "'$bad' left the project where it was")
        }
        assertEquals(listOf("Keep"), workspace.list()!!.toList(), "nothing was moved, nothing appeared outside")
        assertFalse(File(root, "Escaped").exists())
    }

    @Test
    fun `rename onto an existing project is refused with a notice and a successful rename reports true`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "One")
        vm.createProject("com.geo.telecom", "Two")

        assertFalse(vm.renameProject(File(workspace, "One"), "Two"))
        assertEquals("Project not renamed", vm.state.value.notice?.title)
        assertTrue(File(workspace, "One").isDirectory && File(workspace, "Two").isDirectory)

        vm.dismissNotice()
        assertTrue(vm.renameProject(File(workspace, "One"), "  Three  "), "surrounding spaces are ignored")
        assertNull(vm.state.value.notice)
        assertTrue(File(workspace, "Three").isDirectory)
    }

    @Test
    fun `renaming to the same name is a quiet no-op`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Same")

        assertFalse(vm.renameProject(File(workspace, "Same"), "Same"))
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `a name with spaces and accents is allowed for a rename`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Plain")

        assertTrue(vm.renameProject(File(workspace, "Plain"), "Línea Norte 2"))
        assertEquals(listOf("Línea Norte 2"), vm.state.value.projects.map { it.name })
    }

    @Test
    fun `soft deleting two projects of the same name keeps both`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Dup")
        vm.deleteProject(File(workspace, "Dup"), moveToTrash = false)
        vm.createProject("com.geo.telecom", "Dup")
        File(workspace, "Dup/marker.txt").writeText("second")

        vm.deleteProject(File(workspace, "Dup"), moveToTrash = false)

        assertEquals(emptyList(), vm.state.value.projects)
        assertNull(vm.state.value.notice, "no failure was reported")
        val kept = workspace.list()!!.filter { it.startsWith(".deleted_Dup") }
        assertEquals(2, kept.size, "$kept")
        assertTrue(kept.any { File(workspace, "$it/marker.txt").isFile })
    }

    @Test
    fun `a project that cannot be removed is reported`() {
        val failing = ProjectTrash { _, _ -> throw java.io.IOException("in use by another program") }
        val vm = DashboardViewModel(workspace, navigator, listOf(TelecomPlugin()), legacyOffer, failing, Dispatchers.Unconfined)
        vm.createProject("com.geo.telecom", "Busy")

        vm.deleteProject(File(workspace, "Busy"), moveToTrash = false)

        assertEquals("Project not removed", vm.state.value.notice?.title)
        assertTrue(vm.state.value.notice!!.message.contains("in use by another program"))
        assertEquals(listOf("Busy"), vm.state.value.projects.map { it.name })
    }

    @Test
    fun `a blank project name is refused with a notice, and a failing workspace does not crash`() {
        val vm = newViewModel()
        assertFalse(vm.createProject("com.geo.telecom", "   "))
        assertEquals("Project not created", vm.state.value.notice?.title)
        assertEquals(emptyList(), navigator.opened)

        vm.dismissNotice()
        val file = File(root, "not-a-folder").apply { writeText("x") }
        val broken = DashboardViewModel(file, navigator, listOf(TelecomPlugin()), legacyOffer, io = Dispatchers.Unconfined)
        assertFalse(broken.createProject("com.geo.telecom", "Anything"))
        assertEquals("Project not created", broken.state.value.notice?.title)
    }

    @Test
    fun `non-English names make separate projects instead of colliding`() {
        val vm = newViewModel()

        assertTrue(vm.createProject("com.geo.telecom", "线路甲"))
        assertTrue(vm.createProject("com.geo.telecom", "线路乙"))

        assertEquals(setOf("线路甲", "线路乙"), vm.state.value.projects.map { it.name }.toSet())
    }

    @Test
    fun `the project worked on last is listed first, not the one whose folder changed last`() {
        val vm = newViewModel()
        vm.createProject("com.geo.telecom", "Older")
        vm.createProject("com.geo.telecom", "Newer")
        val now = System.currentTimeMillis()
        // "Older" has the more recent database write even though its folder itself is older.
        File(workspace, "Newer").setLastModified(now - 3_000_000)
        File(workspace, "Newer/project.db").setLastModified(now - 3_000_000)
        File(workspace, "Newer/project.json").setLastModified(now - 3_000_000)
        File(workspace, "Older").setLastModified(now - 9_000_000)
        File(workspace, "Older/project.db").setLastModified(now - 1_000)

        vm.refresh()

        assertEquals(listOf("Older", "Newer"), vm.state.value.projects.map { it.name })
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
