package com.geospatial.processing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geospatial.processing.utils.HardwareUtil

@Composable
fun LockScreen(onKeyEntered: (String) -> Unit) {
    var keyInput by remember { mutableStateOf("") }
    val machineId = remember { HardwareUtil.getMachineId() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(500.dp).padding(16.dp),
            elevation = 8.dp,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Trial Expired", style = MaterialTheme.typography.h4, color = Color.Red, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Your 15-day evaluation period has ended. Please contact support to receive an activation key.",
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Display Machine ID for the user to send to you
                Text("Your Machine ID:", style = MaterialTheme.typography.caption)
                Text(
                    machineId,
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color.LightGray.copy(0.3f)).padding(8.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("Enter Activation Key") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { onKeyEntered(keyInput) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2ECC71))
                ) {
                    Text("ACTIVATE APP", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}