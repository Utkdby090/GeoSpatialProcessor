package com.geospatial.processing.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
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
import com.geospatial.processing.domain.model.*
import com.geospatial.processing.util.ImageUtils
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.filechooser.FileNameExtensionFilter
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun DetailView(
    record: GeoRecord,
    rootDir: String,
    prevItemName: String?,
    nextItemName: String?,
    onSave: (GeoRecord) -> Unit
) {
    // --- 1. DETERMINE LABELS EARLY SO THEY ARE IN SCOPE EVERYWHERE ---
    val type = record.reportType.lowercase()
    val isMidSpan = type.contains("mid")
    val isSleeve = type.contains("sleeve")
    val isEarthWire = type.contains("earth")

    val lblLocation = "Location"
    val lblThermal = if (isMidSpan) "THERMAL Image" else "Thermal Image"
    val lblTowerSpan = when {
        isMidSpan -> "SPAN Image"
        isSleeve -> "SLEEVE Image"
        isEarthWire -> "EARTH WIRE Image"
        else -> "Tower Image"
    }
    val lblRgb = "RGB Image"

    // --- 2. FORM DATA STATE ---
    var lineName by remember(record) { mutableStateOf(record.lineName) }
    var towerNumber by remember(record) { mutableStateOf(record.towerNumber) }
    var circuit by remember(record) { mutableStateOf(record.circuit) }
    var lat by remember(record) { mutableStateOf(record.latitude.toString()) }
    var long by remember(record) { mutableStateOf(record.longitude.toString()) }

    // Location Extenders & Time
    var phase by remember(record) { mutableStateOf(record.phase ?: "") }
    var side by remember(record) { mutableStateOf(record.side ?: "") }
    var direction by remember(record) { mutableStateOf(record.direction ?: "") }
    var capturedDate by remember(record) { mutableStateOf(record.capturedDate ?: "") }
    var capturedTime by remember(record) { mutableStateOf(record.capturedTime ?: "") }

    var humidity by remember(record) { mutableStateOf(record.humidity) }
    var emissivity by remember(record) { mutableStateOf(record.emissivity) }
    var ambientTemp by remember(record) { mutableStateOf(record.ambientTemp) }

    // Dynamic Circuit State
    val dynamicCircuitsState = remember(record) {
        mutableStateMapOf<String, String>().apply { putAll(record.dynamicCircuits) }
    }

    // Fault Analysis
    var faultDesc by remember(record) { mutableStateOf(record.faultDescription) }
    var faultTemp by remember(record) { mutableStateOf(record.faultTemp) }
    var riseTemp by remember(record) { mutableStateOf(record.riseTemp ?: "") }

    // --- 3. MANUAL OVERRIDE STATE ---
    var imgThermal by remember(record) { mutableStateOf(record.thermalImage) }
    var imgVisual by remember(record) { mutableStateOf(record.visualImage) }
    var imgTower by remember(record) { mutableStateOf(record.towerImage) }
    var imgExtra by remember(record) { mutableStateOf(record.extraImage) }

    // --- PRO EDITOR SELECTION TARGETS ---
    var imageFileToEdit by remember { mutableStateOf<File?>(null) }
    var editingSlotLabel by remember { mutableStateOf<String>("") }

    // Resolved Image State
    val resolvedThermal = remember(record, imgThermal, rootDir) { record.copy(thermalImage = imgThermal).resolveThermalImage(rootDir) }
    val resolvedVisual = remember(record, imgVisual, rootDir) { record.copy(visualImage = imgVisual).resolveVisualImage(rootDir) }
    val resolvedTower = remember(record, imgTower, rootDir) { record.copy(towerImage = imgTower).resolveTowerImage(rootDir) }
    val resolvedExtra = remember(record, imgExtra, rootDir) { record.copy(extraImage = imgExtra).resolveExtraImage(rootDir) }

    // Validation State
    var lineNameError by remember { mutableStateOf(false) }
    var towerNumError by remember { mutableStateOf(false) }
    var latError by remember { mutableStateOf(false) }
    var longError by remember { mutableStateOf(false) }

    // --- 4. SAVE LOGIC ---
    fun validateAndSave() {
        lineNameError = lineName.isBlank()
        towerNumError = towerNumber.isBlank()
        latError = lat.isBlank() || lat.toDoubleOrNull() == null
        longError = long.isBlank() || long.toDoubleOrNull() == null

        val hasTextError = lineNameError || towerNumError || latError || longError

        if (!hasTextError) {
            onSave(record.copy(
                lineName = lineName,
                towerNumber = towerNumber,
                circuit = circuit,
                latitude = lat.toDoubleOrNull() ?: 0.0,
                longitude = long.toDoubleOrNull() ?: 0.0,

                phase = phase,
                side = side,
                direction = direction,
                capturedDate = capturedDate,
                capturedTime = capturedTime,

                dynamicCircuits = dynamicCircuitsState.toMap(),

                riseTemp = riseTemp,
                humidity = humidity,
                emissivity = emissivity,
                ambientTemp = ambientTemp,
                faultDescription = faultDesc,
                faultTemp = faultTemp,
                thermalImage = imgThermal,
                visualImage = imgVisual,
                towerImage = imgTower,
                extraImage = imgExtra,
                status = RecordStatus.READY
            ))
        }
    }

    // --- 5. MAIN UI ---
    Scaffold(
        backgroundColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("SAVE TOWER DATA", color = MaterialTheme.colors.onPrimary) },
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colors.onPrimary) },
                onClick = { validateAndSave() },
                backgroundColor = MaterialTheme.colors.primaryVariant
            )
        }
    ) { padding ->
        Card(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            elevation = 0.dp,
            shape = RoundedCornerShape(12.dp),
            backgroundColor = MaterialTheme.colors.surface,
            border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)
            ) {
                // --- HEADER ---
                Text("Tower Inspection Details", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onSurface)
                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 1: LOCATION & TIME ---
                SectionHeader("Location & Temporal Data")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(lineName, { lineName = it; lineNameError = false }, "Line Name", lineNameError, Modifier.weight(1f))
                    ValidatedTextField(towerNumber, { towerNumber = it; towerNumError = false }, "Tower No", towerNumError, Modifier.weight(0.5f))
                    ValidatedTextField(circuit, { circuit = it }, "Circuit", false, Modifier.weight(0.5f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(phase, { phase = it }, "Phase", false, Modifier.weight(1f))
                    ValidatedTextField(side, { side = it }, "Side", false, Modifier.weight(1f))
                    ValidatedTextField(direction, { direction = it }, "Direction", false, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(capturedDate, { capturedDate = it }, "Captured Date", false, Modifier.weight(1f))
                    ValidatedTextField(capturedTime, { capturedTime = it }, "Captured Time", false, Modifier.weight(1f))
                    ValidatedTextField(lat, { lat = it; latError = false }, "Latitude", latError, Modifier.weight(1f))
                    ValidatedTextField(long, { long = it; longError = false }, "Longitude", longError, Modifier.weight(1f))
                }

                val reportDisplay = record.resolvedReportTitle

                OutlinedTextField(
                    value = reportDisplay,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Report Classification") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        backgroundColor = if (record.isFault) MaterialTheme.colors.error.copy(alpha = 0.1f) else MaterialTheme.colors.secondary.copy(alpha = 0.1f),
                        disabledTextColor = if (record.isFault) MaterialTheme.colors.error else MaterialTheme.colors.onSurface
                    ),
                    enabled = false
                )

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 2: IMAGES ---
                SectionHeader("Inspection Images")

                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ImageSlot(lblLocation, resolvedVisual, false,
                            onUpload = { imgVisual = it },
                            onEdit = { file -> imageFileToEdit = file; editingSlotLabel = lblLocation }
                        )
                        ImageSlot(lblThermal, resolvedThermal, false,
                            onUpload = { imgThermal = it },
                            onEdit = { file -> imageFileToEdit = file; editingSlotLabel = lblThermal }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                        if (!isMidSpan && !isSleeve && !isEarthWire) {
                            TowerSequenceNavigator(
                                previousItem = prevItemName,
                                currentItem = record.towerNumber,
                                nextItem = nextItemName,
                                modifier = Modifier.width(65.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }

                        ImageSlot(lblTowerSpan, resolvedTower, false,
                            onUpload = { imgTower = it },
                            onEdit = { file -> imageFileToEdit = file; editingSlotLabel = lblTowerSpan }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        ImageSlot(lblRgb, resolvedExtra, false,
                            onUpload = { imgExtra = it },
                            onEdit = { file -> imageFileToEdit = file; editingSlotLabel = lblRgb }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 3: PARAMETERS & LOAD ---
                SectionHeader("Environmental & Load Data")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(humidity, { humidity = it }, "Humidity (%)", false, Modifier.weight(1f))
                    ValidatedTextField(emissivity, { emissivity = it }, "Emissivity", false, Modifier.weight(1f))
                    ValidatedTextField(ambientTemp, { ambientTemp = it }, "Amb. Temp (°C)", false, Modifier.weight(1f))
                }

                if (dynamicCircuitsState.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Dynamic Load Circuits", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(8.dp))

                    val keys = dynamicCircuitsState.keys.toList()
                    keys.chunked(3).forEach { rowKeys ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            rowKeys.forEach { key ->
                                ValidatedTextField(
                                    value = dynamicCircuitsState[key] ?: "",
                                    onValueChange = { newValue -> dynamicCircuitsState[key] = newValue },
                                    label = key,
                                    isError = false,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - rowKeys.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 4: FAULT ANALYSIS ---
                SectionHeader("Fault Analysis")
                ValidatedTextField(faultDesc, { faultDesc = it }, "Fault Description", false, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(faultTemp, { faultTemp = it }, "Fault Temp (°C)", false, Modifier.weight(1f))
                    ValidatedTextField(riseTemp, { riseTemp = it }, "Rise Temp (°C)", false, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // --- NEW: THE PRO ANNOTATION EDITOR POPUP ---
        imageFileToEdit?.let { currentFile ->
            com.geospatial.processing.ui.editor.StandaloneImageEditorWindow(
                initialFile = currentFile,
                onDismiss = {
                    // This kills the child window instantly when the X is clicked
                    imageFileToEdit = null
                },
                onSaveToSlot = { modifiedBytes ->
                    // Instantly saves the compressed output back to the UI!
                    when (editingSlotLabel) {
                        lblLocation -> imgVisual = modifiedBytes
                        lblThermal -> imgThermal = modifiedBytes
                        lblRgb -> imgExtra = modifiedBytes
                        else -> imgTower = modifiedBytes
                    }
                    // Setting this to null closes the Window safely
                    imageFileToEdit = null
                }
            )
        }

    } // end Scaffold
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
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.colors.onSurface,
                cursorColor = MaterialTheme.colors.secondary,
                focusedBorderColor = MaterialTheme.colors.secondary,
                unfocusedBorderColor = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                focusedLabelColor = MaterialTheme.colors.secondary,
                unfocusedLabelColor = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                errorBorderColor = MaterialTheme.colors.error,
                errorLabelColor = MaterialTheme.colors.error
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
    onUpload: (ByteArray?) -> Unit,
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
        Text(label, style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.04f))
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .clickable {
                    val file = pickImageFile()
                    if (file != null) { val compressed = ImageUtils.compressImage(file); if (compressed != null) onUpload(compressed) }
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
                            .background(MaterialTheme.colors.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Create, contentDescription = "Edit Image", tint = MaterialTheme.colors.secondary, modifier = Modifier.size(16.dp))
                    }

                    // 2. DELETE BUTTON
                    IconButton(
                        onClick = { onUpload(ByteArray(0)) },
                        modifier = Modifier
                            .background(MaterialTheme.colors.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Image", tint = MaterialTheme.colors.error, modifier = Modifier.size(16.dp))
                    }
                }

                if (imageSource is ImageSource.FromFile) {
                    Text(
                        text = "Local File",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(MaterialTheme.colors.onBackground.copy(alpha = 0.7f), RoundedCornerShape(topEnd = 8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colors.surface,
                        style = MaterialTheme.typography.overline
                    )
                }
            } else {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colors.onSurface.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary, modifier = Modifier.padding(bottom = 8.dp))
    Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.12f))
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