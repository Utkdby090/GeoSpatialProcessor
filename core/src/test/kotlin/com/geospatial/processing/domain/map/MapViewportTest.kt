package com.geospatial.processing.domain.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapViewportTest {

    private val london = GeoPoint(51.5074, -0.1278)
    private val delhi = GeoPoint(28.6139, 77.2090)

    private fun tileOf(p: GeoPoint, zoom: Int) =
        TileKey(zoom, (WebMercator.x(p.lon, zoom) / 256).toInt(), (WebMercator.y(p.lat, zoom) / 256).toInt())

    @Test
    fun `known places land on the tiles every slippy map uses`() {
        assertEquals(TileKey(10, 511, 340), tileOf(london, 10))
        // Delhi worked out by hand: x = (77.209 + 180) / 360 * 256 = 182.9, y = (0.5 - ln(2.838) / (4 * pi)) * 256 = 106.7.
        assertEquals(TileKey(8, 182, 106), tileOf(delhi, 8))
    }

    @Test
    fun `world pixels convert back to the same coordinates`() {
        for (z in listOf(1, 8, 15, 18)) {
            assertEquals(delhi.lat, WebMercator.lat(WebMercator.y(delhi.lat, z), z), 1e-9)
            assertEquals(delhi.lon, WebMercator.lon(WebMercator.x(delhi.lon, z), z), 1e-9)
        }
    }

    @Test
    fun `the view centre is in the middle of the screen and screen maps back to geo`() {
        val vp = MapViewport(delhi, 12, 800.0, 600.0)

        val mid = vp.toScreen(delhi)
        assertEquals(400.0, mid.x, 1e-6)
        assertEquals(300.0, mid.y, 1e-6)

        val nearby = GeoPoint(28.65, 77.25)
        val back = vp.toGeo(vp.toScreen(nearby))
        assertEquals(nearby.lat, back.lat, 1e-9)
        assertEquals(nearby.lon, back.lon, 1e-9)
    }

    @Test
    fun `dragging moves the map with the finger`() {
        val vp = MapViewport(delhi, 12, 800.0, 600.0)
        val before = vp.toScreen(delhi)

        val after = vp.panBy(100.0, -50.0).toScreen(delhi)

        assertEquals(before.x + 100.0, after.x, 1e-6)
        assertEquals(before.y - 50.0, after.y, 1e-6)
    }

    @Test
    fun `panning never goes past the poles`() {
        val far = MapViewport(GeoPoint(80.0, 0.0), 3, 800.0, 600.0).panBy(0.0, 100000.0)

        assertTrue(far.center.lat <= WebMercator.MAX_LAT)
        assertTrue(far.center.lat >= -WebMercator.MAX_LAT)
    }

    @Test
    fun `zooming keeps the point under the cursor in place`() {
        val vp = MapViewport(delhi, 10, 800.0, 600.0)
        val anchor = ScreenPoint(650.0, 120.0)
        val under = vp.toGeo(anchor)

        for (z in listOf(11, 14, 6)) {
            val zoomed = vp.withZoom(z, anchor)
            val s = zoomed.toScreen(under)
            assertEquals(anchor.x, s.x, 1e-4)
            assertEquals(anchor.y, s.y, 1e-4)
            assertEquals(z, zoomed.zoom)
        }
    }

    @Test
    fun `zoom is limited to what tile servers have`() {
        val vp = MapViewport(delhi, 10, 800.0, 600.0)

        assertEquals(WebMercator.MAX_ZOOM, vp.withZoom(99).zoom)
        assertEquals(WebMercator.MIN_ZOOM, vp.withZoom(-4).zoom)
    }

    @Test
    fun `visible tiles cover the whole view without repeats`() {
        val tiles = MapViewport(delhi, 12, 900.0, 700.0).visibleTiles()

        assertTrue(tiles.minOf { it.left } <= 0.0)
        assertTrue(tiles.minOf { it.top } <= 0.0)
        assertTrue(tiles.maxOf { it.left } + 256 >= 900.0)
        assertTrue(tiles.maxOf { it.top } + 256 >= 700.0)
        assertEquals(tiles.size, tiles.map { it.key }.toSet().size)
    }

    @Test
    fun `tiles wrap around the antimeridian but stop at the poles`() {
        val edge = MapViewport(GeoPoint(0.0, 179.9), 3, 1200.0, 400.0).visibleTiles()
        assertTrue(edge.any { it.key.x == 0 } && edge.any { it.key.x == 7 }, "both sides of the date line are needed")
        assertTrue(edge.all { it.key.x in 0..7 })

        val pole = MapViewport(GeoPoint(80.0, 0.0), 2, 400.0, 1600.0).visibleTiles()
        assertTrue(pole.all { it.key.y in 0..3 })
    }

    @Test
    fun `fit shows every point inside the padding and is the closest such zoom`() {
        val points = listOf(GeoPoint(12.90, 77.50), GeoPoint(13.10, 77.70), GeoPoint(12.95, 77.62))
        val vp = MapViewport.fit(points, 900.0, 600.0, padding = 40.0)

        fun inside(v: MapViewport, p: GeoPoint) = v.toScreen(p).let { it.x in 40.0..860.0 && it.y in 40.0..560.0 }
        assertTrue(points.all { inside(vp, it) })
        assertTrue(points.any { !inside(vp.withZoom(vp.zoom + 1), it) }, "one level closer would cut a point off")
    }

    @Test
    fun `fit handles no points and a single point`() {
        assertEquals(2, MapViewport.fit(emptyList(), 800.0, 600.0).zoom)

        val single = MapViewport.fit(listOf(delhi), 800.0, 600.0)
        assertEquals(15, single.zoom)
        assertEquals(delhi.lat, single.center.lat, 1e-9)
        assertEquals(delhi.lon, single.center.lon, 1e-9)
    }

    @Test
    fun `nearest pin within the radius wins, nothing outside it`() {
        val a = GeoPoint(28.6139, 77.2090)
        val b = GeoPoint(28.6141, 77.2093)
        val vp = MapViewport(a, 17, 800.0, 600.0)
        val pa = vp.toScreen(a)
        val items = listOf("a" to a, "b" to b)

        assertEquals("a", vp.nearest(items, { it.second }, pa.x + 1, pa.y + 1, 14.0)?.first)
        assertNull(vp.nearest(items.take(1), { it.second }, pa.x + 100, pa.y, 14.0))
    }
}
