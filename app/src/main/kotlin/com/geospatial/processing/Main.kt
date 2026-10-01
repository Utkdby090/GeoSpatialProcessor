package com.geospatial.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.geospatial.processing.auth.AuthState
import com.geospatial.processing.auth.LicenseBanner
import com.geospatial.processing.di.allModules
import com.geospatial.processing.ui.LockScreen
import com.geospatial.processing.ui.components.CustomTitleBar
import com.geospatial.processing.ui.components.rememberAppIcon
import com.geospatial.processing.ui.navigation.AppRouter
import com.geospatial.processing.ui.navigation.AppViewModel
import com.geospatial.processing.ui.theme.GeospatialEnterpriseTheme
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration
import java.awt.Dimension
import kotlin.system.exitProcess

fun main() = application {
    KoinApplication(koinConfiguration { modules(allModules) }) {
        // Creating AppViewModel runs the licence/trial check (see DefaultLicenseGate).
        val appViewModel = koinInject<AppViewModel>()
        val auth by appViewModel.auth.collectAsState()

        val exit: () -> Unit = {
            appViewModel.shutdown() // close project & workspace databases cleanly
            exitApplication()
            exitProcess(0)
        }

        when (val current = auth) {
            AuthState.Authorized -> {
                val windowState = rememberWindowState(
                    position = WindowPosition(Alignment.Center),
                    size = DpSize(1280.dp, 800.dp) // Standard professional desktop starting size
                )

                Window(
                    onCloseRequest = exit,
                    state = windowState,
                    title = "GeoSpatial Processor V2.0",
                    icon = rememberAppIcon(),
                    undecorated = true, // Removes default Windows/Mac borders so our Custom Title Bar works
                    transparent = false
                ) {
                    // Enforce a minimum window size so the complex Split-Pane UI never gets crushed
                    window.minimumSize = Dimension(900, 600)

                    GeospatialEnterpriseTheme {
                        Column(modifier = Modifier.fillMaxSize()) {
                            CustomTitleBar(windowState = windowState, onCloseApp = exit)

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                AppRouter(appViewModel = appViewModel, windowState = windowState, onCloseApp = exit)
                            }

                            // The status bar is pinned to the very bottom.
                            val banner by appViewModel.banner.collectAsState()
                            LicenseStatusBar(banner)
                        }
                    }
                }
            }

            is AuthState.Locked -> {
                // We leave the lock screen decorated so it looks like a standard system prompt.
                Window(
                    onCloseRequest = exit,
                    title = current.reason,
                    state = rememberWindowState(width = 600.dp, height = 550.dp),
                    icon = rememberAppIcon()
                ) {
                    GeospatialEnterpriseTheme {
                        LockScreen(
                            showExpiredMessage = current.isSubscriptionExpired,
                            onKeyEntered = { key -> appViewModel.submitLicenseKey(key) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LicenseStatusBar(banner: LicenseBanner?) {
    val message = banner?.message ?: "Checking license..."
    val isWarning = banner?.isWarning ?: false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isWarning) Color(0xFFD32F2F) else Color(0xFF2E7D32))
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}
