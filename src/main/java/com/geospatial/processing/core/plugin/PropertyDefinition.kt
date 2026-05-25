package com.geospatial.processing.core.plugin

import java.io.File

// Defines the types of UI inputs we can generate
enum class FieldType { TEXT, NUMBER, DROPDOWN, BOOLEAN }

// Defines a single dynamic field (e.g., "Tower Number" or "Blade Length")
data class PropertyDefinition(
    val key: String,             // Internal JSON key (e.g., "tower_number")
    val label: String,           // UI Label (e.g., "Tower No.")
    val type: FieldType,
    val isRequired: Boolean = false,
    val dropdownOptions: List<String> = emptyList() // Used if type == DROPDOWN
)

// The Base Interface every industry module must implement
interface DomainPlugin {
    val pluginId: String         // e.g., "com.geo.telecom"
    val displayName: String      // e.g., "Telecom Grid Inspection"
    val description: String      // e.g., "Process 765kV transmission lines and mid-spans."
    val iconName: String         // Points to a vector asset

    // 1. The Schema: Tells the Core UI what text fields to draw
    fun getPropertySchema(): List<PropertyDefinition>

    // 2. The Import Strategy: How to read this specific industry's CSV
    fun getCsvImportStrategy(): CsvImportStrategy

    // 3. The Export Strategy: How to draw the PDF for this industry
    fun getReportStrategy(): ReportStrategy
}

// Strategy Interfaces
interface CsvImportStrategy {
    fun parse(file: File): List<Map<String, String>>
}

interface ReportStrategy {
    fun generatePdf(data: Map<String, String>, imagePaths: List<String>, outputFile: File)
}