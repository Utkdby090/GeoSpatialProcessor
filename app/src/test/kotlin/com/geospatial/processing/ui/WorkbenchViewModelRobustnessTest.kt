package com.geospatial.processing.ui

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.core.plugin.telecom.TelecomPlugin
import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.Asset
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What happens when things go wrong or happen at the same time: bad files, a failing database, overlapping actions, big projects. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelRobustnessTest {

    private val root: File = Files.createTempDirectory("workbench-robust").toFile()
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
            plugin, repository, ImageStore(projectDir), AssetImageResolver(plugin, projectDir), projectDir,
            initialRootImageDirectory = imageRoot.path, exportResultDisplayMillis = 0,
        )
        vm.initialLoad.join()
    }

    @AfterTest
    fun tearDown() {
        runCatching { session.close() }
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private fun csvFile(name: String, text: String) = File(root, name).apply { writeText(text) }
    private fun csv(vararg towers: String) = csvFile("towers.csv", "Tower No.,Line Name,CKT,Lat.,Long.\n" + towers.joinToString("\n") { "$it,Line A,1,12.5,77.5" })
    private fun importTowers(vararg towers: String) = runBlocking { vm.importCsv(csv(*towers)).join() }
    private fun towers() = vm.state.value.records.map { it.property(K.TOWER_NUMBER) }

    // ---- an import that cannot work says why and leaves the screen usable ----

    @Test
    fun `a CSV without the needed columns is explained and adds nothing`() = runBlocking {
        vm.importCsv(csvFile("wrong.csv", "Name,Latitude\nA,1\n")).join()

        val s = vm.state.value
        assertContains(s.notice!!, "No towers were imported")
        assertContains(s.notice, "Tower No.")
        assertNull(s.importProgress, "the progress overlay must not stay on screen")
        assertFalse(s.showImportSuccess)
        assertEquals(emptyList(), s.records)
    }

    @Test
    fun `an empty, missing or header-only CSV is explained too`() = runBlocking {
        for (file in listOf(csvFile("empty.csv", ""), File(root, "missing.csv"), csvFile("head.csv", "Tower No.,Line Name\n"))) {
            vm.onAction(WorkbenchAction.DismissNotice)
            vm.importCsv(file).join()
            assertTrue("No towers were imported" in vm.state.value.notice.orEmpty(), "${file.name}: ${vm.state.value.notice}")
            assertNull(vm.state.value.importProgress, file.name)
        }
        assertEquals(emptyList(), vm.state.value.records)
    }

    @Test
    fun `rows without a tower number are reported after the import`() = runBlocking {
        vm.importCsv(csvFile("some.csv", "Tower No.,Line Name,Lat.,Long.\nT1,L,1,2\n,L,1,2\nT3,L,1,2\n")).join()

        assertEquals(listOf("T1", "T3"), towers())
        assertTrue(vm.state.value.showImportSuccess)
        assertContains(vm.state.value.importWarning!!, "1 row was skipped")
        assertContains(vm.state.value.importWarning!!, "row 3")
    }

    @Test
    fun `when every row is unusable the user is told which rows`() = runBlocking {
        vm.importCsv(csvFile("none.csv", "Tower No.,Line Name\n,L\nT,\n")).join()

        assertContains(vm.state.value.notice!!, "None of the 2 rows")
        assertFalse(vm.state.value.showImportSuccess)
    }

    @Test
    fun `an Excel CSV with a byte-order mark and semicolons imports end to end`() = runBlocking {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "Tower No.;Line Name;CKT;Lat.;Long.\nBOM-1;Line A;1;12,5;77,5\n".toByteArray()
        val f = File(root, "excel.csv").apply { writeBytes(bytes) }

        vm.importCsv(f).join()

        val a = vm.state.value.records.single()
        assertEquals("BOM-1", a.property(K.TOWER_NUMBER))
        assertEquals(12.5, a.latitude)
        assertEquals(77.5, a.longitude)
        assertTrue(f.delete(), "the CSV is not left locked after the import")
    }

    // ---- a failing database never leaves the app stuck or crashed ----

    @Test
    fun `a failing database during an import closes the overlay and reports it`() = runBlocking {
        session.close()

        vm.importCsv(csv("T1", "T2")).join()

        val s = vm.state.value
        assertNull(s.importProgress, "overlay closed")
        assertFalse(s.showImportSuccess)
        assertContains(s.notice!!, "CSV import failed")
        session = ProjectSession.open(projectDir)
    }

    @Test
    fun `a failing database during save, delete, clear and refresh is reported, not thrown`() = runBlocking {
        importTowers("T1", "T2")
        val first = vm.state.value.records.first()
        session.close()

        vm.save(first.copy(properties = first.properties + (K.FAULT_DESCRIPTION to "x"))).join()
        assertContains(vm.state.value.notice!!, "Saving the record failed")
        vm.onAction(WorkbenchAction.DismissNotice)

        vm.delete(first).join()
        assertContains(vm.state.value.notice!!, "Deleting the record failed")
        assertEquals(listOf("T1", "T2"), towers(), "a delete that failed leaves the row on screen")
        vm.onAction(WorkbenchAction.DismissNotice)

        vm.clearAll().join()
        assertContains(vm.state.value.notice!!, "Clearing the project failed")
        assertEquals(2, vm.state.value.records.size)
        vm.onAction(WorkbenchAction.DismissNotice)

        vm.refresh().join()
        assertContains(vm.state.value.notice!!, "Loading the project failed")
        session = ProjectSession.open(projectDir)
    }

    @Test
    fun `the project works again after a failure`() = runBlocking {
        session.close()
        vm.importCsv(csv("T1")).join()
        session = ProjectSession.open(projectDir)
        // The ViewModel keeps its repository, which still points at the closed database; a fresh one is the reopened project.
        val plugin = TelecomPlugin()
        val again = WorkbenchViewModel(
            plugin, AssetRepository(session.database), ImageStore(projectDir), AssetImageResolver(plugin, projectDir), projectDir,
            initialRootImageDirectory = imageRoot.path, exportResultDisplayMillis = 0,
        )
        again.initialLoad.join()
        again.importCsv(csv("T9")).join()

        assertEquals(listOf("T9"), again.state.value.records.map { it.property(K.TOWER_NUMBER) })
    }

    // ---- overlapping actions end in a state that matches the database ----

    @Test
    fun `saves racing an import and each other leave the screen equal to the database`() {
        importTowers(*(1..30).map { "T$it" }.toTypedArray())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = ArrayList<kotlinx.coroutines.Job>()
            val lock = Any()
            val seed = vm.state.value.records.first()
            fun launchJob(block: () -> kotlinx.coroutines.Job) { val j = block(); synchronized(lock) { jobs += j } }

            val tasks = (1..40).map { n ->
                pool.submit {
                    when (n % 4) {
                        0 -> launchJob { vm.importCsv(csv("R$n")) }
                        else -> launchJob { vm.save(seed.copy(properties = seed.properties + (K.FAULT_DESCRIPTION to "edit $n"))) }
                    }
                }
            }
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
            runBlocking { synchronized(lock) { jobs.toList() }.forEach { it.join() } }
        } finally {
            pool.shutdownNow()
        }

        val db = runBlocking { repository.getAll() }
        assertEquals(db.map { it.id to it.property(K.FAULT_DESCRIPTION) }, vm.state.value.records.map { it.id to it.property(K.FAULT_DESCRIPTION) },
            "what the screen shows is what is stored")
        assertEquals(db.map { it.position }.sorted(), db.map { it.position }, "positions stay in order")
        assertEquals(db.size, db.map { it.position }.toSet().size, "no two towers share a position")
    }

    // ---- saving and deleting touch one record ----

    @Test
    fun `saving one record leaves the others on screen untouched`() = runBlocking {
        importTowers("T1", "T2", "T3")
        val before = vm.state.value.records
        val middle = before[1]

        vm.save(middle.copy(properties = middle.properties + (K.FAULT_DESCRIPTION to "edited"))).join()

        val after = vm.state.value.records
        assertEquals(listOf("T1", "T2", "T3"), towers())
        assertSame(before[0], after[0])
        assertSame(before[2], after[2])
        assertEquals("edited", after[1].property(K.FAULT_DESCRIPTION))
        assertEquals("edited", repository.getAll()[1].property(K.FAULT_DESCRIPTION))
    }

    @Test
    fun `saving a record the screen did not have yet adds it in its place`() = runBlocking {
        importTowers("T1", "T3")
        val extra = Asset(pluginId = TelecomPlugin().pluginId, position = 1, latitude = 1.0, longitude = 2.0,
            properties = mapOf(K.TOWER_NUMBER to "T2", K.LINE_NAME to "Line A"))

        vm.save(extra).join()

        // positions 0, 1, 1: the new one sorts among them by position, and the database agrees on the set
        assertEquals(3, vm.state.value.records.size)
        assertEquals(repository.getAll().map { it.id }.toSet(), vm.state.value.records.map { it.id }.toSet())
    }

    @Test
    fun `a saved record is classified before it is shown`() = runBlocking {
        importTowers("T1")
        val a = vm.state.value.records.single()

        vm.save(a.copy(properties = a.properties + (K.RISE_TEMP to "45"))).join()

        assertEquals(Severity.CRITICAL, vm.state.value.records.single().severity)
    }

    @Test
    fun `deleting a record that is not selected keeps the selection`() = runBlocking {
        importTowers("T1", "T2", "T3")
        val (a, b, c) = vm.state.value.records
        vm.onAction(WorkbenchAction.Select(c))

        vm.delete(a).join()

        assertEquals(listOf("T2", "T3"), towers())
        assertEquals(c.id, vm.state.value.selectedRecordId)
        assertEquals(b.id, vm.state.value.previousRecord?.id)
    }

    // ---- the image scan ----

    @Test
    fun `a project of several hundred towers is scanned correctly and quickly`() = runBlocking {
        val names = (1..400).map { "S$it" }
        // Every third tower has a full set of images.
        names.filterIndexed { i, _ -> i % 3 == 0 }.forEach { t ->
            File(imageRoot, t).apply {
                mkdirs()
                listOf("thermal.jpg", "zoom.jpg", "tower.jpg", "overview.jpg").forEach { File(this, it).writeBytes(jpeg) }
            }
        }

        val start = System.nanoTime()
        vm.importCsv(csv(*names.toTypedArray())).join()
        val ms = (System.nanoTime() - start) / 1_000_000

        val records = vm.state.value.records
        assertEquals(400, records.size)
        assertEquals(names, records.map { it.property(K.TOWER_NUMBER) }, "import order kept")
        records.forEachIndexed { i, r ->
            assertEquals(if (i % 3 == 0) RecordStatus.READY else RecordStatus.DRAFT, r.status, "tower ${r.property(K.TOWER_NUMBER)}")
        }
        assertEquals(records, repository.getAll(), "what the screen shows is what is stored")
        assertTrue(ms < 30_000, "400 towers took $ms ms")
        assertNull(vm.state.value.importProgress)
    }

    @Test
    fun `a missing image folder marks everything DRAFT without failing`() = runBlocking {
        imageRoot.deleteRecursively()

        importTowers("T1", "T2")

        assertEquals(listOf(RecordStatus.DRAFT, RecordStatus.DRAFT), vm.state.value.records.map { it.status })
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `severity corrections of many towers are stored together`() = runBlocking {
        importTowers(*(1..50).map { "T$it" }.toTypedArray())
        val all = repository.getAll()
        all.forEach { repository.save(it.copy(properties = it.properties + (K.RISE_TEMP to "45"), severity = Severity.NONE)) }

        vm.refresh().join()

        assertTrue(vm.state.value.records.all { it.severity == Severity.CRITICAL })
        assertTrue(repository.getAll().all { it.severity == Severity.CRITICAL })
    }

    // ---- what the screens read from the state ----

    @Test
    fun `list, neighbours and selection are worked out once per state`() = runBlocking {
        importTowers("T1", "T2", "T3", "T4")
        vm.onAction(WorkbenchAction.ToggleSort)
        vm.onAction(WorkbenchAction.Select(vm.state.value.records[1]))
        val s = vm.state.value

        assertSame(s.displayedRecords, s.displayedRecords)
        assertEquals(listOf("T4", "T3", "T2", "T1"), s.displayedRecords.map { it.property(K.TOWER_NUMBER) })
        assertEquals("T3", s.previousRecord?.property(K.TOWER_NUMBER))
        assertEquals("T1", s.nextRecord?.property(K.TOWER_NUMBER))
        assertEquals("T2", s.selectedRecord?.property(K.TOWER_NUMBER))
    }

    @Test
    fun `a selection that no longer exists has no neighbours`() = runBlocking {
        importTowers("T1", "T2")
        vm.onAction(WorkbenchAction.Select(vm.state.value.records[0]))
        vm.delete(vm.state.value.records[0]).join()

        val s = vm.state.value
        assertNull(s.selectedRecord)
        assertNull(s.previousRecord)
        assertNull(s.nextRecord)
    }

    @Test
    fun `the filter and sort give a stable list for any combination`() = runBlocking {
        importTowers("T1", "T2", "T3")
        val severities = listOf(Severity.NONE, Severity.HIGH, Severity.LOW)
        vm.state.value.records.zip(severities).forEach { (a, sev) ->
            vm.save(a.copy(properties = a.properties + (K.RISE_TEMP to when (sev) { Severity.HIGH -> "25"; Severity.LOW -> "1"; else -> "0" }))).join()
        }

        for (min in Severity.entries) for (bySeverity in listOf(false, true)) for (asc in listOf(true, false)) {
            val base = vm.state.value
            vm.onAction(WorkbenchAction.SetMinSeverity(min))
            if (vm.state.value.sortBySeverity != bySeverity) vm.onAction(WorkbenchAction.ToggleSeveritySort)
            if (vm.state.value.sortAscending != asc) vm.onAction(WorkbenchAction.ToggleSort)
            val shown = vm.state.value.displayedRecords
            assertTrue(shown.all { it.severity >= min }, "min $min")
            if (bySeverity) assertEquals(shown.sortedByDescending { it.severity }.map { it.severity }, shown.map { it.severity })
            assertEquals(base.records, vm.state.value.records, "filtering never changes the data")
        }
        assertNotNull(vm.state.value.records)
    }
}
