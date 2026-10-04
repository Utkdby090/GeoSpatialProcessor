package com.geospatial.processing.ui.map

import com.geospatial.processing.domain.map.TileKey
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore

/**
 * Gets tile images as encoded bytes: from the disk cache when present, otherwise from [fetch], and then stores them.
 * Blocking; call from an IO dispatcher. Returns null when the tile can't be had (offline, server error).
 */
class TileLoader(
    private val cacheDir: File,
    private val fetch: (TileKey) -> ByteArray? = OsmTileFetcher::fetch,
) {
    /** Loads in progress, so that several callers asking for one tile share a single download instead of one each. */
    private val inFlight = ConcurrentHashMap<TileKey, CompletableFuture<ByteArray?>>()

    fun load(key: TileKey): ByteArray? {
        val file = File(cacheDir, "${key.zoom}/${key.x}/${key.y}.png")
        readCached(file)?.let { return it }

        val mine = CompletableFuture<ByteArray?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return await(running)

        try {
            // Re-check: the loader that just finished may have stored it between our first look and registering.
            val bytes = readCached(file) ?: download(key, file)
            mine.complete(bytes)
            return bytes
        } catch (t: Throwable) {
            mine.complete(null) // never leave waiting callers hanging
            throw t
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun await(running: CompletableFuture<ByteArray?>): ByteArray? = try {
        running.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: ExecutionException) {
        null
    }

    /** A cache hit, or null when the tile is absent, empty or unreadable (treated as a miss and fetched again). */
    private fun readCached(file: File): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } else null

    private fun download(key: TileKey, file: File): ByteArray? {
        val bytes = runCatching { fetch(key) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        store(file, bytes)
        return bytes
    }

    /**
     * Writes to a temp name first and then moves it into place, so a crash or a full disk never leaves a half-written
     * tile that would be served as valid. An existing good tile is left alone: replacing a file that another thread is
     * reading fails that read on Windows. The temp name is unique per call and removed whatever happens. Failing to
     * cache is not an error: the tile is downloaded again next time.
     */
    private fun store(file: File, bytes: ByteArray) {
        if (file.isFile && file.length() > 0) return // already cached (an empty leftover is overwritten below)
        var tmp: Path? = null
        try {
            val dir = file.parentFile.toPath()
            Files.createDirectories(dir)
            tmp = Files.createTempFile(dir, file.name, ".tmp")
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            // Already stored by someone else, the disk is full, or the cache folder is read-only.
        } finally {
            tmp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

/**
 * OpenStreetMap's public tile server. Its usage policy requires an identifying User-Agent, caching (which [TileLoader]
 * does) and few parallel downloads, which [downloads] enforces however many threads the map uses.
 */
object OsmTileFetcher {
    private val downloads = Semaphore(2)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    fun fetch(key: TileKey): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create("https://tile.openstreetmap.org/${key.zoom}/${key.x}/${key.y}.png"))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "GeoSpatialProcessor/2.1 (tower inspection desktop app)")
            .GET()
            .build()
        downloads.acquire()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } finally {
            downloads.release()
        }
        return if (response.statusCode() == 200) response.body() else null
    }
}
