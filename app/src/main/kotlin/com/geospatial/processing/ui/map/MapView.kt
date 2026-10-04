package com.geospatial.processing.ui.map

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.map.PositionIssue
import com.geospatial.processing.domain.map.TileKey
import com.geospatial.processing.domain.map.WebMercator
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.ui.CustomTitleBar
import com.geospatial.processing.ui.components.severityColor
import com.geospatial.processing.ui.theme.GeospatialEnterpriseTheme
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import ovh.plrapps.mapcompose.api.BoundingBox
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.centerOnMarker
import ovh.plrapps.mapcompose.api.centroidX
import ovh.plrapps.mapcompose.api.centroidY
import ovh.plrapps.mapcompose.api.hasMarker
import ovh.plrapps.mapcompose.api.onMarkerClick
import ovh.plrapps.mapcompose.api.removeAllMarkers
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.api.snapScrollTo
import ovh.plrapps.mapcompose.api.updateMarkerZ
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.MapUI
import ovh.plrapps.mapcompose.ui.state.MapState
import kotlin.math.max

private const val LEVEL_COUNT = WebMercator.MAX_ZOOM + 1
private const val FULL_SIZE = WebMercator.TILE_SIZE shl WebMercator.MAX_ZOOM
/** Disk-cache reads and decoding run in parallel; network downloads are limited separately ([OsmTileFetcher]). */
private const val TILE_WORKERS = 8
/** Fitting never zooms closer than this share of the world (about 800 m), so a lone tower still shows its surroundings. */
private const val MIN_FIT_SPAN = 0.00002
private val FIT_PADDING = Offset(0.2f, 0.2f)

/** Assets still at 0,0 have no position yet (the CSV left it blank and no image GPS filled it in). */
private fun Asset.hasPosition() = !(latitude == 0.0 && longitude == 0.0)

private fun Asset.mapX() = WebMercator.normalizedX(longitude)
private fun Asset.mapY() = WebMercator.normalizedY(latitude)

