package com.geospatial.processing.domain.model

import com.geospatial.processing.core.plugin.CoreFields
import com.geospatial.processing.core.plugin.PropertyDefinition
import com.geospatial.processing.domain.map.Coordinates

/** Converts between an [Asset] and the flat key→text map an asset form edits. */
object AssetForm {

    fun values(asset: Asset, schema: List<PropertyDefinition>): Map<String, String> =
        schema.associate { field ->
            field.key to when (field.key) {
                CoreFields.LATITUDE -> asset.latitude.toString()
                CoreFields.LONGITUDE -> asset.longitude.toString()
                else -> asset.property(field.key)
            }
        }

    /** Keys of fields that are invalid: required but blank, or coordinates that can't be read (decimal or degrees/minutes/seconds). */
    fun validate(schema: List<PropertyDefinition>, values: Map<String, String>): Set<String> =
        schema.filter { field ->
            val value = values[field.key].orEmpty()
            (field.isRequired && value.isBlank()) ||
                (field.key in COORDINATE_KEYS && Coordinates.parse(value) == null)
        }.map { it.key }.toSet()

    /** Applies edited [values] to [asset]. Properties not in the form are kept unchanged. */
    fun apply(asset: Asset, values: Map<String, String>): Asset {
        val properties = asset.properties.toMutableMap()
        values.forEach { (key, value) -> if (key !in COORDINATE_KEYS) properties[key] = value }
        return asset.copy(
            latitude = Coordinates.parse(values[CoreFields.LATITUDE])?.degrees ?: asset.latitude,
            longitude = Coordinates.parse(values[CoreFields.LONGITUDE])?.degrees ?: asset.longitude,
            properties = properties,
        )
    }

    private val COORDINATE_KEYS = setOf(CoreFields.LATITUDE, CoreFields.LONGITUDE)
}
