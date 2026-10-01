package com.geospatial.processing.data.repository

import com.geospatial.processing.data.entity.GeoEntity
import com.geospatial.processing.data.table.AuditLogs
import com.geospatial.processing.data.table.GeoDataTable
import com.geospatial.processing.domain.model.GeoRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import com.google.gson.Gson

// 1. THE FIX: Inject the specific Database instance for the current project
class GeoRepository(private val database: Database) {

    private val gson = Gson()

    // --- 1. THE AUDIT LOGGING FUNCTION ---
    suspend fun logAuditAction(action: String, details: String) = withContext(Dispatchers.IO) {
        // 2. THE FIX: Bind the transaction exclusively to this project's database
        transaction(database) {
            AuditLogs.insert {
                it[this.timestamp] = LocalDateTime.now()
                it[this.action] = action
                it[this.details] = details
            }
        }
    }

    // --- Fetch All ---
    suspend fun getAllRecords(): List<GeoRecord> = withContext(Dispatchers.IO) {
        transaction(database) {
            GeoEntity.all().map { it.toDomain() }
        }
    }

    // --- Save / Update ---
    suspend fun saveRecord(record: GeoRecord) = withContext(Dispatchers.IO) {
        transaction(database) {
            if (record.id == 0) {
                GeoEntity.new {
                    assignValuesFrom(record)
                }
            } else {
                val entity = GeoEntity.findById(record.id)
                entity?.apply {
                    assignValuesFrom(record)
                    updatedAt = LocalDateTime.now()
                }
            }
        }
    }

    private fun GeoEntity.assignValuesFrom(record: GeoRecord) {
        lineName = record.lineName
        towerNumber = record.towerNumber
        circuit = record.circuit
        latitude = record.latitude
        longitude = record.longitude
        phase = record.phase
        side = record.side
        direction = record.direction
        capturedDate = record.capturedDate
        capturedTime = record.capturedTime
        dynamicCircuits = gson.toJson(record.dynamicCircuits)
        riseTemp = record.riseTemp
        faultDescription = record.faultDescription
        faultTemp = record.faultTemp
        humidity = record.humidity
        emissivity = record.emissivity
        ambientTemp = record.ambientTemp
        thermalImage = record.thermalImage
        visualImage = record.visualImage
        towerImage = record.towerImage
        extraImage = record.extraImage
        reportType = record.reportType
        status = record.status
        faultStatus = record.faultStatus
        companyName = record.companyName
    }

    // --- Delete ---
    suspend fun deleteRecord(id: Int) = withContext(Dispatchers.IO) {
        transaction(database) {
            val entity = GeoEntity.findById(id)
            if (entity != null) {
                AuditLogs.insert {
                    it[this.timestamp] = LocalDateTime.now()
                    it[this.action] = "DELETE_RECORD"
                    it[this.details] = "Deleted Tower ${entity.towerNumber} (ID: $id)"
                }
                entity.delete()
            }
        }
    }

    // --- Purge ---
    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        transaction(database) {
            AuditLogs.insert {
                it[this.timestamp] = LocalDateTime.now()
                it[this.action] = "SYSTEM_PURGE"
                it[this.details] = "User executed clearAllData(). All records wiped."
            }

            GeoDataTable.deleteAll()
            try {
                exec("DELETE FROM sqlite_sequence WHERE name = 'geo_data'")
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}