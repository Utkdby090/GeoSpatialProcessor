package com.geospatial.processing.domain.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinatesTest {

    private fun deg(text: String) = Coordinates.parse(text)!!.degrees

    @Test
    fun `plain decimals pass through unchanged`() {
        assertEquals(ParsedCoordinate(27.5123, null), Coordinates.parse("27.5123"))
        assertEquals(-73.9, deg(" -73.9 "))
    }

    @Test
    fun `degrees minutes seconds with symbols, spaces or colons`() {
        val expected = 27 + 30 / 60.0 + 44.3 / 3600
        assertEquals(expected, deg("27°30'44.3\"N"), 1e-9)
        assertEquals(expected, deg("N 27 30 44.3"), 1e-9)
        assertEquals(expected, deg("27° 30′ 44.3″ N"), 1e-9)
        assertEquals(27 + 30.738 / 60, deg("27:30.738"), 1e-9)
        assertEquals(73 + 54.2 / 60, deg("73° 54.2' E"), 1e-9)
    }

    @Test
    fun `south and west are negative and the letter names the axis`() {
        assertEquals(ParsedCoordinate(-(33 + 52 / 60.0), Axis.LATITUDE), Coordinates.parse("33°52'S"))
        assertEquals(Axis.LONGITUDE, Coordinates.parse("w 0 7 39")!!.axis)
        assertEquals(-(0 + 7 / 60.0 + 39 / 3600.0), deg("w 0 7 39"), 1e-9)
    }

    @Test
    fun `anything else is rejected`() {
        listOf("", "   ", "abc", "27.5.1", "27 61", "27 30 75", "27.5 30", "N 27 E", "-27 S", "NaN", "1 2 3 4").forEach {
            assertNull(Coordinates.parse(it), "\"$it\" should not parse")
        }
        assertNull(Coordinates.parse(null))
    }

    @Test
    fun `a pair whose letters show exchanged columns is put the right way round`() {
        assertEquals(GeoPoint(27.5, 73.9), Coordinates.parsePair("73.9 E", "27.5 N"))
        assertEquals(GeoPoint(27.5, 73.9), Coordinates.parsePair("27.5 N", "73.9 E"))
        // Without letters nothing is known yet; PositionCheck looks at the whole project.
        assertEquals(GeoPoint(73.9, 27.5), Coordinates.parsePair("73.9", "27.5"))
        assertNull(Coordinates.parsePair("27.5", ""))
    }
}
