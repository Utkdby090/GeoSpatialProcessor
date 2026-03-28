package com.geospatial.processing.domain.model


enum class RecordStatus {
    DRAFT,      // Missing data or images (Orange Icon)
    READY,      // All required fields present (Green Icon)
    ARCHIVED    // Processed and exported (Gray Icon)
}

data class GeoRecord(
    val id: Int = 0,
    val lineName: String,
    val towerNumber: String = "",
    val circuit: String = "",
    val latitude: Double,
    val longitude: Double,

    // Parameters
    val humidity: String = "",
    val emissivity: String = "",
    val ambientTemp: String = "",
    val loadValue: String = "",

    // Faults
    val faultDescription: String = "",
    val faultTemp: String = "",

    // Images
    val thermalImage: ByteArray? = null,
    val visualImage: ByteArray? = null,
    val towerImage: ByteArray? = null,
    val extraImage: ByteArray? = null,

    val status: com.geospatial.processing.domain.model.RecordStatus = RecordStatus.DRAFT
)