package com.geospatial.processing.ui.map

import com.geospatial.processing.domain.map.TileKey
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
        }
        return bytes
    }
}

/** OpenStreetMap's public tile server. Its usage policy requires an identifying User-Agent and caching, which [TileLoader] does. */
object OsmTileFetcher {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    fun fetch(key: TileKey): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create("https://tile.openstreetmap.org/${key.zoom}/${key.x}/${key.y}.png"))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "GeoSpatialProcessor/2.1 (tower inspection desktop app)")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return if (response.statusCode() == 200) response.body() else null
    }
}
