package com.geospatial.processing.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

private val EnterpriseLightColors = lightColors(
    primary = NavyPrimary,
    primaryVariant = AzureAccent,
    secondary = AzureAccent,
    background = BackgroundSlate,
    surface = SurfaceWhite,
    onPrimary = SurfaceWhite,
    onSecondary = SurfaceWhite,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun GeospatialEnterpriseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = EnterpriseLightColors,
        // We can add custom Typography and Shapes here later!
        content = content
    )
}