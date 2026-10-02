package com.geospatial.processing.data.table

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/** One row per [com.geospatial.processing.domain.model.Asset]. Plugin-specific fields live in [propertiesJson]. */
object AssetsTable : Table("assets") {
    val id = varchar("id", 36)
    val pluginId = varchar("plugin_id", 100)
    val position = integer("position").index()
    val status = varchar("status", 20)
    val latitude = double("latitude")
    val longitude = double("longitude")
    /** JSON object of string → string. */
    val propertiesJson = text("properties_json")
    /** Epoch milliseconds of the earliest image capture (EXIF), null if unknown. Added in schema v4. */
    val capturedAt = long("captured_at").nullable()
    /** [com.geospatial.processing.domain.model.Severity] name. Added in schema v5. */
    val severity = varchar("severity", 20).default("NONE")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }
    val updatedAt = datetime("updated_at").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

/** Manual image choices per asset and slot; files live under <project>/images/. */
object AssetImagesTable : Table("asset_images") {
    val assetId = varchar("asset_id", 36).references(AssetsTable.id, onDelete = ReferenceOption.CASCADE)
    val slot = varchar("slot", 50)
    /** Relative to the project folder, '/'-separated. Null when the slot was cleared. */
    val relativePath = text("relative_path").nullable()
    val cleared = bool("cleared").default(false)

    override val primaryKey = PrimaryKey(assetId, slot)
}

object AuditLogs : Table("audit_logs") {
    val id = integer("id").autoIncrement()
    val timestamp = datetime("timestamp").clientDefault { LocalDateTime.now() }
    val action = varchar("action", 50)
    val details = varchar("details", 500)

    override val primaryKey = PrimaryKey(id)
}
