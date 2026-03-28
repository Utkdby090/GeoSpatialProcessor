package com.geospatial.processing.data.repository

import com.geospatial.processing.data.entity.GeoEntity
import com.geospatial.processing.data.table.AuditLogs
import com.geospatial.processing.data.table.GeoDataTable
import com.geospatial.processing.domain.model.GeoRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class GeoRepository {

    // --- 1. NEW: THE AUDIT LOGGING FUNCTION ---
    /**
     * Records an immutable action to the audit log.
     */
    suspend fun logAuditAction(action: String, details: String) = withContext(Dispatchers.IO) {
        transaction {
            AuditLogs.insert {
                it[this.timestamp] = LocalDateTime.now()
                it[this.action] = action
                it[this.details] = details
            }
        }
    }

    /**
     * Fetch all records from DB and map to Domain objects.
     */
    suspend fun getAllRecords(): List<GeoRecord> = withContext(Dispatchers.IO) {
        transaction {
            GeoEntity.all().map { it.toDomain() }
        }
    }

    /**
     * Save (Insert) or Update a record.
     */
    suspend fun saveRecord(record: GeoRecord) = withContext(Dispatchers.IO) {
        transaction {
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
        humidity = record.humidity
        emissivity = record.emissivity
        ambientTemp = record.ambientTemp
        loadValue = record.loadValue
        faultDescription = record.faultDescription
        faultTemp = record.faultTemp
        thermalImage = record.thermalImage
        visualImage = record.visualImage
        towerImage = record.towerImage
        extraImage = record.extraImage
        status = record.status
    }

    /**
     * Delete a single record by ID.
     */
    suspend fun deleteRecord(id: Int) = withContext(Dispatchers.IO) {
        transaction {
            val entity = GeoEntity.findById(id)
            if (entity != null) {
                // --- 2. NEW: AUTO-LOG THE DELETION ---
                AuditLogs.insert {
                    it[this.timestamp] = LocalDateTime.now()
                    it[this.action] = "DELETE_RECORD"
                    it[this.details] = "Deleted Tower ${entity.towerNumber} (ID: $id)"
                }
                entity.delete()
            }
        }
    }

    /**
     * Enterprise Purge: Deletes ALL data.
     */
    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        transaction {
            // --- 3. NEW: AUTO-LOG THE PURGE BEFORE IT HAPPENS ---
            AuditLogs.insert {
                it[this.timestamp] = LocalDateTime.now()
                it[this.action] = "SYSTEM_PURGE"
                it[this.details] = "User executed clearAllData(). All records wiped."
            }

            GeoDataTable.deleteAll()
            try {
                exec("DELETE FROM sqlite_sequence WHERE name = 'geo_data'")
            } catch (e: Exception) {
                // Ignore if sequence table doesn't exist
            }
        }
    }
}

// --- 4. NEW: THE AUDIT TABLE SCHEMA ---
// (You can leave this here, or move it to your 'table' package)