/** The smallest map area holding [assets], grown to at least [MIN_FIT_SPAN] each way; null for none. */
private fun boundsOf(assets: List<Asset>): BoundingBox? {
    if (assets.isEmpty()) return null
    val cx = (assets.minOf { it.mapX() } + assets.maxOf { it.mapX() }) / 2
    val cy = (assets.minOf { it.mapY() } + assets.maxOf { it.mapY() }) / 2
    val halfW = max(MIN_FIT_SPAN, assets.maxOf { it.mapX() } - assets.minOf { it.mapX() }) / 2
    val halfH = max(MIN_FIT_SPAN, assets.maxOf { it.mapY() } - assets.minOf { it.mapY() }) / 2
    return BoundingBox(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/**
 * OpenStreetMap view of the project (MapCompose): one pin per positioned asset, coloured by severity.
 * Drag to pan, wheel or pinch to zoom smoothly, hover a pin for its name, click it to select the tower.
 * Pins whose position looks wrong ([positionIssues]) get a warning ring, and a banner offers to swap back
 * latitude/longitude pairs that were exchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MapView(
    assets: List<Asset>,
    selectedId: String?,
    plugin: DomainPlugin,
    tileLoader: TileLoader,
    positionIssues: List<PositionIssue>,
    onSelect: (Asset) -> Unit,
    onFixSwapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val positioned = remember(assets) { assets.filter { it.hasPosition() } }
    val flagged = remember(positionIssues) { positionIssues.associateBy { it.id } }
    // Frame the towers that look right, so one misplaced pin doesn't zoom the map out to the whole world.
    val trusted = remember(positioned, flagged) { positioned.filter { it.id !in flagged }.ifEmpty { positioned } }

    // On high-density screens use tiles one level coarser, so street names stay readable instead of tiny.
    val magnify = if (LocalDensity.current.density >= 1.75f) 1 else 0
    val state = remember(tileLoader) {
        MapState(LEVEL_COUNT, FULL_SIZE, FULL_SIZE, workerCount = TILE_WORKERS) {
            magnifyingFactor(magnify)
        }.apply {
            // MapCompose owns (and closes) the returned source.
            addLayer(TileStreamProvider { row, col, zoom -> tileLoader.load(TileKey(zoom, col, row))?.let { Buffer().apply { write(it) } } })
        }
    }
    DisposableEffect(state) { onDispose { state.shutdown() } }

    val currentPositioned by rememberUpdatedState(positioned)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(state) {
        state.onMarkerClick { id, _, _ -> currentPositioned.firstOrNull { it.id == id }?.let(currentOnSelect) }
    }

    // Pins read these states, so selection and warnings update without re-adding every marker.
    val selectedState = rememberUpdatedState(selectedId)
    val flaggedState = rememberUpdatedState(flagged)
    LaunchedEffect(state, positioned) {
        state.removeAllMarkers()
        positioned.forEach { asset ->
            state.addMarker(
                asset.id, asset.mapX(), asset.mapY(),
                relativeOffset = Offset(-0.5f, -0.5f),
                zIndex = if (asset.id == selectedState.value) 1f else 0f,
            ) {
                TooltipArea(
                    tooltip = { PinTooltip(plugin.present(asset).listTitle, flaggedState.value[asset.id]) },
                    delayMillis = 300,
                    tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.TopCenter, Alignment.TopCenter, DpOffset(0.dp, (-4).dp)),
                ) {
                    Pin(severityColor(asset.severity), selected = asset.id == selectedState.value, flagged = asset.id in flaggedState.value)
                }
            }
        }
    }

    // Selected pin on top, and brought into view when it is chosen in the list.
    var previousSelected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, selectedId, positioned) {
        previousSelected?.let { state.updateMarkerZ(it, 0f) }
        if (selectedId != null && state.hasMarker(selectedId)) {
            state.updateMarkerZ(selectedId, 1f)
            state.centerOnMarker(selectedId)
        }
        previousSelected = selectedId
    }

    // Fit once there is something to show; after that the user is in charge.
    var fitted by remember { mutableStateOf(false) }
    LaunchedEffect(state, trusted) {
        if (fitted) return@LaunchedEffect
        boundsOf(trusted)?.let { state.snapScrollTo(it, FIT_PADDING); fitted = true }
    }

    val scope = rememberCoroutineScope()
    fun zoomBy(factor: Double) = scope.launch { state.scrollTo(state.centroidX, state.centroidY, state.scale * factor) }

    Box(modifier.clipToBounds().background(Color(0xFFE5E7EB))) {
        MapUI(Modifier.fillMaxSize(), state = state)

        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            positioned.firstOrNull { it.id == selectedId }?.let { SelectedCard(plugin.present(it).sequenceLabel, it) }
            if (positionIssues.isNotEmpty()) IssueBanner(positionIssues, assets, plugin, onFixSwapped)
        }

        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = { zoomBy(2.0) }) { Text("+") }
            FilledTonalIconButton(onClick = { zoomBy(0.5) }) { Text("−") }
            FilledTonalIconButton(
                enabled = trusted.isNotEmpty(),
                onClick = { boundsOf(trusted)?.let { scope.launch { state.scrollTo(it, FIT_PADDING) } } },
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

        if (positioned.isEmpty()) {
            Text("No tower has a position yet.", Modifier.align(Alignment.Center), color = Color.DarkGray)
        }
    }
}

@Composable
private fun Pin(color: Color, selected: Boolean, flagged: Boolean) {
    val size = if (selected) 24.dp else 16.dp
    Box(
        Modifier
            .size(size + 8.dp) // a little extra around the dot makes it easier to hit
            .padding(4.dp)
            .then(if (flagged) Modifier.border(2.dp, Color(0xFFFACC15), CircleShape) else Modifier)
            .padding(if (flagged) 2.dp else 0.dp)
            .background(Color.White, CircleShape)
            .padding(2.dp)
            .background(color, CircleShape)
            .then(if (selected) Modifier.border(1.5.dp, Color.Black, CircleShape) else Modifier),
    )
}

@Composable
private fun PinTooltip(title: String, issue: PositionIssue?) {
    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(title, fontSize = 12.sp)
            issue?.let { Text(issueText(it), fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun issueText(issue: PositionIssue) = when (issue) {
    is PositionIssue.Swapped -> "Latitude and longitude look swapped"
    is PositionIssue.Outlier -> "%,.0f km from the other towers".format(issue.distanceKm)
    is PositionIssue.Invalid -> "Not a valid position"
}

/** The selected tower's name and exact coordinates, so its position can be checked against the survey. */
@Composable
private fun SelectedCard(label: String, asset: Asset) {
    Surface(shape = MaterialTheme.shapes.small, tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, fontSize = 13.sp)
            Text("%.6f, %.6f".format(asset.latitude, asset.longitude), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IssueBanner(issues: List<PositionIssue>, assets: List<Asset>, plugin: DomainPlugin, onFixSwapped: () -> Unit) {
    val byId = remember(assets) { assets.associateBy { it.id } }
    fun names(list: List<PositionIssue>) = list.mapNotNull { byId[it.id]?.let { a -> plugin.present(a).sequenceLabel } }
        .let { n -> n.take(4).joinToString(", ") + if (n.size > 4) " +${n.size - 4}" else "" }
    val swapped = issues.filterIsInstance<PositionIssue.Swapped>()
    val other = issues.filter { it !is PositionIssue.Swapped }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        shadowElevation = 2.dp,
        modifier = Modifier.widthIn(max = 380.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (swapped.isNotEmpty()) {
                Text(
                    "${swapped.size} tower${if (swapped.size == 1) " has" else "s have"} latitude and longitude swapped: ${names(swapped)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(onClick = onFixSwapped, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Swap back", fontSize = 12.sp)
                }
            }
            if (other.isNotEmpty()) {
                Text(
                    "Check the coordinates of ${names(other)}: far from the other towers or not a valid position.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
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
    positionIssues: List<PositionIssue>,
    onSelect: (Asset) -> Unit,
    onFixSwapped: () -> Unit,
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
                MapView(assets, selectedId, plugin, tileLoader, positionIssues, onSelect, onFixSwapped, Modifier.fillMaxSize().weight(1f))
            }
        }
    }
}
