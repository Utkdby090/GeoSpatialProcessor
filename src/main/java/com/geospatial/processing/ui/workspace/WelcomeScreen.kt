package com.geospatial.processing.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geospatial.processing.utils.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- STATE 1: THE WORKSPACE LAUNCHER ---
@Composable
fun WorkspaceLauncherUI(onWorkspaceSelected: (File) -> Unit) {
    var selectedPath by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22)), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(500.dp),
            backgroundColor = Color(0xFF2B2D30),
            elevation = 12.dp,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text("Welcome to GeoSpatial V2", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Select a master directory to store your workspace databases and project files.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = selectedPath,
                        onValueChange = { selectedPath = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Workspace Path", color = Color.Gray) },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = Color.White,
                            focusedBorderColor = MaterialTheme.colors.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val dir = FileUtils.pickDirectory("Select Workspace Root")
                            if (dir != null) selectedPath = dir.absolutePath
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Browse Folder", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { onWorkspaceSelected(File(selectedPath)) },
                    enabled = selectedPath.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Text("Launch Workspace", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- STATE 2: THE INTELLIJ-STYLE DASHBOARD ---
@Composable
fun ProjectDashboardUI(
    workspaceDir: File,
    onOpenProject: (File) -> Unit,
    onCreateNewProject: () -> Unit
) {
    // State for modals
    var projectToModify by remember { mutableStateOf<File?>(null) }
    var projectToDelete by remember { mutableStateOf<File?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val projects = remember(workspaceDir, refreshTrigger) {
        // THE LOGIC UPDATE: Ignore anything starting with a dot to hide "Soft Deleted" folders
        workspaceDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    val sdf = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())

    Box(modifier = Modifier.fillMaxSize()) {

        Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {

            // --- LEFT NAV RAIL ---
            Column(modifier = Modifier.width(220.dp).fillMaxHeight().background(Color(0xFF2B2D30)).padding(16.dp)) {
                Text("GeoFlux", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "data processor",
                    color = MaterialTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(40.dp))
            }


            // --- RIGHT CONTENT PANE ---
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(32.dp)) {

                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Projects", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Import Button
                        OutlinedButton(
                            onClick = {
                                val chooser = javax.swing.JFileChooser().apply { dialogTitle = "Import .geox Project" }
                                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    val importedFolder = com.geospatial.processing.utils.ProjectArchiver.importProject(
                                        chooser.selectedFile,
                                        workspaceDir
                                    )
                                    if (importedFolder != null) onOpenProject(importedFolder)
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                androidx.compose.ui.graphics.Color.Gray
                            )
                        ) {
                            Text("Import .geox")
                        }

                        // New Project Button
                        Button(
                            onClick = onCreateNewProject,
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Project", color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))

                // Project List Workspace Region
                if (projects.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No projects found in this workspace.", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(projects) { projectFile ->
                            ProjectRowWithContextMenu(
                                projectFile = projectFile,
                                sdf = sdf,
                                onOpenProject = onOpenProject,
                                onEditProject = { file -> projectToModify = file },
                                onDeleteProject = { file -> projectToDelete = file }
                            )
                        }
                    }
                }
            }
        }

        // --- OVERLAYS ---

        if (projectToDelete != null) {
            DeleteProjectDialog(
                projectName = projectToDelete!!.name,
                onDismiss = { projectToDelete = null },
                onConfirm = { moveToTrash ->

                    val targetFile = projectToDelete!!

                    try {
                        if (moveToTrash) {
                            // SAFE LOGIC 1: Move to OS Trash bin
                            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH)) {
                                java.awt.Desktop.getDesktop().moveToTrash(targetFile)
                            } else {
                                // Fallback if OS blocks trash api
                                println("Trash not supported. Falling back to soft delete.")
                                val hiddenFile = java.io.File(targetFile.parentFile, ".deleted_${targetFile.name}")
                                targetFile.renameTo(hiddenFile)
                            }
                        } else {
                            // SAFE LOGIC 2: Soft delete (Rename)
                            val hiddenFile = java.io.File(targetFile.parentFile, ".deleted_${targetFile.name}")
                            targetFile.renameTo(hiddenFile)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    projectToDelete = null
                    refreshTrigger++
                }
            )
        }

        if (projectToModify != null) {
            ModifyProjectDialog(
                projectFile = projectToModify!!,
                onDismiss = { projectToModify = null },
                onSave = { newName ->
                    val oldFile = projectToModify!!
                    val newFile = File(oldFile.parentFile, newName)

                    if (oldFile.name != newName && !newFile.exists()) {
                        oldFile.renameTo(newFile)
                    }
                    projectToModify = null
                    refreshTrigger++
                }
            )
        }
    }
}

// ... NavRailItem and ProjectRowWithContextMenu remain exactly the same ...
@Composable
private fun NavRailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent
    val tint = if (isSelected) MaterialTheme.colors.primary else Color.Gray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = tint, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRowWithContextMenu(
    projectFile: File,
    sdf: SimpleDateFormat,
    onOpenProject: (File) -> Unit,
    onEditProject: (File) -> Unit,
    onDeleteProject: (File) -> Unit
) {
    var isContextMenuVisible by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2B2D30))
                .onClick(
                    matcher = PointerMatcher.mouse(PointerButton.Primary),
                    onClick = { onOpenProject(projectFile) }
                )
                .onClick(
                    matcher = PointerMatcher.mouse(PointerButton.Secondary),
                    onClick = { isContextMenuVisible = true }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.List,
                contentDescription = null,
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    projectFile.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(projectFile.absolutePath, color = Color.Gray, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "Save Project As..."
                    selectedFile = File("${projectFile.name}.geox")
                }
                if (chooser.showSaveDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                    com.geospatial.processing.utils.ProjectArchiver.exportProject(
                        projectFile,
                        chooser.selectedFile
                    )
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = "Export Project", tint = Color.Gray)
            }

            Spacer(modifier = Modifier.width(16.dp))
            Text(sdf.format(Date(projectFile.lastModified())), color = Color.Gray, fontSize = 12.sp)
        }

        CursorDropdownMenu(
            expanded = isContextMenuVisible,
            onDismissRequest = { isContextMenuVisible = false }
        ) {
            DropdownMenuItem(onClick = {
                isContextMenuVisible = false
                onOpenProject(projectFile)
            }) {
                Text("Open Project")
            }
            DropdownMenuItem(onClick = {
                isContextMenuVisible = false
                onEditProject(projectFile)
            }) {
                Text("Modify Details")
            }
            DropdownMenuItem(onClick = {
                isContextMenuVisible = false
                onDeleteProject(projectFile)
            }) {
                Text("Delete Project", color = Color.Red)
            }
        }
    }
}

