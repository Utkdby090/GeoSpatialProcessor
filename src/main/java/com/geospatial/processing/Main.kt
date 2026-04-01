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

   // LicenseStorage.clearLicense() // TEMPORARY: Wipes the saved key on boot

    // 1. EVALUATE SECURITY STATE ON STARTUP
    val initialAuthState = remember {
        val savedLicense = LicenseStorage.getLicense()

        // Priority 1: Check for a valid permanent/subscription key
        if (savedLicense != null) {
            val status = LicenseManager.verifyLicense(savedLicense)
            if (status is LicenseManager.LicenseStatus.Valid) {
                return@remember true // App is fully licensed
            } else {
                LicenseStorage.clearLicense() // Clean up invalid keys
            }
        }

        // Priority 2: Check if the 15-day offline trial is still valid
        !TrialManager.isTrialExpired()
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

                    // --- TRIAL WATERMARK (Only shows if no permanent license is found) ---
                    val days = TrialManager.getDaysRemaining()
                    if (LicenseStorage.getLicense() == null && days > 0) {
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
    } else {
        // --- THE LOCK SCREEN (Shows when Trial Expires OR No License Found) ---
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = "Trial Expired - Activation Required",
            state = rememberWindowState(width = 600.dp, height = 550.dp),
            icon = painterResource("geoSpatialProcessor.png")
        ) {
            GeospatialEnterpriseTheme {
                LockScreen(onKeyEntered = { key ->
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