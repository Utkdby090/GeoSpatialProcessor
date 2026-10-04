package com.geospatial.processing.ui.map

import com.geospatial.processing.domain.map.TileKey
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * Gets tile images as encoded bytes: from the disk cache when present, otherwise from [fetch], and then stores them.
 * Blocking; call from an IO dispatcher. Returns null when the tile can't be had (offline, server error).
 */
class TileLoader(
    private val cacheDir: File,
    private val fetch: (TileKey) -> ByteArray? = OsmTileFetcher::fetch,
) {
    fun load(key: TileKey): ByteArray? {
        val file = File(cacheDir, "${key.zoom}/${key.x}/${key.y}.png")
        if (file.isFile && file.length() > 0) return runCatching { file.readBytes() }.getOrNull()

        val bytes = runCatching { fetch(key) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching {
            file.parentFile.mkdirs()
            // Write to a temp name first so a crash never leaves a half-written tile that would be served as valid.
            // A unique temp name per call, because the map loads tiles on several threads at once.
            val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
        }
        return bytes
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
