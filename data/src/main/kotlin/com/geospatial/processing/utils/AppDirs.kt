package com.geospatial.processing.utils

import java.io.File

/** Per-user application data folder: %LOCALAPPDATA%\GeoFlux on Windows, ~/.geoflux elsewhere. */
object AppDirs {
    val dataDir: File by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
        val dir = if (!localAppData.isNullOrBlank()) File(localAppData, "GeoFlux")
                  else File(System.getProperty("user.home"), ".geoflux")
        dir.apply { mkdirs() }
    }
}
