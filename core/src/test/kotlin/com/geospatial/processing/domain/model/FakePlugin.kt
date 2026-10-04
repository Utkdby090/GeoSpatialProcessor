package com.geospatial.processing.domain.model

import com.geospatial.processing.core.plugin.*
import java.io.File
import java.io.OutputStream

/** Minimal plugin for :core tests: slots A and B, folder images named "<slot>.jpg" in <root>/<name>/. */
internal open class FakePlugin(
    private val slots: List<String> = listOf("A", "B"),
    private val schema: List<PropertyDefinition> = emptyList(),
) : DomainPlugin {
    override val pluginId = "test"
    override val displayName = "Test"
    override val description = ""
    override val iconName = ""

    override fun getPropertySchema(asset: Asset) = schema
    override fun imageSlots(asset: Asset) = slots.map { ImageSlotDef(it, "Slot $it") }
    override fun present(asset: Asset) = AssetPresentation(
        listTitle = asset.property("name"), listSubtitle = "", sequenceLabel = asset.property("name"),
        reportTitle = "Report", isFault = false, showsSequenceNavigator = false,
    )
    override fun resolveFolderImages(rootDir: String, asset: Asset): Map<String, File> =
        slots.mapNotNull { slot ->
            File(rootDir, "${asset.property("name")}/$slot.jpg").takeIf { it.isFile }?.let { slot to it }
        }.toMap()

    override fun getCsvImportStrategy() = object : CsvImportStrategy {
        override fun parse(file: File, onProgress: (Float) -> Unit) = emptyList<AssetDraft>()
    }

    val written: MutableList<Triple<String, String?, String?>> = java.util.Collections.synchronizedList(mutableListOf())

    override fun getReportStrategy() = object : ReportStrategy {
        override fun entryName(asset: Asset) = "${asset.property("folder")}/${asset.property("name")}.pdf"
        override fun writePdf(asset: Asset, images: Map<String, ByteArray?>, previous: String?, next: String?, out: OutputStream) {
            written += Triple(asset.property("name"), previous, next)
            out.write("pdf:${asset.property("name")}:${images.values.count { it != null }}".toByteArray())
        }
    }
}

internal fun asset(name: String, position: Int = 0, status: RecordStatus = RecordStatus.DRAFT, vararg props: Pair<String, String>) =
    Asset(pluginId = "test", position = position, status = status, latitude = 0.0, longitude = 0.0,
        properties = mapOf("name" to name) + props)
