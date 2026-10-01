package com.geospatial.processing.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

private const val APP_ICON_RESOURCE = "geoSpatialProcessor.png"

/**
 * Window icon loaded from the classpath (src/main/resources).
 * Replaces the deprecated `painterResource(path)` without adopting the Compose resources library.
 */
@Composable
fun rememberAppIcon(): Painter = remember {
    val bytes = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(APP_ICON_RESOURCE)) {
        "Missing resource $APP_ICON_RESOURCE"
    }.use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
