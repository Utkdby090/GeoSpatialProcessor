package com.geospatial.processing.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object HardwareUtil {

    /**
     * Gets a unique hardware identifier for the current machine.
     * On Windows, it retrieves the BIOS UUID.
     */
    fun getMachineId(): String {
        return try {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("win") -> getWindowsUUID()
                else -> "NON-WINDOWS-ID-GENERIC" // Fallback for dev testing
            }
        } catch (e: Exception) {
            "UNKNOWN-HARDWARE-ID"
        }
    }

    private fun getWindowsUUID(): String {
        val process = Runtime.getRuntime().exec("wmic csproduct get uuid")
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        // Skip the header line and grab the actual ID
        reader.readLine()
        var uuid = ""
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) {
                uuid = line.trim()
                break
            }
        }
        return uuid.ifBlank { "WINDOWS-UUID-NOT-FOUND" }
    }
}