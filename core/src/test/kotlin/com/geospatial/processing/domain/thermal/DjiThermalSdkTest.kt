package com.geospatial.processing.domain.thermal

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unpacking the bundled DLLs: a damaged or half-written copy on disk must never be what gets loaded. */
class DjiThermalSdkTest {

    private val temp = Files.createTempDirectory("dji-extract").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun bundled(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/dji-sdk/windows-x64/$name")) { "missing bundled $name" }.use { it.readBytes() }

    @Test
    fun `every library the SDK needs is bundled and non-empty`() {
        for (name in DjiThermalSdk.FILES) assertTrue(bundled(name).isNotEmpty(), name)
        assertTrue("libv_list.ini" in DjiThermalSdk.FILES && "libdirp.dll" in DjiThermalSdk.FILES)
    }

    @Test
    fun `the licence text ships with the binaries`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/dji-sdk/LICENSE-DJI-Thermal-SDK.txt")).use { it.readBytes().decodeToString() }
        assertTrue("MIT" in text && "libv_" in text)
    }

    @Test
    fun `a fresh folder gets every file, byte for byte`() {
        val dir = assertNotNull(DjiThermalSdk.extractBundled(temp))
        for (name in DjiThermalSdk.FILES) assertContentEquals(bundled(name), File(dir, name).readBytes(), name)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") }, "no temp files left behind")
    }

    @Test
    fun `a second extraction leaves intact files alone`() {
        val dir = assertNotNull(DjiThermalSdk.extractBundled(temp))
        val stamps = DjiThermalSdk.FILES.associateWith { File(dir, it).lastModified() }
        Thread.sleep(50)
        DjiThermalSdk.extractBundled(temp)
        for (name in DjiThermalSdk.FILES) assertEquals(stamps.getValue(name), File(dir, name).lastModified(), "$name was rewritten")
    }

    @Test
    fun `a zero-length or truncated file is replaced`() {
        val dir = assertNotNull(DjiThermalSdk.extractBundled(temp))
        File(dir, "libdirp.dll").writeBytes(ByteArray(0))
        File(dir, "libv_dirp.dll").let { it.writeBytes(it.readBytes().copyOf(1000)) }

        DjiThermalSdk.extractBundled(temp)

        assertContentEquals(bundled("libdirp.dll"), File(dir, "libdirp.dll").readBytes())
        assertContentEquals(bundled("libv_dirp.dll"), File(dir, "libv_dirp.dll").readBytes())
    }

    @Test
    fun `a same-size corrupted file is replaced too`() {
        val dir = assertNotNull(DjiThermalSdk.extractBundled(temp))
        val target = File(dir, "libv_iirp.dll")
        val damaged = target.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        target.writeBytes(damaged)

        DjiThermalSdk.extractBundled(temp)

        assertContentEquals(bundled("libv_iirp.dll"), target.readBytes())
    }

    @Test
    fun `a missing file is restored and a leftover temp file does no harm`() {
        val dir = assertNotNull(DjiThermalSdk.extractBundled(temp))
        File(dir, "libexif.dll").delete()
        File(dir, "libdirp.dll.99999.tmp").writeBytes(byteArrayOf(1, 2, 3))

        DjiThermalSdk.extractBundled(temp)

        assertContentEquals(bundled("libexif.dll"), File(dir, "libexif.dll").readBytes())
    }

    @Test
    fun `several threads extracting into the same fresh folder all end up with intact files`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(16) { Callable { DjiThermalSdk.extractBundled(temp) } }, 2, TimeUnit.MINUTES).map { it.get() }
            // Concurrent extraction may fail for a racing loser, but it must never leave damaged files, and someone must win.
            assertTrue(results.any { it != null }, "nobody could extract")
        } finally {
            pool.shutdownNow()
        }
        val dir = DjiThermalSdk.extractBundled(temp)!!
        for (name in DjiThermalSdk.FILES) assertContentEquals(bundled(name), File(dir, name).readBytes(), name)
    }

    @Test
    fun `an unusable target location gives null instead of an exception`() {
        val notADirectory = File(temp, "afile").also { it.writeText("x") }
        assertNull(DjiThermalSdk.extractBundled(notADirectory))
    }
}
