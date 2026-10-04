package com.geospatial.processing.ui.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geospatial.processing.core.plugin.CoreFields
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.map.PositionIssue
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.ui.StatusIndicator
import com.geospatial.processing.ui.components.SeverityBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.skia.Image as SkiaImage

private val log = LoggerFactory.getLogger("TowerCard")

private val CARD_WIDTH = 360.dp
private val PHOTO_HEIGHT = 210.dp

/** Photos are kept at most this many pixels on their longer side, so a 20 MP drone shot doesn't sit in memory at full size. */
private const val MAX_PHOTO_PX = 960

/** Larger files are not photos this card can show; reading them would only risk running out of memory. */
private const val MAX_PHOTO_BYTES = 200L * 1024 * 1024

/** One available photo of the tower. */
private data class TowerPhoto(val label: String, val isThermal: Boolean, val source: ImageSource)

/**
 * The card a tower pin opens on the map: its photos (structure first), name, severity and status,
 * every filled-in detail from the form grouped as on the form, and its exact position with a link to check it on Google Maps.
 * [images] resolves the tower's image slots; it touches the disk, so it runs off the UI thread.
 * The card is as tall as its content up to the height [modifier] allows, and scrolls beyond that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TowerCard(
    asset: Asset,
    plugin: DomainPlugin,
    images: (Asset) -> Map<String, ImageSource>,
    issue: PositionIssue?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(asset) { plugin.present(asset) }
    var photos by remember(asset.id) { mutableStateOf<List<TowerPhoto>?>(null) }
    var shown by remember(asset.id) { mutableStateOf(0) }
    // The caller passes a new lambda on every recomposition; read the latest one when loading.
    val currentImages by rememberUpdatedState(images)

    LaunchedEffect(asset) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val sources = currentImages(asset)
                plugin.imageSlots(asset)
                    .mapNotNull { slot -> sources[slot.id]?.takeIf { it !is ImageSource.Missing }?.let { TowerPhoto(slot.label, slot.isThermal, it) } }
                    // The structure photo is what "the tower" looks like; thermal and location shots come after.
                    .sortedBy { it.isThermal || it.label.contains("location", ignoreCase = true) }
            }.onFailure { log.warn("Could not list images for {}", asset.id, it) }.getOrDefault(emptyList())
        }
        photos = loaded
        // The same tower reloaded (a save, a refresh) keeps the photo the user picked, if it still exists.
        shown = shown.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
    }

    Surface(
        modifier = modifier.width(CARD_WIDTH),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PhotoHeader(
                photo = photos?.getOrNull(shown),
                loading = photos == null,
                title = presentation.listTitle,
                subtitle = presentation.listSubtitle,
                asset = asset,
                onClose = onClose,
            )

            val list = photos.orEmpty()
            if (list.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    list.forEachIndexed { i, photo ->
                        FilterChip(
                            selected = i == shown,
                            onClick = { shown = i },
                            label = { Text(photo.label.substringBefore(" Image").substringBefore(" IMAGE"), fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusIndicator(asset.status)
                    if (presentation.isFault) Text("Fault reported", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }

                issue?.let { IssueNote(it) }

                PositionRow(asset)

                DetailGroups(asset, plugin)
            }
        }
    }
}

@Composable
private fun PhotoHeader(photo: TowerPhoto?, loading: Boolean, title: String, subtitle: String, asset: Asset, onClose: () -> Unit) {
    var bitmap by remember(photo) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(photo) { mutableStateOf(false) }
    LaunchedEffect(photo) {
        if (photo == null) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { decodeScaled(photo.source) }
        failed = bitmap == null
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(PHOTO_HEIGHT)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color(0xFF1F2937)),
    ) {
        val image = bitmap
        when {
            image != null -> Image(image, contentDescription = photo?.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            loading || (photo != null && !failed) -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
            else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
                Text(if (failed) "This photo could not be opened" else "No photos for this tower yet", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }

        // Darkens the bottom of the photo so the name stays readable on any picture.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        SeverityBadge(asset.severity, Modifier.align(Alignment.TopStart).padding(12.dp))
        photo?.let {
            Text(
                it.label,
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun IssueNote(issue: PositionIssue) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
        Text(issueText(issue), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun PositionRow(asset: Asset) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            formatPosition(asset),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { openInGoogleMaps(asset) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Google Maps", fontSize = 12.sp)
        }
    }
}

/** The form's filled-in fields, grouped and labelled as on the form; coordinates are shown separately. */
@Composable
private fun DetailGroups(asset: Asset, plugin: DomainPlugin) {
    val groups = remember(asset) {
        plugin.getPropertySchema(asset)
            .filter { it.key != CoreFields.LATITUDE && it.key != CoreFields.LONGITUDE }
            .mapNotNull { field -> asset.property(field.key).trim().takeIf { it.isNotEmpty() }?.let { field to it } }
            .groupBy({ it.first.group }, { it.first.label to it.second })
    }
    if (groups.isEmpty()) {
        Text("No details filled in yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    groups.forEach { (group, rows) ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (group.isNotBlank()) {
                Text(group.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
            }
            // Two columns: short values side by side, long ones (descriptions) get the full width.
            val (long, short) = rows.partition { it.second.length > 22 }
            short.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (label, value) -> DetailCell(label, value, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            long.forEach { (label, value) -> DetailCell(label, value, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun DetailCell(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Decodes [source] and shrinks it to at most [MAX_PHOTO_PX] wide; null when it can't be read. */
private fun decodeScaled(source: ImageSource): ImageBitmap? = runCatching {
    val bytes = when (source) {
        is ImageSource.FromFile -> {
            val size = source.file.length()
            if (size > MAX_PHOTO_BYTES) { log.warn("Tower photo {} is too large to show ({} bytes)", source.file, size); return null }
            source.file.readBytes()
        }
        is ImageSource.FromBlob -> source.bytes
        ImageSource.Missing -> return null
    }
    SkiaImage.makeFromEncoded(bytes).use { full ->
        val scale = min(1f, MAX_PHOTO_PX.toFloat() / max(full.width, full.height))
        if (scale >= 1f) return@use full.toComposeImageBitmap()
        val w = (full.width * scale).toInt().coerceAtLeast(1)
        val h = (full.height * scale).toInt().coerceAtLeast(1)
        Surface.makeRasterN32Premul(w, h).use { surface ->
            surface.canvas.drawImageRect(full, Rect.makeWH(w.toFloat(), h.toFloat()))
            surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
        }
    }
}.onFailure { log.warn("Could not decode tower photo", it) }.getOrNull()

private fun openInGoogleMaps(asset: Asset) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            // Double.toString always writes a dot, so the link works whatever the system language.
            Desktop.getDesktop().browse(URI("https://www.google.com/maps/search/?api=1&query=${asset.latitude},${asset.longitude}"))
        }
    }.onFailure { log.warn("Could not open the browser", it) }
}
