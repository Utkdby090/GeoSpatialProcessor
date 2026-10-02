package com.geospatial.processing.domain.report

/** The built-in report layouts. [STANDARD] is the layout reports have always had. */
enum class ReportTemplate(val label: String, val description: String, val showImages: Boolean, val showSeverity: Boolean) {
    STANDARD("Standard", "Images and all measurements, as before.", showImages = true, showSeverity = false),
    DETAILED("Detailed", "Standard, plus a severity banner and rating in the fault analysis.", showImages = true, showSeverity = true),
    SUMMARY("Summary", "No images: identification, severity and fault analysis only.", showImages = false, showSeverity = true);

    companion object {
        /** Reads a stored name; anything unknown falls back to [STANDARD]. */
        fun fromName(name: String?): ReportTemplate = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/** The look of the reports of one project. Blank/null values mean "use the default". */
data class ReportBranding(
    /** Shown in the footer instead of the company in the data. */
    val companyName: String = "",
    /** Section header colour as "#RRGGBB"; invalid or blank keeps the default blue. */
    val accentColor: String = "",
    /** Encoded image (PNG/JPEG) drawn next to the title. */
    val logo: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is ReportBranding && companyName == other.companyName &&
        accentColor == other.accentColor && logo.contentEquals(other.logo)

    override fun hashCode() = 31 * (31 * companyName.hashCode() + accentColor.hashCode()) + (logo?.contentHashCode() ?: 0)
}

/** Everything a report strategy may vary on besides the asset itself. */
data class ReportOptions(
    val template: ReportTemplate = ReportTemplate.STANDARD,
    val branding: ReportBranding = ReportBranding(),
)

/** "#RRGGBB" (the # is optional) as an RGB int, or null when [text] isn't that. */
fun parseHexColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6) return null
    return hex.toIntOrNull(16)
}
