package com.geospatial.processing.domain.thermal

/** Colour ramps for showing temperatures. Each ramp is evenly spaced RGB stops from cold to hot. */
enum class ThermalPalette(private val stops: List<Int>) {
    IRON(listOf(0x00000A, 0x20006E, 0x7A0A8C, 0xC8321E, 0xF08C00, 0xFFD23C, 0xFFFFE6)),
    RAINBOW(listOf(0x0000A0, 0x0064FF, 0x00D2C8, 0x32DC32, 0xFFEB00, 0xFF7800, 0xDC0000)),
    GRAYSCALE(listOf(0x000000, 0xFFFFFF));

    /** Opaque ARGB colour for [fraction] of the way from cold (0) to hot (1); values outside are clamped. */
    fun argb(fraction: Float): Int {
        val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val scaled = f * (stops.size - 1)
        val i = scaled.toInt().coerceAtMost(stops.size - 2)
        val t = scaled - i
        fun channel(shift: Int): Int {
            val a = (stops[i] shr shift) and 0xFF
            val b = (stops[i + 1] shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /**
     * ARGB pixels for [frame], scaled between [min] and [max] (default: the frame's own range).
     * Pixels without a reading are transparent.
     */
    fun render(frame: ThermalFrame, min: Float? = null, max: Float? = null): IntArray {
        val stats = frame.stats()
        val lo = min ?: stats?.min ?: 0f
        val hi = max ?: stats?.max ?: 1f
        val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        return IntArray(frame.tempsC.size) { i ->
            val t = frame.tempsC[i]
            if (t.isNaN()) 0 else argb((t - lo) / span)
        }
    }
}
