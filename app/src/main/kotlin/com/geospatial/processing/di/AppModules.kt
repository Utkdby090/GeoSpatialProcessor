package com.geospatial.processing.di

import com.geospatial.processing.auth.DefaultLicenseGate
import com.geospatial.processing.auth.LicenseGate
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.core.plugin.telecom.TelecomPlugin
import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.AssetImageResolver
import com.geospatial.processing.utils.ProjectManager
import com.geospatial.processing.ui.WorkbenchViewModel
import com.geospatial.processing.ui.navigation.AppNavigator
import com.geospatial.processing.ui.navigation.AppViewModel
import com.geospatial.processing.ui.navigation.PrefsWorkspaceStore
import com.geospatial.processing.ui.navigation.WorkspaceStore
import com.geospatial.processing.ui.workspace.DashboardViewModel
import com.geospatial.processing.ui.workspace.LegacyImportOffer
import com.geospatial.processing.ui.workspace.MigratorLegacyImportSource
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

/** App-wide singletons and the per-workspace dashboard. */
val appModule = module {
    single<LicenseGate> { DefaultLicenseGate() }
    single<WorkspaceStore> { PrefsWorkspaceStore }
    single<ProjectOpener> { KoinProjectOpener(getKoin()) }
    single { AppViewModel(get(), get(), get()) } bind AppNavigator::class

    // Installed industry plugins. New plugins are registered here.
    single<List<DomainPlugin>> { listOf(TelecomPlugin()) }
    single { LegacyImportOffer(MigratorLegacyImportSource) }

    viewModel { (workspaceDir: File) -> DashboardViewModel(workspaceDir, get(), get(), get()) }
}

/** Everything that belongs to one open project; created and closed with the project (see [KoinProjectOpener]). */
val projectModule = module {
    scope(PROJECT_SCOPE) {
        scoped { AssetRepository(get<ProjectSession>().database) }
        scoped { ImageStore(get<ProjectSession>().projectDir) }
        // The industry plugin named in the project's project.json.
        scoped<DomainPlugin> {
            val projectDir = get<ProjectSession>().projectDir
            val pluginId = ProjectManager.readProjectConfig(projectDir)?.pluginId
            get<List<DomainPlugin>>().firstOrNull { it.pluginId == pluginId }
                ?: error("Plugin '$pluginId' used by ${projectDir.name} is not installed")
        }
        scoped { AssetImageResolver(get(), get<ProjectSession>().projectDir) }
        viewModel { WorkbenchViewModel(get(), get(), get(), get()) }
    }
}

val allModules = listOf(appModule, projectModule)
