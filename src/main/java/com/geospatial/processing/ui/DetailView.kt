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
    onSave: (GeoRecord) -> Unit
) {
    // --- FORM DATA STATE ---
    var lineName by remember(record) { mutableStateOf(record.lineName) }
    var towerNumber by remember(record) { mutableStateOf(record.towerNumber) }
    var circuit by remember(record) { mutableStateOf(record.circuit) }
    var lat by remember(record) { mutableStateOf(record.latitude.toString()) }
    var long by remember(record) { mutableStateOf(record.longitude.toString()) }

    var humidity by remember(record) { mutableStateOf(record.humidity) }
    var emissivity by remember(record) { mutableStateOf(record.emissivity) }
    var ambientTemp by remember(record) { mutableStateOf(record.ambientTemp) }
    var loadValue by remember(record) { mutableStateOf(record.loadValue) }

    var faultDesc by remember(record) { mutableStateOf(record.faultDescription) }
    var faultTemp by remember(record) { mutableStateOf(record.faultTemp) }

    // --- MANUAL OVERRIDE STATE ---
    var imgThermal by remember(record) { mutableStateOf(record.thermalImage) }
    var imgVisual by remember(record) { mutableStateOf(record.visualImage) }
    var imgTower by remember(record) { mutableStateOf(record.towerImage) }
    var imgExtra by remember(record) { mutableStateOf(record.extraImage) }

    // --- RESOLVED IMAGE STATE ---
    val resolvedThermal = remember(record, imgThermal, rootDir) {
        record.copy(thermalImage = imgThermal).resolveThermalImage(rootDir)
    }
    val resolvedVisual = remember(record, imgVisual, rootDir) {
        record.copy(visualImage = imgVisual).resolveVisualImage(rootDir)
    }
    val resolvedTower = remember(record, imgTower, rootDir) {
        record.copy(towerImage = imgTower).resolveTowerImage(rootDir)
    }
    val resolvedExtra = remember(record, imgExtra, rootDir) {
        record.copy(extraImage = imgExtra).resolveExtraImage(rootDir)
    }

    // --- VALIDATION STATE ---
    var lineNameError by remember { mutableStateOf(false) }
    var towerNumError by remember { mutableStateOf(false) }
    var circuitError by remember { mutableStateOf(false) }
    var latError by remember { mutableStateOf(false) }
    var longError by remember { mutableStateOf(false) }
    var humidityError by remember { mutableStateOf(false) }
    var emissivityError by remember { mutableStateOf(false) }
    var ambTempError by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var faultDescError by remember { mutableStateOf(false) }
    var faultTempError by remember { mutableStateOf(false) }

    var imgThermalError by remember { mutableStateOf(false) }
    var imgVisualError by remember { mutableStateOf(false) }
    var imgTowerError by remember { mutableStateOf(false) }
    var imgExtraError by remember { mutableStateOf(false) }

    // --- SAVE LOGIC ---
    fun validateAndSave() {
        lineNameError = lineName.isBlank()
        towerNumError = towerNumber.isBlank()
        circuitError = circuit.isBlank()
        latError = lat.isBlank() || lat.toDoubleOrNull() == null
        longError = long.isBlank() || long.toDoubleOrNull() == null
        humidityError = humidity.isBlank()
        emissivityError = emissivity.isBlank()
        ambTempError = ambientTemp.isBlank()
        loadError = loadValue.isBlank()
        faultDescError = faultDesc.isBlank()
        faultTempError = faultTemp.isBlank()

        imgThermalError = resolvedThermal is ImageSource.Missing
        imgVisualError = resolvedVisual is ImageSource.Missing
        imgTowerError = resolvedTower is ImageSource.Missing
        imgExtraError = resolvedExtra is ImageSource.Missing

        val hasTextError = lineNameError || towerNumError || circuitError || latError || longError ||
                humidityError || emissivityError || ambTempError || loadError ||
                faultDescError || faultTempError

        val hasImageError = imgThermalError || imgVisualError || imgTowerError || imgExtraError

        if (!hasTextError && !hasImageError) {
            onSave(record.copy(
                lineName = lineName,
                towerNumber = towerNumber,
                circuit = circuit,
                latitude = lat.toDoubleOrNull() ?: 0.0,
                longitude = long.toDoubleOrNull() ?: 0.0,
                humidity = humidity,
                emissivity = emissivity,
                ambientTemp = ambientTemp,
                loadValue = loadValue,
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
        backgroundColor = Color.Transparent, // Allows the Slate background from MainScreen to show through
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("SAVE TOWER DATA", color = Color.White) },
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) },
                onClick = { validateAndSave() },
                backgroundColor = MaterialTheme.colors.primaryVariant // Uses the dark enterprise navy
            )
        }
    ) { padding ->
        // THE PREMIUM FLOATING CARD
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp), // Breathing room from the edges of the window
            elevation = 4.dp,
            shape = RoundedCornerShape(8.dp),
            backgroundColor = MaterialTheme.colors.surface // Pure white
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp) // Internal padding for the text
            ) {
                // --- HEADER ---
                Text("Tower Inspection Details", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onSurface)
                val statusColor = if (record.status == RecordStatus.READY) Color(0xFF10B981) else Color(0xFFF59E0B) // Emerald vs Amber
                Text("ID: ${record.id} • Status: ${record.status}", color = statusColor, fontWeight = FontWeight.SemiBold)

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 1: LOCATION ---
                SectionHeader("Location Coordinates")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(lineName, { lineName = it; lineNameError = false }, "Line Name", lineNameError, Modifier.weight(1f))
                    ValidatedTextField(towerNumber, { towerNumber = it; towerNumError = false }, "Tower No", towerNumError, Modifier.weight(0.5f))
                    ValidatedTextField(circuit, { circuit = it; circuitError = false }, "Circuit", circuitError, Modifier.weight(0.5f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(lat, { lat = it; latError = false }, "Latitude", latError, Modifier.weight(1f))
                    ValidatedTextField(long, { long = it; longError = false }, "Longitude", longError, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 2: IMAGES ---
                SectionHeader("Inspection Images (All 4 Required)")
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ImageSlot("Thermal Image", resolvedThermal, imgThermalError) { newBytes ->
                            imgThermal = newBytes; imgThermalError = false
                        }
                        ImageSlot("RGB / Visual", resolvedVisual, imgVisualError) { newBytes ->
                            imgVisual = newBytes; imgVisualError = false
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ImageSlot("Full Tower", resolvedTower, imgTowerError) { newBytes ->
                            imgTower = newBytes; imgTowerError = false
                        }
                        ImageSlot("Extra / Zoom", resolvedExtra, imgExtraError) { newBytes ->
                            imgExtra = newBytes; imgExtraError = false
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))


                // --- SECTION 3: PARAMETERS ---
                SectionHeader("Environmental & Load")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(humidity, { humidity = it; humidityError = false }, "Humidity (%)", humidityError, Modifier.weight(1f))
                    ValidatedTextField(emissivity, { emissivity = it; emissivityError = false }, "Emissivity", emissivityError, Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ValidatedTextField(ambientTemp, { ambientTemp = it; ambTempError = false }, "Amb. Temp (°C)", ambTempError, Modifier.weight(1f))
                    ValidatedTextField(loadValue, { loadValue = it; loadError = false }, "Load (Amps)", loadError, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(24.dp))

                // --- SECTION 4: FAULT ANALYSIS ---
                SectionHeader("Fault Analysis")
                ValidatedTextField(faultDesc, { faultDesc = it; faultDescError = false }, "Description", faultDescError, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                ValidatedTextField(faultTemp, { faultTemp = it; faultTempError = false }, "Fault Temperature (°C)", faultTempError, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(80.dp)) // Breathing room for the FAB
            }
        }
    }
}

// --- HELPER 1: Text Field with Red Error Border ---
@Composable
fun ValidatedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            isError = isError,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (isError) Icon(Icons.Default.Warning, "Error", tint = MaterialTheme.colors.error)
            },
            singleLine = true
        )
        if (isError) {
            Text("Required", color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// --- HELPER 2: Hybrid Image Slot ---
@Composable
fun RowScope.ImageSlot(
    label: String,
    imageSource: ImageSource,
    isError: Boolean,
    onUpload: (ByteArray?) -> Unit
) {
    val bitmap: ImageBitmap? = remember(imageSource) {
        try {
            when (imageSource) {
                is ImageSource.FromBlob -> SkiaImage.makeFromEncoded(imageSource.bytes).toComposeImageBitmap()
                is ImageSource.FromFile -> SkiaImage.makeFromEncoded(imageSource.file.readBytes()).toComposeImageBitmap()
                is ImageSource.Missing -> null
            }
        } catch (e: Exception) {
            println("Failed to load image for $label: ${e.message}")
            null
        }
    }

    val borderColor = if (isError) MaterialTheme.colors.error else Color.LightGray
    val borderWidth = if (isError) 2.dp else 1.dp

    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.subtitle2, color = if (isError) MaterialTheme.colors.error else Color.DarkGray)
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF1F5F9)) // Very light slate background for empty slots
                .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
                .clickable {
                    val file = pickImageFile()
                    if (file != null) {
                        val compressed = ImageUtils.compressImage(file)
                        if (compressed != null) onUpload(compressed)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())

                if (imageSource is ImageSource.FromBlob) {
                    IconButton(
                        onClick = { onUpload(null) },
                        modifier = Modifier.align(Alignment.TopEnd).background(Color.White.copy(0.7f))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Override", tint = Color.Red)
                    }
                } else if (imageSource is ImageSource.FromFile) {
                    Text(
                        "Local File",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Color.Black.copy(0.6f), RoundedCornerShape(topEnd = 8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.overline
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val iconTint = if (isError) MaterialTheme.colors.error else Color.Gray
                    Icon(Icons.Default.Add, contentDescription = null, tint = iconTint)
                    Text("Click to override", color = iconTint, style = MaterialTheme.typography.caption)
                }
            }
        }
        if (isError) {
            Text("Image Missing", color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
        }
    }
}

// --- HELPER 3: Common UI ---
@Composable
fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary, modifier = Modifier.padding(bottom = 8.dp))
    Divider(color = Color(0xFFE2E8F0)) // Light border color
    Spacer(modifier = Modifier.height(8.dp))
}

fun pickImageFile(): File? {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Select Site Photograph"
    chooser.fileFilter = FileNameExtensionFilter("Images (JPG, PNG)", "jpg", "jpeg", "png")

    val result = chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}