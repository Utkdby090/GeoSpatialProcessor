package com.geospatial.processing.ui



import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


val AzureAccent = Color(0xFF0EA5E9)
val AzureAccentVariant = Color(0xFF0284C7)

// --- LIGHT THEME COLORS ---
private val LightColorPalette = lightColors(
    primary = Color(0xFF0F172A),          // Slate 900 (Dark Navy)
    primaryVariant = AzureAccentVariant,
    secondary = AzureAccent,
    background = Color(0xFFF8FAFC),       // Very light gray/blue
    surface = Color(0xFFFFFFFF),          // Pure white panels
    onPrimary = Color.White,
    onBackground = Color(0xFF1E293B),     // Dark text
    onSurface = Color(0xFF334155),
    error = Color(0xFFEF4444)
)

// --- DARK THEME COLORS ---
private val DarkColorPalette = darkColors(
    primary = Color(0xFF1E293B),          // Slate 800 (Lighter Navy for headers)
    primaryVariant = AzureAccentVariant,
    secondary = AzureAccent,
    background = Color(0xFF0F172A),       // Deep Slate background
    surface = Color(0xFF1E293B),          // Slightly lighter panels
    onPrimary = Color.White,
    onBackground = Color(0xFFF1F5F9),     // Light text
    onSurface = Color(0xFFE2E8F0),
    error = Color(0xFFF87171)
)

// --- THEME WRAPPER ---
@Composable
fun AppTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorPalette else LightColorPalette

    MaterialTheme(
        colors = colors,
        // You can also override typography and shapes here later
        content = content
    )
}

// Helper for dynamic borders based on theme
@Composable
fun getBorderColor() = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)