package com.geospatial.processing.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.geospatial.processing.auth.HardwareUtil
import com.geospatial.processing.auth.LicenseManager
import com.geospatial.processing.auth.LicenseStorage

@Composable
fun LicenseScreen(onLicenseValid: () -> Unit) {
    var licenseInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Get the Machine ID to show the user
    val machineId = remember { HardwareUtil.getMachineId() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Software Locked", style = MaterialTheme.typography.h4)
        Spacer(modifier = Modifier.height(16.dp))

        Text("To unlock this software, please provide your Machine ID to the administrator to receive a 10-day license key.")
        Spacer(modifier = Modifier.height(24.dp))

        // We use SelectionContainer so the user can easily highlight and copy their ID
        SelectionContainer {
            Text(
                text = "Your Machine ID: $machineId",
                style = MaterialTheme.typography.h6,
                color = MaterialTheme.colors.primary
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = licenseInput,
            onValueChange = { licenseInput = it },
            label = { Text("Paste License Key Here") },
            modifier = Modifier.fillMaxWidth(0.8f),
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            // Verify the pasted key
            val status = LicenseManager.verifyLicense(licenseInput)
            when (status) {
                is LicenseManager.LicenseStatus.Valid -> {
                    LicenseStorage.saveLicense(licenseInput) // Save it for next time!
                    errorMessage = null
                    onLicenseValid() // Trigger the callback to launch the main app
                }
                is LicenseManager.LicenseStatus.Expired -> errorMessage = "This license has expired."
                is LicenseManager.LicenseStatus.InvalidMachine -> errorMessage = "This license is registered to a different machine."
                is LicenseManager.LicenseStatus.TamperedOrInvalid -> errorMessage = "Invalid license key."
            }
        }) {
            Text("Unlock Application")
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage!!, color = Color.Red)
        }
    }
}