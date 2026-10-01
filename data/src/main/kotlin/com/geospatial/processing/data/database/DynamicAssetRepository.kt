package com.geospatial.processing.data.database



import com.geospatial.processing.data.database.DynamicAssetsTable
import com.geospatial.processing.domain.model.DynamicAsset
import com.geospatial.processing.domain.model.RecordStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class DynamicAssetRepository {

    // Initialize the DB Table
    init {
        transaction {
            SchemaUtils.create(DynamicAssetsTable)
        }
    }

    fun saveAsset(asset: DynamicAsset) {
        transaction {
            // Convert the Map<String, String> into a flat JSON String
            val jsonPayload = Json.encodeToString(asset.properties)

            DynamicAssetsTable.insert {
                it[id] = asset.id
                it[projectId] = asset.projectId
                it[pluginId] = asset.pluginId
                it[status] = asset.status.name
                it[capturedDate] = asset.capturedDate
                it[lat] = asset.lat
                it[long] = asset.long
                it[propertiesJson] = jsonPayload
                it[thermalImagePath] = asset.thermalImagePath
                it[visualImagePath] = asset.visualImagePath
                it[assetImagePath] = asset.assetImagePath
                it[extraImagePath] = asset.extraImagePath
            }
        }
    }

    fun getAssetsByProject(projectId: String): List<DynamicAsset> {
        return transaction {
            DynamicAssetsTable.select { DynamicAssetsTable.projectId eq projectId }
                .map { row ->
                    // Decode the JSON String back into a Map<String, String>
                    val propertiesMap = Json.decodeFromString<Map<String, String>>(row[DynamicAssetsTable.propertiesJson])

                    DynamicAsset(
                        id = row[DynamicAssetsTable.id],
                        projectId = row[DynamicAssetsTable.projectId],
                        pluginId = row[DynamicAssetsTable.pluginId],
                        properties = propertiesMap,
                        status = RecordStatus.valueOf(row[DynamicAssetsTable.status]),
                        capturedDate = row[DynamicAssetsTable.capturedDate],
                        lat = row[DynamicAssetsTable.lat],
                        long = row[DynamicAssetsTable.long],
                        thermalImagePath = row[DynamicAssetsTable.thermalImagePath],
                        visualImagePath = row[DynamicAssetsTable.visualImagePath],
                        assetImagePath = row[DynamicAssetsTable.assetImagePath],
                        extraImagePath = row[DynamicAssetsTable.extraImagePath]
                    )
                }
        }
    }
}