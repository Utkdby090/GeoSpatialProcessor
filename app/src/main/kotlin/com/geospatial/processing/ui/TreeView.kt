package com.geospatial.processing.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.Severity
import com.geospatial.processing.ui.components.SeverityBadge
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun TreeView(
    records: List<Asset>,
    plugin: DomainPlugin,
    selectedRecord: Asset?,
    rootDir: String,
    isAscending: Boolean,
    onToggleSort: () -> Unit,
    minSeverity: Severity,
    sortBySeverity: Boolean,
    onMinSeverityChange: (Severity) -> Unit,
    onToggleSeveritySort: () -> Unit,

    // NEW: Theme Toggle Parameters
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,

    onSelect: (Asset) -> Unit,
    onImportClick: (File) -> Unit,
    onExportClick: () -> Unit,
    onDeleteClick: (Asset) -> Unit,
    modifier: Modifier = Modifier
) {
    // State for scrolling
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            // Dynamically uses the surface color (White in Light Mode, Slate in Dark Mode)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // --- 1. THE ENTERPRISE HEADER ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Dynamically uses the Primary color for the header
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT SIDE: Title & Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "PROJECT TOWERS",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = records.size.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // RIGHT SIDE: The Quick Actions
                Row(verticalAlignment = Alignment.CenterVertically) {

                    // --- SEVERITY FILTER / SORT ---
                    var filterMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filter by severity",
                                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (minSeverity != Severity.NONE || sortBySeverity) 1f else 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { filterMenuOpen = false }) {
                            Severity.entries.forEach { level ->
                                DropdownMenuItem(
                                    text = {
                                        val prefix = if (minSeverity == level) "✓  " else "    "
                                        Text(prefix + if (level == Severity.NONE) "Show all" else "${level.label} and above")
                                    },
                                    onClick = { onMinSeverityChange(level); filterMenuOpen = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text((if (sortBySeverity) "✓  " else "    ") + "Most severe first") },
                                onClick = { onToggleSeveritySort(); filterMenuOpen = false },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // --- NEW: DAY/NIGHT TOGGLE BUTTON ---
                    IconButton(
                        onClick = onThemeToggle,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text(
                            text = if (isDarkTheme) "☀️" else "🌙", // Visually swaps based on state
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Export ZIP Button
                    IconButton(
                        onClick = onExportClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export ZIP",
                            tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Import CSV Button
                    IconButton(
                        onClick = {
                            val file = pickCsvFile()
                            if (file != null) onImportClick(file)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Import CSV",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // --- 2. THE SCROLLABLE LIST ---
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(records, key = { it.id }) { record ->
                    val labels = plugin.present(record)
                    val isSelected = selectedRecord?.id == record.id

                    // Secondary color (Azure) handles highlights automatically
                    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f) else Color.Transparent
                    val indicatorColor = if (isSelected) MaterialTheme.colorScheme.secondary else Color.Transparent

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(record) }
                                .background(backgroundColor)
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Edge Active Indicator
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(indicatorColor)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            // Main Text Content
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = labels.listTitle,
                                    // Switches between Azure (selected) or dynamic OnSurface color
                                    color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = labels.listSubtitle,
                                    // Secondary text is just onSurface with 60% opacity
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            SeverityBadge(record.severity, modifier = Modifier.padding(end = 8.dp))

                            // Connects to the StatusIndicator in MainScreen
                            StatusIndicator(status = record.status)

                            if (isSelected) {
                                IconButton(
                                    onClick = { onDeleteClick(record) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        // Divider adapts to Dark/Light mode using 12% opacity of the text color
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), thickness = 1.dp)
                    }
                }
            }

            // THE VERTICAL SCROLLBAR
            VerticalScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                adapter = rememberScrollbarAdapter(listState)
            )
        }
    }
}

// --- FILE PICKER HELPERS (SHARED WITH MAINSCREEN) ---

fun pickCsvFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Import Location Data"
    chooser.fileFilter = FileNameExtensionFilter("CSV Files", "csv", "txt")
    val parentFrame = JFrame().apply {
        iconImage = getAwtAppIcon()
    }

    // 2. Pass the custom frame instead of 'null'
    val result = chooser.showOpenDialog(parentFrame)

    // 3. Immediately destroy the frame after the user closes the dialog
    parentFrame.dispose()
    
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun savePdfFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Save Report As"
    chooser.selectedFile = File("TowerReport.pdf")
    chooser.fileFilter = FileNameExtensionFilter("PDF Documents", "pdf")

    val result = chooser.showSaveDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile
        return if (file.name.endsWith(".pdf", ignoreCase = true)) file else File(file.parent, "${file.name}.pdf")
    }
    return null
}

fun saveZipFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Save Bulk PDF ZIP"
    chooser.fileFilter = FileNameExtensionFilter("ZIP Archive", "zip")
    val parentFrame = JFrame().apply {
        iconImage = getAwtAppIcon()
    }

    // 2. Pass the custom frame instead of 'null'
    val result = chooser.showOpenDialog(parentFrame)

    // 3. Immediately destroy the frame after the user closes the dialog
    parentFrame.dispose()
    if (result == JFileChooser.APPROVE_OPTION) {
        var file = chooser.selectedFile
        if (!file.name.lowercase().endsWith(".zip")) {
            file = File(file.absolutePath + ".zip")
        }
        return file
    }
    return null
}