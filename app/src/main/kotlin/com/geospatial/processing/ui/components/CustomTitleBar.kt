package com.geospatial.processing.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

@Composable
fun FrameWindowScope.CustomTitleBar(
    windowState: WindowState,
    onCloseApp: () -> Unit
) {
    // We wrap the whole bar in a WindowDraggableArea so the user can drag the app around
    WindowDraggableArea {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Color(0xFF1E1F22)), // Dark IDE color
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // App Icon and Title
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                Text("GeoFlux", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // Window Controls (Minimize, Maximize, Close)
            Row {
                // MINIMIZE (Drawn natively as a simple horizontal line)
                Box(
                    modifier = Modifier.size(46.dp).clickable { windowState.isMinimized = true },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.width(12.dp).height(1.5.dp).background(Color.LightGray))
                }

                // MAXIMIZE / RESTORE (Drawn natively as a hollow square)
                Box(
                    modifier = Modifier.size(46.dp).clickable {
                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(11.dp).border(1.5.dp, Color.LightGray))
                }

                // CLOSE (Uses the core Close icon which is guaranteed to exist)
                Box(
                    modifier = Modifier.size(46.dp).clickable { onCloseApp() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}