package com.geospatial.processing.ui.navigation

import com.geospatial.processing.auth.AuthState
import com.geospatial.processing.auth.LicenseBanner
import com.geospatial.processing.auth.LicenseGate
import com.geospatial.processing.di.OpenProject
import com.geospatial.processing.di.ProjectOpener
import com.geospatial.processing.domain.model.ProjectConfig
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppViewModelTest {

    private class FakeGate(var startup: AuthState, private val validKey: String = "GOOD") : LicenseGate {
        override fun evaluateOnStartup() = startup
        override fun activate(key: String) = key == validKey
        override fun banner() = LicenseBanner("Trial Mode: 10 days left", isWarning = false)
    }

    private class FakeStore(var last: File? = null) : WorkspaceStore {
        override fun lastWorkspace() = last
        override fun save(workspaceDir: File) { last = workspaceDir }
    }

    private class FakeOpener(var failWith: Exception? = null) : ProjectOpener {
        val opened = mutableListOf<File>()
        val closed = mutableListOf<File>()
        override fun open(projectDir: File): OpenProject {
            failWith?.let { throw it }
            opened += projectDir
            return OpenProject(projectDir, scope = null) { closed += projectDir }
        }
    }

    private class FakeWorkspace(val dir: File) : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    private val workspace = File("ws")
    private val projectA = File(workspace, "A")
    private val projectB = File(workspace, "B")

    private val gate = FakeGate(AuthState.Authorized)
    private val store = FakeStore(last = workspace)
    private val opener = FakeOpener()
    private val workspaces = mutableListOf<FakeWorkspace>()
    private val validProjects = mutableSetOf(projectA, projectB)
    private var openFailure: Exception? = null
    private val missingWorkspaces = mutableSetOf<File>()

    private fun newViewModel() = AppViewModel(
        licenseGate = gate,
        workspaceStore = store,
        projectOpener = opener,
        readProjectConfig = { dir -> if (dir in validProjects) ProjectConfig(dir.name, "com.geo.telecom", 0, 0) else null },
        openWorkspace = { dir -> openFailure?.let { throw it }; FakeWorkspace(dir).also { workspaces += it } },
        workspaceExists = { it !in missingWorkspaces },
    )

    @Test
    fun `authorized start restores the last workspace`() {
        val vm = newViewModel()
        assertEquals(AuthState.Authorized, vm.auth.value)
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
        assertNotNull(vm.banner.value)
    }

    @Test
    fun `authorized start without a saved workspace asks for one`() {
        store.last = null
        val vm = newViewModel()
        assertEquals(AppScreen.WorkspaceSelection, vm.screen.value)

        vm.selectWorkspace(workspace)
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
        assertEquals(workspace, store.last)
    }

    @Test
    fun `locked start stays locked until a valid key is entered`() {
        gate.startup = AuthState.Locked("Trial Expired - Activation Required")
        val vm = newViewModel()
        assertIs<AuthState.Locked>(vm.auth.value)
        assertEquals(AppScreen.WorkspaceSelection, vm.screen.value)
        assertNull(vm.banner.value)
        assertTrue(workspaces.isEmpty(), "nothing is opened before the app is unlocked")

        assertFalse(vm.submitLicenseKey("BAD"))
        assertIs<AuthState.Locked>(vm.auth.value)

        assertTrue(vm.submitLicenseKey("GOOD"))
        assertEquals(AuthState.Authorized, vm.auth.value)
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
        assertNotNull(vm.banner.value)
    }

    @Test
    fun `subscription expiry is recognised for the lock screen message`() {
        assertTrue(AuthState.Locked("Subscription Expired - Renewal Required").isSubscriptionExpired)
        assertFalse(AuthState.Locked("Trial Expired - Activation Required").isSubscriptionExpired)
    }

    @Test
    fun `opening a project shows the workbench, closing it returns to the dashboard`() {
        val vm = newViewModel()

        assertTrue(vm.openProject(projectA))
        val workbench = assertIs<AppScreen.Workbench>(vm.screen.value)
        assertEquals(projectA, workbench.project.projectDir)

        vm.closeProject()
        assertEquals(listOf(projectA), opener.closed)
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
    }

    @Test
    fun `opening another project closes the first`() {
        val vm = newViewModel()
        vm.openProject(projectA)
        vm.openProject(projectB)

        assertEquals(listOf(projectA), opener.closed)
        assertEquals(projectB, assertIs<AppScreen.Workbench>(vm.screen.value).project.projectDir)
    }

    @Test
    fun `a folder without project json is not opened`() {
        val vm = newViewModel()
        assertFalse(vm.openProject(File(workspace, "random-folder")))
        assertTrue(opener.opened.isEmpty())
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
    }

    @Test
    fun `a database that fails to open leaves the user on the dashboard`() {
        opener.failWith = IOException("disk error")
        val vm = newViewModel()

        assertFalse(vm.openProject(projectA))
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
    }

    @Test
    fun `switching workspace closes the open project and the old workspace`() {
        val vm = newViewModel()
        vm.openProject(projectA)

        vm.selectWorkspace(File("other-ws"))

        assertEquals(listOf(projectA), opener.closed)
        assertTrue(workspaces.first().closed)
        assertEquals(AppScreen.Dashboard(File("other-ws")), vm.screen.value)
    }

    @Test
    fun `a remembered workspace that no longer exists sends the user to choose one`() {
        missingWorkspaces += workspace

        val vm = newViewModel()

        assertEquals(AppScreen.WorkspaceSelection, vm.screen.value)
        assertEquals(emptyList(), workspaces, "nothing was opened")
        assertNotNull(vm.banner.value, "the licence banner is unaffected")
    }

    @Test
    fun `a workspace that fails to open leaves the user choosing, and is not remembered`() {
        store.last = null
        val vm = newViewModel()
        openFailure = IOException("access denied")

        assertFalse(vm.selectWorkspace(File("locked-ws")))

        assertEquals(AppScreen.WorkspaceSelection, vm.screen.value)
        assertNull(store.last, "a workspace that did not open is not restored on the next start")
    }

    @Test
    fun `a failing workspace at startup does not crash the app`() {
        openFailure = IOException("drive not ready")

        val vm = newViewModel()

        assertEquals(AppScreen.WorkspaceSelection, vm.screen.value)
    }

    @Test
    fun `after a failed switch the previous workspace is already closed and nothing is left open`() {
        val vm = newViewModel()
        vm.openProject(projectA)
        openFailure = IOException("access denied")

        assertFalse(vm.selectWorkspace(File("other-ws")))

        assertEquals(AppScreen.WorkspaceSelection, vm.screen.value)
        assertEquals(listOf(projectA), opener.closed, "the open project was closed")
        assertTrue(workspaces.single().closed)
        vm.shutdown() // must not close anything twice or throw
    }

    @Test
    fun `selecting a workspace returns true and remembers it`() {
        store.last = null
        val vm = newViewModel()

        assertTrue(vm.selectWorkspace(workspace))
        assertEquals(workspace, store.last)
    }

    @Test
    fun `opening a project from the selection screen is refused`() {
        store.last = null
        val vm = newViewModel()

        assertFalse(vm.openProject(projectA))
        assertEquals(emptyList(), opener.opened)
    }

    @Test
    fun `closing when no project is open does nothing`() {
        val vm = newViewModel()
        vm.closeProject()
        assertEquals(AppScreen.Dashboard(workspace), vm.screen.value)
        assertEquals(emptyList(), opener.closed)
    }

    @Test
    fun `shutdown closes project and workspace`() {
        val vm = newViewModel()
        vm.openProject(projectA)

        vm.shutdown()

        assertEquals(listOf(projectA), opener.closed)
        assertTrue(workspaces.single().closed)
    }
}
