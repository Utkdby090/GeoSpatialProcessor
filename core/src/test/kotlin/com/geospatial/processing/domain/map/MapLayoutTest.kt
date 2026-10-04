package com.geospatial.processing.domain.map

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLayoutTest {

    @Test
    fun `only real positions are plottable`() {
        assertTrue(MapLayout.plottable(GeoPoint(28.6, 77.2)))
        assertFalse(MapLayout.plottable(GeoPoint(0.0, 0.0)), "0,0 means no position yet")
        assertFalse(MapLayout.plottable(GeoPoint(Double.NaN, 77.2)))
        assertFalse(MapLayout.plottable(GeoPoint(28.6, Double.POSITIVE_INFINITY)))
        assertFalse(MapLayout.plottable(GeoPoint(173.9, 28.6)), "latitude out of range (swapped columns)")
        assertFalse(MapLayout.plottable(GeoPoint(28.6, 190.0)))
        assertTrue(MapLayout.plottable(GeoPoint(0.0, 77.2)), "on the equator is a real position")
    }

    @Test
    fun `no points give no bounds`() {
        assertNull(MapLayout.bounds(emptyList()))
    }

    @Test
    fun `a single point gets the minimum span centred on it`() {
        val p = GeoPoint(28.6139, 77.2090)
        val b = assertNotNull(MapLayout.bounds(listOf(p)))
        assertEquals(MapLayout.MIN_FIT_SPAN, b.right - b.left, 1e-12)
        assertEquals(MapLayout.MIN_FIT_SPAN, b.bottom - b.top, 1e-12)
        assertEquals(WebMercator.normalizedX(p.lon), (b.left + b.right) / 2, 1e-12)
        assertEquals(WebMercator.normalizedY(p.lat), (b.top + b.bottom) / 2, 1e-12)
    }

    @Test
    fun `bounds hold every point`() {
        val points = listOf(GeoPoint(28.0, 77.0), GeoPoint(29.0, 78.5), GeoPoint(27.5, 76.8))
        val b = assertNotNull(MapLayout.bounds(points))
        for (p in points) {
            val x = WebMercator.normalizedX(p.lon)
            val y = WebMercator.normalizedY(p.lat)
            assertTrue(x in b.left..b.right && y in b.top..b.bottom, "$p outside $b")
        }
        assertTrue(b.top < b.bottom, "north is the smaller y")
    }

    @Test
    fun `bounds never leave the map, even at the edge of the world`() {
        val b = assertNotNull(MapLayout.bounds(listOf(GeoPoint(89.9, -180.0))))
        assertTrue(b.left >= 0.0 && b.top >= 0.0 && b.right <= 1.0 && b.bottom <= 1.0, "$b")
    }

    @Test
    fun `points that share a spot are spread on a circle, others are left alone`() {
        val spot = GeoPoint(28.6139, 77.2090)
        val out = MapLayout.fanOut(
            linkedMapOf(
                "a" to spot,
                "b" to GeoPoint(spot.lat + 1e-7, spot.lon), // same to about a centimetre
                "c" to spot,
                "alone" to GeoPoint(28.7, 77.3),
            )
        )
        assertEquals(setOf("a", "b", "c"), out.keys)
        out.values.forEach { (x, y) -> assertEquals(1.0, hypot(x, y), 1e-9) }
        val (firstX, firstY) = out.getValue("a")
        assertTrue(abs(firstX) < 1e-9 && abs(firstY + 1) < 1e-9, "the first pin goes straight up")
        assertEquals(3, out.values.toSet().size, "every pin gets its own direction")
    }

    @Test
    fun `fan-out of nothing is nothing`() {
        assertTrue(MapLayout.fanOut(emptyMap()).isEmpty())
    }
}
