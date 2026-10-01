package com.geospatial.processing.domain.model

import java.util.UUID

enum class RecordStatus {
    DRAFT,      // Missing data or images (Orange Icon)
    READY,      // All required fields and images present (Green Icon)
    ARCHIVED    // Processed and exported (Gray Icon)
}

/**
 * One inspected item (a tower, a span, a turbine…). Replaces GeoRecord and DynamicAsset.
 *
 * Only what every industry has lives in fields; everything domain-specific is in [properties],
 * whose keys and meaning are defined by the plugin identified by [pluginId].
 */
data class Asset(
    val id: String = UUID.randomUUID().toString(),
    val pluginId: String,
    /** Display/import order. IDs are random UUIDs, so order must be stored explicitly. */
    val position: Int,
    val status: RecordStatus = RecordStatus.DRAFT,
    val latitude: Double,
    val longitude: Double,
    val properties: Map<String, String> = emptyMap(),
    /** Manual image choices per slot id. A slot without an entry falls back to the image folder. */
    val images: Map<String, AssetImage> = emptyMap(),
) {
    /** The property value, or "" when absent. */
    fun property(key: String): String = properties[key].orEmpty()
}

/**
 * A manual decision for one image slot.
 *  - [relativePath] set: an image copied into the project, relative to the project folder
 *    (e.g. "images/<assetId>/THERMAL.jpg"), so projects stay portable in .geox files.
 *  - [cleared] true: the user removed the image; the slot stays empty even if the folder has a match.
 */
data class AssetImage(val relativePath: String?, val cleared: Boolean = false) {
    companion object {
        val Cleared = AssetImage(relativePath = null, cleared = true)
    }
}

/** A row read by a plugin's CSV import, before it gets an id and position. */
data class AssetDraft(
    val latitude: Double,
    val longitude: Double,
    val properties: Map<String, String>,
)
