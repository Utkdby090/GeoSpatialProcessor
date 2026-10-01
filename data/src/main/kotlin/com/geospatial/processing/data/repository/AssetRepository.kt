package com.geospatial.processing.data.repository

import com.geospatial.processing.data.table.AssetImagesTable
import com.geospatial.processing.data.table.AssetsTable
import com.geospatial.processing.data.table.AuditLogs
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.RecordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/** Assets of ONE project database. Every call runs in a transaction bound to [database]. */
class AssetRepository(private val database: Database) {

    suspend fun getAll(): List<Asset> = io {
        val images = AssetImagesTable.selectAll().groupBy(
            keySelector = { it[AssetImagesTable.assetId] },
            valueTransform = { it[AssetImagesTable.slot] to AssetImage(it[AssetImagesTable.relativePath], it[AssetImagesTable.cleared]) },
        )
        AssetsTable.selectAll()
            .orderBy(AssetsTable.position to SortOrder.ASC)
            .map { row -> AssetRows.toAsset(row, images[row[AssetsTable.id]].orEmpty().toMap()) }
    }

    /** Inserts or updates [asset], replacing its image choices. */
    suspend fun save(asset: Asset) = io { AssetRows.upsert(asset) }

    suspend fun insertAll(assets: List<Asset>) = io { assets.forEach { AssetRows.upsert(it) } }

    /** Position for the next asset, so new imports appear after existing ones. */
    suspend fun nextPosition(): Int = io {
        val max = AssetsTable.position.max()
        (AssetsTable.select(max).singleOrNull()?.get(max) ?: -1) + 1
    }

    suspend fun delete(assetId: String, label: String) = io {
        AuditLogs.insert {
            it[action] = "DELETE_RECORD"
            it[details] = "Deleted $label (ID: $assetId)"
        }
        AssetsTable.deleteWhere { id eq assetId } // images cascade
    }

    suspend fun clearAll() = io {
        AuditLogs.insert {
            it[action] = "SYSTEM_PURGE"
            it[details] = "User executed clearAll(). All records wiped."
        }
        AssetImagesTable.deleteAll()
        AssetsTable.deleteAll()
    }

    suspend fun logAuditAction(action: String, details: String) = io {
        AuditLogs.insert {
            it[AuditLogs.action] = action
            it[AuditLogs.details] = details.take(500)
        }
    }

    private suspend fun <T> io(block: Transaction.() -> T): T =
        withContext(Dispatchers.IO) { transaction(database) { block() } }
}

/** Row mapping shared by [AssetRepository] and the legacy migration (which runs inside its own transaction). */
internal object AssetRows {
    private val json = Json

    fun toAsset(row: ResultRow, images: Map<String, AssetImage>) = Asset(
        id = row[AssetsTable.id],
        pluginId = row[AssetsTable.pluginId],
        position = row[AssetsTable.position],
        status = runCatching { RecordStatus.valueOf(row[AssetsTable.status]) }.getOrDefault(RecordStatus.DRAFT),
        latitude = row[AssetsTable.latitude],
        longitude = row[AssetsTable.longitude],
        properties = json.decodeFromString<Map<String, String>>(row[AssetsTable.propertiesJson]),
        images = images,
    )

    /** Must run inside a transaction. */
    fun upsert(asset: Asset) {
        val now = LocalDateTime.now()
        AssetsTable.upsert(onUpdateExclude = listOf(AssetsTable.createdAt)) {
            it[id] = asset.id
            it[pluginId] = asset.pluginId
            it[position] = asset.position
            it[status] = asset.status.name
            it[latitude] = asset.latitude
            it[longitude] = asset.longitude
            it[propertiesJson] = json.encodeToString(asset.properties)
            it[updatedAt] = now
        }
        AssetImagesTable.deleteWhere { assetId eq asset.id }
        asset.images.forEach { (slotId, image) ->
            AssetImagesTable.insert {
                it[assetId] = asset.id
                it[slot] = slotId
                it[relativePath] = image.relativePath
                it[cleared] = image.cleared
            }
        }
    }
}
