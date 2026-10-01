package com.geospatial.processing.utils

import com.geospatial.processing.data.database.ProjectSession
import com.geospatial.processing.data.images.ImageStore
import com.geospatial.processing.data.repository.AssetRepository
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.AssetImage
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A real project (database + stored image) survives export to .geox and import elsewhere. */
class GeoxRoundTripTest {

    @Test
    fun `assets and their images survive export and import`() = runBlocking {
        val workspace = Files.createTempDirectory("ws").toFile()
        val project = checkNotNull(ProjectManager.createNewProject(workspace, "Line A", "com.geo.telecom"))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7)

        ProjectSession.open(project).use { session ->
            val asset = Asset(pluginId = "com.geo.telecom", position = 0, latitude = 1.0, longitude = 2.0)
            val path = ImageStore(project).write(asset.id, "THERMAL", jpeg)
            AssetRepository(session.database).save(asset.copy(images = mapOf("THERMAL" to AssetImage(path))))
        }

        val geox = File(Files.createTempDirectory("out").toFile(), "Line_A.geox")
        check(ProjectArchiver.exportProject(project, geox))
        val imported = assertIs<ProjectArchiver.ImportResult.Success>(
            ProjectArchiver.importProject(geox, Files.createTempDirectory("ws2").toFile())
        ).projectDir

        ProjectSession.open(imported).use { session ->
            val asset = AssetRepository(session.database).getAll().single()
            val image = File(imported, asset.images.getValue("THERMAL").relativePath!!)
            assertContentEquals(jpeg, image.readBytes())
            assertEquals(imported.canonicalFile, image.canonicalFile.parentFile.parentFile.parentFile, "path is relative to the project")
        }
    }
}
