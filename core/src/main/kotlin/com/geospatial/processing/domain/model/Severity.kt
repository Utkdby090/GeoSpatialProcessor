package com.geospatial.processing.domain.model

/** How urgent an inspection finding is, from nothing to act on to act now. Ordered: later is more severe. */
enum class Severity(val label: String) {
    NONE("None"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    CRITICAL("Critical");

    companion object {
        /** Reads a stored name; anything unknown (an older or newer version wrote it) counts as [NONE]. */
        fun fromName(name: String?): Severity = entries.firstOrNull { it.name == name } ?: NONE
    }
}
