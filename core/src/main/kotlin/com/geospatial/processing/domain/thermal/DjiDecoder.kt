package com.geospatial.processing.domain.thermal

import com.geospatial.processing.domain.imaging.metadata.ImageMetadata
import java.io.ByteArrayInputStream

/**
 * Placeholder for DJI radiometric JPEGs (R-JPEG). Their temperatures need DJI's Thermal SDK (a native library),
 * which is not integrated yet. It exists so the app can say that clearly instead of "unknown format",
 * and so the real decoder can later replace it without touching callers.
 */
class DjiDecoder : ThermalDecoder {

    override val name = "DJI radiometric JPEG"

    override fun canDecode(bytes: ByteArray): Boolean {
        val meta = ImageMetadata.read(ByteArrayInputStream(bytes))
        return meta.isThermalHint && meta.make?.contains("dji", ignoreCase = true) == true
    }

    override fun decode(bytes: ByteArray, overrides: ThermalOverrides): ThermalFrame =
        throw ThermalDecodeException("DJI radiometric images are not supported yet (they need the DJI Thermal SDK). The picture is shown without temperatures.")
}
