package com.geospatial.processing.domain.model

import com.geospatial.processing.core.plugin.DomainPlugin
import java.io.File

/** File naming shared by the project's image store (:data) and the resolver. */
object ImageFiles {
    const val DIR = "images"
    /** `<slot>.orig.<ext>` is the untouched upload kept next to the display image `<slot>.<ext>`. */
    const val ORIGINAL_INFIX = ".orig."
}

sealed class ImageSource {
    /** Image bytes not saved yet (e.g. a fresh upload in the form). */
    data class FromBlob(val bytes: ByteArray) : ImageSource()
    data class FromFile(val file: File) : ImageSource()
    object Missing : ImageSource()
}

/**
 * Decides which image fills each of an asset's slots:
 *  1. a cleared slot stays empty,
 *  2. a manual image stored in the project wins,
 *  3. otherwise the plugin's match in the user's image folder (one folder scan per asset).
 */
class AssetImageResolver(private val plugin: DomainPlugin, private val projectDir: File) {

    fun resolve(asset: Asset, rootDir: String): Map<String, ImageSource> {
        val folderMatches by lazy { plugin.resolveFolderImages(rootDir, asset) }
        return plugin.imageSlots(asset).associate { slot ->
            val manual = asset.images[slot.id]
            val source = when {
                manual?.cleared == true -> ImageSource.Missing
                manual?.relativePath != null && projectFile(manual.relativePath).isFile ->
                    ImageSource.FromFile(projectFile(manual.relativePath))
                else -> folderMatches[slot.id]?.let { ImageSource.FromFile(it) } ?: ImageSource.Missing
            }
            slot.id to source
        }
    }

    /**
     * The untouched file behind a slot, which still has EXIF/radiometric data: the original kept in the project
     * when the user uploaded the image, otherwise the image file itself (a folder match is never re-encoded).
     * Null for an empty slot.
     */
    fun originalFile(asset: Asset, slotId: String, rootDir: String): File? {
        val kept = File(projectDir, "${ImageFiles.DIR}/${asset.id}").listFiles { f ->
            f.isFile && f.name.startsWith("$slotId${ImageFiles.ORIGINAL_INFIX}") && !f.name.endsWith(".tmp")
        }?.minByOrNull { it.name }
        if (asset.images[slotId]?.cleared == true) return null
        if (kept != null) return kept
        return (resolve(asset, rootDir)[slotId] as? ImageSource.FromFile)?.file
    }

    /** An asset is READY when every slot has an image (manual or from the folder). */
    fun isReady(asset: Asset, rootDir: String): Boolean = isReady(resolve(asset, rootDir))

    private fun projectFile(relativePath: String) = File(projectDir, relativePath)

    companion object {
        /** READY from sources that were already resolved, so callers that need them for something else don't scan the folder twice. */
        fun isReady(sources: Map<String, ImageSource>): Boolean = sources.values.none { it is ImageSource.Missing }
    }
}
