package com.geospatial.processing.ui

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.core.plugin.telecom.TelecomPlugin
import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.AssetImageResolver
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelTest {

    private val root: File = Files.createTempDirectory("workbench-test").toFile()
    private val projectDir = File(root, "project").apply { mkdirs() }
    private val imageRoot = File(root, "tower-images").apply { mkdirs() }
    private lateinit var session: ProjectSession
    private lateinit var repository: AssetRepository
    private lateinit var vm: WorkbenchViewModel

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)

    @BeforeTest
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = ProjectSession.open(projectDir)
        repository = AssetRepository(session.database)
        val plugin = TelecomPlugin()
        vm = WorkbenchViewModel(
            plugin, repository, ImageStore(projectDir), AssetImageResolver(plugin, projectDir),
            initialRootImageDirectory = imageRoot.path,
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
        writeText("Tower No.,Line Name,CKT,Lat.,Long.\n" + towers.joinToString("\n") { "$it,Line A,1,12.5,77.5" })
    }

    private fun importTowers(vararg towers: String) = runBlocking { vm.importCsv(csv(*towers)).join() }

    /** A tower folder with all four report images, so the tower becomes READY. */
    private fun completeImageFolder(tower: String) = File(imageRoot, tower).apply {
        mkdirs()
        listOf("thermal.jpg", "zoom.jpg", "tower.jpg", "overview.jpg").forEach { File(this, it).writeBytes(jpeg) }
    }

    private fun towers() = vm.state.value.records.map { it.property(K.TOWER_NUMBER) }

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), vm.state.value.records)
        assertNull(vm.state.value.selectedRecord)
    }

    @Test
    fun `importing a CSV loads assets in order and marks complete towers READY`() {
        completeImageFolder("VMT-2")
        importTowers("VMT-1", "VMT-2")

        val state = vm.state.value
        assertEquals(listOf("VMT-1", "VMT-2"), towers())
        assertEquals(listOf(RecordStatus.DRAFT, RecordStatus.READY), state.records.map { it.status })
        assertNull(state.importProgress, "progress overlay should close when the import finishes")
        assertTrue(state.showImportSuccess)

        vm.onAction(WorkbenchAction.DismissImportSuccess)
        assertFalse(vm.state.value.showImportSuccess)
    }

    @Test
    fun `a second import is appended after the first`() {
        importTowers("VMT-1")
        importTowers("VMT-2", "VMT-3")
        assertEquals(listOf("VMT-1", "VMT-2", "VMT-3"), towers())
    }

    @Test
    fun `previous and next follow the sort order`() {
        importTowers("VMT-1", "VMT-2", "VMT-3")
        val middle = vm.state.value.records[1]

        vm.onAction(WorkbenchAction.Select(middle))
        assertEquals("VMT-1", vm.state.value.previousRecord?.property(K.TOWER_NUMBER))
        assertEquals("VMT-3", vm.state.value.nextRecord?.property(K.TOWER_NUMBER))

        vm.onAction(WorkbenchAction.ToggleSort)
        assertEquals("VMT-3", vm.state.value.previousRecord?.property(K.TOWER_NUMBER))
        assertEquals(middle.id, vm.state.value.selectedRecord?.id)
    }

    @Test
    fun `deleting the selected asset clears the selection and its images`() = runBlocking {
        importTowers("VMT-1", "VMT-2")
        val first = vm.state.value.records.first()
        vm.save(first, mapOf(K.SLOT_THERMAL to ImageEdit.Replace(jpeg))).join()
        vm.onAction(WorkbenchAction.Select(first))

        vm.delete(first).join()

        assertEquals(listOf("VMT-2"), towers())
        assertNull(vm.state.value.selectedRecordId)
        assertFalse(File(projectDir, "images/${first.id}").exists())
    }

    @Test
    fun `clear all removes every asset, image and the selection`() = runBlocking {
        importTowers("VMT-1", "VMT-2")
        val first = vm.state.value.records.first()
        vm.save(first, mapOf(K.SLOT_THERMAL to ImageEdit.Replace(jpeg))).join()
        vm.onAction(WorkbenchAction.Select(first))

        vm.clearAll().join()

        assertEquals(emptyList(), vm.state.value.records)
        assertNull(vm.state.value.selectedRecordId)
        assertTrue(File(projectDir, "images").list().isNullOrEmpty())
    }

    @Test
    fun `saving writes uploaded images into the project and removes cleared ones`() = runBlocking {
        importTowers("VMT-1")
        val asset = vm.state.value.records.single()

        vm.save(
            asset.copy(properties = asset.properties + (K.FAULT_DESCRIPTION to "Hot joint")),
            mapOf(K.SLOT_THERMAL to ImageEdit.Replace(jpeg)),
        ).join()

        val saved = repository.getAll().single()
        assertEquals("Hot joint", saved.property(K.FAULT_DESCRIPTION))
        val path = saved.images.getValue(K.SLOT_THERMAL).relativePath!!
        assertContentEquals(jpeg, File(projectDir, path).readBytes())

        vm.save(saved, mapOf(K.SLOT_THERMAL to ImageEdit.Clear)).join()

        assertEquals(AssetImage.Cleared, repository.getAll().single().images[K.SLOT_THERMAL])
        assertFalse(File(projectDir, path).exists(), "the replaced file is removed")
    }

    @Test
    fun `an uploaded original is kept, survives an annotation, and goes away when the slot is cleared`() = runBlocking {
        importTowers("VMT-1")
        val asset = vm.state.value.records.single()
        val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7, 7, 7)
        val originalFile = { File(projectDir, "images/${asset.id}/${K.SLOT_THERMAL}.orig.jpg") }

        vm.save(asset, mapOf(K.SLOT_THERMAL to ImageEdit.Replace(jpeg, original))).join()
        assertContentEquals(original, originalFile().readBytes())

        // Annotating the display image (no new original) must not touch the stored original.
        vm.save(repository.getAll().single(), mapOf(K.SLOT_THERMAL to ImageEdit.Replace(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2)))).join()
        assertContentEquals(original, originalFile().readBytes())

        vm.save(repository.getAll().single(), mapOf(K.SLOT_THERMAL to ImageEdit.Clear)).join()
        assertFalse(originalFile().exists())
    }

    @Test
    fun `export writes one PDF per READY tower`() = runBlocking {
        completeImageFolder("VMT-1")
        completeImageFolder("VMT-3")
        importTowers("VMT-1", "VMT-2", "VMT-3")
        val zip = File(root, "out.zip")

        vm.exportZip(zip).join()

        assertNull(vm.state.value.exportProgress)
        ZipFile(zip).use { z ->
            assertEquals(
                listOf("Tower_Reports/NORMAL_Tower_VMT-1_Report.pdf", "Tower_Reports/NORMAL_Tower_VMT-3_Report.pdf"),
                z.entries().toList().map { it.name }
            )
        }
    }

    @Test
    fun `export with nothing ready writes no zip`() = runBlocking {
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

    // --- severity -----------------------------------------------------------------------------------

    private fun rate(tower: String, rise: String) = runBlocking {
        val asset = vm.state.value.records.first { it.property(K.TOWER_NUMBER) == tower }
        vm.save(asset.copy(properties = asset.properties + (K.RISE_TEMP to rise))).join()
    }

    @Test
    fun `saving classifies the asset and stores the severity`() = runBlocking {
        importTowers("VMT-1")

        rate("VMT-1", "20")

        assertEquals(Severity.HIGH, vm.state.value.records.single().severity)
        assertEquals(Severity.HIGH, repository.getAll().single().severity)
    }

    @Test
    fun `a CSV with fault temperatures is classified on import`() = runBlocking {
        val file = File(root, "faults.csv").apply {
            writeText("Tower No.,Line Name,CKT,Lat.,Long.,Ambeint Temp.,Fault Temp.\nVMT-1,Line A,1,12.5,77.5,30,75\nVMT-2,Line A,1,12.5,77.5,30,\n")
        }

        vm.importCsv(file).join()

        assertEquals(listOf(Severity.CRITICAL, Severity.NONE), repository.getAll().map { it.severity })
    }

    @Test
    fun `an out-of-date stored severity is corrected when the project loads`() = runBlocking {
        importTowers("VMT-1")
        val asset = repository.getAll().single()
        repository.save(asset.copy(properties = asset.properties + (K.RISE_TEMP to "5"), severity = Severity.NONE))

        vm.refresh().join()

        assertEquals(Severity.MEDIUM, vm.state.value.records.single().severity)
        assertEquals(Severity.MEDIUM, repository.getAll().single().severity)
    }

    @Test
    fun `the severity filter hides milder towers but not their neighbours in the sequence`() = runBlocking {
        importTowers("VMT-1", "VMT-2", "VMT-3")
        rate("VMT-1", "2")    // LOW
        rate("VMT-2", "50")   // CRITICAL
        rate("VMT-3", "8")    // MEDIUM

        vm.onAction(WorkbenchAction.SetMinSeverity(Severity.MEDIUM))
        assertEquals(listOf("VMT-2", "VMT-3"), vm.state.value.displayedRecords.map { it.property(K.TOWER_NUMBER) })

        // VMT-3 is selected; its previous tower is still VMT-2 and the filtered-out VMT-1 stays reachable by id.
        vm.onAction(WorkbenchAction.Select(vm.state.value.records[2]))
        assertEquals("VMT-2", vm.state.value.previousRecord?.property(K.TOWER_NUMBER))
        vm.onAction(WorkbenchAction.Select(vm.state.value.records[0]))
        assertEquals("VMT-1", vm.state.value.selectedRecord?.property(K.TOWER_NUMBER))
        assertEquals("VMT-2", vm.state.value.nextRecord?.property(K.TOWER_NUMBER))

        vm.onAction(WorkbenchAction.SetMinSeverity(Severity.NONE))
        assertEquals(3, vm.state.value.displayedRecords.size)
    }

    @Test
    fun `sorting by severity puts the worst first and keeps the original order for ties`() = runBlocking {
        importTowers("VMT-1", "VMT-2", "VMT-3", "VMT-4")
        rate("VMT-2", "50")   // CRITICAL
        rate("VMT-4", "2")    // LOW
        rate("VMT-3", "2")    // LOW

        vm.onAction(WorkbenchAction.ToggleSeveritySort)

        assertEquals(listOf("VMT-2", "VMT-3", "VMT-4", "VMT-1"), vm.state.value.displayedRecords.map { it.property(K.TOWER_NUMBER) })
    }
}
