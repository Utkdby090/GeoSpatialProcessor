package com.geospatial.processing.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
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
    // --- FORM DATA STATE ---
    var lineName by remember(record) { mutableStateOf(record.lineName) }
    var towerNumber by remember(record) { mutableStateOf(record.towerNumber) }
    var circuit by remember(record) { mutableStateOf(record.circuit) }
    var lat by remember(record) { mutableStateOf(record.latitude.toString()) }
    var long by remember(record) { mutableStateOf(record.longitude.toString()) }

    // NEW: Location Extenders & Time
    var phase by remember(record) { mutableStateOf(record.phase ?: "") }
    var side by remember(record) { mutableStateOf(record.side ?: "") }
    var direction by remember(record) { mutableStateOf(record.direction ?: "") }
    var capturedDate by remember(record) { mutableStateOf(record.capturedDate ?: "") }
    var capturedTime by remember(record) { mutableStateOf(record.capturedTime ?: "") }

    var humidity by remember(record) { mutableStateOf(record.humidity) }
    var emissivity by remember(record) { mutableStateOf(record.emissivity) }
    var ambientTemp by remember(record) { mutableStateOf(record.ambientTemp) }

    // NEW: Load Data
    //var loadValue by remember(record) { mutableStateOf(record.loadValue) }
    var loadDataCkt1 by remember(record) { mutableStateOf(record.loadDataCkt1 ?: "") }
    var loadDataCkt2 by remember(record) { mutableStateOf(record.loadDataCkt2 ?: "") }

    // NEW: Fault Analysis
    var faultDesc by remember(record) { mutableStateOf(record.faultDescription) }
    var faultTemp by remember(record) { mutableStateOf(record.faultTemp) }
    var riseTemp by remember(record) { mutableStateOf(record.riseTemp ?: "") }

    // --- MANUAL OVERRIDE STATE ---
    var imgThermal by remember(record) { mutableStateOf(record.thermalImage) }
    var imgVisual by remember(record) { mutableStateOf(record.visualImage) }
    var imgTower by remember(record) { mutableStateOf(record.towerImage) }
    var imgExtra by remember(record) { mutableStateOf(record.extraImage) }

    // --- RESOLVED IMAGE STATE ---
    val resolvedThermal = remember(record, imgThermal, rootDir) { record.copy(thermalImage = imgThermal).resolveThermalImage(rootDir) }
    val resolvedVisual = remember(record, imgVisual, rootDir) { record.copy(visualImage = imgVisual).resolveVisualImage(rootDir) }
    val resolvedTower = remember(record, imgTower, rootDir) { record.copy(towerImage = imgTower).resolveTowerImage(rootDir) }
    val resolvedExtra = remember(record, imgExtra, rootDir) { record.copy(extraImage = imgExtra).resolveExtraImage(rootDir) }

    // --- VALIDATION STATE ---
    var lineNameError by remember { mutableStateOf(false) }
    var towerNumError by remember { mutableStateOf(false) }
    var latError by remember { mutableStateOf(false) }
    var longError by remember { mutableStateOf(false) }

    // --- SAVE LOGIC ---
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

                // Saving New Fields
                phase = phase,
                side = side,
                direction = direction,
                capturedDate = capturedDate,
                capturedTime = capturedTime,
                loadDataCkt1 = loadDataCkt1,
                loadDataCkt2 = loadDataCkt2,
                riseTemp = riseTemp,

                humidity = humidity,
                emissivity = emissivity,
                ambientTemp = ambientTemp,
                //loadValue = loadValue,
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

    // --- MAIN UI ---
    Scaffold(
        backgroundColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("SAVE TOWER DATA", color = Color.White) },
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) },
                onClick = { validateAndSave() },
                backgroundColor = MaterialTheme.colors.primaryVariant
            )
        }
    ) { padding ->
        Card(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            elevation = 4.dp,
            shape = RoundedCornerShape(8.dp),
            backgroundColor = MaterialTheme.colors.surface
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)
            ) {
                // --- HEADER ---
                Text("Tower Inspection Details", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
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
                        // Dynamic colors: Light Orange/Red for Faults, Light Blue for Normal
                        backgroundColor = if (record.isFault) Color(0xFFFDF2E9) else Color(0xFFE8F4F8),
                        disabledTextColor = if (record.isFault) Color.Red else Color.DarkGray
                    ),
                    enabled = false
                )

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 2: IMAGES ---
                SectionHeader("Inspection Images")

                // --- DYNAMIC OFFICIAL PDF LABELS ---
                val isMidSpan = record.reportType == "mid_span"
                val lblLocation = "Location"
                val lblThermal = if (isMidSpan) "THERMAL Image" else "Thermal Image"
                val lblTowerSpan = if (isMidSpan) "SPAN Image" else "Tower Image"
                val lblRgb = "RGB Image"

                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Top Left: Location Map (mapped to visualImage variable)
                        ImageSlot(lblLocation, resolvedVisual, false) { imgVisual = it }

                        // Top Right: Thermal IR (mapped to thermalImage variable)
                        ImageSlot(lblThermal, resolvedThermal, false) { imgThermal = it }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // THE PERFECTLY BALANCED BOTTOM ROW
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                        // 1. The Navigator (Fixed narrow width, pushed to the left)
                        TowerSequenceNavigator(
                            previousItem = prevItemName,
                            currentItem = record.towerNumber, // Or span/sleeve number
                            nextItem = nextItemName,
                            modifier = Modifier.width(65.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // 2. The Bottom-Left Image (Takes 50% of REMAINING space)
                        ImageSlot(lblTowerSpan, resolvedTower, false) { imgTower = it }

                        Spacer(modifier = Modifier.width(16.dp))

                        // 3. The Bottom-Right Image (Takes 50% of REMAINING space)
                        ImageSlot(lblRgb, resolvedExtra, false) { imgExtra = it }
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
               //     ValidatedTextField(loadValue, { loadValue = it }, "General Load", false, Modifier.weight(1f))
                    ValidatedTextField(loadDataCkt1, { loadDataCkt1 = it }, "Load CKT1", false, Modifier.weight(1f))
                    ValidatedTextField(loadDataCkt2, { loadDataCkt2 = it }, "Load CKT2", false, Modifier.weight(1f))
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
    }
}