// ... ModifyProjectDialog and DeleteProjectDialog remain exactly the same ...
@Composable
fun ModifyProjectDialog(
    projectFile: File,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var projectName by remember { mutableStateOf(projectFile.name) }
    var isTelecomSelected by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(500.dp),
            shape = RoundedCornerShape(12.dp),
            backgroundColor = Color.White,
            elevation = 24.dp
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text("Modify Project Details", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Update the project name or configure environment modules.", fontSize = 13.sp, color = Color.Gray)

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Project Name") },
                    singleLine = true,
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = Color.Black,
                        unfocusedBorderColor = Color.LightGray,
                        focusedBorderColor = Color.Black,
                        focusedLabelColor = Color.Black
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))
                Text("Installed Modules", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isTelecomSelected = !isTelecomSelected },
                    border = BorderStroke(
                        width = if (isTelecomSelected) 2.dp else 1.dp,
                        color = if (isTelecomSelected) Color.Black else Color.LightGray
                    ),
                    backgroundColor = if (isTelecomSelected) Color(0xFFF5F5F5) else Color.White,
                    elevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Telecom Grid Inspection", fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("Process 765kV transmission lines, mid-spans, and hardware fittings.", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        border = BorderStroke(1.dp, Color.LightGray),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onSave(projectName) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF2A2A2E), // Dark button
                            contentColor = Color.White
                        ),
                        enabled = projectName.isNotBlank()
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
    }
}

// THE LOGIC UPDATE: Now passes a boolean for safe deletion handling
@Composable
fun DeleteProjectDialog(
    projectName: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var moveToTrash by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(450.dp),
            shape = RoundedCornerShape(8.dp),
            backgroundColor = Color(0xFF2B2D30),
            elevation = 24.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Remove Project", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Are you sure you want to remove '$projectName' from the workspace?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { moveToTrash = !moveToTrash }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = moveToTrash,
                        onCheckedChange = { moveToTrash = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFD32F2F))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Move to Recycle Bin", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("If unchecked, the project is only hidden from this list.", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = Color.Transparent,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.Gray)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onConfirm(moveToTrash) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (moveToTrash) Color(0xFFD32F2F) else MaterialTheme.colors.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(if (moveToTrash) "Delete to Trash" else "Remove from List")
                    }
                }
            }
        }
    }
}