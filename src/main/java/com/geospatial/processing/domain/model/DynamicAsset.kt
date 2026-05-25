package com.geospatial.processing.domain.model

import kotlinx.serialization.Serializable


// This replaces GeoRecord!

data class DynamicAsset(
    val id: String,                  // UUID
    val projectId: String,           // Links to the user's workspace project
    val pluginId: String,            // e.g., "com.geo.telecom" - tells us which schema to use

    // The Magic Payload: Stores ALL domain-specific text fields as a JSON map
    // Telecom: {"towerNumber": "76_0", "circuit": "1"}
    // Wind: {"turbineId": "W-99", "bladeLength": "45m"}
    val properties: Map<String, String>,

    // Universal App State
    val status: RecordStatus,
    val capturedDate: String?,
    val lat: Double,
    val long: Double,

    // Universal Image Paths
    val thermalImagePath: String?,
    val visualImagePath: String?,
    val assetImagePath: String?,
    val extraImagePath: String?
)