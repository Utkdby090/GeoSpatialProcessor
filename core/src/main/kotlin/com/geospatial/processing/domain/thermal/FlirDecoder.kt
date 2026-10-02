package com.geospatial.processing.domain.thermal

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Radiometric FLIR JPEGs (FLIR cameras and drone payloads that write FLIR's file format).
 *
 * The thermal data sits in JPEG APP1 segments that start with "FLIR\0". Their payloads, concatenated in
 * order, form an "FFF" stream: a record index pointing at the raw 16-bit image (an embedded PNG or TIFF)
 * and at the camera parameters (Planck constants, emissivity, distance, humidity…).
 *
 * Only files whose raw image is a PNG/TIFF that the JDK can read are supported; anything else fails with
 * a [ThermalDecodeException] that says so.
 *
 * @param swapBytes whether the raw 16-bit values need their bytes swapped. FLIR writes PNG samples byte-swapped,
 * so null (the default) tries swapped first and falls back to unswapped when that gives impossible temperatures.
 */
class FlirDecoder(private val swapBytes: Boolean? = null) : ThermalDecoder {

    override val name = "FLIR radiometric JPEG"

    override fun canDecode(bytes: ByteArray): Boolean = flirSegments(bytes).isNotEmpty()

    override fun decode(bytes: ByteArray, overrides: ThermalOverrides): ThermalFrame {
        val fff = assembleFff(bytes) ?: throw ThermalDecodeException("This image has no FLIR radiometric data.")
        val records = readRecords(fff)

        val rawRecord = records.firstOrNull { it.type == TYPE_RAW_DATA }
            ?: throw ThermalDecodeException("The FLIR data has no raw thermal image.")
        val paramsRecord = records.firstOrNull { it.type == TYPE_CAMERA_PARAMS }
            ?: throw ThermalDecodeException("The FLIR data has no camera parameters, so temperatures cannot be calculated.")

        val params = readParams(fff, paramsRecord).with(overrides)
        val (width, height, raw) = readRaw(fff, rawRecord)
        val temps = convert(raw, params)
        return ThermalFrame(width, height, temps, params)
    }

    // --- JPEG → FFF stream ----------------------------------------------------------------------

    private class Segment(val index: Int, val payload: ByteArray)

    private fun flirSegments(jpeg: ByteArray): List<Segment> {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return emptyList()
        val found = mutableListOf<Segment>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break // image data / end: no more metadata segments
            if (marker == 0xFF) { pos++; continue }      // fill byte
            if (marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
            val length = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            if (length < 2 || pos + 2 + length > jpeg.size) break
            val dataStart = pos + 4
            val dataEnd = pos + 2 + length
            if (marker == 0xE1 && dataEnd - dataStart > HEADER_SIZE && matchesFlirMagic(jpeg, dataStart)) {
                found += Segment(jpeg[dataStart + 6].toInt() and 0xFF, jpeg.copyOfRange(dataStart + HEADER_SIZE, dataEnd))
            }
            pos = dataEnd
        }
        return found
    }

    private fun matchesFlirMagic(b: ByteArray, at: Int) =
        b[at] == 'F'.code.toByte() && b[at + 1] == 'L'.code.toByte() && b[at + 2] == 'I'.code.toByte() &&
            b[at + 3] == 'R'.code.toByte() && b[at + 4] == 0.toByte()

    private fun assembleFff(jpeg: ByteArray): ByteArray? {
        val segments = flirSegments(jpeg).sortedBy { it.index }
        if (segments.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        segments.forEach { out.write(it.payload) }
        return out.toByteArray()
    }

    // --- FFF records ----------------------------------------------------------------------------

    private class Record(val type: Int, val offset: Int, val length: Int)

    private fun readRecords(fff: ByteArray): List<Record> {
        if (fff.size < 0x40 || fff[0] != 'F'.code.toByte() || fff[1] != 'F'.code.toByte() || fff[2] != 'F'.code.toByte()) {
            throw ThermalDecodeException("The FLIR data is damaged (no FFF header).")
        }
        val le = ByteBuffer.wrap(fff).order(ByteOrder.LITTLE_ENDIAN)
        val order = if (le.getInt(0x14) in 100..200) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(fff).order(order)

        val indexOffset = buf.getInt(0x18)
        val count = buf.getInt(0x1C)
        if (indexOffset < 0 || count < 0 || count > MAX_RECORDS || indexOffset.toLong() + count.toLong() * RECORD_SIZE > fff.size) {
            throw ThermalDecodeException("The FLIR record index is damaged.")
        }
        return (0 until count).mapNotNull { i ->
            val at = indexOffset + i * RECORD_SIZE
            val type = buf.getShort(at).toInt() and 0xFFFF
            val offset = buf.getInt(at + 12)
            val length = buf.getInt(at + 16)
            if (type == 0 || offset < 0 || length <= 0 || offset.toLong() + length > fff.size) null else Record(type, offset, length)
        }
    }

    private fun orderAt(fff: ByteArray, offset: Int): ByteOrder {
        val firstWord = (fff[offset].toInt() and 0xFF) or ((fff[offset + 1].toInt() and 0xFF) shl 8)
        return if (firstWord == 2) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    }

    // --- camera parameters ----------------------------------------------------------------------

    private fun readParams(fff: ByteArray, record: Record): ThermalParams {
        if (record.length < PARAMS_MIN_LENGTH) throw ThermalDecodeException("The FLIR camera parameters are truncated.")
        val buf = ByteBuffer.wrap(fff).order(orderAt(fff, record.offset))
        fun f(at: Int) = buf.getFloat(record.offset + at).toDouble()
        fun kelvinToC(k: Double) = k - RadiometricCalibration.KELVIN
        fun valid(v: Double) = v.isFinite()

        val humidity = f(0x3C).let { if (it <= 1.5) it * 100.0 else it }
        val params = ThermalParams(
            emissivity = f(0x20),
            distanceM = f(0x24),
            reflectedTempC = kelvinToC(f(0x28)),
            atmosphericTempC = kelvinToC(f(0x2C)),
            irWindowTempC = kelvinToC(f(0x30)),
            irWindowTransmission = f(0x34),
            humidityPct = humidity,
            planckR1 = f(0x58),
            planckB = f(0x5C),
            planckF = f(0x60),
            atmAlpha1 = f(0x70).takeIf { it != 0.0 } ?: DEFAULT_ALPHA1,
            atmAlpha2 = f(0x74).takeIf { it != 0.0 } ?: DEFAULT_ALPHA2,
            atmBeta1 = f(0x78).takeIf { it != 0.0 } ?: DEFAULT_BETA1,
            atmBeta2 = f(0x7C).takeIf { it != 0.0 } ?: DEFAULT_BETA2,
            atmX = f(0x80).takeIf { it != 0.0 } ?: DEFAULT_X,
            planckO = buf.getInt(record.offset + 0x1E0).toDouble(),
            planckR2 = f(0x1E4),
        )
        val essentials = listOf(params.emissivity, params.distanceM, params.reflectedTempC, params.atmosphericTempC, params.planckR1, params.planckR2, params.planckB, params.planckF)
        if (!essentials.all(::valid) || params.planckR1 == 0.0 || params.planckR2 == 0.0 || params.planckB == 0.0) {
            throw ThermalDecodeException("The FLIR calibration constants are missing or unreadable.")
        }
        return params
    }

    // --- raw image ------------------------------------------------------------------------------

    private data class Raw(val width: Int, val height: Int, val values: IntArray)

    private fun readRaw(fff: ByteArray, record: Record): Raw {
        if (record.length <= RAW_HEADER_SIZE) throw ThermalDecodeException("The FLIR raw image is empty.")
        val image = try {
            ImageIO.read(ByteArrayInputStream(fff, record.offset + RAW_HEADER_SIZE, record.length - RAW_HEADER_SIZE))
        } catch (e: Exception) {
            null
        } ?: throw ThermalDecodeException("This camera stores its raw thermal image in a format that is not supported yet.", null)

        val raster = image.raster
        if (raster.numBands != 1 || image.colorModel.pixelSize < 16) {
            throw ThermalDecodeException("The raw thermal image is not 16-bit greyscale.")
        }
        val w = raster.width
        val h = raster.height
        val values = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) values[y * w + x] = raster.getSample(x, y, 0)
        return Raw(w, h, values)
    }

    private fun convert(values: IntArray, params: ThermalParams): FloatArray {
        val calibration = RadiometricCalibration(params)
        fun swapped(v: Int) = ((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)

        val swap = swapBytes ?: run {
            // Judge by the middle of the image: a real scene is somewhere between deep freeze and a furnace.
            val sample = values.filter { it != 0 }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
            fun plausible(raw: Int) = calibration.tempC(raw).let { !it.isNaN() && it in PLAUSIBLE_RANGE_C }
            plausible(swapped(sample)) || !plausible(sample)
        }
        return FloatArray(values.size) { i ->
            val v = values[i]
            calibration.tempC(if (swap) swapped(v) else v)
        }
    }

    private companion object {
        const val HEADER_SIZE = 8           // "FLIR\0", version, index, last index
        const val RECORD_SIZE = 32
        const val MAX_RECORDS = 1024
        const val TYPE_RAW_DATA = 0x01
        const val TYPE_CAMERA_PARAMS = 0x20
        const val RAW_HEADER_SIZE = 32
        const val PARAMS_MIN_LENGTH = 0x1E8
        val PLAUSIBLE_RANGE_C = -80f..700f

        // FLIR's published defaults for the atmospheric transmission model, for files that leave them at zero.
        const val DEFAULT_ALPHA1 = 0.006569
        const val DEFAULT_ALPHA2 = 0.01262
        const val DEFAULT_BETA1 = -0.002276
        const val DEFAULT_BETA2 = -0.00667
        const val DEFAULT_X = 1.9
    }
}
