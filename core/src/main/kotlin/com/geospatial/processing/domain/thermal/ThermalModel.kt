package com.geospatial.processing.domain.thermal

/** Why a thermal image could not be turned into temperatures; [message] is safe to show to the user. */
class ThermalDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Camera and scene settings a temperature depends on.
 * Temperatures are °C, distance is metres, humidity is percent (0–100).
 */
data class ThermalParams(
    val emissivity: Double,
    val reflectedTempC: Double,
    val atmosphericTempC: Double,
    val distanceM: Double,
    val humidityPct: Double,
    val irWindowTempC: Double,
    val irWindowTransmission: Double,
    val planckR1: Double,
    val planckR2: Double,
    val planckB: Double,
    val planckF: Double,
    val planckO: Double,
    val atmAlpha1: Double,
    val atmAlpha2: Double,
    val atmBeta1: Double,
    val atmBeta2: Double,
    val atmX: Double,
) {
    fun with(overrides: ThermalOverrides) = copy(
        emissivity = overrides.emissivity ?: emissivity,
        reflectedTempC = overrides.reflectedTempC ?: reflectedTempC,
        atmosphericTempC = overrides.atmosphericTempC ?: atmosphericTempC,
        distanceM = overrides.distanceM ?: distanceM,
        humidityPct = overrides.humidityPct ?: humidityPct,
    )
}

/**
 * Scene values the inspector knows better than the camera did (the form's emissivity, ambient temperature, humidity).
 * Null fields keep what the image file says.
 */
data class ThermalOverrides(
    val emissivity: Double? = null,
    val reflectedTempC: Double? = null,
    val atmosphericTempC: Double? = null,
    val distanceM: Double? = null,
    val humidityPct: Double? = null,
)

data class Pixel(val x: Int, val y: Int)

/** Inclusive rectangle in pixel coordinates. */
data class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

data class ThermalStats(
    val min: Float,
    val max: Float,
    val mean: Float,
    val minAt: Pixel,
    /** The hottest pixel. */
    val maxAt: Pixel,
    val pixelCount: Int,
)

/** Per-pixel temperatures in °C, row by row. Pixels the sensor could not convert are NaN. */
class ThermalFrame(val width: Int, val height: Int, val tempsC: FloatArray, val params: ThermalParams) {

    init {
        require(width > 0 && height > 0 && tempsC.size == width * height) { "Frame size does not match its data" }
    }

    /** The temperature at ([x], [y]), or null outside the frame or where there is no valid reading. */
    fun temperatureAt(x: Int, y: Int): Float? =
        if (x in 0 until width && y in 0 until height) tempsC[y * width + x].takeUnless { it.isNaN() } else null

    /** Min/max/mean over [region] (clipped to the frame) or the whole frame; null when no pixel has a valid reading. */
    fun stats(region: Region? = null): ThermalStats? {
        val x0 = maxOf(0, minOf(region?.x0 ?: 0, region?.x1 ?: 0))
        val x1 = minOf(width - 1, maxOf(region?.x0 ?: width - 1, region?.x1 ?: width - 1))
        val y0 = maxOf(0, minOf(region?.y0 ?: 0, region?.y1 ?: 0))
        val y1 = minOf(height - 1, maxOf(region?.y0 ?: height - 1, region?.y1 ?: height - 1))

        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var count = 0
        var minAt = Pixel(0, 0)
        var maxAt = Pixel(0, 0)
        for (y in y0..y1) for (x in x0..x1) {
            val t = tempsC[y * width + x]
            if (t.isNaN()) continue
            if (t < min) { min = t; minAt = Pixel(x, y) }
            if (t > max) { max = t; maxAt = Pixel(x, y) }
            sum += t
            count++
        }
        return if (count == 0) null else ThermalStats(min, max, (sum / count).toFloat(), minAt, maxAt, count)
    }
}

/** Turns the bytes of one image file into temperatures. One implementation per camera vendor format. */
interface ThermalDecoder {
    val name: String

    /** Cheap check on the file contents (no full decode). */
    fun canDecode(bytes: ByteArray): Boolean

    /** @throws ThermalDecodeException when the file is damaged or its format variant is not supported */
    fun decode(bytes: ByteArray, overrides: ThermalOverrides = ThermalOverrides()): ThermalFrame
}
