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
import com.geospatial.processing.domain.map.GeoPoint
import com.geospatial.processing.domain.map.MapLayout
import com.geospatial.processing.domain.map.PositionIssue
import com.geospatial.processing.domain.map.TileKey
import com.geospatial.processing.domain.map.WebMercator
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.ui.CustomTitleBar
import com.geospatial.processing.ui.components.severityColor
import com.geospatial.processing.ui.theme.GeospatialEnterpriseTheme
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import ovh.plrapps.mapcompose.api.BoundingBox
import ovh.plrapps.mapcompose.api.addClusterer
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.centerOnMarker
import ovh.plrapps.mapcompose.api.centroidX
import ovh.plrapps.mapcompose.api.centroidY
import ovh.plrapps.mapcompose.api.hasMarker
import ovh.plrapps.mapcompose.api.onMarkerClick
import ovh.plrapps.mapcompose.api.onTap
import ovh.plrapps.mapcompose.api.removeAllMarkers
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.api.snapScrollTo
import ovh.plrapps.mapcompose.api.updateMarkerZ
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.MapUI
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.ui.state.markers.model.RenderingStrategy
import org.slf4j.LoggerFactory
import java.util.Locale

private val log = LoggerFactory.getLogger("MapView")

private const val LEVEL_COUNT = WebMercator.MAX_ZOOM + 1
private const val FULL_SIZE = WebMercator.TILE_SIZE shl WebMercator.MAX_ZOOM
/** Disk-cache reads and decoding run in parallel; network downloads are limited separately ([OsmTileFetcher]). */
private const val TILE_WORKERS = 8
/** Room left around the towers when the map frames them, as a share of the view. */
private val FIT_PADDING = Offset(0.2f, 0.2f)

private const val CLUSTERER = "towers"
/** How far pins that share one position are spread apart, so every one of them can be seen and clicked. */
private val FAN_OUT_RADIUS = 14.dp

/** Screen offsets for assets that share a position, so every pin can be seen and clicked; others get no entry. */
private fun fanOutOffsets(assets: List<Asset>): Map<String, DpOffset> =
    MapLayout.fanOut(assets.associate { it.id to it.geoPoint() })
        .mapValues { (_, d) -> DpOffset(FAN_OUT_RADIUS * d.first.toFloat(), FAN_OUT_RADIUS * d.second.toFloat()) }

/** What decides where a pin is drawn; two lists with equal spots need no marker changes. */
private data class PinSpot(val id: String, val latitude: Double, val longitude: Double)

private fun Asset.geoPoint() = GeoPoint(latitude, longitude)

/** Real coordinates only: 0,0 means "no position yet", and NaN or out-of-range values would break the map layout. */
private fun Asset.isPlottable() = MapLayout.plottable(geoPoint())

private fun Asset.mapX() = WebMercator.normalizedX(longitude)
private fun Asset.mapY() = WebMercator.normalizedY(latitude)

/** The map area that frames [assets]; null for none. */
private fun boundsOf(assets: List<Asset>): BoundingBox? =
    MapLayout.bounds(assets.map { it.geoPoint() })?.let { BoundingBox(it.left, it.top, it.right, it.bottom) }

/** Coordinates with a dot as decimal separator whatever the system language, as surveys and GPS units write them. */
internal fun formatPosition(asset: Asset) = String.format(Locale.ROOT, "%.6f, %.6f", asset.latitude, asset.longitude)

