package com.geospatial.processing.domain.map

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/** An area of the map in normalized Web Mercator coordinates (0..1 from the west and from the north). */
data class MapBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)

/** Where pins go on the map: which positions can be drawn, how to frame them, and how to spread pins that share a spot. */
object MapLayout {

    /** Fitting never zooms closer than this share of the world (about 800 m), so a lone tower still shows its surroundings. */
    const val MIN_FIT_SPAN = 0.00002

    /**
     * True when [p] can be drawn: a real latitude/longitude pair (so never NaN or infinite, which would crash layout)
     * and not 0,0, which this app uses for "no position yet".
     */
    fun plottable(p: GeoPoint): Boolean = p.isValid && !(p.lat == 0.0 && p.lon == 0.0)

    /** The smallest area holding every point, grown to at least [MIN_FIT_SPAN] each way; null for none. */
    fun bounds(points: Collection<GeoPoint>): MapBounds? {
        if (points.isEmpty()) return null
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in points) {
            val x = WebMercator.normalizedX(p.lon)
            val y = WebMercator.normalizedY(p.lat)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        // Grow around the points rather than rebuilding from the centre, so rounding never cuts off the outermost ones.
        val padX = max(0.0, MIN_FIT_SPAN - (maxX - minX)) / 2
        val padY = max(0.0, MIN_FIT_SPAN - (maxY - minY)) / 2
        return MapBounds(
            (minX - padX).coerceAtLeast(0.0), (minY - padY).coerceAtLeast(0.0),
            (maxX + padX).coerceAtMost(1.0), (maxY + padY).coerceAtMost(1.0),
        )
    }

    /**
     * Directions for items that share a position (the same to about a metre), as unit vectors around a circle starting
     * at the top, so their pins can be spread apart and each one seen and clicked. Items with a position of their own get
     * no entry. Order within a group follows [points], so the spread is stable between redraws.
     */
    fun fanOut(points: Map<String, GeoPoint>): Map<String, Pair<Double, Double>> {
        val groups = LinkedHashMap<Pair<Long, Long>, MutableList<String>>()
        for ((id, p) in points) {
            groups.getOrPut((p.lat * 1e5).roundToLong() to (p.lon * 1e5).roundToLong()) { mutableListOf() } += id
        }
        val result = HashMap<String, Pair<Double, Double>>()
        for (ids in groups.values) {
            if (ids.size < 2) continue
            ids.forEachIndexed { i, id ->
                val angle = 2 * PI * i / ids.size - PI / 2
                result[id] = cos(angle) to sin(angle)
            }
        }
        return result
    }
}
