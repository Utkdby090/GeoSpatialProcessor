package com.geospatial.processing.ui.map

import com.geospatial.processing.domain.map.TileKey
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun `many threads loading the same tile all get it, download it once and leave one file`() {
        repeat(25) { round -> // a race shows up in some rounds only
            val tiles = Files.createTempDirectory("tiles-race").toFile()
            val fetches = AtomicInteger()
            val loader = TileLoader(tiles) { fetches.incrementAndGet(); Thread.sleep(5); byteArrayOf(8, 8) }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(16)
            try {
                val jobs = (1..32).map { pool.submit<ByteArray?> { start.await(); loader.load(key) } }
                start.countDown()
                jobs.map { it.get(30, TimeUnit.SECONDS) }.forEach { assertContentEquals(byteArrayOf(8, 8), it, "round $round") }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, fetches.get(), "round $round: one download for simultaneous requests")
            assertEquals(listOf("12.png"), tiles.walkTopDown().filter { it.isFile }.map { it.name }.toList(), "round $round")
            tiles.deleteRecursively()
        }
    }

    @Test
    fun `different tiles load in parallel, one slow tile does not hold the others up`() {
        val slow = TileKey(5, 1, 1)
        val fast = TileKey(5, 2, 2)
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val loader = TileLoader(dir) { k ->
            if (k == slow) { slowStarted.countDown(); releaseSlow.await(30, TimeUnit.SECONDS) }
            byteArrayOf(k.x.toByte())
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val slowJob = pool.submit<ByteArray?> { loader.load(slow) }
            assertTrue(slowStarted.await(10, TimeUnit.SECONDS))
            // The fast tile must finish while the slow one is still downloading.
            assertContentEquals(byteArrayOf(2), pool.submit<ByteArray?> { loader.load(fast) }.get(10, TimeUnit.SECONDS))
            assertFalse(slowJob.isDone)
            releaseSlow.countDown()
            assertContentEquals(byteArrayOf(1), slowJob.get(10, TimeUnit.SECONDS))
        } finally {
            releaseSlow.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `callers waiting on a download that fails are released with null, and the next call tries again`() {
        val fetches = AtomicInteger()
        val gate = CountDownLatch(1)
        val loader = TileLoader(dir) {
            if (fetches.incrementAndGet() == 1) { gate.await(30, TimeUnit.SECONDS); error("server down") } else byteArrayOf(6)
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val first = pool.submit<ByteArray?> { loader.load(key) }
            while (fetches.get() == 0) Thread.sleep(1)
            val waiters = (1..3).map { pool.submit<ByteArray?> { loader.load(key) } }
            Thread.sleep(50)
            gate.countDown()
            assertNull(first.get(10, TimeUnit.SECONDS))
            waiters.forEach { assertNull(it.get(10, TimeUnit.SECONDS)) }
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
        assertContentEquals(byteArrayOf(6), loader.load(key), "the failure was not cached")
    }

    @Test
    fun `an empty leftover file from a crash is treated as a miss and healed`() {
        File(dir, "5/10").mkdirs()
        File(dir, "5/10/12.png").writeBytes(ByteArray(0))

        assertContentEquals(byteArrayOf(3), TileLoader(dir) { byteArrayOf(3) }.load(key))
        assertContentEquals(byteArrayOf(3), File(dir, "5/10/12.png").readBytes())
    }

    @Test
    fun `a cached tile is served without calling the network`() {
        TileLoader(dir) { byteArrayOf(5) }.load(key)
        assertContentEquals(byteArrayOf(5), TileLoader(dir) { error("must not be called") }.load(key))
    }
}
