package com.geospatial.processing.domain.thermal

/** Picks the decoder that understands a file. The first decoder that [ThermalDecoder.canDecode] wins. */
class ThermalEngine(private val decoders: List<ThermalDecoder> = listOf(FlirDecoder(), DjiDecoder())) {

    /** The decoder for [bytes], or null when the file is not a radiometric image we know. */
    fun decoderFor(bytes: ByteArray): ThermalDecoder? = decoders.firstOrNull { runCatching { it.canDecode(bytes) }.getOrDefault(false) }

    /** @throws ThermalDecodeException when no decoder knows the file or the matching decoder fails */
    fun decode(bytes: ByteArray, overrides: ThermalOverrides = ThermalOverrides()): ThermalFrame {
        val decoder = decoderFor(bytes) ?: throw ThermalDecodeException("This image has no radiometric (temperature) data.")
        return try {
            decoder.decode(bytes, overrides)
        } catch (e: ThermalDecodeException) {
            throw e
        } catch (e: Exception) {
            throw ThermalDecodeException("The ${decoder.name} could not be read.", e)
        }
    }

    /** Like [decode], but null instead of an exception (plain photos are normal, not errors). */
    fun decodeOrNull(bytes: ByteArray, overrides: ThermalOverrides = ThermalOverrides()): ThermalFrame? =
        runCatching { decode(bytes, overrides) }.getOrNull()
}
