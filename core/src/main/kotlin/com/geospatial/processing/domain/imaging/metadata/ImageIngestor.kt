package com.geospatial.processing.domain.imaging.metadata

import com.geospatial.processing.domain.model.Asset
import java.io.File
import java.time.Instant

/** One image file and what its metadata says. */
data class IngestedImage(val file: File, val meta: ImageMeta)

/**
 * Pulls location and capture time out of an asset's image files.
 *
 * Only fills gaps: coordinates the user or the CSV already provided are never overwritten.
 * The no-fix position (0.0, 0.0) counts as "missing", because that is what an empty CSV cell becomes.
 */
object ImageIngestor {

    fun read(files: Collection<File>): List<IngestedImage> =
        files.sortedBy { it.name.lowercase() }.map { IngestedImage(it, ImageMetadata.read(it)) }

    /** Thermal images are told apart by metadata, complementing the filename rules in TowerImageResolver. */
    fun thermalFiles(images: List<IngestedImage>): List<File> = images.filter { it.meta.isThermalHint }.map { it.file }

    /**
     * Returns [asset] with coordinates and capture time filled in from [images]; same instance when nothing changed.
     * Coordinates come from the visible-light image first (a thermal sensor's GPS tag is the same fix, but
     * visible images are the more reliable carriers), then from any image with a GPS tag.
     */
    fun enrich(asset: Asset, images: List<IngestedImage>): Asset {
        val withGps = images.filter { it.meta.hasGps }
        val source = withGps.firstOrNull { !it.meta.isThermalHint } ?: withGps.firstOrNull()
        val needsPosition = asset.latitude == 0.0 && asset.longitude == 0.0
        val capturedAt: Instant? = images.mapNotNull { it.meta.capturedAt }.minOrNull()

        var result = asset
        if (needsPosition && source != null) {
            result = result.copy(latitude = source.meta.latitude!!, longitude = source.meta.longitude!!)
        }
        if (asset.capturedAt == null && capturedAt != null) {
            result = result.copy(capturedAt = capturedAt)
        }
        return result
    }
}
