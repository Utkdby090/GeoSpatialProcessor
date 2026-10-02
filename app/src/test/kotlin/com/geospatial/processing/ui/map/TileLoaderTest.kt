package com.geospatial.processing.ui.map

import com.geospatial.processing.domain.map.TileKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileLoaderTest {
    private val key = TileKey(5, 10, 12)
    private val dir = Files.createTempDirectory("tiles").toFile()

    @Test
    fun `a tile is fetched once and then served from disk`() {
        var fetches = 0
        val loader = TileLoader(dir) { fetches++; byteArrayOf(1, 2, 3) }

        assertContentEquals(byteArrayOf(1, 2, 3), loader.load(key))
        assertContentEquals(byteArrayOf(1, 2, 3), loader.load(key))

        assertEquals(1, fetches)
    }

    @Test
    fun `a failed or empty fetch gives null and caches nothing`() {
        assertNull(TileLoader(dir) { error("offline") }.load(key))
        assertNull(TileLoader(dir) { ByteArray(0) }.load(key))

        // Once the network is back the tile loads normally.
        assertContentEquals(byteArrayOf(9), TileLoader(dir) { byteArrayOf(9) }.load(key))
    }
}
