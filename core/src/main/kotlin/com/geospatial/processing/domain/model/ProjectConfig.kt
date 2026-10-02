package com.geospatial.processing.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectConfig(
    val projectName: String,
    val pluginId: String,       // e.g., "com.geo.telecom"
    val createdAt: Long,        // Timestamp
    val lastModified: Long,     // Timestamp
    val report: ReportSettings = ReportSettings(),
)

/**
 * The report choices of one project, saved in project.json. Older files without this block get the defaults.
 * [logoPath] is relative to the project folder (e.g. "branding/logo.png") so .geox files stay portable.
 */
@Serializable
data class ReportSettings(
    val template: String = "STANDARD",
    val companyName: String = "",
    val accentColor: String = "",
    val logoPath: String = "",
)