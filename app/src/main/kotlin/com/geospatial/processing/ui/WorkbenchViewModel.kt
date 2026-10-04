package com.geospatial.processing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.data.geopackage.GeoPackage
import com.geospatial.processing.data.geopackage.GeoPackageException
import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.imaging.metadata.ImageIngestor
import com.geospatial.processing.domain.map.GeoPoint
import com.geospatial.processing.domain.map.PositionCheck
import com.geospatial.processing.domain.map.PositionIssue
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.AssetImageResolver
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.domain.model.ReportSettings
import com.geospatial.processing.domain.report.BulkReportExporter
import com.geospatial.processing.domain.report.ReportBranding
import com.geospatial.processing.domain.report.ReportOptions
import com.geospatial.processing.domain.report.ReportTemplate
import com.geospatial.processing.utils.ProjectManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/** Progress overlay shown while importing a CSV or exporting PDFs. */
data class ProgressState(val statusText: String, val progress: Float)

data class WorkbenchUiState(
    val records: List<Asset> = emptyList(),
    val sortAscending: Boolean = true,
    val selectedRecordId: String? = null,
    val rootImageDirectory: String = DEFAULT_ROOT_IMAGE_DIRECTORY,
    val isDarkTheme: Boolean = false,
    /** Non-null while a CSV import runs. */
    val importProgress: ProgressState? = null,
    /** Non-null while a PDF export runs (and briefly afterwards, to show the result). */
    val exportProgress: ProgressState? = null,
    val showImportSuccess: Boolean = false,
    /** Shown in the import result when some imported positions look wrong; null when all look right. */
    val importWarning: String? = null,
    /** The list shows only assets at least this severe; [Severity.NONE] shows everything. */
    val minSeverity: Severity = Severity.NONE,
    /** Most severe first in the list (ties keep the import/sort order). */
    val sortBySeverity: Boolean = false,
    /** The project's saved report template and branding. */
    val reportSettings: ReportSettings = ReportSettings(),
    /** A result the user should read (e.g. what a GeoPackage import did); null when there is none. */
    val notice: String? = null,
) {
    /** Positions that look wrong across the whole project (not only the filtered list), worked out once per state. */
    val positionIssues: List<PositionIssue> by lazy {
        PositionCheck.check(records.associate { it.id to GeoPoint(it.latitude, it.longitude) })
    }

    /** True when every position probably has latitude and longitude exchanged (the project lands in the polar regions). */
    val allPositionsLookSwapped: Boolean by lazy {
        PositionCheck.likelyAllSwapped(records.map { GeoPoint(it.latitude, it.longitude) })
    }

    /** All records in import order (or reversed). Neighbours come from here, so a filter never changes who is next to a tower. */
    private val orderedRecords: List<Asset> get() = if (sortAscending) records else records.reversed()

    /** What the list shows: [orderedRecords] narrowed by [minSeverity] and optionally sorted by severity. */
    val displayedRecords: List<Asset> get() = orderedRecords
        .filter { it.severity >= minSeverity }
        .let { list -> if (sortBySeverity) list.sortedByDescending { it.severity } else list }

    private val selectedIndex: Int? get() = orderedRecords.indexOfFirst { it.id == selectedRecordId }.takeIf { it >= 0 }
    val selectedRecord: Asset? get() = selectedIndex?.let { orderedRecords[it] }
    val previousRecord: Asset? get() = selectedIndex?.let { orderedRecords.getOrNull(it - 1) }
    val nextRecord: Asset? get() = selectedIndex?.let { orderedRecords.getOrNull(it + 1) }

    companion object {
        const val DEFAULT_ROOT_IMAGE_DIRECTORY = "C:\\Tower_images"
    }
}

/** A change to one image slot made in the form, applied when the asset is saved. */
sealed interface ImageEdit {
    /**
     * [bytes] is the display image. [original] is the untouched file the user picked (keeps EXIF and radiometric data);
     * null for edits derived from an existing image (annotations), which leave the stored original alone.
     */
    class Replace(val bytes: ByteArray, val original: ByteArray? = null) : ImageEdit
    data object Clear : ImageEdit
}

