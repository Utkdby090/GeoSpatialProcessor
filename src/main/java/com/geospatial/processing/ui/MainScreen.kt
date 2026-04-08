package com.geospatial.processing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.GeoRecord
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.isDynamicallyReady
import com.geospatial.processing.domain.usecase.CsvImportService
import com.geospatial.processing.domain.usecase.PdfGenerationService

// Imports from our new Enterprise Theme!
import com.geospatial.processing.ui.theme.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun FrameWindowScope.MainScreen(repository: GeoRepository) {
    // --- Application State ---
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<GeoRecord>>(emptyList()) }
    var sortAscending by remember { mutableStateOf(true) }
    var selectedRecordId by remember { mutableStateOf<Int?>(null) }
    val displayedRecords = remember(records, sortAscending) {
        if (sortAscending) records else records.reversed()
    }

    var selectedIndex = displayedRecords.indexOfFirst { it.id == selectedRecordId }.takeIf { it >= 0 }
    val selectedRecord = selectedIndex?.let { displayedRecords[it] }
    val prevRecordName = selectedIndex?.let { displayedRecords.getOrNull(it - 1)?.towerNumber }
    val nextRecordName = selectedIndex?.let { displayedRecords.getOrNull(it + 1)?.towerNumber }

    // --- ROOT DIRECTORY STATE ---
    var rootImageDirectory by remember { mutableStateOf("C:\\Tower_images") }

    // --- PROGRESS & DIALOG STATE ---
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0f) }
    var importStatusText by remember { mutableStateOf("Preparing...") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showImportSuccessDialog by remember { mutableStateOf(false) }

    // --- EXPORT STATE ---
    var isExporting by remember { mutableStateOf(false) }
    var exportProgressRatio by remember { mutableStateOf(0f) }
    var exportStatusText by remember { mutableStateOf("Preparing...") }

    // --- Services ---
    val csvService = remember { CsvImportService(repository) }
    val pdfService = remember { PdfGenerationService(repository) }

    // --- Data Loading ---
    LaunchedEffect(Unit) {
        records = repository.getAllRecords()
    }

    // --- LOGIC FUNCTIONS ---
    fun doImport(file: File) {
        scope.launch {
            isImporting = true
            importProgress = 0f
            importStatusText = "Reading CSV Data..."

            csvService.importCsvFile(file) { progress ->
                importProgress = progress * 0.5f
            }

            importStatusText = "Scanning Local Folders for Images..."
            val importedRecords = repository.getAllRecords()
            val total = importedRecords.size

            withContext(Dispatchers.IO) {
                importedRecords.forEachIndexed { index, record ->
                    val isReady = record.isDynamicallyReady(rootImageDirectory)
                    val newStatus = if (isReady) RecordStatus.READY else RecordStatus.DRAFT

                    if (record.status != newStatus) {
                        repository.saveRecord(record.copy(status = newStatus))
                    }
                    importProgress = 0.5f + (((index + 1).toFloat() / total) * 0.5f)
                }
            }

            records = repository.getAllRecords()
            isImporting = false
            showImportSuccessDialog = true
        }
    }

    fun doExport(zipFile: File) {
        scope.launch {
            isExporting = true
            exportProgressRatio = 0f
            exportStatusText = "Initializing Export..."

            try {
                val processedCount = pdfService.generateBulkZipReport(zipFile, rootImageDirectory) { current, total ->
                    exportProgressRatio = current.toFloat() / total.toFloat()
                    exportStatusText = "Compressing Report $current of $total..."
                }
                exportStatusText = "Successfully exported $processedCount reports!"
            } catch (e: Exception) {
                e.printStackTrace()
                exportStatusText = "Error during export: ${e.message}"
            } finally {
                kotlinx.coroutines.delay(2000)
                isExporting = false
            }
        }
    }

    fun doDelete(record: GeoRecord) {
        scope.launch {
            repository.deleteRecord(record.id)
            records = repository.getAllRecords()
            if (selectedRecord?.id == record.id) selectedIndex = null
        }
    }

    fun doClearAll() {
        scope.launch {
            repository.clearAllData()
            records = emptyList()
            selectedIndex = null
            showClearConfirmDialog = false
        }
    }

    // --- NATIVE MENU BAR ---
    MenuBar {
        Menu("File") {
            Item("New Project", onClick = { showClearConfirmDialog = true }, shortcut = KeyShortcut(Key.N, ctrl = true))
            Separator()
            Item("Import CSV...", onClick = {
                val file = pickCsvFile()
                if (file != null) doImport(file)
            }, shortcut = KeyShortcut(Key.I, ctrl = true))
            Item("Export PDF...", onClick = {
                val file = saveZipFile()
                if (file != null) doExport(file)
            }, shortcut = KeyShortcut(Key.E, ctrl = true))
            Separator()
            Item("Exit", onClick = { kotlin.system.exitProcess(0) })
        }
        Menu("Edit") {
            Item("Delete Selected", onClick = { selectedRecord?.let { doDelete(it) } }, enabled = selectedRecord != null)
            Item("Clear All Data", onClick = { showClearConfirmDialog = true })
        }
        Menu("View") {
            Item("Refresh List", onClick = { scope.launch { records = repository.getAllRecords() } })
        }
    }

    // --- UI LAYOUT WITH THEME ---
    Box(modifier = Modifier.fillMaxSize().background(BackgroundSlate)) {

        // --- ROOT NAVIGATION LOGIC ---
        if (records.isEmpty()) {
            // SHOW THE PREMIUM EMPTY STATE IF DB IS EMPTY
            NoProjectView(
                onImportClicked = {
                    val file = pickCsvFile()
                    if (file != null) doImport(file)
                }
            )
        } else {
            // SHOW YOUR EXISTING SPLIT-PANE LAYOUT IF DB HAS DATA
            Row(modifier = Modifier.fillMaxSize()) {

                // LEFT PANE: Tree View
                Box(
                    modifier = Modifier
                        .weight(0.3f)
                        .fillMaxHeight()
                        .background(Color.White)
                        .padding(end = 1.dp)
                ) {
                    TreeView(
                        records = displayedRecords,
                        selectedRecord = selectedRecord,
                        rootDir = rootImageDirectory,
                        isAscending = sortAscending,
                        onToggleSort = { sortAscending = !sortAscending },
                        onSelect = { record ->
                            selectedRecordId = record.id
                        },
                        onImportClick = { doImport(it) },
                        onExportClick = { doExport(it) },
                        onDeleteClick = { doDelete(it) }
                    )
                }

                // VERTICAL DIVIDER
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(BorderLight))

                // RIGHT PANE: Detail View
                Box(modifier = Modifier.weight(0.7f).fillMaxHeight()) {
                    if (selectedRecord != null) {
                        DetailView(
                            record = selectedRecord!!,
                            rootDir = rootImageDirectory,
                            prevItemName = prevRecordName,
                            nextItemName = nextRecordName,
                            onSave = { updatedRecord ->
                                scope.launch {
                                    repository.saveRecord(updatedRecord)
                                    records = repository.getAllRecords()
                                    selectedIndex = records.indexOfFirst { it.id == updatedRecord.id }
                                }
                            }
                        )
                    } else {
                        // The subtle empty state for the right pane
                        NoSelectionView()
                    }
                }
            }
        }

        // --- OVERLAYS & DIALOGS ---

        // 1. IMPORT PROGRESS OVERLAY
        if (isImporting) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(modifier = Modifier.width(350.dp).padding(16.dp)) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(importStatusText, style = MaterialTheme.typography.h6, color = NavyPrimary)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = importProgress,
                            modifier = Modifier.fillMaxWidth(),
                            color = AzureAccent
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${(importProgress * 100).toInt()}% Complete")
                    }
                }
            }
        }

        // 2. EXPORT PROGRESS OVERLAY
        if (isExporting) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(modifier = Modifier.width(400.dp).padding(16.dp)) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Generating Bulk ZIP...", style = MaterialTheme.typography.h6, color = NavyPrimary)
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = exportProgressRatio,
                            modifier = Modifier.fillMaxWidth(),
                            color = AzureAccent
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(exportStatusText, style = MaterialTheme.typography.body2)
                    }
                }
            }
        }

        // 3. CONFIRMATION DIALOG
        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                title = { Text("Start New Project?", color = NavyPrimary) },
                text = { Text("This will delete all current tower records and photos. This action cannot be undone.") },
                confirmButton = {
                    Button(onClick = { doClearAll() }, colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error, contentColor = Color.White)) {
                        Text("Yes, Clear All")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel") }
                }
            )
        }

        // 4. IMPORT SUCCESS NOTIFICATION
        if (showImportSuccessDialog) {
            AlertDialog(
                onDismissRequest = { showImportSuccessDialog = false },
                title = { Text("Import Successful", color = AzureAccent) },
                text = {
                    Column {
                        Text("The tower metrics have been loaded successfully.")
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("The app is now automatically syncing photos from your root directory:")
                        Text(
                            text = rootImageDirectory,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Text("Please ensure your tower folders (e.g., '76_0') are placed in this location to view the images.")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showImportSuccessDialog = false },
                        colors = ButtonDefaults.buttonColors(backgroundColor = AzureAccent, contentColor = Color.White)
                    ) {
                        Text("Got it")
                    }
                }
            )
        }
    }
}

// --- REUSABLE UI COMPONENTS ---

@Composable
fun StatusIndicator(status: RecordStatus) {
    val indicatorColor = when (status) {
        RecordStatus.READY -> StatusReady
        RecordStatus.DRAFT -> StatusDraft
        else -> StatusError
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(indicatorColor)
        )
        Text(
            text = status.name,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// Shown in the right pane when data exists but nothing is clicked
@Composable
fun NoSelectionView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(modifier = Modifier.size(64.dp), imageVector = Icons.Default.Info, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("No Tower Selected", style = MaterialTheme.typography.h5, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Select a tower from the left menu to view details", style = MaterialTheme.typography.body2, color = TextSecondary)
    }
}

// Shown full screen when the database is totally empty
@Composable
fun NoProjectView(onImportClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Create,
            contentDescription = "No Data",
            modifier = Modifier.size(64.dp),
            tint = TextSecondary.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Project Loaded",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Import a geospatial CSV file to begin processing.",
            fontSize = 14.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onImportClicked,
            colors = ButtonDefaults.buttonColors(backgroundColor = AzureAccent),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text("Import CSV", color = Color.White)
        }
    }
}

