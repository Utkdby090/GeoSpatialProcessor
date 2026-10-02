package com.geospatial.processing.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.map.GeoPoint
import com.geospatial.processing.domain.map.MapViewport
import com.geospatial.processing.domain.map.ScreenPoint
import com.geospatial.processing.domain.map.TileKey
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.ui.components.severityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

private const val PIN_RADIUS_DP = 8f
private const val HIT_RADIUS_DP = 14.0

/** Assets still at 0,0 have no position yet (the CSV left it blank and no image GPS filled it in). */
private fun Asset.hasPosition() = !(latitude == 0.0 && longitude == 0.0)

/**
 * OpenStreetMap view of the project: one pin per positioned asset, coloured by severity.
 * Drag to pan, wheel to zoom at the cursor, click a pin to select it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MapView(
    assets: List<Asset>,
    selectedId: String?,
    plugin: DomainPlugin,
    tileLoader: TileLoader,
    onSelect: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positioned = remember(assets) { assets.filter { it.hasPosition() } }
    val points = remember(positioned) { positioned.map { GeoPoint(it.latitude, it.longitude) } }

    var viewport by remember { mutableStateOf(MapViewport.fit(emptyList(), 800.0, 600.0)) }
    var sized by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }

    val tiles = remember { mutableStateMapOf<TileKey, ImageBitmap>() }
    val failed = remember { mutableStateMapOf<TileKey, Boolean>() }
    val visible = remember(viewport) { viewport.visibleTiles() }

    // Fit once when the size is known and there is something to show; after that the user is in charge.
    LaunchedEffect(sized, points.isNotEmpty()) {
        if (sized && !fitted && points.isNotEmpty()) {
            viewport = MapViewport.fit(points, viewport.width, viewport.height)
            fitted = true
        }
    }

    val scope = rememberCoroutineScope()
    LaunchedEffect(visible) {
        visible.map { it.key }.distinct().filter { it !in tiles && failed[it] != true }.forEach { key ->
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { tileLoader.load(key) }
                val image = bytes?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
                if (image != null) tiles[key] = image else failed[key] = true
            }
        }
    }

    val selected = positioned.firstOrNull { it.id == selectedId }

    Box(modifier.background(Color(0xFFE5E7EB))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    viewport = viewport.withSize(size.width.toDouble(), size.height.toDouble())
                    sized = size.width > 0 && size.height > 0
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val dy = change.scrollDelta.y
                    if (dy != 0f) {
                        val at = ScreenPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        viewport = viewport.withZoom(viewport.zoom + if (dy < 0) 1 else -1, at)
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        viewport = viewport.panBy(drag.x.toDouble(), drag.y.toDouble())
                    }
                }
                .pointerInput(positioned) {
                    detectTapGestures { pos ->
                        val hit = viewport.nearest(
                            positioned, { GeoPoint(it.latitude, it.longitude) },
                            pos.x.toDouble(), pos.y.toDouble(), HIT_RADIUS_DP * density,
                        )
                        if (hit != null) onSelect(hit)
                    }
                },
        ) {
            visible.forEach { placed ->
                val bitmap = tiles[placed.key]
                if (bitmap != null) {
                    drawImage(
                        bitmap,
                        dstOffset = IntOffset(placed.left.toInt(), placed.top.toInt()),
                        dstSize = IntSize(256, 256),
                    )
                }
            }

            val r = PIN_RADIUS_DP.dp.toPx()
            // Selected pin last so it is never hidden under a neighbour.
            (positioned.filter { it.id != selectedId } + listOfNotNull(selected)).forEach { asset ->
                val s = viewport.toScreen(GeoPoint(asset.latitude, asset.longitude))
                val centre = Offset(s.x.toFloat(), s.y.toFloat())
                val isSelected = asset.id == selectedId
                drawCircle(Color.White, radius = (if (isSelected) r * 1.5f else r) + 2.dp.toPx(), center = centre)
                drawCircle(severityColor(asset.severity), radius = if (isSelected) r * 1.5f else r, center = centre)
                if (isSelected) drawCircle(Color.Black, radius = r * 1.5f + 2.dp.toPx(), center = centre, style = Stroke(1.5.dp.toPx()))
            }
        }

        // Selected tower's name, so the pin can be told apart without leaving the map.
        selected?.let {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
            ) {
                Text(plugin.present(it).sequenceLabel, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
            }
        }

        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = { viewport = viewport.withZoom(viewport.zoom + 1) }) { Text("+") }
            FilledTonalIconButton(onClick = { viewport = viewport.withZoom(viewport.zoom - 1) }) { Text("−") }
            FilledTonalIconButton(
                enabled = points.isNotEmpty(),
                onClick = { viewport = MapViewport.fit(points, viewport.width, viewport.height) },
            ) { Text("⌖") }
        }

        val skipped = assets.size - positioned.size
        Text(
            buildString {
                if (skipped > 0) append("$skipped without position · ")
                append("© OpenStreetMap contributors")
            },
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            color = Color.DarkGray,
        )

        if (points.isEmpty()) {
            Text("No tower has a position yet.", Modifier.align(Alignment.Center), color = Color.DarkGray)
        }
    }
}
