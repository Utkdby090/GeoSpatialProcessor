package com.geospatial.processing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.GeoRecord
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.isDynamicallyReady
import com.geospatial.processing.domain.usecase.CsvImportService
import com.geospatial.processing.domain.usecase.PdfGenerationService
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
    val records: List<GeoRecord> = emptyList(),
    val sortAscending: Boolean = true,
    val selectedRecordId: Int? = null,
    val rootImageDirectory: String = DEFAULT_ROOT_IMAGE_DIRECTORY,
    val isDarkTheme: Boolean = false,
    /** Non-null while a CSV import runs. */
    val importProgress: ProgressState? = null,
    /** Non-null while a PDF export runs (and briefly afterwards, to show the result). */
    val exportProgress: ProgressState? = null,
    val showImportSuccess: Boolean = false,
) {
    val displayedRecords: List<GeoRecord> get() = if (sortAscending) records else records.reversed()

    private val selectedIndex: Int? get() = displayedRecords.indexOfFirst { it.id == selectedRecordId }.takeIf { it >= 0 }
    val selectedRecord: GeoRecord? get() = selectedIndex?.let { displayedRecords[it] }
    val previousTowerName: String? get() = selectedIndex?.let { displayedRecords.getOrNull(it - 1)?.towerNumber }
    val nextTowerName: String? get() = selectedIndex?.let { displayedRecords.getOrNull(it + 1)?.towerNumber }

    companion object {
        const val DEFAULT_ROOT_IMAGE_DIRECTORY = "C:\\Tower_images"
    }
}

/** User actions on the workbench (MainScreen and its children). */
sealed interface WorkbenchAction {
    data class ImportCsv(val file: File) : WorkbenchAction
    data class ExportZip(val file: File) : WorkbenchAction
    data class Select(val record: GeoRecord) : WorkbenchAction
    data class Delete(val record: GeoRecord) : WorkbenchAction
    data object DeleteSelected : WorkbenchAction
    data object ClearAll : WorkbenchAction
    data class Save(val record: GeoRecord) : WorkbenchAction
    data object Refresh : WorkbenchAction
    data object ToggleSort : WorkbenchAction
    data object ToggleTheme : WorkbenchAction
    data object DismissImportSuccess : WorkbenchAction
}

/** State and logic of one open project's workbench. Lives in the project's Koin scope. */
class WorkbenchViewModel(
    private val repository: GeoRepository,
    private val csvService: CsvImportService,
    private val pdfService: PdfGenerationService,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How long the export result stays visible before the overlay closes. */
    private val exportResultDisplayMillis: Long = 2000,
) : ViewModel() {

    private val log = LoggerFactory.getLogger(WorkbenchViewModel::class.java)

    private val _state = MutableStateFlow(WorkbenchUiState())
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
            is WorkbenchAction.Save -> save(action.record)
            WorkbenchAction.Refresh -> refresh()
            WorkbenchAction.ToggleSort -> _state.update { it.copy(sortAscending = !it.sortAscending) }
            WorkbenchAction.ToggleTheme -> _state.update { it.copy(isDarkTheme = !it.isDarkTheme) }
            WorkbenchAction.DismissImportSuccess -> _state.update { it.copy(showImportSuccess = false) }
        }
    }

    fun refresh(): Job = viewModelScope.launch {
        val records = repository.getAllRecords()
        _state.update { it.copy(records = records) }
    }

    /** CSV rows into the database (first half of the progress bar), then image check per record (second half). */
    fun importCsv(file: File): Job = viewModelScope.launch {
        setImport("Reading CSV Data...", 0f)
        csvService.importCsvFile(file) { progress -> setImport("Reading CSV Data...", progress * 0.5f) }

        setImport("Scanning Local Folders for Images...", 0.5f)
        val rootDir = _state.value.rootImageDirectory
        val imported = repository.getAllRecords()
        withContext(io) {
            imported.forEachIndexed { index, record ->
                val newStatus = if (record.isDynamicallyReady(rootDir)) RecordStatus.READY else RecordStatus.DRAFT
                if (record.status != newStatus) repository.saveRecord(record.copy(status = newStatus))
                setImport("Scanning Local Folders for Images...", 0.5f + ((index + 1).toFloat() / imported.size) * 0.5f)
            }
        }

        val records = repository.getAllRecords()
        _state.update { it.copy(records = records, importProgress = null, showImportSuccess = true) }
    }

    fun exportZip(zipFile: File): Job = viewModelScope.launch {
        setExport("Initializing Export...", 0f)
        try {
            val processedCount = pdfService.generateBulkZipReport(zipFile, _state.value.rootImageDirectory) { current, total ->
                setExport("Compressing Report $current of $total...", current.toFloat() / total.toFloat())
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

    fun delete(record: GeoRecord): Job = viewModelScope.launch {
        repository.deleteRecord(record.id)
        val records = repository.getAllRecords()
        _state.update {
            it.copy(records = records, selectedRecordId = it.selectedRecordId.takeUnless { id -> id == record.id })
        }
    }

    fun clearAll(): Job = viewModelScope.launch {
        repository.clearAllData()
        _state.update { it.copy(records = emptyList(), selectedRecordId = null) }
    }

    fun save(record: GeoRecord): Job = viewModelScope.launch {
        repository.saveRecord(record)
        val records = repository.getAllRecords()
        _state.update { it.copy(records = records) }
    }

    private fun setImport(text: String, progress: Float) =
        _state.update { it.copy(importProgress = ProgressState(text, progress)) }

    private fun setExport(text: String, progress: Float) =
        _state.update { it.copy(exportProgress = ProgressState(text, progress)) }
}
