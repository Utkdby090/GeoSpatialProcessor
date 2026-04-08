package com.geospatial.processing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TowerSequenceNavigator(
    previousItem: String?,
    currentItem: String,
    nextItem: String?,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .width(100.dp)
            .background(Color(0xFFECF0F1), shape = RoundedCornerShape(8.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp)
    ) {
        // --- PREVIOUS ITEM (Text first, then Arrow pointing to Current) ---
        if (previousItem != null) {
            Text(text = previousItem, color = Color.Gray, fontSize = 12.sp)
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous", tint = Color.Gray)
        } else {
            Spacer(modifier = Modifier.height(36.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- CURRENT ITEM ---
        Text(
            text = currentItem,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2C3E50),
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- NEXT ITEM (Arrow pointing to Next, then Text) ---
        if (nextItem != null) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next", tint = Color.Gray)
            Text(text = nextItem, color = Color.Gray, fontSize = 12.sp)
        } else {
            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}