package com.geospatial.processing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.geospatial.processing.domain.model.ReportSettings
import com.geospatial.processing.domain.report.ReportTemplate
import com.geospatial.processing.domain.report.parseHexColor
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Asks which report template and branding to use, then (via [onExport]) where to save the ZIP.
 * The choices are remembered in the project, so the dialog opens with what was used last.
 */
@Composable
fun ExportDialog(
    settings: ReportSettings,
    onDismiss: () -> Unit,
    onExport: (settings: ReportSettings, newLogo: File?, zip: File) -> Unit,
) {
    var template by remember { mutableStateOf(ReportTemplate.fromName(settings.template)) }
    var company by remember { mutableStateOf(settings.companyName) }
    var accent by remember { mutableStateOf(settings.accentColor) }
    var newLogo by remember { mutableStateOf<File?>(null) }
    var keepLogo by remember { mutableStateOf(settings.logoPath.isNotBlank()) }

    val accentValid = accent.isBlank() || parseHexColor(accent) != null
    val logoName = newLogo?.name ?: settings.logoPath.substringAfterLast('/').takeIf { keepLogo && it.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Export Reports", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Template", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                ReportTemplate.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = template == option, onClick = { template = option })
                        Column {
                            Text(option.label, color = MaterialTheme.colorScheme.onSurface)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }

                Text("Branding", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                OutlinedTextField(
                    value = company, onValueChange = { company = it }, singleLine = true,
                    label = { Text("Company name (footer)") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = accent, onValueChange = { accent = it }, singleLine = true, isError = !accentValid,
                        label = { Text("Accent colour (#RRGGBB)") }, modifier = Modifier.weight(1f),
                        supportingText = { if (!accentValid) Text("Use six hex digits, e.g. #2980B9") },
                    )
                    val swatch = parseHexColor(accent)?.let { Color(0xFF000000.toInt() or it) } ?: Color(41, 128, 185)
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(swatch))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickLogoFile()?.let { newLogo = it; keepLogo = true } }) { Text("Choose logo…") }
                    if (logoName != null) {
                        Text(logoName, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        TextButton(onClick = { newLogo = null; keepLogo = false }) { Text("Remove") }
                    } else {
                        Text("No logo", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = accentValid,
                onClick = {
                    val zip = saveZipFile() ?: return@Button
                    onExport(
                        ReportSettings(
                            template = template.name,
                            companyName = company.trim(),
                            accentColor = accent.trim(),
                            logoPath = if (keepLogo) settings.logoPath else "",
                        ),
                        newLogo,
                        zip,
                    )
                },
            ) { Text("Export…") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun pickLogoFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Choose Report Logo"
    chooser.fileFilter = FileNameExtensionFilter("PNG or JPEG image", "png", "jpg", "jpeg")
    val parentFrame = JFrame().apply { iconImage = getAwtAppIcon() }
    val result = chooser.showOpenDialog(parentFrame)
    parentFrame.dispose()
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
