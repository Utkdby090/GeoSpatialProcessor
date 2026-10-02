package com.geospatial.processing.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
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
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.model.AssetImageResolver
import com.geospatial.processing.domain.model.RecordStatus
import com.geospatial.processing.ui.map.MapView
import com.geospatial.processing.ui.map.TileLoader
import com.geospatial.processing.ui.theme.*
import com.geospatial.processing.utils.AppDirs
import java.io.File
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.JFrame

@Composable
fun FrameWindowScope.MainScreen(
    state: WorkbenchUiState,
    onAction: (WorkbenchAction) -> Unit,
    plugin: DomainPlugin,
    imageResolver: AssetImageResolver,
    windowState: WindowState,
    onCloseApp: () -> Unit,
    onCloseProject: () -> Unit
) {
    // UI-only state; everything else comes from WorkbenchViewModel.
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showAnnotationUtility by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val tileLoader = remember { TileLoader(File(AppDirs.dataDir, "tiles")) }

    val selectedRecord = state.selectedRecord
    val importCsv = { val file = pickCsvFile(); if (file != null) onAction(WorkbenchAction.ImportCsv(file)) }

    GeospatialEnterpriseTheme(darkTheme = state.isDarkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

            Column(modifier = Modifier.fillMaxSize()) {

                // --- CUSTOM IN-APP MENU BAR ---
                CustomThemeableMenuBar(
                    isDarkTheme = state.isDarkTheme,
                    onThemeToggle = { onAction(WorkbenchAction.ToggleTheme) },
                    showMap = showMap,
                    onToggleMap = { showMap = !showMap },
                    onNewProject = { showClearConfirmDialog = true },
                    onImportCsv = importCsv,
                    onExportPdf = { val file = saveZipFile(); if (file != null) onAction(WorkbenchAction.ExportZip(file)) },
                    onExit = onCloseApp,
                    hasSelection = selectedRecord != null,
                    onDeleteSelected = { onAction(WorkbenchAction.DeleteSelected) },
                    onClearAllData = { showClearConfirmDialog = true },
                    onRefreshList = { onAction(WorkbenchAction.Refresh) },
                    onOpenTools = { showAnnotationUtility = true },
                    onCloseProject = onCloseProject
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                // --- ROOT NAVIGATION LOGIC ---
                if (state.records.isEmpty()) {
                    NoProjectView(onImportClicked = importCsv)
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {

                        // LEFT PANE: Tree View
                        Box(modifier = Modifier.weight(0.3f).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(end = 1.dp)) {
                            TreeView(
                                records = state.displayedRecords,
                                plugin = plugin,
                                selectedRecord = selectedRecord,
                                rootDir = state.rootImageDirectory,
                                isAscending = state.sortAscending,
                                onToggleSort = { onAction(WorkbenchAction.ToggleSort) },
                                minSeverity = state.minSeverity,
                                sortBySeverity = state.sortBySeverity,
                                onMinSeverityChange = { onAction(WorkbenchAction.SetMinSeverity(it)) },
                                onToggleSeveritySort = { onAction(WorkbenchAction.ToggleSeveritySort) },
                                isDarkTheme = state.isDarkTheme,
                                onThemeToggle = { onAction(WorkbenchAction.ToggleTheme) },
                                onSelect = { record -> onAction(WorkbenchAction.Select(record)) },
                                onImportClick = { onAction(WorkbenchAction.ImportCsv(it)) },
                                onExportClick = { onAction(WorkbenchAction.ExportZip(it)) },
                                onDeleteClick = { onAction(WorkbenchAction.Delete(it)) }
                            )
                        }

                        // VERTICAL DIVIDER
                        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))

                        // RIGHT PANE: Detail View
                        Box(modifier = Modifier.weight(0.7f).fillMaxHeight()) {
                            if (showMap) {
                                MapView(
                                    assets = state.displayedRecords,
                                    selectedId = state.selectedRecordId,
                                    plugin = plugin,
                                    tileLoader = tileLoader,
                                    onSelect = { onAction(WorkbenchAction.Select(it)) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (selectedRecord != null) {
                                DetailView(
                                    record = selectedRecord,
                                    rootDir = state.rootImageDirectory,
                                    plugin = plugin,
                                    imageResolver = imageResolver,
                                    prevItemName = state.previousRecord?.let { plugin.present(it).sequenceLabel },
                                    nextItemName = state.nextRecord?.let { plugin.present(it).sequenceLabel },
                                    onSave = { updated, imageEdits -> onAction(WorkbenchAction.Save(updated, imageEdits)) }
                                )
                            } else {
                                NoSelectionView()
                            }
                        }
                    }
                }
            }

            // --- OVERLAYS & DIALOGS ---
            state.importProgress?.let { import ->
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.width(350.dp).padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(import.statusText, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { import.progress }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("${(import.progress * 100).toInt()}% Complete", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            state.exportProgress?.let { export ->
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Card(modifier = Modifier.width(400.dp).padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Generating Bulk ZIP...", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(progress = { export.progress }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(export.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            if (showClearConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showClearConfirmDialog = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = { Text("Start New Project?", color = MaterialTheme.colorScheme.onSurface) },
                    text = { Text("This will delete all current tower records and photos. This action cannot be undone.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)) },
                    confirmButton = {
                        Button(
                            onClick = { onAction(WorkbenchAction.ClearAll); showClearConfirmDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White)
                        ) {
                            Text("Yes, Clear All")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showClearConfirmDialog = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) }
                    }
                )
            }

            if (state.showImportSuccess) {
                AlertDialog(
                    onDismissRequest = { onAction(WorkbenchAction.DismissImportSuccess) },
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = { Text("Import Successful", color = MaterialTheme.colorScheme.secondary) },
                    text = {
                        Column {
                            Text("The tower metrics have been loaded successfully.", color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("The app is now automatically syncing photos from your root directory:", color = MaterialTheme.colorScheme.onSurface)
                            Text(text = state.rootImageDirectory, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 8.dp))
                            Text("Please ensure your tower folders (e.g., '76_0') are placed in this location to view the images.", color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    confirmButton = {
                        Button(onClick = { onAction(WorkbenchAction.DismissImportSuccess) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = Color.White)) {
                            Text("Got it")
                        }
                    }
                )
            }

            // --- STANDALONE ANNOTATION UTILITY ---
            if (showAnnotationUtility) {
                com.geospatial.processing.ui.editor.StandaloneImageEditorWindow(
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
                .background(MaterialTheme.colorScheme.background),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = appName,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp)
            )

            Row {
                val baseIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                val hoverIconColor = MaterialTheme.colorScheme.onSurface
                val hoverBgColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                val appBgColor = MaterialTheme.colorScheme.background

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
    showMap: Boolean,
    onToggleMap: () -> Unit,
    onNewProject: () -> Unit,
    onImportCsv: () -> Unit,
    onExportPdf: () -> Unit,
    onExit: () -> Unit,
    hasSelection: Boolean,
    onDeleteSelected: () -> Unit,
    onClearAllData: () -> Unit,
    onRefreshList: () -> Unit,
    onOpenTools: () -> Unit, // NEW PARAMETER
    onCloseProject: () -> Unit
) {
    var fileMenuExpanded by remember { mutableStateOf(false) }
    var editMenuExpanded by remember { mutableStateOf(false) }
    var viewMenuExpanded by remember { mutableStateOf(false) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            TextButton(onClick = { fileMenuExpanded = true }) { Text("File", color = MaterialTheme.colorScheme.onSurface) }
            DropdownMenu(expanded = fileMenuExpanded, onDismissRequest = { fileMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onNewProject() }, text = { Text("New Project", color = MaterialTheme.colorScheme.onSurface) })
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onCloseProject() }, text = { Text("Close Project", color = MaterialTheme.colorScheme.onSurface) })
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onImportCsv() }, text = { Text("Import CSV...", color = MaterialTheme.colorScheme.onSurface) })
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onExportPdf() }, text = { Text("Export PDF...", color = MaterialTheme.colorScheme.onSurface) })
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                DropdownMenuItem(onClick = { fileMenuExpanded = false; onExit() }, text = { Text("Exit", color = MaterialTheme.colorScheme.onSurface) })
            }
        }

        Box {
            TextButton(onClick = { editMenuExpanded = true }) { Text("Edit", color = MaterialTheme.colorScheme.onSurface) }
            DropdownMenu(expanded = editMenuExpanded, onDismissRequest = { editMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                DropdownMenuItem(onClick = { editMenuExpanded = false; onDeleteSelected() }, enabled = hasSelection, text = {
                    Text("Delete Selected", color = if (hasSelection) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                })
                DropdownMenuItem(onClick = { editMenuExpanded = false; onClearAllData() }, text = { Text("Clear All Data", color = MaterialTheme.colorScheme.error) })
            }
        }

        Box {
            TextButton(onClick = { viewMenuExpanded = true }) { Text("View", color = MaterialTheme.colorScheme.onSurface) }
            DropdownMenu(expanded = viewMenuExpanded, onDismissRequest = { viewMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                DropdownMenuItem(onClick = { viewMenuExpanded = false; onRefreshList() }, text = { Text("Refresh List", color = MaterialTheme.colorScheme.onSurface) })
                DropdownMenuItem(onClick = { viewMenuExpanded = false; onToggleMap() }, text = {
                    Text(if (showMap) "Hide Map" else "Show Map", color = MaterialTheme.colorScheme.onSurface)
                })
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                DropdownMenuItem(onClick = { viewMenuExpanded = false; onThemeToggle() }, text = {
                    Text(if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode", color = MaterialTheme.colorScheme.onSurface)
                })
            }
        }

        // --- TOOLS MENU (Now correctly inside the Row) ---
        Box {
            TextButton(onClick = { toolsMenuExpanded = true }) { Text("Tools", color = MaterialTheme.colorScheme.onSurface) }
            DropdownMenu(expanded = toolsMenuExpanded, onDismissRequest = { toolsMenuExpanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                DropdownMenuItem(onClick = {
                    toolsMenuExpanded = false
                    onOpenTools()
                }, text = {
                    Text("Image Annotation Utility", color = MaterialTheme.colorScheme.onSurface)
                })
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
        Text(text = status.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NoSelectionView() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(modifier = Modifier.size(64.dp), imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("No Tower Selected", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Select a tower from the left menu to view details", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
fun NoProjectView(onImportClicked: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = Icons.Default.Create, contentDescription = "No Data", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))
        Text("No Project Loaded", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Import a geospatial CSV file to begin processing.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onImportClicked, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
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