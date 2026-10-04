package com.geospatial.processing.domain.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebMercatorTest {

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
    fun `normalized coordinates span the world from north-west 0,0 to south-east 1,1`() {
        assertEquals(0.0, WebMercator.normalizedX(-180.0), 1e-12)
        assertEquals(1.0, WebMercator.normalizedX(180.0), 1e-12)
        assertEquals(0.5, WebMercator.normalizedY(0.0), 1e-12)
        assertEquals(0.0, WebMercator.normalizedY(WebMercator.MAX_LAT), 1e-9)
        assertEquals(1.0, WebMercator.normalizedY(-90.0), 1e-9)
    }

    @Test
    fun `distances match known figures`() {
        // Delhi to London is about 6,710 km along the great circle.
        assertEquals(6710.0, delhi.distanceKm(london), 15.0)
        assertEquals(0.0, delhi.distanceKm(delhi), 1e-9)
        // One degree of latitude is about 111.2 km.
        assertEquals(111.2, GeoPoint(27.0, 73.0).distanceKm(GeoPoint(28.0, 73.0)), 0.2)
    }

    @Test
    fun `validity and swapping`() {
        assertTrue(GeoPoint(27.5, 73.9).isValid)
        assertFalse(GeoPoint(173.9, 27.5).isValid)
        assertEquals(GeoPoint(73.9, 27.5), GeoPoint(27.5, 73.9).swapped())
    }
}
