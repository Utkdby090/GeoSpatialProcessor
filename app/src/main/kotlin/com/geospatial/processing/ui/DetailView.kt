package com.geospatial.processing.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geospatial.processing.core.plugin.DomainPlugin
import com.geospatial.processing.domain.model.*
import com.geospatial.processing.ui.components.PropertyGroupForm
import com.geospatial.processing.ui.components.SeverityBadge
import com.geospatial.processing.util.ImageUtils
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.filechooser.FileNameExtensionFilter
import org.jetbrains.skia.Image as SkiaImage

/**
 * Edit form for one asset, built from the plugin's schema:
 * first field group, report classification, images (two per row, sequence navigator before the
 * second row when the plugin asks for it), then the remaining field groups.
 */
@Composable
fun DetailView(
    record: Asset,
    plugin: DomainPlugin,
    imageResolver: AssetImageResolver,
    rootDir: String,
    prevItemName: String?,
    nextItemName: String?,
    onSave: (Asset, Map<String, ImageEdit>) -> Unit
) {
    val schema = remember(record) { plugin.getPropertySchema(record) }
    val groups = remember(schema) { schema.groupBy { it.group }.toList() }
    val presentation = remember(record) { plugin.present(record) }
    val slots = remember(record) { plugin.imageSlots(record) }

    // --- FORM STATE ---
    val values = remember(record) { mutableStateMapOf<String, String>().apply { putAll(AssetForm.values(record, schema)) } }
    var errors by remember(record) { mutableStateOf(emptySet<String>()) }

    // Image changes made in the form; applied by the ViewModel on save.
    val imageEdits = remember(record) { mutableStateMapOf<String, ImageEdit>() }

    // --- PRO EDITOR SELECTION TARGETS ---
    var imageFileToEdit by remember { mutableStateOf<File?>(null) }
    var editingSlotId by remember { mutableStateOf("") }

    // Saved/folder images, overlaid with unsaved edits for preview.
    val savedImages = remember(record, rootDir) { imageResolver.resolve(record, rootDir) }
    fun previewOf(slotId: String): ImageSource = when (val edit = imageEdits[slotId]) {
        is ImageEdit.Replace -> ImageSource.FromBlob(edit.bytes)
        ImageEdit.Clear -> ImageSource.Missing
        null -> savedImages[slotId] ?: ImageSource.Missing
    }
    fun setImage(slotId: String, bytes: ByteArray?, original: ByteArray? = null) {
        imageEdits[slotId] = if (bytes == null || bytes.isEmpty()) {
            ImageEdit.Clear
        } else {
            // An annotation (no new original) on a not-yet-saved upload keeps that upload's original.
            ImageEdit.Replace(bytes, original ?: (imageEdits[slotId] as? ImageEdit.Replace)?.original)
        }
    }

    // --- SAVE LOGIC ---
    fun validateAndSave() {
        errors = AssetForm.validate(schema, values)
        if (errors.isEmpty()) {
            onSave(AssetForm.apply(record, values).copy(status = RecordStatus.READY), imageEdits.toMap())
        }
    }

    @Composable
    fun FieldGroup(name: String, fields: List<com.geospatial.processing.core.plugin.PropertyDefinition>) {
        SectionHeader(name)
        PropertyGroupForm(
            fields = fields,
            values = values,
            errors = errors,
            onValueChange = { key, value -> values[key] = value; errors = errors - key }
        )
        Spacer(modifier = Modifier.height(24.dp))
    }

    @Composable
    fun RowScope.Slot(slot: com.geospatial.processing.core.plugin.ImageSlotDef) {
        ImageSlot(slot.label, previewOf(slot.id), false,
            onUpload = { bytes, original -> setImage(slot.id, bytes, original) },
            onEdit = { file -> imageFileToEdit = file; editingSlotId = slot.id }
        )
    }

    // --- MAIN UI ---
    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("SAVE TOWER DATA", color = MaterialTheme.colorScheme.onPrimary) },
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) },
                onClick = { validateAndSave() },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        }
    ) { padding ->
        Card(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)
            ) {
                // --- HEADER ---
                Text("Tower Inspection Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(24.dp))

                // --- FIRST FIELD GROUP + CLASSIFICATION ---
                groups.firstOrNull()?.let { (name, fields) ->
                    SectionHeader(name)
                    PropertyGroupForm(fields, values, errors, onValueChange = { key, value -> values[key] = value; errors = errors - key })
                }

                OutlinedTextField(
                    value = presentation.reportTitle,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Report Classification") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledContainerColor = if (presentation.isFault) MaterialTheme.colorScheme.error.copy(alpha = 0.1f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                        disabledTextColor = if (presentation.isFault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    ),
                    enabled = false
                )

                // Severity as the form currently stands, so it follows the temperatures filled in from the thermal image.
                val liveSeverity = runCatching { plugin.classify(AssetForm.apply(record, values.toMap())) }.getOrDefault(record.severity)
                if (liveSeverity != Severity.NONE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Severity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.width(8.dp))
                        SeverityBadge(liveSeverity)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- IMAGES ---
                SectionHeader("Inspection Images")
                Column {
                    slots.chunked(2).forEachIndexed { rowIndex, rowSlots ->
                        if (rowIndex > 0) Spacer(modifier = Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (rowIndex == 1 && presentation.showsSequenceNavigator) {
                                TowerSequenceNavigator(
                                    previousItem = prevItemName,
                                    currentItem = presentation.sequenceLabel,
                                    nextItem = nextItemName,
                                    modifier = Modifier.width(65.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            rowSlots.forEachIndexed { i, slot ->
                                if (i > 0) Spacer(modifier = Modifier.width(16.dp))
                                Slot(slot)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- THERMAL ANALYSIS (temperatures from the radiometric original of the thermal slot) ---
                slots.firstOrNull { it.isThermal }?.let { thermal ->
                    val edit = imageEdits[thermal.id]
                    ThermalPanel(
                        loadKey = Triple(record.id, rootDir, edit),
                        loadBytes = {
                            when (edit) {
                                ImageEdit.Clear -> null
                                // An annotation has no original of its own; the one stored for the slot still applies.
                                is ImageEdit.Replace -> edit.original ?: imageResolver.originalFile(record, thermal.id, rootDir)?.readBytes()
                                null -> imageResolver.originalFile(record, thermal.id, rootDir)?.readBytes()
                            }
                        },
                        overrides = plugin.thermalOverrides(values.toMap()),
                        onApply = { stats ->
                            plugin.thermalFindings(stats, values.toMap()).forEach { (key, value) ->
                                values[key] = value
                                errors = errors - key
                            }
                        },
                    )
                }

                // --- REMAINING FIELD GROUPS ---
                groups.drop(1).forEach { (name, fields) -> FieldGroup(name, fields) }
                Spacer(modifier = Modifier.height(56.dp))
            }
        }

        // --- THE PRO ANNOTATION EDITOR POPUP ---
        imageFileToEdit?.let { currentFile ->
            com.geospatial.processing.ui.editor.StandaloneImageEditorWindow(
                initialFile = currentFile,
                onDismiss = { imageFileToEdit = null },
                onSaveToSlot = { modifiedBytes ->
                    setImage(editingSlotId, modifiedBytes)
                    imageFileToEdit = null
                }
            )
        }
    }
}

// --- HELPER 1: Text Field ---
@Composable
fun ValidatedTextField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.secondary,
                focusedBorderColor = MaterialTheme.colorScheme.secondary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                focusedLabelColor = MaterialTheme.colorScheme.secondary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                errorBorderColor = MaterialTheme.colorScheme.error,
                errorLabelColor = MaterialTheme.colorScheme.error
            )
        )
    }
}

// --- HELPER 2: Image Slot (WITH DYNAMIC HOVER ACTION OVERLAYS) ---
@Composable
fun RowScope.ImageSlot(
    label: String,
    imageSource: ImageSource,
    isError: Boolean,
    onUpload: (display: ByteArray?, original: ByteArray?) -> Unit,
    onEdit: (File) -> Unit
) {
    val bitmap: ImageBitmap? = remember(imageSource) {
        try {
            when (imageSource) {
                is ImageSource.FromBlob -> SkiaImage.makeFromEncoded(imageSource.bytes).toComposeImageBitmap()
                is ImageSource.FromFile -> SkiaImage.makeFromEncoded(imageSource.file.readBytes()).toComposeImageBitmap()
                is ImageSource.Missing -> null
            }
        } catch (e: Exception) { null }
    }

    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .clickable {
                    val file = pickImageFile()
                    if (file != null) {
                        // The display copy is compressed; the original is kept so EXIF/radiometric data survives.
                        val compressed = ImageUtils.compressImage(file)
                        if (compressed != null) onUpload(compressed, file.readBytes())
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())

                // ACTION BUTTON CONTROLS (Top Right Side Overlays)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. PRO EDIT BUTTON
                    IconButton(
                        onClick = {
                            when (imageSource) {
                                is ImageSource.FromFile -> onEdit(imageSource.file)
                                is ImageSource.FromBlob -> {
                                    try {
                                        // Unpack DB raw blob bytes safely into a high-speed cache file
                                        val cacheFile = File.createTempFile("spatial_edit_cache_", ".jpg")
                                        cacheFile.writeBytes(imageSource.bytes)
                                        onEdit(cacheFile)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                                is ImageSource.Missing -> {}
                            }
                        },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Create, contentDescription = "Edit Image", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                    }

                    // 2. DELETE BUTTON
                    IconButton(
                        onClick = { onUpload(ByteArray(0), null) },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Image", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }

                if (imageSource is ImageSource.FromFile) {
                    Text(
                        text = "Local File",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), RoundedCornerShape(topEnd = 8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surface,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    Spacer(modifier = Modifier.height(8.dp))
}

private var lastVisitedDirectory: File? = null

fun pickImageFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Select Site Photograph"
    chooser.fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png")

    lastVisitedDirectory?.let {
        chooser.currentDirectory = it
    }
    val parentFrame = JFrame().apply {
        iconImage = getAwtAppIcon()
    }

    // 2. Pass the custom frame instead of 'null'
    val result = chooser.showOpenDialog(parentFrame)

    // 3. Immediately destroy the frame after the user closes the dialog
    parentFrame.dispose()

    return if (result == JFileChooser.APPROVE_OPTION) {
        lastVisitedDirectory = chooser.currentDirectory
        chooser.selectedFile
    } else {
        null
    }
}