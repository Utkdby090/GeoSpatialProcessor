package com.geospatial.processing.ui.map

import com.geospatial.processing.domain.map.TileKey
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
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

    @Test
    fun `storing a tile leaves no temp files behind`() {
        TileLoader(dir) { byteArrayOf(4, 5) }.load(key)
        val files = dir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("12.png"), files)
    }

    @Test
    fun `a cache that cannot be written still gives the tile`() {
        val notADirectory = File(dir, "blocked").apply { writeText("x") }
        assertContentEquals(byteArrayOf(7), TileLoader(notADirectory) { byteArrayOf(7) }.load(key))
    }

    @Test
    fun `many threads loading the same tile all get it and leave one file`() {
        val loader = TileLoader(dir) { Thread.sleep(5); byteArrayOf(8, 8) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { pool.submit<ByteArray?> { loader.load(key) } }.map { it.get() }
            results.forEach { assertContentEquals(byteArrayOf(8, 8), it) }
        } finally {
            pool.shutdown()
        }
        assertEquals(listOf("12.png"), dir.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }
}
