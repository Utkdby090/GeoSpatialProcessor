package com.geospatial.processing.util

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam // FIXED: Was WriteParam
import kotlin.math.max

object ImageUtils {

    private const val MAX_DIMENSION = 1024 // Max width or height in pixels
    private const val COMPRESSION_QUALITY = 0.75f // 75% Quality

    /**
     * Reads an image file, resizes it to fit within 1024x1024,
     * and compresses it to JPEG.
     */
    fun compressImage(file: File): ByteArray? {
        if (!file.exists()) return null

        return try {
            // 1. Read the original image
            val originalImage = ImageIO.read(file) ?: return null

            // 2. Calculate new dimensions (maintain aspect ratio)
            val (newWidth, newHeight) = calculateDimensions(originalImage.width, originalImage.height)

            // 3. Resize using efficient scaling
            val resizedImage = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
            val graphics = resizedImage.createGraphics()
            graphics.drawImage(originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
            graphics.dispose()

            // 4. Compress to JPEG
            val outStream = ByteArrayOutputStream()

            // Get a writer for JPEG
            val writers = ImageIO.getImageWritersByFormatName("jpg")
            if (!writers.hasNext()) return null // Safety check
            val writer = writers.next()

            val ios = ImageIO.createImageOutputStream(outStream)
            writer.output = ios

            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                // FIXED: Use ImageWriteParam here
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = COMPRESSION_QUALITY
            }

            writer.write(null, IIOImage(resizedImage, null, null), param)

            // Clean up
            writer.dispose()
            ios.close()

            outStream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateDimensions(currentWidth: Int, currentHeight: Int): Pair<Int, Int> {
        if (currentWidth <= MAX_DIMENSION && currentHeight <= MAX_DIMENSION) {
            return Pair(currentWidth, currentHeight)
        }

        val ratio = currentWidth.toDouble() / currentHeight.toDouble()
        return if (currentWidth > currentHeight) {
            Pair(MAX_DIMENSION, (MAX_DIMENSION / ratio).toInt())
        } else {
            Pair((MAX_DIMENSION * ratio).toInt(), MAX_DIMENSION)
        }
    }
}