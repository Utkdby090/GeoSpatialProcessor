package com.geospatial.processing.core.plugin.telecom

/**
 * Property keys of telecom/grid assets.
 *
 * Lives in :core (not in the telecom plugin module) because the legacy migration in :data
 * maps old GeoRecord columns onto these keys, and :data must not depend on a plugin.
 */
object TelecomKeys {
    const val PLUGIN_ID = "com.geo.telecom"

    const val LINE_NAME = "lineName"
    const val TOWER_NUMBER = "towerNumber"
    const val CIRCUIT = "circuit"
    const val PHASE = "phase"
    const val SIDE = "side"
    const val DIRECTION = "direction"
    const val CAPTURED_DATE = "capturedDate"
    const val CAPTURED_TIME = "capturedTime"
    const val HUMIDITY = "humidity"
    const val EMISSIVITY = "emissivity"
    const val AMBIENT_TEMP = "ambientTemp"
    const val FAULT_DESCRIPTION = "faultDescription"
    const val FAULT_TEMP = "faultTemp"
    const val RISE_TEMP = "riseTemp"
    /** "tower", "mid_span", "repair_sleeve", "earth_wire"…; may contain "fault". */
    const val REPORT_TYPE = "reportType"
    /** "Normal" or "Fault…". */
    const val FAULT_STATUS = "faultStatus"
    const val COMPANY_NAME = "companyName"

    /** Extra CSV columns (per-circuit load data) are stored as "load.<column header>". */
    const val LOAD_PREFIX = "load."

    // Image slot ids
    const val SLOT_LOCATION = "LOCATION"     // top-left
    const val SLOT_THERMAL = "THERMAL"       // top-right
    const val SLOT_STRUCTURE = "STRUCTURE"   // bottom-left: tower / span / sleeve / earth-wire photo
    const val SLOT_RGB_ZOOM = "RGB_ZOOM"     // bottom-right
}
