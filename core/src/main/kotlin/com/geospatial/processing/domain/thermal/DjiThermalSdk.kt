package com.geospatial.processing.domain.thermal

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** JNA view of the C API in the DJI Thermal SDK's `dirp_api.h` (only the calls we use). */
internal interface DirpLibrary : Library {
    fun dirp_create_from_rjpeg(data: Pointer, size: Int, handle: PointerByReference): Int
    fun dirp_destroy(handle: Pointer): Int
    fun dirp_get_rjpeg_resolution(handle: Pointer, resolution: DirpResolution): Int
    fun dirp_measure_ex(handle: Pointer, tempImage: Pointer, size: Int): Int
    fun dirp_get_measurement_params(handle: Pointer, params: DirpMeasurementParams): Int
    fun dirp_set_measurement_params(handle: Pointer, params: DirpMeasurementParams): Int
    fun dirp_get_measurement_params_range(handle: Pointer, range: DirpMeasurementRange): Int
}

// The C structs are declared with #pragma pack(1).
@Structure.FieldOrder("width", "height")
internal class DirpResolution : Structure(ALIGN_NONE) {
    @JvmField var width = 0
    @JvmField var height = 0
}

@Structure.FieldOrder("distance", "humidity", "emissivity", "reflection", "ambientTemp")
internal class DirpMeasurementParams : Structure(ALIGN_NONE) {
    @JvmField var distance = 0f
    @JvmField var humidity = 0f
    @JvmField var emissivity = 0f
    @JvmField var reflection = 0f
    @JvmField var ambientTemp = 0f
}

@Structure.FieldOrder("distanceMin", "distanceMax", "humidityMin", "humidityMax", "emissivityMin", "emissivityMax", "reflectionMin", "reflectionMax", "ambientMin", "ambientMax")
internal class DirpMeasurementRange : Structure(ALIGN_NONE) {
    @JvmField var distanceMin = 0f
    @JvmField var distanceMax = 0f
    @JvmField var humidityMin = 0f
    @JvmField var humidityMax = 0f
    @JvmField var emissivityMin = 0f
    @JvmField var emissivityMax = 0f
    @JvmField var reflectionMin = 0f
    @JvmField var reflectionMax = 0f
    @JvmField var ambientMin = 0f
    @JvmField var ambientMax = 0f
}

/**
 * Loads DJI's `libdirp.dll` once. The SDK needs its whole library set (libdirp, libv_*, Micro*, libexif ...) plus
 * `libv_list.ini` in one folder. The set ships inside this jar (`/dji-sdk/windows-x64`) and is extracted to a per-user
 * folder on first use, so every packaging route (Gradle run, installer, Conveyor) behaves the same.
 * For development a folder can be forced with the `geospatial.dji.sdk` system property or `GEOSPATIAL_DJI_SDK` variable.
 */
internal object DjiThermalSdk {

    const val PROPERTY = "geospatial.dji.sdk"
    const val ENVIRONMENT = "GEOSPATIAL_DJI_SDK"

    private const val SDK_VERSION = "1.8"
    private const val RESOURCE_DIR = "/dji-sdk/windows-x64"
    internal val FILES = listOf(
        "libdirp.dll", "libexif.dll", "libiconv-2.dll", "libintl-8.dll",
        "libv_dirp.dll", "libv_girp.dll", "libv_hirp.dll", "libv_iirp.dll",
        "MicroIA_Release_x64.dll", "MicroJPEG_Release_x64.dll", "MicroTA_Release_x64.dll", "libv_list.ini",
    )

    /** The loaded library, or null with [unavailableReason] explaining why not. */
    val library: DirpLibrary? by lazy { load() }

    var unavailableReason: String? = null
        private set

    private fun overrideDir(): File? =
        listOfNotNull(System.getProperty(PROPERTY), System.getenv(ENVIRONMENT))
            .map(::File).firstOrNull { File(it, "libdirp.dll").isFile }

    /** Copies the bundled libraries next to each other under the user's local app data (once per SDK version). */
    internal fun extractBundled(
        base: File = File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir")),
    ): File? {
        val dir = File(base, "GeoSpatialProcessor/dji-sdk-$SDK_VERSION")
        return try {
            dir.mkdirs()
            for (name in FILES) {
                val target = File(dir, name)
                val bytes = DjiThermalSdk::class.java.getResourceAsStream("$RESOURCE_DIR/$name")?.use { it.readBytes() } ?: return null
                // Compare content, not just size: a half-written or damaged copy of the same length must not be loaded.
                if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
                val tmp = File(dir, "$name.${java.util.UUID.randomUUID()}.tmp") // unique per call: threads and processes can race
                tmp.writeBytes(bytes)
                try {
                    java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (e: java.io.IOException) { // a copy loaded by another running instance is locked; use what is there
                    tmp.delete()
                    if (!target.isFile) return null
                }
            }
            dir
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun load(): DirpLibrary? {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        if (!os.startsWith("Windows") || (arch != "amd64" && arch != "x86_64")) {
            unavailableReason = "The DJI Thermal SDK is only bundled for 64-bit Windows."
            return null
        }
        val dir = overrideDir() ?: extractBundled()
        if (dir == null) {
            unavailableReason = "The DJI Thermal SDK libraries could not be unpacked."
            return null
        }
        return try {
            // Turn a native access violation (the SDK parses untrusted files) into a catchable Error instead of a JVM crash.
            Native.setProtected(true)
            NativeLibrary.addSearchPath("libdirp", dir.absolutePath)
            Native.load(File(dir, "libdirp.dll").absolutePath, DirpLibrary::class.java)
        } catch (e: Throwable) { // UnsatisfiedLinkError is an Error, not an Exception
            unavailableReason = "The DJI Thermal SDK could not be loaded: ${e.message}"
            null
        }
    }

    /** JNA's protected mode reports an access violation inside native code as a plain `java.lang.Error`. */
    fun isNativeFault(e: Error): Boolean =
        e.javaClass == Error::class.java && e.message.orEmpty().contains("memory access", ignoreCase = true)

    /** True when the SDK is available and recognises [bytes] as a radiometric JPEG. Never throws. */
    fun accepts(bytes: ByteArray): Boolean {
        val lib = library ?: return false
        if (bytes.size < 4) return false
        return try {
            val data = nativeCopy(bytes)
            try {
                val ref = PointerByReference()
                val ok = lib.dirp_create_from_rjpeg(data, bytes.size, ref) == 0 && ref.value != null
                if (ok) lib.dirp_destroy(ref.value)
                ok
            } finally {
                data.close()
            }
        } catch (e: Exception) {
            false
        } catch (e: LinkageError) {
            false
        } catch (e: Error) { // JNA raises a bare Error for a native fault; a probe must not escalate it. Anything else (OOM) propagates.
            if (isNativeFault(e)) false else throw e
        }
    }

    /** A copy of [bytes] in native memory; the SDK keeps pointing at it until the handle is destroyed. */
    fun nativeCopy(bytes: ByteArray): Memory = Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
}
