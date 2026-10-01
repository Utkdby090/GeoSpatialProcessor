package com.geospatial.processing.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import com.geospatial.processing.data.database.DatabaseConnectionManager
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.ui.MainScreen
import com.geospatial.processing.ui.workspace.ProjectDashboardUI
import com.geospatial.processing.ui.workspace.WorkspaceLauncherUI
import com.geospatial.processing.utils.ProjectManager
import java.io.File

// --- NEW EXPOSED IMPORTS REQUIRED FOR ISOLATION ---
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import com.geospatial.processing.data.table.GeoDataTable
import com.geospatial.processing.data.table.AuditLogs

sealed class AppState {
    object WorkspaceSelection : AppState()
    data class ProjectDashboard(val workspaceDir: File) : AppState()
    data class ActiveWorkbench(val workspaceDir: File, val projectDir: File) : AppState()
}

@Composable
fun FrameWindowScope.AppRouter(
    windowState: WindowState,
    onCloseApp: () -> Unit
) {
    var currentAppState by remember { mutableStateOf<AppState>(com.geospatial.processing.utils.WorkspacePrefs.getLastWorkspace()?.let { savedDir ->
        DatabaseConnectionManager.connectToWorkspace(savedDir)
        AppState.ProjectDashboard(savedDir)
    } ?: AppState.WorkspaceSelection) }

    when (val state = currentAppState) {

        is AppState.WorkspaceSelection -> {
            WorkspaceLauncherUI(
                onWorkspaceSelected = { workspaceDir ->
                    if (!workspaceDir.exists()) workspaceDir.mkdirs()
                    com.geospatial.processing.utils.WorkspacePrefs.saveWorkspace(workspaceDir.absolutePath)
                    DatabaseConnectionManager.connectToWorkspace(workspaceDir)
                    currentAppState = AppState.ProjectDashboard(workspaceDir)
                }
            )
        }

        is AppState.ProjectDashboard -> {
            var showNewProjectWizard by remember { mutableStateOf(false) }

            val installedPlugins = remember { listOf(com.geospatial.processing.core.plugin.telecom.TelecomPlugin()) }

            Box(modifier = Modifier.fillMaxSize()) {
                ProjectDashboardUI(
                    workspaceDir = state.workspaceDir,
                    onOpenProject = { projectDir ->
                        val config = ProjectManager.readProjectConfig(projectDir)
                        if (config != null) {
                            // Note: Kept your existing manager call if it sets up folders/globals
                            DatabaseConnectionManager.connectToProject(projectDir)
                            currentAppState = AppState.ActiveWorkbench(state.workspaceDir, projectDir)
                        } else {
                            println("Invalid Project: Missing project.json")
                        }
                    },
                    onCreateNewProject = { showNewProjectWizard = true }
                )

                if (showNewProjectWizard) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        com.geospatial.processing.ui.workspace.NewProjectWizard(
                            availablePlugins = installedPlugins,
                            onProjectCreated = { selectedPluginId, projectName ->
                                val newProjectDir = ProjectManager.createNewProject(
                                    workspaceDir = state.workspaceDir,
                                    projectName = projectName,
                                    pluginId = selectedPluginId
                                )

                                if (newProjectDir != null) {
                                    showNewProjectWizard = false
                                    currentAppState = AppState.ActiveWorkbench(state.workspaceDir, newProjectDir)
                                }
                            },
                            onCancel = { showNewProjectWizard = false }
                        )
                    }
                }
            }
        }

        is AppState.ActiveWorkbench -> {
            // THE FIX: The key block strictly binds this entire screen's identity to the project path.
            key(state.projectDir.absolutePath) {

                // 1. Initialize the ISOLATED database strictly inside the key block
                val repository = remember {
                    val dbFile = File(state.projectDir, "local_geodata.db")

                    // Create an isolated connection just for this project
                    val isolatedDatabase = Database.connect(
                        url = "jdbc:sqlite:${dbFile.absolutePath}",
                        driver = "org.sqlite.JDBC"
                    )

                    // Force schema generation to prevent the "no such table" crash
                    transaction(isolatedDatabase) {
                        SchemaUtils.create(GeoDataTable, AuditLogs)
                    }

                    // Inject the isolated database into our updated GeoRepository
                    GeoRepository(isolatedDatabase)
                }

                // 2. Render the IDE screen with a perfectly clean slate
                MainScreen(
                    repository = repository,
                    windowState = windowState,
                    onCloseApp = onCloseApp,
                    onCloseProject = {
                        DatabaseConnectionManager.connectToWorkspace(state.workspaceDir)
                        currentAppState = AppState.ProjectDashboard(state.workspaceDir)
                    }
                )
            }
        }
    }
}

// --- PLACEHOLDER WIZARD ---
@Composable
private fun MockNewProjectWizard(
    onProjectCreated: (pluginId: String, projectName: String) -> Unit,
    onCancel: () -> Unit
) {
    var projectName by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.width(400.dp).padding(16.dp),
        elevation = 24.dp,
        shape = RoundedCornerShape(8.dp),
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Create New Project", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Project Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    enabled = projectName.isNotBlank(),
                    onClick = { onProjectCreated("com.geo.telecom", projectName) }
                ) {
                    Text("Create")
                }
            }
        }
    }
}