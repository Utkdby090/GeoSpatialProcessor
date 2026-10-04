package com.geospatial.processing.domain.map

import kotlin.math.max

/** Something wrong with one item's position. [id] is the caller's key for the item. */
sealed interface PositionIssue {
    val id: String
    val current: GeoPoint

    /**
     * Latitude and longitude look exchanged: [corrected] is the position with them swapped back.
     * [certain] when the stored values cannot be a position at all (e.g. latitude 173.9) but the swapped ones can.
     */
    data class Swapped(override val id: String, override val current: GeoPoint, val corrected: GeoPoint, val certain: Boolean) : PositionIssue

    /** Far from the rest ([distanceKm] from their middle) and swapping doesn't help: a typo or a wrong source. */
    data class Outlier(override val id: String, override val current: GeoPoint, val distanceKm: Double) : PositionIssue

    /** Not a latitude/longitude pair either way round. */
    data class Invalid(override val id: String, override val current: GeoPoint) : PositionIssue
}

/**
 * Finds positions that don't belong with the rest of a project. Items of one project (a line, a site) sit close together,
 * so a point thousands of kilometres from the others is wrong; if swapping its latitude and longitude puts it among them,
 * the columns were exchanged.
 *
 * The majority decides what "the rest" is. With fewer than [MIN_ITEMS_FOR_CLUSTER] items, or when the far points are not
 * clearly outnumbered, only the certain cases (values out of range) are reported.
 */
object PositionCheck {

    const val MIN_ITEMS_FOR_CLUSTER = 3

    /** Points closer than this to the middle are never flagged, however tight the rest are. */
    private const val MIN_THRESHOLD_KM = 25.0

    /** A point further than this many times the typical distance to the middle is flagged. */
    private const val SPREAD_FACTOR = 10.0

    /** Items at 0,0 are "no position yet" in this app and are skipped. */
    fun check(points: Map<String, GeoPoint>): List<PositionIssue> {
        val issues = mutableListOf<PositionIssue>()
        val usable = LinkedHashMap<String, GeoPoint>()
        for ((id, p) in points) {
            if (p.lat == 0.0 && p.lon == 0.0) continue
            if (!p.lat.isFinite() || !p.lon.isFinite()) { issues += PositionIssue.Invalid(id, p); continue }
            when {
                p.isValid -> usable[id] = p
                p.swapped().isValid -> {
                    issues += PositionIssue.Swapped(id, p, p.swapped(), certain = true)
                    usable[id] = p.swapped()
                }
                else -> issues += PositionIssue.Invalid(id, p)
            }
        }
        if (usable.size < MIN_ITEMS_FOR_CLUSTER) return issues

        val middle = GeoPoint(median(usable.values.map { it.lat }), median(usable.values.map { it.lon }))
        val distances = usable.mapValues { (_, p) -> p.distanceKm(middle) }
        val threshold = max(MIN_THRESHOLD_KM, SPREAD_FACTOR * median(distances.values.toList()))

        val far = distances.filterValues { it > threshold }.keys
        // Without a clear majority there is no "rest" to compare with.
        if (far.isEmpty() || far.size * 2 >= usable.size) return issues

        val alreadySwapped = issues.filterIsInstance<PositionIssue.Swapped>().associateBy { it.id }
        for (id in far) {
            val p = usable.getValue(id)
            val original = points.getValue(id)
            val back = p.swapped()
            if (id in alreadySwapped) {
                // Out of range as stored, and the swapped value is far too: report the far position, not the swap.
                issues.remove(alreadySwapped.getValue(id))
                issues += PositionIssue.Outlier(id, original, distances.getValue(id))
            } else if (back.isValid && back.distanceKm(middle) <= threshold) {
                issues += PositionIssue.Swapped(id, original, back, certain = false)
            } else {
                issues += PositionIssue.Outlier(id, original, distances.getValue(id))
            }
        }
        return issues
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
