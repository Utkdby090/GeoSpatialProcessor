package com.geospatial.processing.domain.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh

data class GeoPoint(val lat: Double, val lon: Double)

/** A position in the map view, in pixels from its top-left corner. */
data class ScreenPoint(val x: Double, val y: Double)

/** A map tile: [x] columns from the west and [y] rows from the north at [zoom]. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/** A tile and where its top-left corner lies in the view. [left]/[top] may be negative (partly off-screen). */
data class PlacedTile(val key: TileKey, val left: Double, val top: Double)

/** Web Mercator ("slippy map") maths shared by OpenStreetMap-style tile servers: 256-pixel tiles, integer zoom levels. */
object WebMercator {
    const val TILE_SIZE = 256
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 18

    /** Mercator cannot show the poles. */
    const val MAX_LAT = 85.05112878

    fun worldSize(zoom: Int): Double = TILE_SIZE * (1L shl zoom).toDouble()

    fun x(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun y(lat: Double, zoom: Int): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(zoom)
    }

    fun lon(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / worldSize(zoom)))))
}

/**
 * What part of the world a [width]×[height] pixel view shows: the geographic [center] at integer [zoom].
 * Immutable; panning and zooming return a new viewport.
 */
data class MapViewport(val center: GeoPoint, val zoom: Int, val width: Double, val height: Double) {

    private val originX get() = WebMercator.x(center.lon, zoom) - width / 2
    private val originY get() = WebMercator.y(center.lat, zoom) - height / 2

    fun toScreen(p: GeoPoint) = ScreenPoint(WebMercator.x(p.lon, zoom) - originX, WebMercator.y(p.lat, zoom) - originY)

    fun toGeo(p: ScreenPoint) = GeoPoint(WebMercator.lat(originY + p.y, zoom), WebMercator.lon(originX + p.x, zoom))

    /** The map follows a drag: dragging right by [dx] shows what lies to the west. */
    fun panBy(dx: Double, dy: Double): MapViewport = copy(center = clampLat(toGeo(ScreenPoint(width / 2 - dx, height / 2 - dy))))

    /** Changes the zoom, keeping the spot under [anchor] (default: the middle of the view) where it is. */
    fun withZoom(newZoom: Int, anchor: ScreenPoint = ScreenPoint(width / 2, height / 2)): MapViewport {
        val z = newZoom.coerceIn(WebMercator.MIN_ZOOM, WebMercator.MAX_ZOOM)
        if (z == zoom) return this
        val under = toGeo(anchor)
        val newOriginX = WebMercator.x(under.lon, z) - anchor.x
        val newOriginY = WebMercator.y(under.lat, z) - anchor.y
        val newCenter = GeoPoint(WebMercator.lat(newOriginY + height / 2, z), WebMercator.lon(newOriginX + width / 2, z))
        return copy(center = clampLat(newCenter), zoom = z)
    }

    fun withSize(width: Double, height: Double): MapViewport = copy(width = width, height = height)

    fun centeredOn(p: GeoPoint): MapViewport = copy(center = clampLat(p))

    /** Tiles needed to cover the view, with the east-west wrap applied to their keys. */
    fun visibleTiles(): List<PlacedTile> {
        val tiles = 1 shl zoom
        val ts = WebMercator.TILE_SIZE.toDouble()
        val firstCol = floor(originX / ts).toInt()
        val lastCol = floor((originX + width) / ts).toInt()
        val firstRow = max(0, floor(originY / ts).toInt())
        val lastRow = min(tiles - 1, floor((originY + height) / ts).toInt())
        return buildList {
            for (row in firstRow..lastRow) for (col in firstCol..lastCol) {
                add(PlacedTile(TileKey(zoom, Math.floorMod(col, tiles), row), col * ts - originX, row * ts - originY))
            }
        }
    }

    /** The item nearest to ([x], [y]) within [radius] pixels, or null. */
    fun <T> nearest(items: List<T>, position: (T) -> GeoPoint, x: Double, y: Double, radius: Double): T? {
        fun distSq(item: T): Double = toScreen(position(item)).let { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
        return items.filter { distSq(it) <= radius * radius }.minByOrNull { distSq(it) }
    }

    private fun clampLat(p: GeoPoint) = p.copy(lat = p.lat.coerceIn(-WebMercator.MAX_LAT, WebMercator.MAX_LAT))

    companion object {
        private const val WORLD_ZOOM = 2
        private const val SINGLE_POINT_ZOOM = 15
        private const val MAX_FIT_ZOOM = 17

        /** The closest zoom that shows every point with [padding] pixels to spare; a lone point gets a street-level view. */
        fun fit(points: List<GeoPoint>, width: Double, height: Double, padding: Double = 48.0): MapViewport {
            if (points.isEmpty()) return MapViewport(GeoPoint(20.0, 0.0), WORLD_ZOOM, width, height)
            val minLat = points.minOf { it.lat }
            val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }
            val maxLon = points.maxOf { it.lon }
            val availW = max(1.0, width - 2 * padding)
            val availH = max(1.0, height - 2 * padding)

            var zoom = WebMercator.MIN_ZOOM
            for (z in MAX_FIT_ZOOM downTo WebMercator.MIN_ZOOM) {
                val w = WebMercator.x(maxLon, z) - WebMercator.x(minLon, z)
                val h = WebMercator.y(minLat, z) - WebMercator.y(maxLat, z)
                if (w <= availW && h <= availH) { zoom = z; break }
            }
            if (minLat == maxLat && minLon == maxLon) zoom = SINGLE_POINT_ZOOM
            val cx = (WebMercator.x(minLon, zoom) + WebMercator.x(maxLon, zoom)) / 2
            val cy = (WebMercator.y(minLat, zoom) + WebMercator.y(maxLat, zoom)) / 2
            return MapViewport(GeoPoint(WebMercator.lat(cy, zoom), WebMercator.lon(cx, zoom)), zoom, width, height)
        }
    }
}
