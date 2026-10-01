package com.geospatial.processing.ui.theme

import androidx.compose.ui.graphics.Color

// --- Core Palette ---
val NavyPrimary = Color(0xFF0F172A)      // Deep slate/navy for sidebars & headers
val AzureAccent = Color(0xFF0EA5E9)      // Bright accent for selection & primary actions
val AzureAccentVariant = Color(0xFF0284C7) // Deeper accent (former primaryVariant)
val BackgroundSlate = Color(0xFFF8FAFC)  // Off-white for the main app background
val SurfaceWhite = Color(0xFFFFFFFF)     // Pure white for data cards

// --- Text Colors ---
val TextPrimary = Color(0xFF1E293B)      // Almost black for high readability
val TextSecondary = Color(0xFF64748B)    // Muted grey for metadata and timestamps
val BorderLight = Color(0xFFE2E8F0)      // Very subtle borders

// --- Status Indicators (Traffic Lights) ---
val StatusReady = Color(0xFF10B981)      // Emerald Green
val StatusDraft = Color(0xFFF59E0B)      // Amber/Orange
val StatusError = Color(0xFFEF4444)      // Red (for missing critical data)