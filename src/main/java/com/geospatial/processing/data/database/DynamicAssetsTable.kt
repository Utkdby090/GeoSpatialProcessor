package com.geospatial.processing.data.database


import org.jetbrains.exposed.sql.Table

object DynamicAssetsTable : Table("dynamic_assets") {
    // Core Identifiers
    val id = varchar("id", 36) // UUID
    val projectId = varchar("project_id", 36)
    val pluginId = varchar("plugin_id", 100) // e.g., "com.geo.telecom"

    // Universal App State
    val status = varchar("status", 20)
    val capturedDate = varchar("captured_date", 50).nullable()
    val lat = double("latitude").default(0.0)
    val long = double("longitude").default(0.0)

    // THE DYNAMIC PAYLOAD (Stored as a JSON String)
    val propertiesJson = text("properties_json")

    // Universal Image Paths (Instead of heavy BLOBs)
    val thermalImagePath = text("thermal_image_path").nullable()
    val visualImagePath = text("visual_image_path").nullable()
    val assetImagePath = text("asset_image_path").nullable()
    val extraImagePath = text("extra_image_path").nullable()

    override val primaryKey = PrimaryKey(id)
}