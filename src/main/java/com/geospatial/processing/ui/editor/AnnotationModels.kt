package com.geospatial.processing.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class DrawTool {
    PAN,
    PEN,
    ARROW,
    RECTANGLE,
    CIRCLE
}

data class DrawnShape(
    val tool: DrawTool,
    val points: List<Offset>,
    val color: Color = Color.Red,
    val strokeWidth: Float = 4f
)