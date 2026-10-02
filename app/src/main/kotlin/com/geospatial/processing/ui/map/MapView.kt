package com.geospatial.processing.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.geospatial.processing.ui.CustomTitleBar
import com.geospatial.processing.ui.theme.GeospatialEnterpriseTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
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
private const val MAX_CACHED_TILES = 400
private const val MAX_FALLBACK_LEVELS = 4
private const val ZOOM_MILLIS = 220
private const val ZOOM_IN_START = 0.8f
private const val ZOOM_OUT_START = 1.25f

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
    val loading = remember { HashSet<TileKey>() }
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
        // Keep memory bounded: when many tiles pile up, drop the ones not on screen (they reload from the disk cache).
        if (tiles.size > MAX_CACHED_TILES) {
            val keep = visible.map { it.key }.toSet()
            tiles.keys.filter { it !in keep }.forEach { tiles.remove(it) }
        }
        visible.map { it.key }.distinct().filter { it !in tiles && failed[it] != true && loading.add(it) }.forEach { key ->
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { tileLoader.load(key) }
                // Decoding is off the UI thread too, so panning never stutters on it.
                val image = withContext(Dispatchers.Default) {
                    bytes?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
                }
                loading.remove(key)
                if (image != null) tiles[key] = image else failed[key] = true
            }
        }
    }

    val selected = positioned.firstOrNull { it.id == selectedId }

    // A zoom step eases in: the new level starts slightly scaled and settles to 1, around the zoom anchor.
    val zoomScale = remember { Animatable(1f) }
    var zoomPivot by remember { mutableStateOf(TransformOrigin.Center) }
    fun zoomTo(level: Int, anchor: ScreenPoint) {
        val next = viewport.withZoom(level, anchor)
        if (next.zoom == viewport.zoom) return
        zoomPivot = TransformOrigin((anchor.x / viewport.width).toFloat(), (anchor.y / viewport.height).toFloat())
        val zoomingIn = next.zoom > viewport.zoom
        viewport = next
        scope.launch {
            zoomScale.snapTo(if (zoomingIn) ZOOM_IN_START else ZOOM_OUT_START)
            zoomScale.animateTo(1f, tween(ZOOM_MILLIS, easing = FastOutSlowInEasing))
        }
    }
    fun zoomBy(step: Int, anchor: ScreenPoint = ScreenPoint(viewport.width / 2, viewport.height / 2)) = zoomTo(viewport.zoom + step, anchor)

    Box(modifier.background(Color(0xFFE5E7EB))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = zoomScale.value; scaleY = zoomScale.value; transformOrigin = zoomPivot }
                .onSizeChanged { size ->
                    viewport = viewport.withSize(size.width.toDouble(), size.height.toDouble())
                    sized = size.width > 0 && size.height > 0
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.first()
                    val dy = change.scrollDelta.y
                    if (dy != 0f) {
                        val at = ScreenPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        zoomBy(if (dy < 0) 1 else -1, at)
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
                val dst = IntOffset(placed.left.roundToInt(), placed.top.roundToInt())
                // 257 px: a one-pixel overlap hides hairline seams between rounded tile edges.
                val dstSize = IntSize(257, 257)
                val exact = tiles[placed.key]
                if (exact != null) {
                    drawImage(exact, dstOffset = dst, dstSize = dstSize)
                } else {
                    // Not loaded yet: show the blurry enlarged part of a coarser tile we already have.
                    for (up in 1..MAX_FALLBACK_LEVELS) {
                        val level = placed.key.zoom - up
                        if (level < 0) break
                        val parent = tiles[TileKey(level, placed.key.x shr up, placed.key.y shr up)] ?: continue
                        val part = 256 shr up
                        val mask = (1 shl up) - 1
                        drawImage(
                            parent,
                            srcOffset = IntOffset((placed.key.x and mask) * part, (placed.key.y and mask) * part),
                            srcSize = IntSize(part, part),
                            dstOffset = dst,
                            dstSize = dstSize,
                        )
                        break
                    }
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
            FilledTonalIconButton(onClick = { zoomBy(1) }) { Text("+") }
            FilledTonalIconButton(onClick = { zoomBy(-1) }) { Text("−") }
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

/**
 * The map in its own native window with the same minimise / maximise / close title bar as the main window.
 * Selecting a pin selects that tower in the workbench behind it.
 */
@Composable
fun MapWindow(
    assets: List<Asset>,
    selectedId: String?,
    plugin: DomainPlugin,
    tileLoader: TileLoader,
    isDarkTheme: Boolean,
    onSelect: (Asset) -> Unit,
    onDismiss: () -> Unit,
) {
    val windowState = rememberWindowState(width = 1100.dp, height = 750.dp, position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = onDismiss,
        state = windowState,
        undecorated = true,
        title = "Tower Map",
        icon = com.geospatial.processing.ui.components.rememberAppIcon(),
    ) {
        GeospatialEnterpriseTheme(darkTheme = isDarkTheme) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                CustomTitleBar(windowState = windowState, onCloseApp = onDismiss, appName = "Tower Map")
                MapView(assets, selectedId, plugin, tileLoader, onSelect, Modifier.fillMaxSize())
            }
        }
    }
}
