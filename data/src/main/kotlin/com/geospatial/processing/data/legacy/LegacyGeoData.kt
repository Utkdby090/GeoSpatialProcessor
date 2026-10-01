package com.geospatial.processing.data.legacy

import com.geospatial.processing.core.plugin.telecom.TelecomKeys as K
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.RecordStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Transaction

/**
 * The pre-v3 data model: one `geo_data` row per tower with fixed columns and the four images as BLOBs.
 * Used by both the per-project migration (ProjectMigrator) and the import of the old shared database
 * (LegacyDatabaseMigrator). Read with plain SQL, column by column, so a database that lacks
 * newer columns still converts.
 */
object LegacyGeoData {

    const val TABLE = "geo_data"

    /** Legacy BLOB column → telecom image slot (same placement as GeoRecord.resolveAllImages). */
    private val IMAGE_COLUMNS = mapOf(
        "img_thermal" to K.SLOT_THERMAL,
        "img_extra" to K.SLOT_RGB_ZOOM,
        "img_tower" to K.SLOT_STRUCTURE,
        "img_visual" to K.SLOT_LOCATION,
    )

    /** Legacy text column → property key. */
    private val TEXT_COLUMNS = mapOf(
        "line_name" to K.LINE_NAME,
        "tower_number" to K.TOWER_NUMBER,
        "circuit" to K.CIRCUIT,
        "phase" to K.PHASE,
        "side" to K.SIDE,
        "direction" to K.DIRECTION,
        "captured_date" to K.CAPTURED_DATE,
        "captured_time" to K.CAPTURED_TIME,
        "humidity" to K.HUMIDITY,
        "emissivity" to K.EMISSIVITY,
        "ambient_temp" to K.AMBIENT_TEMP,
        "fault_desc" to K.FAULT_DESCRIPTION,
        "fault_temp" to K.FAULT_TEMP,
        "rise_temp" to K.RISE_TEMP,
        "report_type" to K.REPORT_TYPE,
        "fault_status" to K.FAULT_STATUS,
        "company_name" to K.COMPANY_NAME,
    )

    /** One legacy row: column name (lowercase) → value (String, Number, ByteArray or null). */
    class Row(val columns: Map<String, Any?>) {
        fun text(name: String): String? = columns[name]?.toString()
        fun number(name: String): Double? = (columns[name] as? Number)?.toDouble() ?: text(name)?.toDoubleOrNull()
        fun bytes(name: String): ByteArray? = columns[name] as? ByteArray
    }

    /** All rows in id order. Must run inside a transaction on the legacy database. */
    fun readAll(tx: Transaction): List<Row> =
        tx.exec("SELECT * FROM $TABLE ORDER BY id") { rs ->
            val meta = rs.metaData
            val names = (1..meta.columnCount).map { meta.getColumnName(it).lowercase() }
            buildList {
                while (rs.next()) {
                    add(Row(names.withIndex().associate { (i, name) ->
                        name to if (name in IMAGE_COLUMNS) rs.getBytes(i + 1) else rs.getObject(i + 1)
                    }))
                }
            }
        } ?: emptyList()

    /**
     * Converts a legacy row into an asset. [writeImage] stores a non-empty BLOB for (assetId, slot)
     * and returns its project-relative path; an empty BLOB means the user cleared that slot.
     */
    fun toAsset(row: Row, writeImage: (assetId: String, slot: String, bytes: ByteArray) -> String): Asset {
        val base = Asset(
            pluginId = K.PLUGIN_ID,
            position = row.number("id")?.toInt() ?: 0,
            status = row.text("status")?.let { runCatching { RecordStatus.valueOf(it) }.getOrNull() } ?: RecordStatus.DRAFT,
            latitude = row.number("latitude") ?: 0.0,
            longitude = row.number("longitude") ?: 0.0,
        )

        val properties = linkedMapOf<String, String>()
        TEXT_COLUMNS.forEach { (column, key) -> row.text(column)?.let { properties[key] = it } }
        parseLoadColumns(row.text("dynamic_circuits")).forEach { (header, value) -> properties[K.LOAD_PREFIX + header] = value }

        val images = IMAGE_COLUMNS.mapNotNull { (column, slot) ->
            val blob = row.bytes(column) ?: return@mapNotNull null
            slot to if (blob.isEmpty()) AssetImage.Cleared else AssetImage(writeImage(base.id, slot, blob))
        }.toMap()

        return base.copy(properties = properties, images = images)
    }

    /** The old `dynamic_circuits` column: a JSON object of header → value (written by Gson). */
    internal fun parseLoadColumns(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            (Json.parseToJsonElement(json) as? JsonObject)
                ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
                ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
