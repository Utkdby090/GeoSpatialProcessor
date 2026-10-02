package com.geospatial.processing.domain.thermal

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Raw sensor counts to object temperature, using the standard FLIR radiometric model
 * (Planck curve plus atmosphere, reflected-radiation and IR-window corrections; the same maths as
 * ExifTool-based tools and the Thermimage package).
 *
 * The IR window is treated as absorbing but not reflecting, as in the reference formulas.
 *
 * Everything that does not depend on the pixel is computed once in the constructor.
 */
class RadiometricCalibration(private val p: ThermalParams) {

    private val emissivity = p.emissivity.coerceIn(0.01, 1.0)
    private val irt = p.irWindowTransmission.takeIf { it > 0.0 } ?: 1.0

    private fun rawOf(tempC: Double) = p.planckR1 / (p.planckR2 * (exp(p.planckB / (tempC + KELVIN)) - p.planckF)) - p.planckO

    private val tau: Double = run {
        val h2o = (p.humidityPct / 100.0) * exp(
            1.5587 + 0.06939 * p.atmosphericTempC - 0.00027816 * p.atmosphericTempC * p.atmosphericTempC +
                0.00000068455 * p.atmosphericTempC * p.atmosphericTempC * p.atmosphericTempC
        )
        val d = sqrt(p.distanceM / 2.0)
        p.atmX * exp(-d * (p.atmAlpha1 + p.atmBeta1 * sqrt(h2o))) + (1 - p.atmX) * exp(-d * (p.atmAlpha2 + p.atmBeta2 * sqrt(h2o)))
    }

    /** What the camera sees from everything that is not the object, expressed in sensor counts. */
    private val backgroundCounts: Double = run {
        val refl = rawOf(p.reflectedTempC)
        val atm = rawOf(p.atmosphericTempC)
        val window = rawOf(p.irWindowTempC)
        val reflAttn1 = (1 - emissivity) / emissivity * refl
        val atmAttn1 = (1 - tau) / emissivity / tau * atm
        val windowAttn = (1 - irt) / emissivity / tau / irt * window
        val atmAttn2 = (1 - tau) / emissivity / tau / irt / tau * atm
        atmAttn1 + atmAttn2 + windowAttn + reflAttn1
    }

    private val objectScale = 1.0 / (emissivity * tau * irt * tau)

    /** Temperature in °C for one raw sensor value, or NaN when the value is outside the curve. */
    fun tempC(raw: Int): Float {
        val obj = raw * objectScale - backgroundCounts
        val x = p.planckR1 / (p.planckR2 * (obj + p.planckO)) + p.planckF
        if (obj + p.planckO <= 0.0 || x <= 0.0) return Float.NaN
        val t = p.planckB / ln(x) - KELVIN
        return if (t.isFinite()) t.toFloat() else Float.NaN
    }

    companion object {
        const val KELVIN = 273.15
    }
}
