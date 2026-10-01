package com.geospatial.processing.core.plugin.telecom

import com.geospatial.processing.core.plugin.*
import java.io.File

class TelecomPlugin : DomainPlugin {
    override val pluginId = "com.geo.telecom"
    override val displayName = "Electric Grid Inspection"
    override val description = "Process 765kV transmission lines, mid-spans, and hardware fittings."
    override val iconName = "settings" // We'll map this to a native Compose icon in the UI

    // 1. The Schema: Defines the dynamic text fields for the UI
    override fun getPropertySchema(): List<PropertyDefinition> {
        return listOf(
            PropertyDefinition(key = "towerNumber", label = "Tower No.", type = FieldType.TEXT, isRequired = true),
            PropertyDefinition(key = "circuit", label = "Circuit", type = FieldType.DROPDOWN, dropdownOptions = listOf("NA", "1", "2")),
            PropertyDefinition(key = "ambientTemp", label = "Amb. Temp (°C)", type = FieldType.NUMBER)
        )
    }

    // 2. Dummy CSV Strategy (We will implement the real logic later)
    override fun getCsvImportStrategy(): CsvImportStrategy {
        return object : CsvImportStrategy {
            override fun parse(file: File): List<Map<String, String>> {
                println("Mock importing telecom CSV...")
                return emptyList()
            }
        }
    }

    // 3. Dummy PDF Strategy (We will implement the real logic later)
    override fun getReportStrategy(): ReportStrategy {
        return object : ReportStrategy {
            override fun generatePdf(data: Map<String, String>, imagePaths: List<String>, outputFile: File) {
                println("Mock generating telecom PDF...")
            }
        }
    }
}