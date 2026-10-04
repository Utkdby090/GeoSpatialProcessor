package com.geospatial.processing.domain.map

import com.geospatial.processing.domain.map.PositionIssue.Invalid
import com.geospatial.processing.domain.map.PositionIssue.Outlier
import com.geospatial.processing.domain.map.PositionIssue.Swapped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PositionCheckTest {

    /** Ten towers about 400 m apart heading north-east from Bhadla, Rajasthan. */
    private val line = (0 until 10).associate { "t$it" to GeoPoint(27.50 + it * 0.003, 71.90 + it * 0.003) }

    @Test
    fun `a clean line has no issues`() {
        assertEquals(emptyList(), PositionCheck.check(line))
    }

    @Test
    fun `a tower with latitude and longitude exchanged is found and the fix puts it back on the line`() {
        val points = line + ("t3" to line.getValue("t3").swapped())

        val issue = assertIs<Swapped>(PositionCheck.check(points).single())

        assertEquals("t3", issue.id)
        assertEquals(line.getValue("t3"), issue.corrected)
        assertEquals(false, issue.certain)
    }

    @Test
    fun `values that cannot be a position are certain swaps even in a tiny project`() {
        val points = mapOf("a" to GeoPoint(173.9, 27.5), "b" to GeoPoint(27.5, 73.9))

        val issue = assertIs<Swapped>(PositionCheck.check(points).single())

        assertEquals(GeoPoint(27.5, 173.9), issue.corrected)
        assertTrue(issue.certain)
    }

    @Test
    fun `a far point that swapping does not help is an outlier`() {
        val points = line + ("t5" to GeoPoint(12.97, 77.59)) // Bengaluru

        val issue = assertIs<Outlier>(PositionCheck.check(points).single())

        assertEquals("t5", issue.id)
        assertTrue(issue.distanceKm > 1500)
    }

    @Test
    fun `nonsense either way round is invalid and positions not yet set are skipped`() {
        val points = line + ("bad" to GeoPoint(200.0, 300.0)) + ("unset" to GeoPoint(0.0, 0.0))

        assertEquals(listOf<PositionIssue>(Invalid("bad", GeoPoint(200.0, 300.0))), PositionCheck.check(points))
    }

    @Test
    fun `without a clear majority nothing is guessed`() {
        val half = line.entries.take(5).associate { it.key to it.value } +
            line.entries.drop(5).associate { it.key to it.value.swapped() }

        assertEquals(emptyList(), PositionCheck.check(half))
    }

    @Test
    fun `a long line is not flagged for its own length`() {
        // 300 km of line: towers every 3 km.
        val long = (0 until 100).associate { "t$it" to GeoPoint(27.0 + it * 0.027, 73.0) }

        assertEquals(emptyList(), PositionCheck.check(long))
    }

    @Test
    fun `a whole line with latitude and longitude exchanged is recognised from where it lands`() {
        val allSwapped = line.mapValues { it.value.swapped() } // Rajasthan swapped lands in the Barents Sea

        assertEquals(emptyList(), PositionCheck.check(allSwapped), "every point agrees with the others")
        assertTrue(PositionCheck.likelyAllSwapped(allSwapped.values))
        assertFalse(PositionCheck.likelyAllSwapped(line.values))
        // A polar site whose swapped values are not a position at all (McMurdo) is left alone.
        assertFalse(PositionCheck.likelyAllSwapped(listOf(GeoPoint(-77.8, 166.7))))
    }

    @Test
    fun `a photo's GPS fix shows when one position is swapped`() {
        val tower = GeoPoint(27.5, 71.9)
        val photo = GeoPoint(27.5003, 71.9004)

        assertTrue(PositionCheck.swappedComparedTo(tower.swapped(), photo))
        assertFalse(PositionCheck.swappedComparedTo(tower, photo))
        assertFalse(PositionCheck.swappedComparedTo(GeoPoint(12.9, 77.6), photo), "far either way: not a swap")
    }
}
