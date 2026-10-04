package com.geospatial.processing.domain.thermal

import com.geospatial.processing.domain.imaging.metadata.ImageMetadata
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.ByteArrayInputStream

/**
 * DJI radiometric JPEGs (R-JPEG), decoded by DJI's Thermal SDK (`libdirp`, see [DjiThermalSdk]).
 * The SDK does the calibration itself, so unlike FLIR we only hand it the scene settings and read back °C.
 */
class DjiDecoder : ThermalDecoder {

    override val name = "DJI radiometric JPEG"

    /**
     * A DJI-made file is ours when its metadata says thermal camera, or when the SDK itself accepts it as an R-JPEG.
     * Asking the SDK means new camera models work without extending a model-name list (the Mavic 2 Enterprise Advanced
     * calls itself "MAVIC2-ENTERPRISE-ADVANCED", which no keyword would match), while a plain DJI photo is still refused.
     */
    override fun canDecode(bytes: ByteArray): Boolean {
        val meta = ImageMetadata.read(ByteArrayInputStream(bytes))
        if (meta.make?.contains("dji", ignoreCase = true) != true) return false
        return meta.isThermalHint || DjiThermalSdk.accepts(bytes)
    }

    override fun decode(bytes: ByteArray, overrides: ThermalOverrides): ThermalFrame {
        if (bytes.isEmpty()) throw ThermalDecodeException("The DJI image file is empty.")
        if (!startsLikeJpeg(bytes)) throw ThermalDecodeException("The DJI image file is damaged (it is not a JPEG).")
        val lib = DjiThermalSdk.library
            ?: throw ThermalDecodeException("${DjiThermalSdk.unavailableReason} The picture is shown without temperatures.")

        try {
            val data = DjiThermalSdk.nativeCopy(bytes)
            try {
                val ref = PointerByReference()
                check(lib.dirp_create_from_rjpeg(data, bytes.size, ref), "open")
                val handle = ref.value ?: throw ThermalDecodeException("Could not open the DJI image: the SDK returned no handle.")
                try {
                    return measure(lib, handle, overrides)
                } finally {
                    lib.dirp_destroy(handle)
                }
            } finally {
                data.close() // after destroy: the handle reads the buffer until then
            }
        } catch (e: LinkageError) {
            // A broken native setup (missing function, stripped class). Not an Exception, so it would escape every
            // `catch (e: Exception)` above us and take the app down.
            throw ThermalDecodeException("The DJI Thermal SDK could not be called: ${e.message}", e)
        } catch (e: Error) {
            // JNA turns a native access violation into a plain java.lang.Error. The SDK parses untrusted bytes and
            // a damaged file can trigger one; that must cost this picture its temperatures, not the application.
            if (DjiThermalSdk.isNativeFault(e)) {
                throw ThermalDecodeException("The DJI image is damaged and could not be read safely.", e)
            }
            throw e
        }
    }

    private fun measure(lib: DirpLibrary, handle: Pointer, overrides: ThermalOverrides): ThermalFrame {
        val resolution = DirpResolution()
        check(lib.dirp_get_rjpeg_resolution(handle, resolution), "read the size of")
        val width = resolution.width
        val height = resolution.height
        if (width <= 0 || height <= 0) throw ThermalDecodeException("The DJI image reports an empty thermal size.")
        val byteCount = width.toLong() * height * Float.SIZE_BYTES
        if (byteCount > Int.MAX_VALUE) throw ThermalDecodeException("The DJI thermal image is too large.")

        val fileParams = DirpMeasurementParams()
        check(lib.dirp_get_measurement_params(handle, fileParams), "read the settings of")

        val out = Memory(byteCount)
        try {
            fun measureWith(p: DirpMeasurementParams): Boolean =
                lib.dirp_set_measurement_params(handle, p) == 0 && lib.dirp_measure_ex(handle, out, byteCount.toInt()) == 0

            var applied = fileParams.copy()
            var measured = false

            val wanted = adjustments(lib, handle, fileParams, overrides)
            if (wanted.isNotEmpty()) {
                // All at once first. The SDK rejects some values that are inside its own advertised ranges (an M4T
                // fails to measure at emissivity 0.1, an XT S fails to accept a distance of 1 m), so when that fails
                // each setting is tried on its own and the ones the SDK refuses keep the camera's value.
                val all = fileParams.copy().also { p -> wanted.forEach { it(p) } }
                if (measureWith(all)) {
                    applied = all
                    measured = true
                } else {
                    for (apply in wanted) {
                        val candidate = applied.copy().also(apply)
                        if (measureWith(candidate)) {
                            applied = candidate
                            measured = true
                        }
                    }
                    if (!measured || !measureWith(applied)) { // leave the handle and the buffer consistent with `applied`
                        applied = fileParams.copy()
                        measured = false
                    }
                }
            }
            if (!measured) {
                check(lib.dirp_set_measurement_params(handle, applied), "apply the settings to")
                check(lib.dirp_measure_ex(handle, out, byteCount.toInt()), "measure")
            }

            val temps = FloatArray(width * height)
            out.read(0, temps, 0, temps.size)
            return ThermalFrame(width, height, temps, toThermalParams(applied))
        } finally {
            out.close()
        }
    }

