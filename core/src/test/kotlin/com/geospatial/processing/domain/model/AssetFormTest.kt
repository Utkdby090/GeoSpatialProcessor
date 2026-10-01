package com.geospatial.processing.domain.model

import com.geospatial.processing.core.plugin.CoreFields
import com.geospatial.processing.core.plugin.FieldType
import com.geospatial.processing.core.plugin.PropertyDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetFormTest {

    private val schema = listOf(
        PropertyDefinition("name", "Name", FieldType.TEXT, isRequired = true),
        PropertyDefinition("note", "Note", FieldType.TEXT),
        PropertyDefinition(CoreFields.LATITUDE, "Latitude", FieldType.NUMBER),
        PropertyDefinition(CoreFields.LONGITUDE, "Longitude", FieldType.NUMBER),
    )

    private val original = Asset(
        pluginId = "test", position = 0, latitude = 12.5, longitude = 77.25,
        properties = mapOf("name" to "T1", "hidden" to "kept"),
    )

    @Test
    fun `values include properties and coordinates`() {
        assertEquals(
            mapOf("name" to "T1", "note" to "", CoreFields.LATITUDE to "12.5", CoreFields.LONGITUDE to "77.25"),
            AssetForm.values(original, schema)
        )
    }

    @Test
    fun `required blanks and non-numeric coordinates are errors`() {
        val values = AssetForm.values(original, schema) + mapOf("name" to " ", CoreFields.LATITUDE to "abc")
        assertEquals(setOf("name", CoreFields.LATITUDE), AssetForm.validate(schema, values))
        assertEquals(emptySet(), AssetForm.validate(schema, AssetForm.values(original, schema)))
    }

    @Test
    fun `apply writes properties and coordinates and keeps fields not on the form`() {
        val values = AssetForm.values(original, schema) + mapOf("note" to "hot joint", CoreFields.LONGITUDE to " 78.0 ")
        val updated = AssetForm.apply(original, values)

        assertEquals("hot joint", updated.property("note"))
        assertEquals("kept", updated.property("hidden"))
        assertEquals(78.0, updated.longitude)
        assertEquals(12.5, updated.latitude)
        assertEquals(false, CoreFields.LATITUDE in updated.properties)
    }
}
