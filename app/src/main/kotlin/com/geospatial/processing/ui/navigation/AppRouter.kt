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
import com.geospatial.processing.ui.MainScreen
import com.geospatial.processing.ui.WorkbenchViewModel
import com.geospatial.processing.ui.components.ScopedViewModelStore
import com.geospatial.processing.ui.workspace.DashboardViewModel
import com.geospatial.processing.ui.workspace.NewProjectWizard
import com.geospatial.processing.ui.workspace.ProjectDashboardUI
import com.geospatial.processing.ui.workspace.WorkspaceLauncherUI
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Shows the screen chosen by [AppViewModel]. Each screen gets its own ViewModel store, cleared when
 * the screen is left, so a closed project's ViewModel never outlives its database.
 */
@Composable
fun FrameWindowScope.AppRouter(
    appViewModel: AppViewModel,
    windowState: WindowState,
    onCloseApp: () -> Unit
) {
    val screen by appViewModel.screen.collectAsState()

    when (val current = screen) {
        AppScreen.WorkspaceSelection ->
            WorkspaceLauncherUI(onWorkspaceSelected = appViewModel::selectWorkspace)

        is AppScreen.Dashboard -> ScopedViewModelStore(key = current.workspaceDir) {
            val dashboard = koinViewModel<DashboardViewModel> { parametersOf(current.workspaceDir) }
            DashboardRoute(dashboard)
        }

        is AppScreen.Workbench -> ScopedViewModelStore(key = current.project) {
            val workbench = koinViewModel<WorkbenchViewModel>(scope = checkNotNull(current.project.scope))
            val state by workbench.state.collectAsState()
            MainScreen(
                state = state,
                onAction = workbench::onAction,
                plugin = workbench.plugin,
                imageResolver = workbench.imageResolver,
                windowState = windowState,
                onCloseApp = onCloseApp,
                // Also closes the project database, so the folder can be exported/renamed.
                onCloseProject = appViewModel::closeProject
            )
        }
    }
}

@Composable
private fun DashboardRoute(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()
    var showNewProjectWizard by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ProjectDashboardUI(
            state = state,
            onOpenProject = viewModel::openProject,
            onCreateNewProject = { showNewProjectWizard = true },
            onImportGeox = { viewModel.importGeox(it) },
            onExportProject = { project, destination -> viewModel.exportProject(project, destination) },
            onRenameProject = viewModel::renameProject,
            onDeleteProject = viewModel::deleteProject,
            onDismissNotice = viewModel::dismissNotice
        )

        if (showNewProjectWizard) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                NewProjectWizard(
                    availablePlugins = viewModel.availablePlugins,
                    onProjectCreated = { selectedPluginId, projectName ->
                        if (viewModel.createProject(selectedPluginId, projectName)) showNewProjectWizard = false
                    },
                    onCancel = { showNewProjectWizard = false }
                )
            }
        }

        state.legacyOffer?.let { offer ->
            LegacyImportDialog(
                isWorking = offer.isWorking,
                errorMessage = offer.error,
                onImport = { viewModel.importLegacyData() },
                onNotNow = viewModel::snoozeLegacyOffer,
                onNeverAsk = viewModel::dismissLegacyOfferForever
            )
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
