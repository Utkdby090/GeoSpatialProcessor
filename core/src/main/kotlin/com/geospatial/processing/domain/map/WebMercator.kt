package com.geospatial.processing.domain.map

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double) {

    /** Great-circle distance in kilometres (haversine on a spherical Earth; well within 0.5 % for this use). */
    fun distanceKm(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** True when the values are a real latitude/longitude pair. */
    val isValid: Boolean get() = lat in -90.0..90.0 && lon in -180.0..180.0

    /** The same numbers with latitude and longitude exchanged. */
    fun swapped() = GeoPoint(lon, lat)

    private companion object {
        const val EARTH_RADIUS_KM = 6371.0088
    }
}

/** A map tile: [x] columns from the west and [y] rows from the north at [zoom]. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/** Web Mercator ("slippy map") maths shared by OpenStreetMap-style tile servers: 256-pixel tiles. */
object WebMercator {
    const val TILE_SIZE = 256
    const val MAX_ZOOM = 18

    /** Mercator cannot show the poles. */
    const val MAX_LAT = 85.05112878

    fun worldSize(zoom: Int): Double = TILE_SIZE * (1L shl zoom).toDouble()

    fun x(lon: Double, zoom: Int): Double = normalizedX(lon) * worldSize(zoom)

    fun y(lat: Double, zoom: Int): Double = normalizedY(lat) * worldSize(zoom)

    /** West edge 0, east edge 1. */
    fun normalizedX(lon: Double): Double = (lon + 180.0) / 360.0

    /** North edge 0, south edge 1. */
    fun normalizedY(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun lon(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / worldSize(zoom)))))
}