// --- HELPER 1: Text Field ---
@Composable
fun ValidatedTextField(value: String, onValueChange: (String) -> Unit, label: String, isError: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, isError = isError, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
}

// --- HELPER 2: Image Slot ---

@Composable
fun RowScope.ImageSlot(label: String, imageSource: ImageSource, isError: Boolean, onUpload: (ByteArray?) -> Unit) {
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
        Text(label, style = MaterialTheme.typography.subtitle2, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF1F5F9)).border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)).clickable {
                val file = pickImageFile()
                if (file != null) { val compressed = ImageUtils.compressImage(file); if (compressed != null) onUpload(compressed) }
            },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())

                // --- RESTORED: RED DUSTBIN DELETE BUTTON ---

                IconButton(
                    onClick = { onUpload(ByteArray(0)) }, // Send an empty byte array to clear
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.White.copy(0.9f), RoundedCornerShape(50)).size(36.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Image", tint = Color.Red)
                }

                // --- RESTORED: LOCAL FILE BADGE ---
                if (imageSource is ImageSource.FromFile) {
                    Text("Local File", modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(0.6f), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.overline)
                }
            }
            else Icon(Icons.Default.Add, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary, modifier = Modifier.padding(bottom = 8.dp))
    Divider(color = Color(0xFFE2E8F0))
    Spacer(modifier = Modifier.height(8.dp))
}

// --- GLOBAL CACHE FOR IMAGE PICKER ---
// This variable remembers the last folder you were in across the entire session
private var lastVisitedDirectory: File? = null

fun pickImageFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Select Site Photograph"
    chooser.fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png")

    // If we have a cached directory, tell the chooser to start there
    lastVisitedDirectory?.let {
        chooser.currentDirectory = it
    }

    val result = chooser.showOpenDialog(null)

    return if (result == JFileChooser.APPROVE_OPTION) {
        // Save the directory we just used into the cache for next time!
        lastVisitedDirectory = chooser.currentDirectory
        chooser.selectedFile
    } else {
        null
    }
}