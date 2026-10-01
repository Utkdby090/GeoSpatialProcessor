package com.geospatial.processing.data.repository

import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetImage
import com.geospatial.processing.domain.model.RecordStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetRepositoryTest {

    private val dir: File = Files.createTempDirectory("repo-test").toFile()
    private val session = ProjectSession.open(dir)
    private val repo = AssetRepository(session.database)

    @AfterTest
    fun cleanup() {
        session.close()
        dir.deleteRecursively()
    }

    private fun asset(name: String, position: Int) = Asset(
        pluginId = "com.geo.telecom", position = position, latitude = 12.5, longitude = 77.5,
        properties = linkedMapOf("towerNumber" to name, "load.Load CKT1" to "120A", "empty" to ""),
    )

    @Test
    fun `round trip keeps every field, properties order and images`() = runBlocking {
        val original = asset("T1", 0).copy(
            status = RecordStatus.READY,
            images = mapOf("THERMAL" to AssetImage("images/x/THERMAL.jpg"), "LOCATION" to AssetImage.Cleared),
        )
        repo.save(original)

        val loaded = repo.getAll().single()
        assertEquals(original, loaded)
        assertEquals(listOf("towerNumber", "load.Load CKT1", "empty"), loaded.properties.keys.toList())
    }

    @Test
    fun `assets come back in position order`() = runBlocking {
        repo.insertAll(listOf(asset("C", 2), asset("A", 0), asset("B", 1)))
        assertEquals(listOf("A", "B", "C"), repo.getAll().map { it.property("towerNumber") })
        assertEquals(3, repo.nextPosition())
    }

    @Test
    fun `next position starts at zero`() = runBlocking {
        assertEquals(0, repo.nextPosition())
    }

    @Test
    fun `saving again updates the asset and replaces its images`() = runBlocking {
        val a = asset("T1", 0).copy(images = mapOf("THERMAL" to AssetImage("images/a.jpg")))
        repo.save(a)
        repo.save(a.copy(properties = a.properties + ("towerNumber" to "T1-renamed"), images = mapOf("RGB_ZOOM" to AssetImage.Cleared)))

        val loaded = repo.getAll().single()
        assertEquals("T1-renamed", loaded.property("towerNumber"))
        assertEquals(mapOf("RGB_ZOOM" to AssetImage.Cleared), loaded.images)
    }

    @Test
    fun `delete and clear all`() = runBlocking {
        val a = asset("T1", 0).copy(images = mapOf("THERMAL" to AssetImage("images/a.jpg")))
        repo.insertAll(listOf(a, asset("T2", 1), asset("T3", 2)))

        repo.delete(a.id, "Tower T1")
        assertEquals(listOf("T2", "T3"), repo.getAll().map { it.property("towerNumber") })

        repo.clearAll()
        assertEquals(emptyList(), repo.getAll())
    }
}
