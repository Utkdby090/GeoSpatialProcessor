package com.geospatial.processing.data.entity

import com.geospatial.processing.data.table.GeoDataTable
import com.geospatial.processing.domain.model.GeoRecord
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class GeoEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<GeoEntity>(GeoDataTable)

    // --- Core Location Info ---
    var lineName by GeoDataTable.lineName
    var towerNumber by GeoDataTable.towerNumber
    var circuit by GeoDataTable.circuit
    var latitude by GeoDataTable.latitude
    var longitude by GeoDataTable.longitude

    // --- NEW: Location Extenders ---
    var phase by GeoDataTable.phase
    var side by GeoDataTable.side
    var direction by GeoDataTable.direction

    // --- NEW: Time Data ---
    var capturedDate by GeoDataTable.capturedDate
    var capturedTime by GeoDataTable.capturedTime

    // --- Environmental Parameters ---
    var humidity by GeoDataTable.humidity
    var emissivity by GeoDataTable.emissivity
    var ambientTemp by GeoDataTable.ambientTemp

    // --- Load Data ---
  //  var loadValue by GeoDataTable.loadValue
    var loadDataCkt1 by GeoDataTable.loadDataCkt1
    var loadDataCkt2 by GeoDataTable.loadDataCkt2

    // --- Fault Analysis ---
    var faultDescription by GeoDataTable.faultDescription
    var faultTemp by GeoDataTable.faultTemp
    var riseTemp by GeoDataTable.riseTemp

    // --- Media (4 Slots) ---
    var thermalImage by GeoDataTable.thermalImage
    var visualImage by GeoDataTable.visualImage
    var towerImage by GeoDataTable.towerImage
    var extraImage by GeoDataTable.extraImage

    // --- Meta ---
    var status by GeoDataTable.status
    var updatedAt by GeoDataTable.updatedAt

    var reportType by GeoDataTable.reportType

    var faultStatus by GeoDataTable.faultStatus
    var companyName by GeoDataTable.companyName

    /**
     * Mapper function: Entity (DB) -> Domain Model (UI)
     */
    fun toDomain(): GeoRecord {
        return GeoRecord(
            id = this.id.value,
            lineName = this.lineName,
            towerNumber = this.towerNumber,
            circuit = this.circuit,
            latitude = this.latitude,
            longitude = this.longitude,

            // New Location & Time Extenders
            phase = this.phase,
            side = this.side,
            direction = this.direction,
            capturedDate = this.capturedDate,
            capturedTime = this.capturedTime,

            // Parameters & Load
            humidity = this.humidity,
            emissivity = this.emissivity,
            ambientTemp = this.ambientTemp,
           // loadValue = this.loadValue,
            loadDataCkt1 = this.loadDataCkt1,
            loadDataCkt2 = this.loadDataCkt2,

            // Faults
            faultDescription = this.faultDescription,
            faultTemp = this.faultTemp,
            riseTemp = this.riseTemp,

            // Images
            thermalImage = this.thermalImage,
            visualImage = this.visualImage,
            towerImage = this.towerImage,
            extraImage = this.extraImage,

            //reportType
            reportType = this.reportType,
            faultStatus = this.faultStatus,
            status = this.status,
            companyName = this.companyName
        )
    }
}