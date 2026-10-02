package com.geospatial.processing.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geospatial.processing.domain.model.Severity

/** One colour per severity, shared by the list, the form and the map. NONE is a neutral grey. */
fun severityColor(severity: Severity): Color = when (severity) {
    Severity.NONE -> Color(0xFF9CA3AF)
    Severity.LOW -> Color(0xFFCA8A04)
    Severity.MEDIUM -> Color(0xFFEA580C)
    Severity.HIGH -> Color(0xFFDC2626)
    Severity.CRITICAL -> Color(0xFF7F1D1D)
}

/** A small filled pill with the severity name. Draws nothing for [Severity.NONE] unless [showNone] is set. */
@Composable
fun SeverityBadge(severity: Severity, modifier: Modifier = Modifier, showNone: Boolean = false) {
    if (severity == Severity.NONE && !showNone) return
    Text(
        text = severity.label.uppercase(),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(severityColor(severity))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
