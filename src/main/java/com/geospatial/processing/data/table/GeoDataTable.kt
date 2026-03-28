package com.geospatial.processing.data.table

import com.geospatial.processing.domain.model.RecordStatus
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime


object GeoDataTable : IntIdTable("geo_data") {
    // --- Core Location Info ---
    // Was 'name', now mapping to 'Line Name' or generic Location
    val lineName = varchar("line_name", 255)
    val towerNumber = varchar("tower_number", 255).default("") // New: e.g. "180/2"
    val circuit = varchar("circuit", 255).default("")          // New: e.g. "1"

    val latitude = double("latitude")
    val longitude = double("longitude")

    // --- Environmental Parameters ---
    val humidity = varchar("humidity", 255).default("")       // e.g. "50%"
    val emissivity = varchar("emissivity", 255).default("")   // e.g. "0.95"
    val ambientTemp = varchar("ambient_temp", 255).default("")// e.g. "23.4 C"
    val loadValue = varchar("load_val", 255).default("")      // e.g. "NA"

    // --- Fault Analysis ---
    val faultDescription = text("fault_desc").default("")    // e.g. "No Thermal Fault..."
    val faultTemp = varchar("fault_temp", 255).default("")    // e.g. "NA"

    // --- Media (4 Slots) ---
    val thermalImage = binary("img_thermal").nullable()
    val visualImage = binary("img_visual").nullable() // RGB Image
    val towerImage = binary("img_tower").nullable()   // Full Tower View
    val extraImage = binary("img_extra").nullable()   // 4th Slot

    // --- Meta ---
    val status = enumerationByName("status", 255, RecordStatus::class).default(RecordStatus.DRAFT)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)


}

object AuditLogs : Table("audit_logs") {
    val id = integer("id").autoIncrement()
    val timestamp = datetime("timestamp").clientDefault { LocalDateTime.now() }
    val action = varchar("action", 50)     // e.g., "IMPORT", "UPDATE", "DELETE_ALL"
    val details = varchar("details", 500)  // e.g., "Imported 150 records from towers.csv"

    override val primaryKey = PrimaryKey(id)
}