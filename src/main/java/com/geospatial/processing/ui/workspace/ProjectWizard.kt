package com.geospatial.processing.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geospatial.processing.core.plugin.DomainPlugin

@Composable
fun NewProjectWizard(
    availablePlugins: List<DomainPlugin>,
    onProjectCreated: (selectedPluginId: String, projectName: String) -> Unit,
    onCancel: () -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var selectedPlugin by remember { mutableStateOf<DomainPlugin?>(availablePlugins.firstOrNull()) }

    Card(
        modifier = Modifier.width(600.dp).padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 24.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text("Create New Workspace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("Select a domain module to configure the environment.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontSize = 14.sp)
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("Project Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text("Installed Modules", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))

            // DYNAMIC PLUGIN LIST
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                availablePlugins.forEach { plugin ->
                    val isSelected = selectedPlugin?.pluginId == plugin.pluginId
                    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(bgColor)
                            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                            .clickable { selectedPlugin = plugin }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Placeholder for the plugin's icon
                        Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(plugin.displayName, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(plugin.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    enabled = projectName.isNotBlank() && selectedPlugin != null,
                    onClick = { onProjectCreated(selectedPlugin!!.pluginId, projectName) }
                ) {
                    Text("Initialize Workspace", color = Color.White)
                }
            }
        }
    }
}