/** User actions on the workbench (MainScreen and its children). */
sealed interface WorkbenchAction {
    data class ImportCsv(val file: File) : WorkbenchAction
    /**
     * Exports the reports to [file]. With [settings] they are saved in the project first (with [logo], a new logo image
     * to copy in); without, the project's saved settings are used.
     */
    data class ExportZip(val file: File, val settings: ReportSettings? = null, val logo: File? = null) : WorkbenchAction
    data class Select(val record: Asset) : WorkbenchAction
    data class Delete(val record: Asset) : WorkbenchAction
    data object DeleteSelected : WorkbenchAction
    data object ClearAll : WorkbenchAction
    data class Save(val record: Asset, val imageEdits: Map<String, ImageEdit> = emptyMap()) : WorkbenchAction
    data object Refresh : WorkbenchAction
    data object ToggleSort : WorkbenchAction
    data class SetMinSeverity(val min: Severity) : WorkbenchAction
    data object ToggleSeveritySort : WorkbenchAction
    data object ToggleTheme : WorkbenchAction
    data object DismissImportSuccess : WorkbenchAction
    data class ExportGeoPackage(val file: File) : WorkbenchAction
    data class ImportGeoPackage(val file: File) : WorkbenchAction
    data object DismissNotice : WorkbenchAction
    /** Swaps latitude and longitude back on every tower [WorkbenchUiState.positionIssues] reports as swapped. */
    data object FixSwappedPositions : WorkbenchAction
    /** Swaps latitude and longitude on every tower that has a position (for a CSV whose columns were exchanged). Doing it twice undoes it. */
    data object SwapAllPositions : WorkbenchAction
}

