package com.geospatial.processing.domain.thermal

import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SDK is native code. A corrupt file that makes it fault would not throw, it would kill the whole application,
 * so damaged R-JPEGs are thrown at it here. The run is seeded, so a failure is reproducible from the printed seed.
 *
 * If this test ever crashes the Gradle test worker, the decoder needs process isolation (see the report).
 */
class DjiFuzzTest {

    private val seed = 20261004L

    private var decoded = 0
    private var refused = 0
    private var nativeFaults = 0

    private fun attempt(label: String, bytes: ByteArray) {
        val outcome = runCatching { DjiDecoder().decode(bytes) }
        val failure = outcome.exceptionOrNull()
        if (failure == null) decoded++ else {
            assertTrue(failure is ThermalDecodeException, "$label threw ${failure::class.simpleName}: ${failure.message} (seed $seed)")
            if (failure.cause is Error) nativeFaults++ else refused++
        }
    }

    /** A caught native fault must not leave the SDK unable to read a good picture, or reading it differently. */
    @AfterTest
    fun theSdkStillWorksAndAgrees() {
        println("FUZZ decoded=$decoded refused=$refused nativeFaultsCaught=$nativeFaults")
        val good = DjiSamples.firstBytes()
        val expected = checkNotNull(javaClass.getResourceAsStream("/thermal/dji-reference-stats.csv")).bufferedReader().readLines()
            .first { it.startsWith("M4T,DJI_0001") }.split(',')
        val stats = DjiDecoder().decode(good).stats()!!
        assertEquals(expected[3].toDouble(), stats.min.toDouble(), 6e-4)
        assertEquals(expected[4].toDouble(), stats.max.toDouble(), 6e-4)
        assertEquals(expected[5].toDouble(), stats.mean.toDouble(), 6e-4)
    }

    @Test
    fun `random byte flips in the headers never crash`() {
        val original = DjiSamples.firstBytes()
        val rnd = Random(seed)
        repeat(120) { n ->
            val b = original.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(minOf(b.size, 8192))] = rnd.nextInt(256).toByte() }
            attempt("header flip #$n", b)
        }
    }

    @Test
    fun `random byte flips anywhere in the file never crash`() {
        val original = DjiSamples.firstBytes()
        val rnd = Random(seed + 1)
        repeat(100) { n ->
            val b = original.copyOf()
            repeat(1 + rnd.nextInt(64)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            attempt("flip #$n", b)
        }
    }

    @Test
    fun `zeroed and 0xFF-filled regions never crash`() {
        val original = DjiSamples.firstBytes()
        val rnd = Random(seed + 2)
        repeat(60) { n ->
            val b = original.copyOf()
            val start = rnd.nextInt(b.size)
            val len = minOf(b.size - start, 1 + rnd.nextInt(4096))
            val fill = if (n % 2 == 0) 0 else 0xFF.toByte().toInt()
            java.util.Arrays.fill(b, start, start + len, fill.toByte())
            attempt("fill #$n", b)
        }
    }

    @Test
    fun `truncation every 16 KB of the file never crashes`() {
        val original = DjiSamples.firstBytes()
        var cut = 0
        while (cut < original.size) {
            attempt("truncated at $cut", original.copyOf(cut))
            cut += 16 * 1024
        }
    }

    @Test
    fun `swapping the thermal payload of one camera into another's file never crashes`() {
        DjiSamples.requireSdk()
        val a = DjiSamples.files("M4T").firstOrNull()?.readBytes()
        val b = DjiSamples.files("H30T").firstOrNull()?.readBytes()
        org.junit.jupiter.api.Assumptions.assumeTrue(a != null && b != null)
        // first half of one camera's file followed by the second half of another's
        attempt("splice A|B", a!!.copyOf(a.size / 2) + b!!.copyOfRange(b.size / 2, b.size))
        attempt("splice B|A", b.copyOf(b.size / 2) + a.copyOfRange(a.size / 2, a.size))
    }

    @Test
    fun `random garbage that starts like a JPEG never crashes`() {
        DjiSamples.requireSdk()
        val rnd = Random(seed + 3)
        repeat(200) { n ->
            val b = ByteArray(1 + rnd.nextInt(20_000)).also { rnd.nextBytes(it) }
            if (b.size > 3) { b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte() }
            attempt("garbage #$n", b)
        }
    }
}