    /**
     * The requested overrides as edits to a parameter set, each clamped to the range the SDK reports.
     * A setting whose reported range is a single value (the H20T and XT S cannot change ambient temperature) is skipped,
     * as are NaN/infinite values (a half-typed form field).
     */
    private fun adjustments(
        lib: DirpLibrary,
        handle: Pointer,
        fileParams: DirpMeasurementParams,
        overrides: ThermalOverrides,
    ): List<(DirpMeasurementParams) -> Unit> {
        fun Double?.usable() = this?.takeIf { it.isFinite() }?.toFloat()
        val emissivity = overrides.emissivity.usable()
        val reflected = overrides.reflectedTempC.usable()
        val ambient = overrides.atmosphericTempC.usable()
        val distance = overrides.distanceM.usable()
        val humidity = overrides.humidityPct.usable()
        if (listOf(emissivity, reflected, ambient, distance, humidity).all { it == null }) return emptyList()

        val range = DirpMeasurementRange()
        check(lib.dirp_get_measurement_params_range(handle, range), "read the setting limits of")

        val edits = ArrayList<(DirpMeasurementParams) -> Unit>(5)
        fun add(value: Float?, min: Float, max: Float, current: Float, set: (DirpMeasurementParams, Float) -> Unit) {
            if (value == null || !(max > min)) return
            val target = value.coerceIn(min, max)
            if (target != current) edits.add { set(it, target) }
        }
        add(distance, range.distanceMin, range.distanceMax, fileParams.distance) { p, v -> p.distance = v }
        add(humidity, range.humidityMin, range.humidityMax, fileParams.humidity) { p, v -> p.humidity = v }
        add(emissivity, range.emissivityMin, range.emissivityMax, fileParams.emissivity) { p, v -> p.emissivity = v }
        add(reflected, range.reflectionMin, range.reflectionMax, fileParams.reflection) { p, v -> p.reflection = v }
        add(ambient, range.ambientMin, range.ambientMax, fileParams.ambientTemp) { p, v -> p.ambientTemp = v }
        return edits
    }

    private fun DirpMeasurementParams.copy() = DirpMeasurementParams().also {
        it.distance = distance
        it.humidity = humidity
        it.emissivity = emissivity
        it.reflection = reflection
        it.ambientTemp = ambientTemp
    }

    /**
     * Only the start can be checked. An R-JPEG does not have to end with the JPEG end marker: DJI appends its
     * thermal payload after it, so most real files would fail an end-of-file test.
     */
    private fun startsLikeJpeg(b: ByteArray): Boolean = b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    /** The SDK keeps its calibration constants to itself, so those fields are NaN; only the scene settings are real. */
    private fun toThermalParams(p: DirpMeasurementParams) = ThermalParams(
        emissivity = p.emissivity.toDouble(),
        reflectedTempC = p.reflection.toDouble(),
        atmosphericTempC = p.ambientTemp.toDouble(),
        distanceM = p.distance.toDouble(),
        humidityPct = p.humidity.toDouble(),
        irWindowTempC = Double.NaN,
        irWindowTransmission = Double.NaN,
        planckR1 = Double.NaN,
        planckR2 = Double.NaN,
        planckB = Double.NaN,
        planckF = Double.NaN,
        planckO = Double.NaN,
        atmAlpha1 = Double.NaN,
        atmAlpha2 = Double.NaN,
        atmBeta1 = Double.NaN,
        atmBeta2 = Double.NaN,
        atmX = Double.NaN,
    )

    private fun check(code: Int, doing: String) {
        if (code == 0) return
        val why = when (code) {
            -4, -5, -6, -7 -> "it is not a valid DJI radiometric JPEG"
            -12 -> "this camera or function is not supported by the SDK"
            -13 -> "the SDK is not ready (a library file may be missing)"
            -14 -> "the SDK could not be activated"
            -15, -16 -> "the SDK library set is incomplete"
            -64 -> "super-resolution pictures are not supported"
            in -63..-32 -> "the SDK reported an internal error ($code)"
            else -> "SDK error $code"
        }
        throw ThermalDecodeException("Could not $doing the DJI image: $why.")
    }
}
