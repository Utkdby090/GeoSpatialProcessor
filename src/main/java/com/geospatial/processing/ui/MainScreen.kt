package com.geospatial.processing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.domain.model.GeoRecord
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.domain.model.isDynamicallyReady
import com.geospatial.processing.domain.usecase.CsvImportService
import com.geospatial.processing.domain.usecase.PdfGenerationService
import com.geospatial.processing.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.JFrame

@Composable
fun FrameWindowScope.MainScreen(
    repository: GeoRepository,
    windowState: WindowState,
    onCloseApp: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<GeoRecord>>(emptyList()) }
    var isDarkTheme by remember { mutableStateOf(false) }
    var sortAscending by remember { mutableStateOf(true) }
    var selectedRecordId by remember { mutableStateOf<Int?>(null) }


    val displayedRecords = remember(records, sortAscending) {
        if (sortAscending) records else records.reversed()
    }

    var selectedIndex = displayedRecords.indexOfFirst { it.id == selectedRecordId }.takeIf { it >= 0 }
    val selectedRecord = selectedIndex?.let { displayedRecords[it] }
    val prevRecordName = selectedIndex?.let { displayedRecords.getOrNull(it - 1)?.towerNumber }
    val nextRecordName = selectedIndex?.let { displayedRecords.getOrNull(it + 1)?.towerNumber }

    var rootImageDirectory by remember { mutableStateOf("C:\\Tower_images") }

    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0f) }
    var importStatusText by remember { mutableStateOf("Preparing...") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showImportSuccessDialog by remember { mutableStateOf(false) }

    // Editor State
    var showAnnotationUtility by remember { mutableStateOf(false) }

    var isExporting by remember { mutableStateOf(false) }
    var exportProgressRatio by remember { mutableStateOf(0f) }
    var exportStatusText by remember { mutableStateOf("Preparing...") }

    val csvService = remember { CsvImportService(repository) }
    val pdfService = remember { PdfGenerationService(repository) }

    LaunchedEffect(Unit) {
        records = repository.getAllRecords()
    }

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
                delay(2000)
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

    AppTheme(darkTheme = isDarkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {

            Column(modifier = Modifier.fillMaxSize()) {

                // --- 1. NEW CUSTOM THEMED TITLE BAR ---
                CustomTitleBar(
                    windowState = windowState,
                    onCloseApp = onCloseApp,
                    appName = "GeoSpatial Processor"
                )

                // --- 2. CUSTOM IN-APP MENU BAR ---
                CustomThemeableMenuBar(
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = { isDarkTheme = !isDarkTheme },
                    onNewProject = { showClearConfirmDialog = true },
                    onImportCsv = { val file = pickCsvFile(); if (file != null) doImport(file) },
                    onExportPdf = { val file = saveZipFile(); if (file != null) doExport(file) },
                    onExit = onCloseApp,
                    hasSelection = selectedRecord != null,
                    onDeleteSelected = { selectedRecord?.let { doDelete(it) } },
                    onClearAllData = { showClearConfirmDialog = true },
                    onRefreshList = { scope.launch { records = repository.getAllRecords() } },
                    onOpenTools = { showAnnotationUtility = true } // Wired callback
                )

                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f))

                // --- 3. ROOT NAVIGATION LOGIC ---
                if (records.isEmpty()) {
                    NoProjectView(onImportClicked = { val file = pickCsvFile(); if (file != null) doImport(file) })
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {

                        // LEFT PANE: Tree View
                        Box(modifier = Modifier.weight(0.3f).fillMaxHeight().background(MaterialTheme.colors.surface).padding(end = 1.dp)) {
                            TreeView(
                                records = displayedRecords,
                                selectedRecord = selectedRecord,
                                rootDir = rootImageDirectory,
                                isAscending = sortAscending,
                                onToggleSort = { sortAscending = !sortAscending },
                                isDarkTheme = isDarkTheme,
                                onThemeToggle = { isDarkTheme = !isDarkTheme },
                                onSelect = { record -> selectedRecordId = record.id },
                                onImportClick = { doImport(it) },
                                onExportClick = { doExport(it) },
                                onDeleteClick = { doDelete(it) }
                            )
                        }

                        // VERTICAL DIVIDER
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)))

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
                                NoSelectionView()
                            }
                        }
                    }
                }
            }

            // --- OVERLAYS & DIALOGS ---
            if (isImporting) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.width(350.dp).padding(16.dp), backgroundColor = MaterialTheme.colors.surface) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(importStatusText, style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(progress = importProgress, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colors.secondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("${(importProgress * 100).toInt()}% Complete", color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            if (isExporting) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.width(400.dp).padding(16.dp), backgroundColor = MaterialTheme.colors.surface) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Generating Bulk ZIP...", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(progress = exportProgressRatio, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colors.secondary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(exportStatusText, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            if (showClearConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showClearConfirmDialog = false },
                    backgroundColor = MaterialTheme.colors.surface,
                    title = { Text("Start New Project?", color = MaterialTheme.colors.onSurface) },
                    text = { Text("This will delete all current tower records and photos. This action cannot be undone.", color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)) },
                    confirmButton = {
                        Button(onClick = { doClearAll() }, colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error, contentColor = Color.White)) {
                            Text("Yes, Clear All")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel", color = MaterialTheme.colors.onSurface) }
                    }
                )
            }

            if (showImportSuccessDialog) {
                AlertDialog(
                    onDismissRequest = { showImportSuccessDialog = false },
                    backgroundColor = MaterialTheme.colors.surface,
                    title = { Text("Import Successful", color = MaterialTheme.colors.secondary) },
                    text = {
                        Column {
                            Text("The tower metrics have been loaded successfully.", color = MaterialTheme.colors.onSurface)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("The app is now automatically syncing photos from your root directory:", color = MaterialTheme.colors.onSurface)
                            Text(text = rootImageDirectory, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onSurface, modifier = Modifier.padding(vertical = 8.dp))
                            Text("Please ensure your tower folders (e.g., '76_0') are placed in this location to view the images.", color = MaterialTheme.colors.onSurface)
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showImportSuccessDialog = false }, colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary, contentColor = Color.White)) {
                            Text("Got it")
                        }
                    }
                )
            }

            // --- STANDALONE ANNOTATION UTILITY ---
            if (showAnnotationUtility) {
                com.geospatial.processing.ui.editor.StandaloneImageEditorWindow( // <-- Add "Window" here
                    onDismiss = { showAnnotationUtility = false }
                )
            }
        }
    }
}

