package com.geospatial.processing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.imaging.metadata.ImageIngestor
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.AssetImageResolver
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.domain.report.BulkReportExporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    /** The list shows only assets at least this severe; [Severity.NONE] shows everything. */
    val minSeverity: Severity = Severity.NONE,
    /** Most severe first in the list (ties keep the import/sort order). */
    val sortBySeverity: Boolean = false,
) {
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
    data class ExportZip(val file: File) : WorkbenchAction
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
}

/** State and logic of one open project's workbench. Lives in the project's Koin scope. */
class WorkbenchViewModel(
    /** The project's industry plugin: form schema, labels, CSV format and report layout. */
    val plugin: DomainPlugin,
    private val repository: AssetRepository,
    private val imageStore: ImageStore,
    val imageResolver: AssetImageResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    initialRootImageDirectory: String = WorkbenchUiState.DEFAULT_ROOT_IMAGE_DIRECTORY,
    /** How long the export result stays visible before the overlay closes. */
    private val exportResultDisplayMillis: Long = 2000,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(WorkbenchViewModel::class.java)

    private val _state = MutableStateFlow(WorkbenchUiState(rootImageDirectory = initialRootImageDirectory))
    val state: StateFlow<WorkbenchUiState> = _state.asStateFlow()

    /** Completes when the initial record load has finished. */
    val initialLoad: Job = refresh()

    fun onAction(action: WorkbenchAction) {
        when (action) {
            is WorkbenchAction.ImportCsv -> importCsv(action.file)
            is WorkbenchAction.ExportZip -> exportZip(action.file)
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
            WorkbenchAction.DismissImportSuccess -> _state.update { it.copy(showImportSuccess = false) }
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

        val records = loadRecords()
        _state.update { it.copy(records = records, importProgress = null, showImportSuccess = true) }
    }

    fun exportZip(zipFile: File): Job = viewModelScope.launch {
        setExport("Initializing Export...", 0f)
        try {
            val rootDir = _state.value.rootImageDirectory
            val assets = repository.getAll()
            val processedCount = withContext(io) {
                BulkReportExporter(plugin).export(assets, { imageResolver.resolve(it, rootDir) }, zipFile) { current, total ->
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
