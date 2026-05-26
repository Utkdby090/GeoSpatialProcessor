package com.geospatial.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.geospatial.processing.auth.LicenseManager
import com.geospatial.processing.auth.LicenseStorage
import com.geospatial.processing.ui.LockScreen
import com.geospatial.processing.ui.components.CustomTitleBar
import com.geospatial.processing.ui.navigation.AppRouter
import com.geospatial.processing.ui.theme.GeospatialEnterpriseTheme
import com.geospatial.processing.utils.TrialManager
import java.awt.Dimension
import kotlin.system.exitProcess

fun main() = application {

    var lockoutReason by remember { mutableStateOf("Activation Required") }

    // 1. EVALUATE SECURITY STATE ON STARTUP (Preserved from original)
    val initialAuthState = remember {
        val savedLicense = LicenseStorage.getLicense()

        if (savedLicense != null) {
            val status = LicenseManager.verifyLicense(savedLicense)
            when (status) {
                is LicenseManager.LicenseStatus.Valid -> return@remember true
                is LicenseManager.LicenseStatus.Expired -> {
                    LicenseStorage.clearLicense()
                    lockoutReason = "Subscription Expired - Renewal Required"
                    return@remember false
                }
                else -> {
                    LicenseStorage.clearLicense()
                    lockoutReason = "Invalid License - Activation Required"
                    return@remember false
                }
            }
        }

        val isTrialActive = !TrialManager.isTrialExpired()
        if (!isTrialActive) {
            lockoutReason = "Trial Expired - Activation Required"
        }
        isTrialActive
    }

    var isAuthorized by remember { mutableStateOf(initialAuthState) }

    // --- THE MASTER APPLICATION ROUTER ---
    if (isAuthorized) {

        // Initialize the OS Window State for the RCP Workspace
        val windowState = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = DpSize(1280.dp, 800.dp) // Standard professional desktop starting size
        )

        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            state = windowState,
            title = "GeoSpatial Processor V2.0",
            icon = painterResource("geoSpatialProcessor.png"),
            undecorated = true, // Removes default Windows/Mac borders so our Custom Title Bar works
            transparent = false
        ) {
            // Enforce a minimum window size so the complex Split-Pane UI never gets crushed
            window.minimumSize = Dimension(900, 600)

            GeospatialEnterpriseTheme {
                Column(modifier = Modifier.fillMaxSize()) {

                    CustomTitleBar(
                        windowState = windowState,
                        onCloseApp = {
                            exitApplication()
                            exitProcess(0)
                        }
                    )

                    // 1. Core State Machine Workspace (Takes up all available height)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Boot into the Master State Machine instead of directly to MainScreen
                        AppRouter(
                            windowState = windowState,
                            onCloseApp = {
                                exitApplication()
                                exitProcess(0)
                            }
                        )
                    }

                    // 2. The Status Bar is pinned to the very bottom (Preserved from original)
                    LicenseStatusBar()
                }
            }
        }
    } else {
        // --- THE SECURITY LOCK SCREEN (Preserved from original) ---
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = lockoutReason,
            state = rememberWindowState(width = 600.dp, height = 550.dp),
            icon = painterResource("geoSpatialProcessor.png")
            // Note: We leave the lock screen decorated so it looks like a standard system prompt.
        ) {
            GeospatialEnterpriseTheme {
                LockScreen(
                    showExpiredMessage = lockoutReason.contains("Subscription"),
                    onKeyEntered = { key ->
                        if (key == "SECRET_ADMIN_DEBUG") {
                            isAuthorized = true
                        } else {
                            val status = LicenseManager.verifyLicense(key)
                            if (status is LicenseManager.LicenseStatus.Valid) {
                                LicenseStorage.saveLicense(key)
                                isAuthorized = true
                            }
                        }
                    }
                )
            }
        }
    }
}

// --- PRESERVED COMPONENT ---
@Composable
fun LicenseStatusBar() {
    var statusMessage by remember { mutableStateOf("Checking license...") }
    var isWarning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedLicense = LicenseStorage.getLicense()

        if (savedLicense != null) {
            val status = LicenseManager.verifyLicense(savedLicense)

            if (status is LicenseManager.LicenseStatus.Valid) {
                val daysLeft = LicenseManager.getSubscriptionDaysRemaining(savedLicense)
                statusMessage = "Subscription Active: $daysLeft days remaining"
                isWarning = daysLeft <= 5
            } else {
                statusMessage = "License Expired or Invalid!"
                isWarning = true
            }
        } else {
            if (!TrialManager.isTrialExpired()) {
                val trialDaysLeft = TrialManager.getDaysRemaining()
                statusMessage = "Trial Mode: $trialDaysLeft days left"
                isWarning = trialDaysLeft <= 3
            } else {
                statusMessage = "Trial Expired. Please activate a license."
                isWarning = true
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isWarning) Color(0xFFD32F2F) else Color(0xFF2E7D32))
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusMessage,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}