package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.domain.model.Severity

/**
 * Temperature rise over ambient (°C) at which a hot spot moves up a severity level.
 *
 * The defaults follow the commonly used electrical-inspection bands (roughly NETA-style: 1–3 °C possible deficiency,
 * 4–15 °C probable deficiency, above 15 °C major). They are a starting point; a utility's own maintenance
 * standard should replace them.
 */
data class SeverityThresholds(
    val low: Double = 1.0,
    val medium: Double = 4.0,
    val high: Double = 16.0,
    val critical: Double = 40.0,
) {
    init {
        require(low <= medium && medium <= high && high <= critical) { "Thresholds must not decrease" }
    }

    fun of(rise: Double): Severity = when {
        rise >= critical -> Severity.CRITICAL
        rise >= high -> Severity.HIGH
        rise >= medium -> Severity.MEDIUM
        rise >= low -> Severity.LOW
        else -> Severity.NONE
    }
}
