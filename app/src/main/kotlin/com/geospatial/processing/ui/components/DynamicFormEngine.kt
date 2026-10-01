package com.geospatial.processing.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.geospatial.processing.core.plugin.PropertyDefinition
import com.geospatial.processing.ui.ValidatedTextField

/**
 * Renders the fields of ONE schema group, row by row (see [PropertyDefinition.row] / [PropertyDefinition.weight]).
 * Rows narrower than the group's widest row are padded, so e.g. a row with one load circuit
 * keeps the same column width as a full row of three.
 */
@Composable
fun PropertyGroupForm(
    fields: List<PropertyDefinition>,
    values: Map<String, String>,
    errors: Set<String>,
    onValueChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = fields.groupBy { it.row }.toSortedMap().values
    val fullRowWeight = rows.maxOfOrNull { row -> row.sumOf { it.weight.toDouble() } }?.toFloat() ?: 1f

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowFields ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowFields.forEach { field ->
                    // DROPDOWN and BOOLEAN are shown as text for now; no plugin uses them yet.
                    ValidatedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = { onValueChange(field.key, it) },
                        label = field.label,
                        isError = field.key in errors,
                        modifier = Modifier.weight(field.weight)
                    )
                }
                val missing = fullRowWeight - rowFields.sumOf { it.weight.toDouble() }.toFloat()
                if (missing > 0.01f) Spacer(Modifier.weight(missing))
            }
        }
    }
}
