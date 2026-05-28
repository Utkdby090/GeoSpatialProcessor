package com.geospatial.processing.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object HardwareUtil {

    /**
     * Gets a unique hardware identifier for the current machine.
     * On Windows, it attempts PowerShell first (modern), then falls back to wmic.
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
        // 1. Try modern PowerShell first (wmic is deprecated in Windows 11+)
        try {
            val process = ProcessBuilder("powershell.exe", "-Command", "(Get-CimInstance -Class Win32_ComputerSystemProduct).UUID").start()
            val id = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()

            // Validate that we actually got an ID and not a PowerShell error message
            if (id.isNotBlank() && !id.contains("Exception") && !id.contains("Error")) {
                // Strip all invisible whitespace/newlines to ensure cryptographic safety
                return id.replace("\\s+".toRegex(), "").uppercase()
            }
        } catch (e: Exception) {
            // Silently swallow and fall through to the wmic fallback
        }

        // 2. Fallback to wmic for older/restricted Windows environments
        try {
            val process = Runtime.getRuntime().exec("wmic csproduct get uuid")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            // Skip the header line ("UUID") and grab the actual ID
            reader.readLine()
            var uuid = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    uuid = line.trim()
                    break
                }
            }
            if (uuid.isNotBlank()) {
                return uuid.replace("\\s+".toRegex(), "").uppercase()
            }
        } catch (e: Exception) {
            // Both methods failed
        }

        return "WINDOWS-UUID-NOT-FOUND"
    }
}