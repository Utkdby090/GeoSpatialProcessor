package com.geospatial.processing.domain.map

/** Which way a hemisphere letter points: N/S is a latitude, E/W a longitude. */
enum class Axis { LATITUDE, LONGITUDE }

/** A coordinate read from text: signed decimal degrees, and the axis its hemisphere letter named, if it had one. */
data class ParsedCoordinate(val degrees: Double, val axis: Axis?)

/**
 * Reads coordinates the way survey sheets and GPS tools write them:
 * `27.5123`, `-73.9`, `27°30'44.3"N`, `N 27 30 44.3`, `27:30.738`, `73° 54.2' E`.
 * The value is not range-checked here, so a swapped pair can still be recognised later ([PositionCheck]).
 */
object Coordinates {

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    private val LEADING_HEMISPHERE = Regex("""^([NSEWnsew])\s*""")
    private val TRAILING_HEMISPHERE = Regex("""\s*([NSEWnsew])$""")
    private val SEPARATORS = Regex("""[°º˚'′’"″”:\s]+""")
    private val SHAPE = Regex("""-?\s*\d+(?:[.,]\d+)?(\s+\d+(?:[.,]\d+)?){0,2}""")

    /** Null when [text] is blank or not a coordinate. */
    fun parse(text: String?): ParsedCoordinate? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return if (it.isFinite()) ParsedCoordinate(it, null) else null }

        // One hemisphere letter, at the start or the end.
        val match = LEADING_HEMISPHERE.find(s) ?: TRAILING_HEMISPHERE.find(s)
        val letter = match?.groupValues?.get(1)?.uppercase()?.single()
        val body = if (match != null) s.removeRange(match.range) else s
        val rest = body.replace(SEPARATORS, " ").trim()
        if (!rest.matches(SHAPE)) return null

        val parts = NUMBER.findAll(rest).map { it.value.replace(',', '.').toDouble() }.toList()
        if (parts.isEmpty() || parts.size > 3) return null
        val (deg, min, sec) = Triple(parts[0], parts.getOrElse(1) { 0.0 }, parts.getOrElse(2) { 0.0 })
        if (min >= 60 || sec >= 60) return null
        // Fractions belong on the last part only (27.5° 30' is ambiguous).
        if (parts.dropLast(1).any { it % 1.0 != 0.0 }) return null

        val magnitude = deg + min / 60 + sec / 3600
        val negative = rest.startsWith("-") || letter == 'S' || letter == 'W'
        if (rest.startsWith("-") && (letter == 'S' || letter == 'W')) return null
        val axis = when (letter) {
            'N', 'S' -> Axis.LATITUDE
            'E', 'W' -> Axis.LONGITUDE
            else -> null
        }
        return ParsedCoordinate(if (negative) -magnitude else magnitude, axis)
    }

    /**
     * A latitude/longitude pair from two columns. When the hemisphere letters show the columns were exchanged
     * (an E/W value in the latitude column and N/S in the longitude one), the pair is put the right way round.
     * Null when either value is missing or unreadable.
     */
    fun parsePair(latText: String?, lonText: String?): GeoPoint? {
        val lat = parse(latText) ?: return null
        val lon = parse(lonText) ?: return null
        return if (lat.axis == Axis.LONGITUDE && lon.axis != Axis.LONGITUDE || lon.axis == Axis.LATITUDE && lat.axis != Axis.LATITUDE) {
            GeoPoint(lon.degrees, lat.degrees)
        } else {
            GeoPoint(lat.degrees, lon.degrees)
        }
    }
}
