package com.geospatial.processing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.geospatial.processing.config.DatabaseConfig
import com.geospatial.processing.data.repository.GeoRepository
import com.geospatial.processing.ui.MainScreen
import com.geospatial.processing.ui.theme.GeospatialEnterpriseTheme
import com.geospatial.processing.auth.LicenseManager
import com.geospatial.processing.auth.LicenseStorage
import com.geospatial.processing.ui.LockScreen
import com.geospatial.processing.utils.TrialManager
import kotlin.system.exitProcess

fun main() = application {



    // State to control what the Lock Screen window title says
    var lockoutReason by remember { mutableStateOf("Activation Required") }

    // 1. EVALUATE SECURITY STATE ON STARTUP
    val initialAuthState = remember {
        val savedLicense = LicenseStorage.getLicense()

        // Priority 1: Check for a valid permanent/subscription key
        if (savedLicense != null) {
            val status = LicenseManager.verifyLicense(savedLicense)
            when (status) {
                is LicenseManager.LicenseStatus.Valid -> {
                    return@remember true // App is fully licensed and within date
                }
                is LicenseManager.LicenseStatus.Expired -> {
                    LicenseStorage.clearLicense() // Clean up expired keys
                    lockoutReason = "Subscription Expired - Renewal Required"
                    return@remember false
                }
                else -> {
                    LicenseStorage.clearLicense() // Clean up tampered/invalid keys
                    lockoutReason = "Invalid License - Activation Required"
                    return@remember false
                }
            }
        }

        // Priority 2: Check if the 15-day offline trial is still valid
        val isTrialActive = !TrialManager.isTrialExpired()
       // val isTrialActive = false
        if (!isTrialActive) {
            lockoutReason = "Trial Expired - Activation Required"
        }
        isTrialActive
    }

    var isAuthorized by remember { mutableStateOf(initialAuthState) }

    // --- THE ROUTER ---
    if (isAuthorized) {
        val repository = remember {
            DatabaseConfig.init()
            GeoRepository()
        }

        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = "GeoSpatial Data Processor",
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
            icon = painterResource("geoSpatialProcessor.png")
        ) {
            GeospatialEnterpriseTheme {
                Box {
                    MainScreen(repository)

                    // --- DYNAMIC WATERMARK (Trial vs Subscription) ---
                    val savedLicense = LicenseStorage.getLicense()
                    if (savedLicense != null) {
                        // It's a subscription. Calculate days remaining.
                        val subDays = LicenseManager.getSubscriptionDaysRemaining(savedLicense)

                        // Only show the badge if they have 7 days or less left to warn them
                        if (subDays <= 7) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                                Text(
                                    "Subscription: $subDays days remaining",
                                    modifier = Modifier
                                        .padding(16.dp)
                                        // Turn the box red when they drop to 3 days or less
                                        .background(if (subDays <= 3) Color.Red.copy(0.8f) else Color.Black.copy(0.5f))
                                        .padding(4.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.overline
                                )
                            }
                        }
                    } else {
                        // It's the trial. Show the trial countdown.
                        val days = TrialManager.getDaysRemaining()
                        if (days > 0) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                                Text(
                                    "Trial Mode: $days days remaining",
                                    modifier = Modifier.padding(16.dp).background(Color.Black.copy(0.5f)).padding(4.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.overline
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        // --- THE LOCK SCREEN (Shows when Trial/Subscription Expired OR No License Found) ---
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = lockoutReason, // Uses the dynamic reason text calculated at startup
            state = rememberWindowState(width = 600.dp, height = 550.dp),
            icon = painterResource("geoSpatialProcessor.png")
        ) {
            GeospatialEnterpriseTheme {
                LockScreen(showExpiredMessage = lockoutReason.contains("Subscription"),onKeyEntered = { key ->
                    // 1. Check for the debug bypass first
                    if (key == "SECRET_ADMIN_DEBUG") {
                        isAuthorized = true
                    } else {
                        // 2. Otherwise, attempt RSA validation
                        val status = LicenseManager.verifyLicense(key)
                        if (status is LicenseManager.LicenseStatus.Valid) {
                            LicenseStorage.saveLicense(key)
                            isAuthorized = true
                        }
                    }
                })
            }
        }
    }
}