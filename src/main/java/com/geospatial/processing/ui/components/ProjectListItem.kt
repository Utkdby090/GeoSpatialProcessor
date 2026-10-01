package com.geospatial.processing.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.onClick
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectListItem(
    projectName: String,
    projectPath: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // Tracks whether the right-click menu is currently open
    var isContextMenuVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp)
            // 1. Detect Left Click (Primary) -> Opens the project normally
            .onClick(
                matcher = PointerMatcher.mouse(PointerButton.Primary),
                onClick = { onOpen() }
            )
            // 2. Detect Right Click (Secondary) -> Opens the Context Menu
            .onClick(
                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                onClick = { isContextMenuVisible = true }
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2E)), // Match your dark theme
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Your existing UI for the project icon, name, and path goes here
            Column(modifier = Modifier.weight(1f)) {
                Text(text = projectName, color = Color.White)
                Text(text = projectPath, color = Color.Gray)
            }

            // The Desktop-native context menu
            CursorDropdownMenu(
                expanded = isContextMenuVisible,
                onDismissRequest = { isContextMenuVisible = false }
            ) {
                DropdownMenuItem(onClick = {
                    isContextMenuVisible = false
                    onOpen()
                }, leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = "Open") }, text = { Text("Open Project") })

                DropdownMenuItem(onClick = {
                    isContextMenuVisible = false
                    onEdit()
                }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Edit") }, text = { Text("Modify Details") })

                // Optional: Add a visual separator
                // HorizontalDivider()

                DropdownMenuItem(onClick = {
                    isContextMenuVisible = false
                    onDelete()
                }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red) }, text = { Text("Delete Project", color = Color.Red) })
            }
        }
    }
}