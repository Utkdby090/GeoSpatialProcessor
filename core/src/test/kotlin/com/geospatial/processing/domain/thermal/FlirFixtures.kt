package com.geospatial.processing.domain.thermal

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Builds radiometric FLIR JPEGs in memory, following the published layout (APP1 "FLIR\0" chunks → FFF stream →
 * record index → raw PNG + camera parameters), so decoder tests need no binary sample files.
 *
 * NOTE: this encodes the layout as documented by ExifTool/Thermimage; it cannot prove the decoder matches a real
 * camera file. Keep a genuine sample next to these tests when one is available.
 */
internal object FlirFixtures {

    // Typical constants of a FLIR T-series camera.
    const val R1 = 21106.77
    const val R2 = 0.012545258
    const val B = 1501.0
    const val F = 1.0
    const val O = -7340

    /** Sensor counts of a perfect black body at [tempC]. */
    fun rawFor(tempC: Double): Int = (R1 / (R2 * (exp(B / (tempC + 273.15)) - F)) - O).roundToInt()

    data class Scene(
        val emissivity: Float = 1f,
        val distanceM: Float = 0f,
        val reflectedC: Float = 20f,
        val atmosphericC: Float = 20f,
        val humidity: Float = 0.5f,
    )

    fun jpeg(
        width: Int,
        height: Int,
        raw: IntArray,
        scene: Scene = Scene(),
        bigEndian: Boolean = false,
        storeSwapped: Boolean = true,
        chunks: Int = 1,
        shuffleChunks: Boolean = false,
    ): ByteArray {
        val fff = fff(width, height, raw, scene, if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN, storeSwapped)
        val parts = split(fff, chunks)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val ordered = if (shuffleChunks) parts.withIndex().reversed() else parts.withIndex().toList()
        ordered.forEach { (index, part) ->
            val payload = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'I'.code.toByte(), 'R'.code.toByte(), 0, 1, index.toByte(), (parts.size - 1).toByte()) + part
            out.write(0xFF); out.write(0xE1)
            out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
            out.write(payload)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun split(bytes: ByteArray, n: Int): List<ByteArray> {
        val size = (bytes.size + n - 1) / n
        return bytes.toList().chunked(size).map { it.toByteArray() }
    }

    private fun png(width: Int, height: Int, raw: IntArray, storeSwapped: Boolean): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY)
        raw.forEachIndexed { i, v ->
            val stored = if (storeSwapped) ((v and 0xFF) shl 8) or ((v shr 8) and 0xFF) else v
            image.raster.setSample(i % width, i / width, 0, stored)
        }
        return ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
    }

    private fun params(scene: Scene, order: ByteOrder): ByteArray {
        val buf = ByteBuffer.allocate(0x400).order(order)
        buf.putShort(0, 2)
        buf.putFloat(0x20, scene.emissivity)
        buf.putFloat(0x24, scene.distanceM)
        buf.putFloat(0x28, scene.reflectedC + 273.15f)
        buf.putFloat(0x2C, scene.atmosphericC + 273.15f)
        buf.putFloat(0x30, 20f + 273.15f)
        buf.putFloat(0x34, 1f)
        buf.putFloat(0x3C, scene.humidity)
        buf.putFloat(0x58, R1.toFloat())
        buf.putFloat(0x5C, B.toFloat())
        buf.putFloat(0x60, F.toFloat())
        buf.putFloat(0x70, 0.006569f)
        buf.putFloat(0x74, 0.01262f)
        buf.putFloat(0x78, -0.002276f)
        buf.putFloat(0x7C, -0.00667f)
        buf.putFloat(0x80, 1.9f)
        buf.putInt(0x308, O)
        buf.putFloat(0x30C, R2.toFloat())
        return buf.array()
    }

    private fun fff(width: Int, height: Int, raw: IntArray, scene: Scene, order: ByteOrder, storeSwapped: Boolean): ByteArray {
        val rawBlock = ByteBuffer.allocate(32).order(order).apply { putShort(0, 2); putShort(2, width.toShort()); putShort(4, height.toShort()) }.array() +
            png(width, height, raw, storeSwapped)
        val paramBlock = params(scene, order)

        val headerSize = 0x40
        val rawOffset = headerSize
        val paramOffset = rawOffset + rawBlock.size
        val indexOffset = paramOffset + paramBlock.size
        val records = listOf(1 to (rawOffset to rawBlock.size), 0x20 to (paramOffset to paramBlock.size))

        val buf = ByteBuffer.allocate(indexOffset + records.size * 32).order(order)
        buf.put(byteArrayOf('F'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0))
        buf.position(0x14); buf.putInt(100); buf.putInt(indexOffset); buf.putInt(records.size)
        buf.position(rawOffset); buf.put(rawBlock)
        buf.position(paramOffset); buf.put(paramBlock)
        records.forEachIndexed { i, (type, loc) ->
            val at = indexOffset + i * 32
            buf.putShort(at, type.toShort())
            buf.putInt(at + 12, loc.first)
            buf.putInt(at + 16, loc.second)
        }
        return buf.array()
    }
}