// --- NEW: THE VS-CODE STYLE DRAGGABLE TITLE BAR (WITH HOVER EFFECTS) ---
@Composable
fun FrameWindowScope.CustomTitleBar(
    windowState: WindowState,
    onCloseApp: () -> Unit,
    appName: String
) {
    WindowDraggableArea {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(MaterialTheme.colors.background),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = appName,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp)
            )

            Row {
                val baseIconColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                val hoverIconColor = MaterialTheme.colors.onSurface
                val hoverBgColor = MaterialTheme.colors.onSurface.copy(alpha = 0.1f)
                val appBgColor = MaterialTheme.colors.background

                // 1. Minimize Button
                val minInteraction = remember { MutableInteractionSource() }
                val isMinHovered by minInteraction.collectIsHoveredAsState()

                Box(
                    modifier = Modifier
                        .size(46.dp, 32.dp)
                        .background(if (isMinHovered) hoverBgColor else Color.Transparent)
                        .clickable(interactionSource = minInteraction, indication = null) { windowState.isMinimized = true },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawLine(
                            color = if (isMinHovered) hoverIconColor else baseIconColor,
                            start = Offset(0f, size.height / 2),
                            end = Offset(size.width, size.height / 2),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }

                // 2. Maximize / Restore Button
                val maxInteraction = remember { MutableInteractionSource() }
                val isMaxHovered by maxInteraction.collectIsHoveredAsState()

                Box(
                    modifier = Modifier
                        .size(46.dp, 32.dp)
                        .background(if (isMaxHovered) hoverBgColor else Color.Transparent)
                        .clickable(interactionSource = maxInteraction, indication = null) {
                            if (windowState.placement == WindowPlacement.Maximized) {
                                windowState.placement = WindowPlacement.Floating
                            } else {
                                windowState.placement = WindowPlacement.Maximized
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        val strokeW = 1.dp.toPx()
                        val currentColor = if (isMaxHovered) hoverIconColor else baseIconColor

                        if (windowState.placement == WindowPlacement.Maximized) {
                            drawRect(color = currentColor, topLeft = Offset(2.dp.toPx(), 0f), size = Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(width = strokeW))
                            drawRect(color = appBgColor, topLeft = Offset(0f, 2.dp.toPx()), size = Size(8.dp.toPx(), 8.dp.toPx()))
                            drawRect(color = currentColor, topLeft = Offset(0f, 2.dp.toPx()), size = Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(width = strokeW))
                        } else {
                            drawRect(color = currentColor, style = Stroke(width = strokeW))
                        }
                    }
                }

                // 3. Close Button
                val closeInteraction = remember { MutableInteractionSource() }
                val isCloseHovered by closeInteraction.collectIsHoveredAsState()

                Box(
                    modifier = Modifier
                        .size(46.dp, 32.dp)
                        .background(if (isCloseHovered) Color(0xFFE81123) else Color.Transparent)
                        .clickable(interactionSource = closeInteraction, indication = null) { onCloseApp() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = if (isCloseHovered) Color.White else baseIconColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CustomThemeableMenuBar(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onNewProject: () -> Unit,
    onImportCsv: () -> Unit,
    onExportPdf: () -> Unit,
    onExit: () -> Unit,
    hasSelection: Boolean,
    onDeleteSelected: () -> Unit,
    onClearAllData: () -> Unit,
    onRefreshList: () -> Unit,
    onOpenTools: () -> Unit // NEW PARAMETER
) {
    var fileMenuExpanded by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }
    var viewMenuExpanded by remember { mutableStateOf(false) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { fileMenuExpanded = true }) { Text("File", color = MaterialTheme.colors.onSurface) }
            DropdownMenu(expanded = fileMenuExpanded, onDismissRequest = { fileMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colors.surface)) {
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onNewProject() }) { Text("New Project", color = MaterialTheme.colors.onSurface) }
                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onImportCsv() }) { Text("Import CSV...", color = MaterialTheme.colors.onSurface) }
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onExportPdf() }) { Text("Export PDF...", color = MaterialTheme.colors.onSurface) }
                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onExit() }) { Text("Exit", color = MaterialTheme.colors.onSurface) }
            }
        }

        Box {
            TextButton(onClick = { editMenuExpanded = true }) { Text("Edit", color = MaterialTheme.colors.onSurface) }
            DropdownMenu(expanded = editMenuExpanded, onDismissRequest = { editMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colors.surface)) {
                DropdownMenuItem(onClick = { editMenuExpanded = false; onDeleteSelected() }, enabled = hasSelection) {
                    Text("Delete Selected", color = if (hasSelection) MaterialTheme.colors.onSurface else MaterialTheme.colors.onSurface.copy(alpha = 0.3f))
                }
                DropdownMenuItem(onClick = { editMenuExpanded = false; onClearAllData() }) { Text("Clear All Data", color = MaterialTheme.colors.error) }
            }
        }

        Box {
            TextButton(onClick = { viewMenuExpanded = true }) { Text("View", color = MaterialTheme.colors.onSurface) }
            DropdownMenu(expanded = viewMenuExpanded, onDismissRequest = { viewMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colors.surface)) {
                DropdownMenuItem(onClick = { viewMenuExpanded = false; onRefreshList() }) { Text("Refresh List", color = MaterialTheme.colors.onSurface) }
                Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
                DropdownMenuItem(onClick = { viewMenuExpanded = false; onThemeToggle() }) {
                    Text(if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode", color = MaterialTheme.colors.onSurface)
                }
            }
        }

        // --- TOOLS MENU (Now correctly inside the Row) ---
        Box {
            TextButton(onClick = { toolsMenuExpanded = true }) { Text("Tools", color = MaterialTheme.colors.onSurface) }
            DropdownMenu(expanded = toolsMenuExpanded, onDismissRequest = { toolsMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colors.surface)) {
                DropdownMenuItem(onClick = {
                    toolsMenuExpanded = false
                    onOpenTools()
                }) {
                    Text("Image Annotation Utility", color = MaterialTheme.colors.onSurface)
                }
            }
        }
    }
}

// --- REUSABLE UI COMPONENTS ---
@Composable
fun StatusIndicator(status: RecordStatus) {
    val indicatorColor = when (status) {
        RecordStatus.READY -> Color(0xFF10B981)
        RecordStatus.DRAFT -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(indicatorColor))
        Text(text = status.name, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NoSelectionView() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(modifier = Modifier.size(64.dp), imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("No Tower Selected", style = MaterialTheme.typography.h5, color = MaterialTheme.colors.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Select a tower from the left menu to view details", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
fun NoProjectView(onImportClicked: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = Icons.Default.Create, contentDescription = "No Data", modifier = Modifier.size(64.dp), tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("No Project Loaded", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colors.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Import a geospatial CSV file to begin processing.", fontSize = 14.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onImportClicked, colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
            Text("Import CSV", color = Color.White)
        }
    }
}

fun getAwtAppIcon(): Image? {
    return try {
        val resourceStream = Thread.currentThread().contextClassLoader.getResourceAsStream("geoSpatialProcessor.png")
        if (resourceStream != null) ImageIO.read(resourceStream) else null
    } catch (e: Exception) {
        null
    }
}