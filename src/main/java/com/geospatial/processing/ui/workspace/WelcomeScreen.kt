package com.geospatial.processing.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geospatial.processing.utils.FileUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.filled.Share


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
                        // FIXED: Replaced missing Folder with native Search icon to denote directory discovery
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
    val projects = remember(workspaceDir) {
        workspaceDir.listFiles()?.filter { it.isDirectory && it.name != ".metadata" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    val sdf = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())

    // 1. THIS IS THE CRITICAL PARENT ROW! It must wrap everything.
    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {

        // --- LEFT NAV RAIL ---
        Column(modifier = Modifier.width(220.dp).fillMaxHeight().background(Color(0xFF2B2D30)).padding(16.dp)) {
            Text("GeoSpatial", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text("RCP Workbench", color = MaterialTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(40.dp))

            // Note: If you have your NavRailItem components, they go here!
            // NavRailItem(icon = Icons.Default.List, label = "Projects", isSelected = true) {}
        }

        // --- RIGHT CONTENT PANE ---
        // Because this Column is inside the Row above, .weight(1f) works perfectly!
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
                    // --- FIXED IMPORT BUTTON ---
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
                        // Explicitly force a transparent background and a clean gray border
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Gray)
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
                        Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No projects found in this workspace.", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(projects) { projectFile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2B2D30))
                                .clickable { onOpenProject(projectFile) }
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

                            // Export Icon
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
                    }
                }
            }
        }
    }
}


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