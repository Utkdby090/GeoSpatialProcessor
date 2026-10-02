package com.geospatial.processing.data.images

import java.io.File

/**
 * Image files that belong to a project: <project>/images/<assetId>/<slot>.<ext>.
 * Paths handed out are relative to the project folder and '/'-separated, so they survive moving
 * the project or exporting it as .geox.
 */
class ImageStore(private val projectDir: File) {

    private val imagesDir = File(projectDir, IMAGES_DIR)

    /** Writes [bytes] for [assetId]/[slot] and returns its relative path. Overwrites an earlier image of that slot and type. */
    fun write(assetId: String, slot: String, bytes: ByteArray): String {
        require(SAFE_NAME.matches(assetId) && SAFE_NAME.matches(slot)) { "Unsafe image name: $assetId/$slot" }
        val relativePath = "$IMAGES_DIR/$assetId/$slot.${extensionOf(bytes)}"
        val target = File(projectDir, relativePath)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not write image $target" }
        return relativePath
    }

    /**
     * Keeps the untouched file the user picked next to the display image, as `<slot>.orig.<ext>`.
     * Display images are re-encoded (smaller, no EXIF); radiometric temperatures and GPS only survive in the original.
     * Any earlier original of that slot is replaced, whatever its extension.
     */
    fun writeOriginal(assetId: String, slot: String, bytes: ByteArray) {
        require(SAFE_NAME.matches(assetId) && SAFE_NAME.matches(slot)) { "Unsafe image name: $assetId/$slot" }
        deleteOriginal(assetId, slot)
        val target = File(imagesDir, "$assetId/$slot$ORIGINAL_MARKER${extensionOf(bytes)}")
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target)) { "Could not write image $target" }
    }

    /** The original file kept for [assetId]/[slot], if any. */
    fun originalFile(assetId: String, slot: String): File? {
        if (!SAFE_NAME.matches(assetId) || !SAFE_NAME.matches(slot)) return null
        return File(imagesDir, assetId).listFiles { f -> f.isFile && f.name.startsWith("$slot$ORIGINAL_MARKER") && !f.name.endsWith(".tmp") }
            ?.minByOrNull { it.name }
    }

    fun deleteOriginal(assetId: String, slot: String) {
        originalFile(assetId, slot)?.delete()
    }

    /** The file for [relativePath], or null if the path would point outside this project's images folder. */
    fun file(relativePath: String): File? {
        val file = File(projectDir, relativePath).canonicalFile
        return file.takeIf { it.toPath().startsWith(imagesDir.canonicalFile.toPath()) }
    }

    fun delete(relativePath: String) {
        file(relativePath)?.delete()
    }

    fun deleteAsset(assetId: String) {
        if (SAFE_NAME.matches(assetId)) File(imagesDir, assetId).deleteRecursively()
    }

    /** Removes every stored image (keeps the empty images folder). */
    fun deleteAll() {
        imagesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val IMAGES_DIR = "images"
        private const val ORIGINAL_MARKER = ".orig."
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]+")

        /** File type from the first bytes; the app only accepts JPEG and PNG uploads. */
        fun extensionOf(bytes: ByteArray): String = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
            else -> "img"
        }
    }
}