/** State and logic of one open project's workbench. Lives in the project's Koin scope. */
class WorkbenchViewModel(
    /** The project's industry plugin: form schema, labels, CSV format and report layout. */
    val plugin: DomainPlugin,
    private val repository: AssetRepository,
    private val imageStore: ImageStore,
    val imageResolver: AssetImageResolver,
    /** The project folder, where report settings and the logo are kept. */
    private val projectDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    initialRootImageDirectory: String = WorkbenchUiState.DEFAULT_ROOT_IMAGE_DIRECTORY,
    /** How long the export result stays visible before the overlay closes. */
    private val exportResultDisplayMillis: Long = 2000,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(WorkbenchViewModel::class.java)

    private val _state = MutableStateFlow(
        WorkbenchUiState(
            rootImageDirectory = initialRootImageDirectory,
            reportSettings = ProjectManager.readProjectConfig(projectDir)?.report ?: ReportSettings(),
        )
    )
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    /** Completes when the initial record load has finished. */
    val initialLoad: Job = refresh()

    fun onAction(action: WorkbenchAction) {
        when (action) {
            is WorkbenchAction.ImportCsv -> importCsv(action.file)
            is WorkbenchAction.ExportZip -> exportZip(action.file, action.settings, action.logo)
            is WorkbenchAction.Select -> _state.update { it.copy(selectedRecordId = action.record.id) }
            is WorkbenchAction.Delete -> delete(action.record)
            WorkbenchAction.DeleteSelected -> _state.value.selectedRecord?.let { delete(it) }
            WorkbenchAction.ClearAll -> clearAll()
            is WorkbenchAction.Save -> save(action.record, action.imageEdits)
            WorkbenchAction.Refresh -> refresh()
            WorkbenchAction.ToggleSort -> _state.update { it.copy(sortAscending = !it.sortAscending) }
            is WorkbenchAction.SetMinSeverity -> _state.update { it.copy(minSeverity = action.min) }
            WorkbenchAction.ToggleSeveritySort -> _state.update { it.copy(sortBySeverity = !it.sortBySeverity) }
            WorkbenchAction.ToggleTheme -> _state.update { it.copy(isDarkTheme = !it.isDarkTheme) }
            WorkbenchAction.DismissImportSuccess -> _state.update { it.copy(showImportSuccess = false, importWarning = null) }
            is WorkbenchAction.ExportGeoPackage -> exportGeoPackage(action.file)
            is WorkbenchAction.ImportGeoPackage -> importGeoPackage(action.file)
            WorkbenchAction.DismissNotice -> _state.update { it.copy(notice = null) }
            WorkbenchAction.FixSwappedPositions -> fixSwappedPositions()
            WorkbenchAction.SwapAllPositions -> swapAllPositions()
        }
    }

    fun refresh(): Job = viewModelScope.launch {
        val records = loadRecords()
        _state.update { it.copy(records = records) }
    }

    /** All records with up-to-date severity; a stored severity that is out of date (old project, changed rules) is corrected in the database. */
    private suspend fun loadRecords(): List<Asset> {
        val stored = repository.getAll()
        return stored.map { old -> classified(old).also { if (it != old) repository.save(it) } }
    }

    /** [asset] with its severity worked out by the plugin. */
    private fun classified(asset: Asset): Asset = plugin.classify(asset).let { if (it == asset.severity) asset else asset.copy(severity = it) }

    /** CSV rows into the database (first half of the progress bar), then an image check per asset (second half). */
    fun importCsv(file: File): Job = viewModelScope.launch {
        setImport("Reading CSV Data...", 0f)
        val drafts = withContext(io) {
            plugin.getCsvImportStrategy().parse(file) { progress -> setImport("Reading CSV Data...", progress * 0.5f) }
        }
        val start = repository.nextPosition()
        repository.insertAll(drafts.mapIndexed { i, draft ->
            Asset(
                pluginId = plugin.pluginId, position = start + i,
                latitude = draft.latitude, longitude = draft.longitude, properties = draft.properties,
            )
        })

        scanImages()

        val records = loadRecords()
        _state.update { it.copy(records = records, importProgress = null, showImportSuccess = true) }
        _state.update { it.copy(importWarning = positionWarning(it)) }
    }

    /** What the user should know about suspicious positions after an import; null when all look right. */
    private fun positionWarning(state: WorkbenchUiState): String? {
        if (state.allPositionsLookSwapped) {
            return "The towers land in the polar regions, so the Lat. and Long. columns are probably swapped.\n" +
                "Open the map to swap them back for all towers in one click."
        }
        val issues = state.positionIssues
        if (issues.isEmpty()) return null
        val byId = state.records.associateBy { it.id }
        fun names(list: List<PositionIssue>) = list.mapNotNull { byId[it.id]?.let { a -> plugin.present(a).sequenceLabel } }
            .let { n -> n.take(5).joinToString(", ") + if (n.size > 5) " and ${n.size - 5} more" else "" }
        val swapped = issues.filterIsInstance<PositionIssue.Swapped>()
        val other = issues - swapped.toSet()
        return buildString {
            append("Some positions look wrong.")
            if (swapped.isNotEmpty()) append("\nLatitude and longitude look swapped for ${names(swapped)}.")
            if (other.isNotEmpty()) append("\nFar from the rest or not a valid position: ${names(other)}.")
            append("\nOpen the map to review them; swapped ones can be fixed there in one click.")
        }
    }

    /** Serializes the position fixes: a double click on "Swap for all" must not run two swaps over each other (net: none). */
    private val positionEdits = Mutex()

    internal fun swapAllPositions(): Job = viewModelScope.launch {
        if (!positionEdits.tryLock()) return@launch
        try {
            val count = withContext(io) {
                val swapped = repository.getAll()
                    .filter { !(it.latitude == 0.0 && it.longitude == 0.0) && GeoPoint(it.longitude, it.latitude).isValid }
                    .map { it.copy(latitude = it.longitude, longitude = it.latitude) }
                // One transaction: all towers are swapped or, on failure, none, so a retry never un-swaps half of them.
                repository.insertAll(swapped)
                swapped.size
            }
            val records = loadRecords()
            _state.update { it.copy(records = records, notice = "Swapped latitude and longitude for $count tower${if (count == 1) "" else "s"}.") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Swapping all positions failed", e)
            _state.update { it.copy(notice = "Could not swap the positions: ${e.message}") }
        } finally {
            positionEdits.unlock()
        }
    }

    internal fun fixSwappedPositions(): Job = viewModelScope.launch {
        if (!positionEdits.tryLock()) return@launch
        try {
            val fixes = _state.value.positionIssues.filterIsInstance<PositionIssue.Swapped>().associateBy { it.id }
            if (fixes.isEmpty()) return@launch
            val count = withContext(io) {
                val fixed = repository.getAll().mapNotNull { asset ->
                    val fix = fixes[asset.id] ?: return@mapNotNull null
                    // Only if the tower still has the position the check saw (it may have been edited meanwhile).
                    if (asset.latitude == fix.current.lat && asset.longitude == fix.current.lon) {
                        asset.copy(latitude = fix.corrected.lat, longitude = fix.corrected.lon)
                    } else null
                }
                repository.insertAll(fixed)
                fixed.size
            }
            val records = loadRecords()
            _state.update { it.copy(records = records, notice = "Swapped latitude and longitude back for $count tower${if (count == 1) "" else "s"}.") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Fixing swapped positions failed", e)
            _state.update { it.copy(notice = "Could not swap the positions back: ${e.message}") }
        } finally {
            positionEdits.unlock()
        }
    }

    /** Imports the towers of a GeoPackage exported by this app; towers already in the project (same id) are left alone. */
    fun importGeoPackage(file: File): Job = viewModelScope.launch {
        setImport("Reading GeoPackage...", 0f)
        try {
            val content = withContext(io) { GeoPackage.read(file) }
            val existing = repository.getAll().mapTo(HashSet()) { it.id }
            val (sameKind, otherKind) = content.assets.partition { it.pluginId == plugin.pluginId }
            val (duplicates, fresh) = sameKind.partition { it.id in existing }

            val start = repository.nextPosition()
            // Images are not part of a GeoPackage: they are matched from the image folder again, so status starts as DRAFT.
            repository.insertAll(fresh.mapIndexed { i, a -> a.copy(position = start + i, status = RecordStatus.DRAFT, images = emptyMap()) })
            if (fresh.isNotEmpty()) scanImages()

            val records = loadRecords()
            _state.update { it.copy(records = records, notice = importSummary(fresh.size, duplicates.size, otherKind.size, content.skipped)) }
        } catch (e: GeoPackageException) {
            _state.update { it.copy(notice = "GeoPackage import failed: ${e.message}") }
        } catch (e: Exception) {
            log.error("GeoPackage import failed", e)
            _state.update { it.copy(notice = "GeoPackage import failed: ${e.message}") }
        } finally {
            _state.update { it.copy(importProgress = null) }
        }
    }

    private fun importSummary(imported: Int, duplicates: Int, otherKind: Int, damaged: List<String>): String = buildString {
        append("Imported $imported tower${if (imported == 1) "" else "s"}.")
        if (duplicates > 0) append("\n$duplicates already in this project (left unchanged).")
        if (otherKind > 0) append("\n$otherKind belong to a different project type and were skipped.")
        if (damaged.isNotEmpty()) {
            append("\n${damaged.size} damaged row${if (damaged.size == 1) "" else "s"} skipped:")
            damaged.take(5).forEach { append("\n  • $it") }
            if (damaged.size > 5) append("\n  • …and ${damaged.size - 5} more")
        }
    }

    fun exportGeoPackage(file: File): Job = viewModelScope.launch {
        setExport("Writing GeoPackage...", 0f)
        try {
            val assets = repository.getAll()
            val count = withContext(io) {
                GeoPackage.write(assets, file, layerTitle = projectDir.name) { current, total ->
                    setExport("Writing GeoPackage $current of $total...", current.toFloat() / total.toFloat())
                }
            }
            _state.update { it.copy(notice = "Exported $count tower${if (count == 1) "" else "s"} to ${file.name}.") }
        } catch (e: Exception) {
            log.error("GeoPackage export failed", e)
            _state.update { it.copy(notice = "GeoPackage export failed: ${e.message}") }
        } finally {
            _state.update { it.copy(exportProgress = null) }
        }
    }

    /** An image check per asset: position and capture time from metadata, and READY/DRAFT from what the folders hold. */
    private suspend fun scanImages() {
        setImport("Scanning Local Folders for Images...", 0.5f)
        val rootDir = _state.value.rootImageDirectory
        val all = repository.getAll()
        withContext(io) {
            all.forEachIndexed { index, asset ->
                // Position and capture time the CSV didn't provide come from the image metadata.
                val files = imageResolver.resolve(asset, rootDir).values.filterIsInstance<ImageSource.FromFile>().map { it.file }
                val enriched = ImageIngestor.enrich(asset, ImageIngestor.read(files))
                val newStatus = if (imageResolver.isReady(asset, rootDir)) RecordStatus.READY else RecordStatus.DRAFT
                val updated = classified(enriched.copy(status = newStatus))
                if (updated != asset) repository.save(updated)
                setImport("Scanning Local Folders for Images...", 0.5f + ((index + 1).toFloat() / all.size) * 0.5f)
            }
        }
    }

    fun exportZip(zipFile: File, newSettings: ReportSettings? = null, newLogo: File? = null): Job = viewModelScope.launch {
        setExport("Initializing Export...", 0f)
        try {
            val rootDir = _state.value.rootImageDirectory
            val assets = repository.getAll()
            val processedCount = withContext(io) {
                if (newSettings != null) {
                    val stored = ProjectManager.saveReportSettings(projectDir, newSettings, newLogo)
                    _state.update { it.copy(reportSettings = stored) }
                }
                val settings = _state.value.reportSettings
                val options = ReportOptions(
                    template = ReportTemplate.fromName(settings.template),
                    branding = ReportBranding(settings.companyName, settings.accentColor, ProjectManager.readLogo(projectDir, settings)),
                )
                BulkReportExporter(plugin).export(assets, { imageResolver.resolve(it, rootDir) }, zipFile, options) { current, total ->
                    setExport("Compressing Report $current of $total...", current.toFloat() / total.toFloat())
                }
            }
            setExport("Successfully exported $processedCount reports!", _state.value.exportProgress?.progress ?: 1f)
        } catch (e: Exception) {
            log.error("PDF export failed", e)
            setExport("Error during export: ${e.message}", _state.value.exportProgress?.progress ?: 0f)
        } finally {
            delay(exportResultDisplayMillis)
            _state.update { it.copy(exportProgress = null) }
        }
    }

    fun delete(record: Asset): Job = viewModelScope.launch {
        repository.delete(record.id, plugin.present(record).listTitle)
        withContext(io) { imageStore.deleteAsset(record.id) }
        val records = loadRecords()
        _state.update {
            it.copy(records = records, selectedRecordId = it.selectedRecordId.takeUnless { id -> id == record.id })
        }
    }

    fun clearAll(): Job = viewModelScope.launch {
        repository.clearAll()
        withContext(io) { imageStore.deleteAll() }
        _state.update { it.copy(records = emptyList(), selectedRecordId = null) }
    }

    /** Saves [record]; new images are written into the project first, replaced/cleared image files are removed after. */
    fun save(record: Asset, imageEdits: Map<String, ImageEdit> = emptyMap()): Job = viewModelScope.launch {
        val images = record.images.toMutableMap()
        val obsolete = mutableListOf<String>()
        withContext(io) {
            imageEdits.forEach { (slot, edit) ->
                val oldPath = record.images[slot]?.relativePath
                when (edit) {
                    is ImageEdit.Replace -> {
                        val newPath = imageStore.write(record.id, slot, edit.bytes)
                        images[slot] = AssetImage(newPath)
                        edit.original?.let { imageStore.writeOriginal(record.id, slot, it) }
                        if (oldPath != null && oldPath != newPath) obsolete += oldPath
                    }
                    ImageEdit.Clear -> {
                        images[slot] = AssetImage.Cleared
                        imageStore.deleteOriginal(record.id, slot)
                        if (oldPath != null) obsolete += oldPath
                    }
                }
            }
        }
        repository.save(classified(record.copy(images = images)))
        withContext(io) { obsolete.forEach(imageStore::delete) }
        val records = loadRecords()
        _state.update { it.copy(records = records) }
    }

    private fun setImport(text: String, progress: Float) =
        _state.update { it.copy(importProgress = ProgressState(text, progress)) }

    private fun setExport(text: String, progress: Float) =
        _state.update { it.copy(exportProgress = ProgressState(text, progress)) }
}
