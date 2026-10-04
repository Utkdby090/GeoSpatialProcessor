package com.geospatial.processing.domain.report

import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.core.plugin.ReportStrategy
import com.geospatial.processing.domain.model.Asset
import com.geospatial.processing.domain.model.ImageSource
import com.geospatial.processing.domain.model.RecordStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes one PDF per READY asset into a ZIP, using the plugin's report strategy.
 * Previous/next labels come from the full list order (not just the READY ones), as before.
 *
 * PDFs are built on several threads (the work is image decoding and layout, which dominate large exports) but are
 * written to the ZIP in a fixed order, so the result is the same whatever the thread count.
 */
class BulkReportExporter(private val plugin: DomainPlugin) {

    /**
     * Returns the number of PDFs written. Writes nothing (and creates no file) when no asset is READY.
     * The ZIP is built under a temporary name and moved into place at the end, so a failure leaves an existing file at
     * [destZipFile] untouched and never leaves a partial ZIP behind; the exception is rethrown.
     *
     * [imagesOf] is called from worker threads and must be safe to call concurrently.
     */
    fun export(
        assets: List<Asset>,
        imagesOf: (Asset) -> Map<String, ImageSource>,
        destZipFile: File,
        options: ReportOptions = ReportOptions(),
        parallelism: Int = defaultParallelism(),
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val ready = assets.filter { it.status == RecordStatus.READY }
        if (ready.isEmpty()) return 0

        val report = plugin.getReportStrategy()
        val indexOf = assets.withIndex().associate { (i, a) -> a.id to i }
        // Group by top-level ZIP folder (e.g. one folder per report type), keeping first-seen order.
        val ordered = ready.groupBy { report.entryName(it).substringBefore('/', "") }.values.flatten()
        val entryNames = uniqueEntryNames(ordered, report)

        fun build(asset: Asset): ByteArray {
            val index = indexOf.getValue(asset.id)
            val previous = assets.getOrNull(index - 1)?.let { plugin.present(it).sequenceLabel }
            val next = assets.getOrNull(index + 1)?.let { plugin.present(it).sequenceLabel }
            val images = imagesOf(asset).mapValues { (_, source) -> source.readBytes() }
            return ByteArrayOutputStream(64 * 1024).also { report.writePdf(asset, images, previous, next, options, it) }.toByteArray()
        }

        val parent = destZipFile.absoluteFile.parentFile
        parent?.mkdirs()
        val tmp = File(parent, "${destZipFile.name}.${UUID.randomUUID()}.tmp")
        var processed = 0
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                forEachInOrder(ordered, parallelism.coerceIn(1, MAX_PARALLELISM), ::build) { asset, pdf ->
                    zip.putNextEntry(ZipEntry(entryNames.getValue(asset.id)))
                    zip.write(pdf)
                    zip.closeEntry()
                    processed++
                    onProgress(processed, ready.size)
                }
            }
            moveIntoPlace(tmp, destZipFile)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return processed
    }

    /**
     * Runs [work] for every item on up to [threads] threads and hands the results to [consume] one at a time, in the
     * order of [items]. At most about twice [threads] results are held in memory at once.
     */
    private fun <T, R> forEachInOrder(items: List<T>, threads: Int, work: (T) -> R, consume: (T, R) -> Unit) {
        if (threads <= 1 || items.size < 2) {
            items.forEach { consume(it, work(it)) }
            return
        }
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "report-export").apply { isDaemon = true } }
        val window = ArrayDeque<Pair<T, Future<R>>>()
        try {
            val next = items.iterator()
            while (next.hasNext() || window.isNotEmpty()) {
                while (next.hasNext() && window.size < threads * 2) {
                    val item = next.next()
                    window.addLast(item to pool.submit<R> { work(item) })
                }
                val (item, future) = window.removeFirst()
                val result = try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e // report the real failure, not the wrapper
                }
                consume(item, result)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Names for the ZIP entries. Two towers can produce the same name (several phases of one tower); the later ones get _2, _3, ... */
    private fun uniqueEntryNames(assets: List<Asset>, report: ReportStrategy): Map<String, String> {
        val taken = HashSet<String>() // compared case-insensitively: Windows and macOS extract "A.pdf" and "a.pdf" to one file
        val result = HashMap<String, String>(assets.size * 2)
        for (asset in assets) {
            val wanted = report.entryName(asset)
            var name = wanted
            var n = 2
            while (!taken.add(name.lowercase())) {
                val dot = wanted.lastIndexOf('.').takeIf { it > wanted.lastIndexOf('/') } ?: wanted.length
                name = "${wanted.substring(0, dot)}_${n++}${wanted.substring(dot)}"
            }
            result[asset.id] = name
        }
        return result
    }

    private fun moveIntoPlace(tmp: File, dest: File) {
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw IOException("Could not write the report ZIP to ${dest.absolutePath}: ${e.message}", e)
        }
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

    companion object {
        private const val MAX_PARALLELISM = 8

        /** Leaves a core free for the UI; PDF building is CPU-bound. */
        fun defaultParallelism(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    }
}
