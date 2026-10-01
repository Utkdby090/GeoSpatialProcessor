package com.geospatial.processing.domain.model

import com.geospatial.processing.core.plugin.DomainPlugin
import java.io.File

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

    /** An asset is READY when every slot has an image (manual or from the folder). */
    fun isReady(asset: Asset, rootDir: String): Boolean =
        resolve(asset, rootDir).values.none { it is ImageSource.Missing }

    private fun projectFile(relativePath: String) = File(projectDir, relativePath)
}
