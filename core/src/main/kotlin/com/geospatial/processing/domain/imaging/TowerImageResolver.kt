package com.geospatial.processing.domain.imaging

import java.io.File

/**
 * The four image slots on an inspection report.
 *
 *  LOCATION   (top-left)     – leftover overview / location photo
 *  THERMAL    (top-right)    – IR / radiometric image
 *  STRUCTURE  (bottom-left)  – tower, span, sleeve or earth-wire photo (depends on report type)
 *  RGB_ZOOM   (bottom-right) – zoomed visible-light photo
 */
enum class ImageSlot { LOCATION, THERMAL, STRUCTURE, RGB_ZOOM }

/**
 * Resolves which file in a tower folder belongs in which report slot.
 *
 * Replaces the old substring matching (`name.contains("ir")`), which wrongly matched
 * "repair", "pair", "first", "dir"… as thermal images and silently dropped sleeve photos.
 *
 * Rules:
 *  - File names are split into whole-word tokens (separators, camelCase and letter/digit boundaries),
 *    so "Repair_Sleeve_01.jpg" -> [repair, sleeve, 01] and never matches "ir".
 *  - Long, unambiguous keywords ("thermal", "zoom", "tower"…) also match as substrings,
 *    so legacy names like "76_0thermal.jpg" or "TowerWide.jpg" keep working.
 *  - DJI enterprise suffixes are recognised when they are the LAST token:
 *    _T = thermal, _Z = zoom, _V = visual, _W = wide (structure shot).
 *  - Each file fills at most one slot. Files are considered in name order, so results are deterministic.
 */