/**
 * OpenStreetMap view of the project (MapCompose): one pin per positioned asset, coloured by severity.
 * Drag to pan, wheel or pinch to zoom smoothly, hover a pin for its name, click it to select the tower and open its
 * [TowerCard] (photos and details); [images] resolves a tower's photos.
 * Pins whose position looks wrong ([positionIssues]) get a warning ring, and a banner offers to swap back
 * latitude/longitude pairs that were exchanged.
 *
 * [assets] are the towers shown as pins (the list's current filter); [allAssets] is the whole project, which the
 * position checks and the swap actions cover, so banners name and count from it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MapView(
    assets: List<Asset>,
    allAssets: List<Asset>,
    selectedId: String?,
    plugin: DomainPlugin,
    tileLoader: TileLoader,
    positionIssues: List<PositionIssue>,
    allLookSwapped: Boolean,
    images: (Asset) -> Map<String, ImageSource>,
    onSelect: (Asset) -> Unit,
    onFixSwapped: () -> Unit,
    onSwapAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val positioned = remember(assets) { assets.filter { it.isPlottable() } }
    val flagged = remember(positionIssues) { positionIssues.associateBy { it.id } }
    // Frame the towers that look right, so one misplaced pin doesn't zoom the map out to the whole world.
    val trusted = remember(positioned, flagged) { positioned.filter { it.id !in flagged }.ifEmpty { positioned } }

    // On high-density screens use tiles one level coarser, so street names stay readable instead of tiny.
    val magnify = if (LocalDensity.current.density >= 1.75f) 1 else 0
    val state = remember(tileLoader) {
        MapState(LEVEL_COUNT, FULL_SIZE, FULL_SIZE, workerCount = TILE_WORKERS) {
            magnifyingFactor(magnify)
        }.apply {
            // MapCompose owns (and closes) the returned source. It does not catch exceptions from here, and one would stop
            // a tile worker for good, so a failure becomes "no tile" (the area stays grey and is asked for again later).
            addLayer(
                TileStreamProvider { row, col, zoom ->
                    runCatching { tileLoader.load(TileKey(zoom, col, row))?.let { Buffer().apply { write(it) } } }
                        .onFailure { log.warn("Tile {}/{}/{} failed", zoom, col, row, it) }
                        .getOrNull()
                }
            )
            // Pins closer than this on screen merge into one bubble with a count; clicking it zooms in to separate them.
            addClusterer(CLUSTERER, clusteringThreshold = 28.dp) { ids -> { ClusterBubble(ids.size) } }
        }
    }
    DisposableEffect(state) { onDispose { state.shutdown() } }

    // The tower whose card is open: set by clicking its pin, cleared by the card's close button or a click on the map.
    var cardId by remember { mutableStateOf<String?>(null) }
    val currentPositioned by rememberUpdatedState(positioned)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(state) {
        state.onMarkerClick { id, _, _ ->
            cardId = id
            currentPositioned.firstOrNull { it.id == id }?.let(currentOnSelect)
        }
        state.onTap { _, _ -> cardId = null }
    }

    // Pins read these states, so selection, warnings, severity and names update without re-adding every marker.
    val selectedState = rememberUpdatedState(selectedId)
    val flaggedState = rememberUpdatedState(flagged)
    val liveById = rememberUpdatedState(remember(positioned) { positioned.associateBy { it.id } })

    // Markers are rebuilt only when a pin has to move or appear: every save replaces the list of towers, and rebuilding
    // them all (and the clustering with them) for an edit that did not change any position makes a large map flicker.
    val spots = remember(positioned) { positioned.map { PinSpot(it.id, it.latitude, it.longitude) } }
    LaunchedEffect(state, spots, plugin) {
        state.removeAllMarkers()
        val fanOut = fanOutOffsets(positioned)
        positioned.forEach { asset ->
            state.addMarker(
                asset.id, asset.mapX(), asset.mapY(),
                relativeOffset = Offset(-0.5f, -0.5f),
                absoluteOffset = fanOut[asset.id] ?: DpOffset.Zero,
                zIndex = if (asset.id == selectedState.value) 1f else 0f,
                renderingStrategy = RenderingStrategy.Clustering(CLUSTERER),
            ) {
                val live = liveById.value[asset.id] ?: asset // the tower as it is now, not as it was when the pin was made
                TooltipArea(
                    tooltip = { PinTooltip(plugin.present(live).listTitle, flaggedState.value[asset.id]) },
                    delayMillis = 300,
                    tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.TopCenter, Alignment.TopCenter, DpOffset(0.dp, (-4).dp)),
                ) {
                    Pin(severityColor(live.severity), selected = asset.id == selectedState.value, flagged = asset.id in flaggedState.value)
                }
            }
        }
    }

    // Selected pin on top, and brought into view when the selection changes. Not keyed on the data: a save or a refresh
    // must not pull the map back to the selected tower after the user panned away (re-added pins get their z above).
    var previousSelected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, selectedId) {
        previousSelected?.let { state.updateMarkerZ(it, 0f) }
        previousSelected = selectedId
        if (selectedId != null && state.hasMarker(selectedId)) {
            state.updateMarkerZ(selectedId, 1f)
            state.centerOnMarker(selectedId)
        }
    }

    // Frame the towers when the map opens and whenever positions change (an import, a swap); selecting doesn't refit.
    val trustedPositions = remember(trusted) { trusted.map { it.latitude to it.longitude } }
    LaunchedEffect(state, trustedPositions) {
        boundsOf(trusted)?.let { state.snapScrollTo(it, FIT_PADDING) }
    }

    var confirmSwapAll by remember { mutableStateOf(false) }
    // What "swap all" changes: every tower in the project whose swapped position is real (see WorkbenchViewModel).
    val swappableCount = remember(allAssets) { allAssets.count { MapLayout.plottable(it.geoPoint().swapped()) } }

    val scope = rememberCoroutineScope()
    fun zoomBy(factor: Double) = scope.launch { state.scrollTo(state.centroidX, state.centroidY, state.scale * factor) }

    Box(modifier.clipToBounds().background(Color(0xFFE5E7EB))) {
        MapUI(Modifier.fillMaxSize(), state = state)

        // Full height so the card can take whatever the banners leave (weight below) and scroll inside it. The column
        // itself has no pointer handling, so drags and clicks beside the card still reach the map.
        Column(Modifier.align(Alignment.TopStart).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allLookSwapped) SwapAllBanner(swappableCount) { confirmSwapAll = true }
            else if (positionIssues.isNotEmpty()) IssueBanner(positionIssues, allAssets, plugin, onFixSwapped)

            val cardAsset = positioned.firstOrNull { it.id == cardId }
            if (cardAsset != null) {
                TowerCard(
                    asset = cardAsset,
                    plugin = plugin,
                    images = images,
                    issue = flagged[cardAsset.id],
                    onClose = { cardId = null },
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                positioned.firstOrNull { it.id == selectedId }?.let { SelectedCard(plugin.present(it).sequenceLabel, it) }
            }
        }

        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = { zoomBy(2.0) }) { Text("+") }
            FilledTonalIconButton(onClick = { zoomBy(0.5) }) { Text("−") }
            FilledTonalIconButton(
                enabled = trusted.isNotEmpty(),
                onClick = { boundsOf(trusted)?.let { scope.launch { state.scrollTo(it, FIT_PADDING) } } },
            ) { Text("⌖") }
            TooltipArea(tooltip = { PinTooltip("Swap latitude and longitude for all towers", null) }, delayMillis = 300) {
                FilledTonalIconButton(enabled = swappableCount > 0, onClick = { confirmSwapAll = true }) { Text("⇄") }
            }
        }

        if (confirmSwapAll) {
            AlertDialog(
                onDismissRequest = { confirmSwapAll = false },
                title = { Text("Swap latitude and longitude?") },
                text = { Text("This exchanges latitude and longitude for all $swappableCount towers with a position in this project. Doing it again undoes it.") },
                confirmButton = { Button(onClick = { confirmSwapAll = false; onSwapAll() }) { Text("Swap for all") } },
                dismissButton = { TextButton(onClick = { confirmSwapAll = false }) { Text("Cancel") } },
            )
        }

        val skipped = assets.size - positioned.size
        Text(
            buildString {
                if (skipped > 0) append("$skipped without a usable position · ")
                append("© OpenStreetMap contributors")
            },
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            color = Color.DarkGray,
        )

        if (positioned.isEmpty()) {
            Text(if (assets.isEmpty()) "No towers to show." else "None of these towers has a usable position yet.", Modifier.align(Alignment.Center), color = Color.DarkGray)
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
private fun ClusterBubble(count: Int) {
    Box(
        Modifier.size(30.dp).background(Color.White, CircleShape).padding(2.dp).background(Color(0xFF2563EB), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun SwapAllBanner(count: Int, onSwap: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        shadowElevation = 2.dp,
        modifier = Modifier.widthIn(max = 380.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "The towers sit in the polar regions, so latitude and longitude are probably swapped for all of them.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onSwap, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("Swap for all $count towers", fontSize = 12.sp)
            }
        }
    }
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

internal fun issueText(issue: PositionIssue) = when (issue) {
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
            Text(formatPosition(asset), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    allAssets: List<Asset>,
    selectedId: String?,
    plugin: DomainPlugin,
    tileLoader: TileLoader,
    isDarkTheme: Boolean,
    positionIssues: List<PositionIssue>,
    allLookSwapped: Boolean,
    images: (Asset) -> Map<String, ImageSource>,
    onSelect: (Asset) -> Unit,
    onFixSwapped: () -> Unit,
    onSwapAll: () -> Unit,
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
                MapView(
                    assets, allAssets, selectedId, plugin, tileLoader, positionIssues, allLookSwapped, images,
                    onSelect, onFixSwapped, onSwapAll, Modifier.fillMaxSize().weight(1f),
                )
            }
        }
    }
}
