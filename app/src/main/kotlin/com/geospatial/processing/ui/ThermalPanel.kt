package com.geospatial.processing.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.geospatial.processing.domain.thermal.Pixel
import com.geospatial.processing.domain.thermal.Region
import com.geospatial.processing.domain.thermal.ThermalDecodeException
import com.geospatial.processing.domain.thermal.ThermalEngine
import com.geospatial.processing.domain.thermal.ThermalFrame
import com.geospatial.processing.domain.thermal.ThermalOverrides
import com.geospatial.processing.domain.thermal.ThermalPalette
import com.geospatial.processing.domain.thermal.ThermalStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.util.Locale

private sealed interface ThermalOutcome {
    /** Nothing to show: no image, or a plain photo without temperature data. */
    data object None : ThermalOutcome
    data class Decoded(val frame: ThermalFrame) : ThermalOutcome
    /** A radiometric image we recognise but cannot read (e.g. DJI without its SDK). */
    data class Unreadable(val reason: String) : ThermalOutcome
}

/**
 * Temperature readout for a thermal image: colour palette, hover temperature, hotspot, and min/max/mean over the
 * whole image or a rectangle dragged with the mouse. Shows nothing for ordinary photos.
 *
 * @param loadKey changes whenever [loadBytes] would return a different file
 * @param loadBytes the ORIGINAL file bytes (the display copy has no temperature data); runs off the UI thread
 * @param overrides scene settings from the form, which refine the temperatures
 * @param onApply receives the stats of the current selection when the user asks to copy them into the form
 */
@Composable
fun ThermalPanel(
    loadKey: Any?,
    loadBytes: () -> ByteArray?,
    overrides: ThermalOverrides,
    engine: ThermalEngine = remember { ThermalEngine() },
    onApply: (ThermalStats) -> Unit,
) {
    val bytes by produceState<ByteArray?>(null, loadKey) {
        value = withContext(Dispatchers.IO) { runCatching(loadBytes).getOrNull() }
    }
    val outcome by produceState<ThermalOutcome>(ThermalOutcome.None, bytes, overrides) {
        val data = bytes
        value = if (data == null) ThermalOutcome.None else withContext(Dispatchers.Default) {
            try {
                ThermalOutcome.Decoded(engine.decode(data, overrides))
            } catch (e: ThermalDecodeException) {
                if (engine.decoderFor(data) == null) ThermalOutcome.None else ThermalOutcome.Unreadable(e.message ?: "Unreadable thermal image.")
            }
        }
    }

    when (val o = outcome) {
        ThermalOutcome.None -> Unit
        is ThermalOutcome.Unreadable -> {
            SectionHeader("Thermal Analysis")
            Text(o.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(24.dp))
        }
        is ThermalOutcome.Decoded -> {
            SectionHeader("Thermal Analysis")
            ThermalFrameView(o.frame, onApply)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThermalFrameView(frame: ThermalFrame, onApply: (ThermalStats) -> Unit) {
    var palette by remember { mutableStateOf(ThermalPalette.IRON) }
    var hover by remember(frame) { mutableStateOf<Pixel?>(null) }
    var region by remember(frame) { mutableStateOf<Region?>(null) }
    var dragStart by remember(frame) { mutableStateOf<Pixel?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val image = remember(frame, palette) {
        val pixels = palette.render(frame)
        BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, frame.width, frame.height, pixels, 0, frame.width)
        }.toComposeImageBitmap()
    }
    val whole = remember(frame) { frame.stats() }
    val selected = remember(frame, region) { frame.stats(region) }

    fun pixelAt(p: Offset): Pixel = Pixel(
        (p.x / size.width.coerceAtLeast(1) * frame.width).toInt().coerceIn(0, frame.width - 1),
        (p.y / size.height.coerceAtLeast(1) * frame.height).toInt().coerceIn(0, frame.height - 1),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThermalPalette.entries.forEach { p ->
            FilterChip(selected = palette == p, onClick = { palette = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
    Spacer(Modifier.height(8.dp))

    val hotspot = whole?.maxAt
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .aspectRatio(frame.width.toFloat() / frame.height)
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged { size = it }
            .pointerInput(frame) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        hover = if (event.type == PointerEventType.Exit) null else pixelAt(event.changes.first().position)
                    }
                }
            }
            .pointerInput(frame) { detectTapGestures(onTap = { region = null }) }
            .pointerInput(frame) {
                detectDragGestures(
                    onDragStart = { dragStart = pixelAt(it) },
                    onDrag = { change, _ -> dragStart?.let { s -> pixelAt(change.position).let { e -> region = Region(s.x, s.y, e.x, e.y) } } },
                    onDragEnd = { dragStart = null },
                    onDragCancel = { dragStart = null },
                )
            }
            .drawWithContent {
                drawContent()
                val sx = this.size.width / frame.width
                val sy = this.size.height / frame.height
                hotspot?.let { drawCircle(Color.White, radius = 9f, center = Offset((it.x + 0.5f) * sx, (it.y + 0.5f) * sy), style = Stroke(3f)) }
                region?.let {
                    val x0 = minOf(it.x0, it.x1) * sx
                    val y0 = minOf(it.y0, it.y1) * sy
                    drawRect(outline, Offset(x0, y0), Size((kotlin.math.abs(it.x1 - it.x0) + 1) * sx, (kotlin.math.abs(it.y1 - it.y0) + 1) * sy), style = Stroke(3f))
                }
            },
    ) {
        Image(bitmap = image, contentDescription = "Thermal image", contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
    }

    Spacer(Modifier.height(8.dp))
    val cursor = hover?.let { frame.temperatureAt(it.x, it.y) }
    Text(
        text = "Cursor: " + (cursor?.let { fmt(it) } ?: "–"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = (if (region == null) "Whole image" else "Selection") + ": " + (selected?.let {
            "min ${fmt(it.min)}   max ${fmt(it.max)}   mean ${fmt(it.mean)}"
        } ?: "no temperature readings"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
    Text(
        text = "Drag on the image to measure an area, click to clear it. Emissivity ${"%.2f".format(Locale.ROOT, frame.params.emissivity)}, " +
            "distance ${"%.0f".format(Locale.ROOT, frame.params.distanceM)} m.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { selected?.let(onApply) },
        enabled = selected != null,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
    ) {
        Text(if (region == null) "Fill fault fields from hottest point" else "Fill fault fields from selection")
    }
}

private fun fmt(t: Float) = "%.1f °C".format(Locale.ROOT, t)
