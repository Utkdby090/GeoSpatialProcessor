package com.geospatial.processing.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Material 3 reads many more colour roles than Material 2 did (containers, outlines, surface tint).
// Every role is set explicitly so no M3 baseline purple or tonal tint leaks into the UI.

private val LightColors = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = Color.White,
    primaryContainer = AzureAccentVariant,
    onPrimaryContainer = Color.White,
    secondary = AzureAccent,
    onSecondary = Color.White,
    secondaryContainer = AzureAccent.copy(alpha = 0.15f),
    onSecondaryContainer = TextPrimary,
    tertiary = AzureAccentVariant,
    onTertiary = Color.White,
    background = BackgroundSlate,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = Color(0xFF334155),
    surfaceVariant = BackgroundSlate,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Color.Transparent,
    outline = Color(0xFFCBD5E1),
    outlineVariant = BorderLight,
    error = StatusError,
    onError = Color.White,
).withFlatSurfaces()

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1E293B),
    onPrimary = Color.White,
    primaryContainer = AzureAccentVariant,
    onPrimaryContainer = Color.White,
    secondary = AzureAccent,
    onSecondary = Color.White,
    secondaryContainer = AzureAccent.copy(alpha = 0.25f),
    onSecondaryContainer = Color(0xFFF1F5F9),
    tertiary = AzureAccentVariant,
    onTertiary = Color.White,
    background = NavyPrimary,
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF273449),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color.White,
).withFlatSurfaces()

/** M3 Cards, menus and dialogs default to the surfaceContainer* roles; keep them on the plain surface like M2. */
private fun ColorScheme.withFlatSurfaces() = copy(
    surfaceContainerLowest = surface,
    surfaceContainerLow = surface,
    surfaceContainer = surface,
    surfaceContainerHigh = surface,
    surfaceContainerHighest = surface,
    surfaceBright = surface,
    surfaceDim = surface,
)

@Composable
fun GeospatialEnterpriseTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

// Helper for dynamic borders based on theme
@Composable
fun getBorderColor() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
