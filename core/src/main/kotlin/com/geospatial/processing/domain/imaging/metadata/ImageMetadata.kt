package com.geospatial.processing.domain.imaging.metadata

import com.drew.imaging.ImageMetadataReader
import com.drew.lang.GeoLocation
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.TimeZone

/** What the camera recorded in one image. Every field is null when the file doesn't carry it. */
data class ImageMeta(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val capturedAt: Instant? = null,
    val make: String? = null,
    val model: String? = null,
    /** True when the metadata alone says this is an infrared image (thermal camera make/model or a FLIR segment). */
    val isThermalHint: Boolean = false,
) {
    val hasGps: Boolean get() = latitude != null && longitude != null
}

/** Reads EXIF/GPS from the ORIGINAL file; re-encoded images (see ImageUtils.compressImage) have none of it. */
object ImageMetadata {

    private val THERMAL_MAKES = setOf("flir", "infrared", "infratec", "testo", "fluke", "seek")
    private val THERMAL_MODEL_KEYWORDS = listOf("xt2", "xt s", "h20t", "h30t", "h20n", "m3t", "m30t", "m4t", "zh20t", "zh20n", "thermal", "flir")

    /** Never throws: unreadable or non-image files give an empty [ImageMeta]. */
    fun read(file: File): ImageMeta =
        runCatching { file.inputStream().buffered().use(::read) }.getOrDefault(ImageMeta())

    fun read(stream: InputStream): ImageMeta {
        val metadata = runCatching { ImageMetadataReader.readMetadata(stream) }.getOrNull() ?: return ImageMeta()
        return from(metadata)
    }

    private fun from(metadata: Metadata): ImageMeta {
        val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val make = ifd0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
        val model = ifd0?.getString(ExifIFD0Directory.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }

        val geo: GeoLocation? = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation
            ?.takeIf { !it.isZero }

        val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val capturedAt = sub?.getDateOriginal(TimeZone.getTimeZone("UTC"))?.toInstant()

        val hasFlirSegment = metadata.directories.any { it.name.contains("FLIR", ignoreCase = true) }

        return ImageMeta(
            latitude = geo?.latitude,
            longitude = geo?.longitude,
            capturedAt = capturedAt,
            make = make,
            model = model,
            isThermalHint = hasFlirSegment || looksThermal(make, model),
        )
    }

    internal fun looksThermal(make: String?, model: String?): Boolean {
        val m = make?.lowercase().orEmpty()
        val mod = model?.lowercase().orEmpty()
        return THERMAL_MAKES.any { it in m } || THERMAL_MODEL_KEYWORDS.any { it in mod }
    }
}
