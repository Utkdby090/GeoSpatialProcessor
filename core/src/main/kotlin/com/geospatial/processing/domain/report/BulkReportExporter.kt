package com.geospatial.processing.domain.report

import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.domain.model.RecordStatus
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one PDF per READY asset into a ZIP, using the plugin's report strategy.
 * Previous/next labels come from the full list order (not just the READY ones), as before.
 */
class BulkReportExporter(private val plugin: DomainPlugin) {

    /**
     * Returns the number of PDFs written. Writes nothing (and creates no file) when no asset is READY.
     * On failure the partial ZIP is deleted and the exception is rethrown.
     */
    fun export(
        assets: List<Asset>,
        imagesOf: (Asset) -> Map<String, ImageSource>,
        destZipFile: File,
        options: ReportOptions = ReportOptions(),
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val ready = assets.filter { it.status == RecordStatus.READY }
        if (ready.isEmpty()) return 0

        val report = plugin.getReportStrategy()
        val indexOf = assets.withIndex().associate { (i, a) -> a.id to i }
        // Group by top-level ZIP folder (e.g. one folder per report type), keeping first-seen order.
        val ordered = ready.groupBy { report.entryName(it).substringBefore('/', "") }.values.flatten()

        var processed = 0
        try {
            ZipOutputStream(FileOutputStream(destZipFile)).use { zip ->
                for (asset in ordered) {
                    val index = indexOf.getValue(asset.id)
                    val previous = assets.getOrNull(index - 1)?.let { plugin.present(it).sequenceLabel }
                    val next = assets.getOrNull(index + 1)?.let { plugin.present(it).sequenceLabel }
                    val images = imagesOf(asset).mapValues { (_, source) -> source.readBytes() }

                    zip.putNextEntry(ZipEntry(report.entryName(asset)))
                    report.writePdf(asset, images, previous, next, options, zip)
                    zip.closeEntry()

                    processed++
                    onProgress(processed, ready.size)
                }
            }
        } catch (e: Exception) {
            destZipFile.delete()
            throw e
        }
        return processed
    }

    private fun ImageSource.readBytes(): ByteArray? = try {
        when (this) {
            is ImageSource.FromBlob -> bytes
            is ImageSource.FromFile -> file.readBytes()
            ImageSource.Missing -> null
        }
    } catch (e: Exception) {
        null // an unreadable image shows as "No Image Provided", like before
    }
}
