package com.geospatial.processing.ui

import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.usecase.CsvImportService
import com.geospatial.processing.domain.usecase.PdfGenerationService
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelTest {

    private val root: File = Files.createTempDirectory("workbench-test").toFile()
    private lateinit var session: ProjectSession
    private lateinit var repository: GeoRepository
    private lateinit var vm: WorkbenchViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = ProjectSession.open(File(root, "project").apply { mkdirs() })
        repository = GeoRepository(session.database)
        vm = WorkbenchViewModel(
            repository, CsvImportService(repository), PdfGenerationService(repository),
            exportResultDisplayMillis = 0,
        )
        vm.initialLoad.join()
    }

    @AfterTest
    fun tearDown() {
        session.close()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun csv(vararg towers: String): File = File(root, "towers.csv").apply {
        writeText(
            "Tower No.,Line Name,CKT,Lat.,Long.\n" +
                towers.joinToString("\n") { "$it,Line A,1,12.5,77.5" }
        )
    }

    private fun importTowers(vararg towers: String) = runBlocking {
        vm.importCsv(csv(*towers)).join()
    }

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), vm.state.value.records)
        assertNull(vm.state.value.selectedRecord)
    }

    @Test
    fun `importing a CSV loads records and shows the success dialog`() {
        importTowers("VMT-1", "VMT-2")

        val state = vm.state.value
        assertEquals(listOf("VMT-1", "VMT-2"), state.records.map { it.towerNumber })
        assertNull(state.importProgress, "progress overlay should close when the import finishes")
        assertTrue(state.showImportSuccess)
        // No tower image folders exist, so nothing can be READY.
        assertTrue(state.records.all { it.status == RecordStatus.DRAFT })

        vm.onAction(WorkbenchAction.DismissImportSuccess)
        assertFalse(vm.state.value.showImportSuccess)
    }

    @Test
    fun `previous and next tower follow the sort order`() {
        importTowers("VMT-1", "VMT-2", "VMT-3")
        val middle = vm.state.value.records[1]

        vm.onAction(WorkbenchAction.Select(middle))
        assertEquals("VMT-1", vm.state.value.previousTowerName)
        assertEquals("VMT-3", vm.state.value.nextTowerName)

        vm.onAction(WorkbenchAction.ToggleSort)
        assertEquals("VMT-3", vm.state.value.previousTowerName)
        assertEquals("VMT-1", vm.state.value.nextTowerName)
        assertEquals(middle.id, vm.state.value.selectedRecord?.id)
    }

    @Test
    fun `deleting the selected record clears the selection`() = runBlocking {
        importTowers("VMT-1", "VMT-2")
        val first = vm.state.value.records.first()
        vm.onAction(WorkbenchAction.Select(first))

        vm.delete(first).join()

        assertEquals(listOf("VMT-2"), vm.state.value.records.map { it.towerNumber })
        assertNull(vm.state.value.selectedRecordId)
    }

    @Test
    fun `deleting another record keeps the selection`() = runBlocking {
        importTowers("VMT-1", "VMT-2")
        val (first, second) = vm.state.value.records
        vm.onAction(WorkbenchAction.Select(first))

        vm.delete(second).join()

        assertEquals(first.id, vm.state.value.selectedRecordId)
    }

    @Test
    fun `clear all removes every record and the selection`() = runBlocking {
        importTowers("VMT-1", "VMT-2")
        vm.onAction(WorkbenchAction.Select(vm.state.value.records.first()))

        vm.clearAll().join()

        assertEquals(emptyList(), vm.state.value.records)
        assertNull(vm.state.value.selectedRecordId)
        assertEquals(emptyList(), repository.getAllRecords())
    }

    @Test
    fun `saving a record persists it and refreshes the list`() = runBlocking {
        importTowers("VMT-1")
        val record = vm.state.value.records.single()

        vm.save(record.copy(faultDescription = "Hot joint")).join()

        assertEquals("Hot joint", vm.state.value.records.single().faultDescription)
        assertEquals("Hot joint", repository.getAllRecords().single().faultDescription)
    }

    @Test
    fun `export with nothing ready closes the overlay and writes no zip`() = runBlocking {
        importTowers("VMT-1")
        val zip = File(root, "out.zip")

        vm.exportZip(zip).join()

        assertNull(vm.state.value.exportProgress)
        assertFalse(zip.exists())
    }

    @Test
    fun `theme toggle`() {
        assertFalse(vm.state.value.isDarkTheme)
        vm.onAction(WorkbenchAction.ToggleTheme)
        assertTrue(vm.state.value.isDarkTheme)
    }
}
