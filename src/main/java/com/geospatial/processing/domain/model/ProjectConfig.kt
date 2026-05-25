package com.geospatial.processing.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectConfig(
    val projectName: String,
    val pluginId: String,       // e.g., "com.geo.telecom"
    val createdAt: Long,        // Timestamp
    val lastModified: Long      // Timestamp
)