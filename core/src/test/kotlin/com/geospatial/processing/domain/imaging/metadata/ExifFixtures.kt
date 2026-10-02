package com.geospatial.processing.domain.imaging.metadata

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Builds a minimal JPEG that carries only an EXIF block, so tests need no binary fixture files. */
internal object ExifFixtures {

    /** Coordinates are whole degrees/minutes/seconds, e.g. 28°36'0"N 77°12'0"E. */
    data class Dms(
        val latDeg: Int, val latMin: Int, val latSec: Int, val north: Boolean,
        val lonDeg: Int, val lonMin: Int, val lonSec: Int, val east: Boolean,
    )

    fun jpeg(
        make: String? = null,
        model: String? = null,
        gps: Dms? = null,
        dateOriginal: String? = null, // "yyyy:MM:dd HH:mm:ss"
    ): ByteArray {
        val tiff = tiff(make, model, gps, dateOriginal)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort(0xFFD8)
            writeShort(0xFFE1)
            writeShort(2 + 6 + tiff.size)
            write(byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0))
            write(tiff)
            writeShort(0xFFD9)
        }
        return out.toByteArray()
    }

    private class Entry(
        val tag: Int,
        val type: Int,
        val count: Int,
        val inline: ByteArray? = null,
        val data: ByteArray? = null,
        val pointerTo: String? = null,
    )

    private fun ascii(s: String) = (s + "\u0000").toByteArray(Charsets.ISO_8859_1)

    private fun rationals(d: Int, m: Int, s: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        listOf(d, m, s).forEach { out.writeInt(it); out.writeInt(1) }
        return bytes.toByteArray()
    }

    private fun tiff(make: String?, model: String?, gps: Dms?, date: String?): ByteArray {
        val ifd0 = mutableListOf<Entry>()
        make?.let { ifd0 += Entry(0x010F, 2, ascii(it).size, data = ascii(it)) }
        model?.let { ifd0 += Entry(0x0110, 2, ascii(it).size, data = ascii(it)) }
        if (date != null) ifd0 += Entry(0x8769, 4, 1, pointerTo = "exif")
        if (gps != null) ifd0 += Entry(0x8825, 4, 1, pointerTo = "gps")

        val exif = mutableListOf<Entry>()
        date?.let { exif += Entry(0x9003, 2, ascii(it).size, data = ascii(it)) }

        val gpsEntries = mutableListOf<Entry>()
        gps?.let {
            gpsEntries += Entry(0x0001, 2, 2, inline = ascii(if (it.north) "N" else "S").copyOf(4))
            gpsEntries += Entry(0x0002, 5, 3, data = rationals(it.latDeg, it.latMin, it.latSec))
            gpsEntries += Entry(0x0003, 2, 2, inline = ascii(if (it.east) "E" else "W").copyOf(4))
            gpsEntries += Entry(0x0004, 5, 3, data = rationals(it.lonDeg, it.lonMin, it.lonSec))
        }

        fun ifdSize(e: List<Entry>) = 2 + e.size * 12 + 4
        // TIFF stores values of up to 4 bytes inline in the entry instead of behind an offset.
        fun isExternal(e: Entry) = e.data != null && e.data.size > 4
        fun dataSize(e: List<Entry>) = e.filter(::isExternal).sumOf { it.data!!.size + it.data.size % 2 }

        val ifd0Off = 8
        val exifOff = ifd0Off + ifdSize(ifd0) + dataSize(ifd0)
        val gpsOff = exifOff + (if (exif.isEmpty()) 0 else ifdSize(exif) + dataSize(exif))
        val offsets = mapOf("exif" to exifOff, "gps" to gpsOff)

        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte()))
        out.writeShort(0x002A)
        out.writeInt(ifd0Off)

        fun writeIfd(entries: List<Entry>, start: Int) {
            var dataPos = start + ifdSize(entries)
            out.writeShort(entries.size)
            entries.forEach { e ->
                out.writeShort(e.tag)
                out.writeShort(e.type)
                out.writeInt(e.count)
                when {
                    e.pointerTo != null -> out.writeInt(offsets.getValue(e.pointerTo))
                    e.inline != null -> out.write(e.inline)
                    !isExternal(e) -> out.write(e.data!!.copyOf(4))
                    else -> {
                        out.writeInt(dataPos)
                        dataPos += e.data!!.size + e.data.size % 2
                    }
                }
            }
            out.writeInt(0)
            entries.filter(::isExternal).forEach { e -> out.write(e.data!!); if (e.data.size % 2 == 1) out.write(0) }
        }
        writeIfd(ifd0, ifd0Off)
        if (exif.isNotEmpty()) writeIfd(exif, exifOff)
        if (gpsEntries.isNotEmpty()) writeIfd(gpsEntries, gpsOff)
        return bytes.toByteArray()
    }
}
