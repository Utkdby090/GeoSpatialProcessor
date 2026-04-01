package com.geospatial.processing.data.table

import com.geospatial.processing.domain.model.RecordStatus
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

object GeoDataTable : IntIdTable("geo_data") {
    // --- Core Location Info ---
    val lineName = varchar("line_name", 255)
    val towerNumber = varchar("tower_number", 255).default("")
    val circuit = varchar("circuit", 255).default("")

    val latitude = double("latitude")
    val longitude = double("longitude")

    // --- NEW: Location Extenders ---
    val phase = varchar("phase", 255).nullable()
    val side = varchar("side", 255).nullable()
    val direction = varchar("direction", 255).nullable()

    // --- NEW: Time Data ---
    val capturedDate = varchar("captured_date", 255).nullable()
    val capturedTime = varchar("captured_time", 255).nullable()

    // --- Environmental Parameters ---
    val humidity = varchar("humidity", 255).default("")
    val emissivity = varchar("emissivity", 255).default("")
    val ambientTemp = varchar("ambient_temp", 255).default("")

    // --- NEW: Load Data ---
    val loadValue = varchar("load_val", 255).default("")      // General load
    val loadDataCkt3 = varchar("load_data_ckt3", 255).nullable()
    val loadDataCkt4 = varchar("load_data_ckt4", 255).nullable()

    // --- Fault Analysis ---
    val faultDescription = text("fault_desc").default("")
    val faultTemp = varchar("fault_temp", 255).default("")
    val riseTemp = varchar("rise_temp", 255).nullable() // NEW

    // --- Media (4 Slots) ---
    val thermalImage = binary("img_thermal").nullable()
    val visualImage = binary("img_visual").nullable()
    val towerImage = binary("img_tower").nullable()
    val extraImage = binary("img_extra").nullable()

    // --- Meta ---
    val status = enumerationByName("status", 255, RecordStatus::class).default(RecordStatus.DRAFT)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    val reportType = varchar("report_type", 50).default("TOWER_THERMAL_FAULT")
}

object AuditLogs : Table("audit_logs") {
    val id = integer("id").autoIncrement()
    val timestamp = datetime("timestamp").clientDefault { LocalDateTime.now() }
    val action = varchar("action", 50)
    val details = varchar("details", 500)

    override val primaryKey = PrimaryKey(id)


}