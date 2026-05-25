package com.geospatial.processing.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geospatial.processing.core.plugin.FieldType
import com.geospatial.processing.core.plugin.PropertyDefinition

@Composable
fun DynamicFormEngine(
    schema: List<PropertyDefinition>,
    propertiesState: MutableMap<String, String>, // Live map holding the user's typed data
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Chunk the fields into rows of 3 for a clean dashboard look
        schema.chunked(3).forEach { rowFields ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                rowFields.forEach { field ->
                    // Read current value from the map, default to empty string
                    val currentValue = propertiesState[field.key] ?: ""

                    Box(modifier = Modifier.weight(1f)) {
                        when (field.type) {
                            FieldType.TEXT, FieldType.NUMBER -> {
                                OutlinedTextField(
                                    value = currentValue,
                                    onValueChange = { propertiesState[field.key] = it },
                                    label = { Text(field.label) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    isError = field.isRequired && currentValue.isBlank(),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = MaterialTheme.colors.onSurface
                                    )
                                )
                            }
                            FieldType.DROPDOWN -> {
                                // You would implement a standard Compose DropdownMenu here
                                // using field.dropdownOptions
                                OutlinedTextField(
                                    value = currentValue,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(field.label) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            FieldType.BOOLEAN -> {
                                // You would implement a Checkbox or Switch here
                            }
                        }
                    }
                }
                // Fill empty slots if the row has less than 3 items to keep width uniform
                repeat(3 - rowFields.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}