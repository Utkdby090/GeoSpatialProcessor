package com.geospatial.processing.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import com.geospatial.processing.data.database.DatabaseConnectionManager
import com.geospatial.processing.data.database.LegacyDatabaseMigrator
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.ui.MainScreen
import com.geospatial.processing.ui.workspace.ProjectDashboardUI
import com.geospatial.processing.ui.workspace.WorkspaceLauncherUI
import com.geospatial.processing.utils.ProjectManager
import kotlinx.coroutines.launch
import java.io.File

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

    // "Not now" on the legacy-import offer lasts for this app session (it is offered again next launch).
    var legacyOfferSnoozed by remember { mutableStateOf(false) }

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

            // One-time offer to rescue records saved by older versions in the shared global database.
            var showLegacyOffer by remember { mutableStateOf(!legacyOfferSnoozed && LegacyDatabaseMigrator.hasPendingLegacyData()) }
            var legacyMigrating by remember { mutableStateOf(false) }
            var legacyError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            val installedPlugins = remember { listOf(com.geospatial.processing.core.plugin.telecom.TelecomPlugin()) }

            Box(modifier = Modifier.fillMaxSize()) {
                ProjectDashboardUI(
                    workspaceDir = state.workspaceDir,
                    onOpenProject = { projectDir ->
                        val config = ProjectManager.readProjectConfig(projectDir)
                        if (config != null) {
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
                                // createNewProject() also opens the new project's database.
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

                if (showLegacyOffer) {
                    LegacyImportDialog(
                        isWorking = legacyMigrating,
                        errorMessage = legacyError,
                        onImport = {
                            legacyMigrating = true
                            legacyError = null
                            scope.launch {
                                val dir = LegacyDatabaseMigrator.migrateInto(state.workspaceDir)
                                legacyMigrating = false
                                if (dir != null) {
                                    showLegacyOffer = false
                                    currentAppState = AppState.ActiveWorkbench(state.workspaceDir, dir)
                                } else {
                                    legacyError = "The import could not be completed. Your original data is untouched. " +
                                        "You can try again or choose \"Not now\"."
                                }
                            }
                        },
                        onNotNow = {
                            legacyOfferSnoozed = true
                            showLegacyOffer = false
                        },
                        onNeverAsk = {
                            LegacyDatabaseMigrator.dismiss()
                            showLegacyOffer = false
                        }
                    )
                }
            }
        }

        is AppState.ActiveWorkbench -> {
            // Binds this screen's identity to the project path: switching projects rebuilds everything.
            key(state.projectDir.absolutePath) {

                // All database handling lives in DatabaseConnectionManager: one connection, one file
                // (project.db), properly closed when the project closes. The repository just uses it.
                val repository = remember {
                    val openDir = DatabaseConnectionManager.currentProjectDir?.canonicalFile
                    if (openDir != state.projectDir.canonicalFile) {
                        DatabaseConnectionManager.connectToProject(state.projectDir)
                    }
                    GeoRepository(DatabaseConnectionManager.project)
                }

                MainScreen(
                    repository = repository,
                    windowState = windowState,
                    onCloseApp = onCloseApp,
                    onCloseProject = {
                        // Also closes the project database, so the folder can be exported/renamed.
                        DatabaseConnectionManager.connectToWorkspace(state.workspaceDir)
                        currentAppState = AppState.ProjectDashboard(state.workspaceDir)
                    }
                )
            }
        }
    }
}

// --- LEGACY DATA IMPORT DIALOG (styled to match the dashboard's dialogs) ---
@Composable
private fun LegacyImportDialog(
    isWorking: Boolean,
    errorMessage: String?,
    onImport: () -> Unit,
    onNotNow: () -> Unit,
    onNeverAsk: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(480.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D30)),
            elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Data from a previous version found", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isWorking) "Importing records, please wait…"
                    else "Records saved by an earlier version of GeoFlux were found on this computer. " +
                        "Import them into a new project called \"Legacy Import\"? Your original file is kept.",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
                if (isWorking) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(errorMessage, color = Color(0xFFEF5350), fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onNeverAsk,
                        enabled = !isWorking,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                    ) { Text("Don't ask again") }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onNotNow,
                        enabled = !isWorking,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
                    ) { Text("Not now") }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onImport,
                        enabled = !isWorking,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White)
                    ) { Text("Import") }
                }
            }
        }
    }
}