object TowerImageResolver {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    // Compiled once: tokenize() runs for every image of every tower during a scan.
    private val PATH_UNSAFE = Regex("[\\\\/:*?\"<>|]")
    private val CAMEL_CASE = Regex("([a-z])([A-Z])")
    private val ACRONYM = Regex("([A-Z]+)([A-Z][a-z])")
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+|(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")

    // --- keyword tables -------------------------------------------------------------------------

    private val THERMAL_TOKENS = setOf("thermal", "ir", "irx", "radiometric", "flir", "lwir")
    private val THERMAL_SUBSTRINGS = setOf("thermal", "radiometric")
    private const val THERMAL_SUFFIX = "t"

    private val ZOOM_TOKENS = setOf("zoom", "rgb", "visual", "supp", "closeup")
    private val ZOOM_SUBSTRINGS = setOf("zoom", "visual")
    private val ZOOM_SUFFIXES = setOf("z", "v")

    private const val WIDE_SUFFIX = "w"

    private enum class StructureKind { TOWER, MID_SPAN, SLEEVE, EARTH_WIRE }

    private val STRUCTURE_TOKENS: Map<StructureKind, Set<String>> = mapOf(
        StructureKind.TOWER to setOf("tower", "wide", "structure"),
        StructureKind.MID_SPAN to setOf("span", "mid", "midspan"),
        StructureKind.SLEEVE to setOf("sleeve", "repair"),
        StructureKind.EARTH_WIRE to setOf("earth", "wire", "joint", "span", "mid", "midspan", "sleeve", "repair"),
    )
    private val STRUCTURE_SUBSTRINGS: Map<StructureKind, Set<String>> = mapOf(
        StructureKind.TOWER to setOf("tower", "structure"),
        StructureKind.MID_SPAN to setOf("midspan"),
        StructureKind.SLEEVE to setOf("sleeve"),
        StructureKind.EARTH_WIRE to setOf("earthwire", "sleeve", "midspan"),
    )

    // --- public API -----------------------------------------------------------------------------

    /**
     * Finds the folder for a tower inside [rootDir].
     * Tries the tower number as-is, then with path-unsafe characters replaced by "_" ("76/0" -> "76_0").
     * Never returns a folder outside [rootDir] (blocks "../" tricks in tower numbers).
     */
    fun findTowerFolder(rootDir: String, towerNumber: String): File? {
        if (rootDir.isBlank()) return null
        val root = try { File(rootDir).canonicalFile } catch (e: Exception) { return null }
        val trimmed = towerNumber.trim()
        val candidates = listOf(trimmed, trimmed.replace(PATH_UNSAFE, "_")).distinct()

        for (name in candidates) {
            if (name.isEmpty() || name == "." || name == "..") continue
            val dir = try { File(root, name).canonicalFile } catch (e: Exception) { continue }
            if (dir.parentFile == root && dir.isDirectory) return dir
        }
        return null
    }

    /** Resolves every slot for one tower folder in a single directory listing. */
    fun resolve(folder: File?, reportType: String): Map<ImageSlot, File> {
        if (folder == null || !folder.isDirectory) return emptyMap()

        val images = folder.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: return emptyMap()

        val kind = structureKindOf(reportType)
        val classified = images.map { it to classify(it.name, kind) }

        val result = linkedMapOf<ImageSlot, File>()
        val used = mutableSetOf<File>()

        for (slot in listOf(ImageSlot.THERMAL, ImageSlot.RGB_ZOOM, ImageSlot.STRUCTURE)) {
            classified.firstOrNull { (file, slots) -> slot in slots && file !in used }?.let { (file, _) ->
                result[slot] = file
                used += file
            }
        }

        // LOCATION gets the first leftover image that wasn't recognised as anything else.
        classified.firstOrNull { (file, slots) -> file !in used && slots.isEmpty() }?.let { (file, _) ->
            result[ImageSlot.LOCATION] = file
        }
        return result
    }

    fun resolve(rootDir: String, towerNumber: String, reportType: String): Map<ImageSlot, File> =
        resolve(findTowerFolder(rootDir, towerNumber), reportType)

    // --- internals (internal for tests) ---------------------------------------------------------

    internal fun tokenize(fileName: String): List<String> {
        val base = fileName.substringBeforeLast('.')
        return base
            .replace(CAMEL_CASE, "$1 $2")              // camelCase -> camel Case
            .replace(ACRONYM, "$1 $2")        // IRImage -> IR Image
            .lowercase()
            .split(TOKEN_SPLIT)
            .filter { it.isNotEmpty() }
    }

    /** Returns the slots a file could fill. Thermal wins exclusively; empty = unrecognised. */
    internal fun classify(fileName: String, reportType: String): Set<ImageSlot> =
        classify(fileName, structureKindOf(reportType))

    private fun classify(fileName: String, kind: StructureKind): Set<ImageSlot> {
        val tokens = tokenize(fileName)
        val compact = tokens.joinToString("")
        val last = tokens.lastOrNull()

        fun matches(words: Set<String>, substrings: Set<String>) =
            tokens.any { it in words } || substrings.any { compact.contains(it) }

        val isThermal = matches(THERMAL_TOKENS, THERMAL_SUBSTRINGS) || (tokens.size > 1 && last == THERMAL_SUFFIX)
        if (isThermal) return setOf(ImageSlot.THERMAL)

        val slots = mutableSetOf<ImageSlot>()
        if (matches(ZOOM_TOKENS, ZOOM_SUBSTRINGS) || (tokens.size > 1 && last in ZOOM_SUFFIXES)) {
            slots += ImageSlot.RGB_ZOOM
        }
        val isWideShot = tokens.size > 1 && last == WIDE_SUFFIX
        if (matches(STRUCTURE_TOKENS.getValue(kind), STRUCTURE_SUBSTRINGS.getValue(kind)) || isWideShot) {
            slots += ImageSlot.STRUCTURE
        }
        return slots
    }

    private fun structureKindOf(reportType: String): StructureKind {
        val t = reportType.lowercase()
        return when {
            t.contains("earth") || t.contains("wire") -> StructureKind.EARTH_WIRE
            t.contains("mid") -> StructureKind.MID_SPAN
            t.contains("sleeve") -> StructureKind.SLEEVE
            else -> StructureKind.TOWER
        }
    }
}
