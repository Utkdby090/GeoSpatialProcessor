package com.geospatial.processing

import androidx.compose.runtime.*
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
import com.geospatial.processing.ui.LicenseScreen
import kotlin.system.exitProcess

fun main() = application {

    // 1. Check the license instantly on startup before drawing anything
    val initialAuthState = remember {
        var isAuthorized = false
        val savedLicense = LicenseStorage.getLicense()

        if (savedLicense != null) {
            val status = LicenseManager.verifyLicense(savedLicense)
            if (status is LicenseManager.LicenseStatus.Valid) {
                isAuthorized = true
            } else {
                // Wipe the dead key if expired or invalid
                LicenseStorage.clearLicense()
            }
        }
        isAuthorized
    }

    // 2. State to track if the user has unlocked the app
    var isAuthorized by remember { mutableStateOf(initialAuthState) }

    // 3. The Router: Because this evaluates instantly, Compose always sees a Window!
    if (isAuthorized) {
        // Initialize the database ONLY if they have a valid license
        val repository = remember {
            DatabaseConfig.init()
            GeoRepository()
        }

        // --- THE MAIN APPLICATION ---
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
                MainScreen(repository)
            }
        }
    } else {
        // --- THE LOCK SCREEN ---
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            },
            title = "Software License Required",
            state = rememberWindowState(width = 600.dp, height = 500.dp),
            icon = painterResource("geoSpatialProcessor.png")
        ) {
            GeospatialEnterpriseTheme {
                LicenseScreen(
                    onLicenseValid = {
                        isAuthorized = true
                    }
                )
            }
        }
    }
}