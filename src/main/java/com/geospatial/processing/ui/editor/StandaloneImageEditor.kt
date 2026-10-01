package com.geospatial.processing.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.geospatial.processing.ui.components.rememberAppIcon
import com.geospatial.processing.ui.getAwtAppIcon
import com.geospatial.processing.util.ImageUtils
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.jetbrains.skia.Image as SkiaImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StandaloneImageEditorWindow(
    initialFile: File? = null,
    onDismiss: () -> Unit,
    onSaveToSlot: ((ByteArray) -> Unit)? = null
) {
    var activeFile by remember { mutableStateOf(initialFile) }
    var originalSkiaImage by remember { mutableStateOf<SkiaImage?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var imgRatio by remember { mutableStateOf(1f) }

    // Initial asset decoding task
    LaunchedEffect(initialFile) {
        if (initialFile != null && initialFile.exists()) {
            try {
                val bytes = initialFile.readBytes()
                originalSkiaImage = SkiaImage.makeFromEncoded(bytes)
                imageBitmap = originalSkiaImage!!.toComposeImageBitmap()
                imgRatio = originalSkiaImage!!.width.toFloat() / originalSkiaImage!!.height.toFloat()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Tool Configurations
    var shapes by remember { mutableStateOf(listOf<DrawnShape>()) }
    var currentShape by remember { mutableStateOf<DrawnShape?>(null) }
    var currentTool by remember { mutableStateOf(DrawTool.ARROW) }
    var currentColor by remember { mutableStateOf(Color.Red) }

    // Use a native OS Window instead of a Dialog
    val windowState = rememberWindowState(
        width = 1200.dp,
        height = 800.dp,
        position = WindowPosition(Alignment.Center)
    )

    Window(
        onCloseRequest = onDismiss,
        title = "Image Studio Utility — ${activeFile?.name ?: "No Resource Loaded"}",
        state = windowState,
        icon = rememberAppIcon()
    ) {
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFF20252B))) {

            // --- 1. THE PAINT-STYLE DOCKED APPLICATION RIBBON / TOOLBAR ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(Color(0xFF282E36))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeFile != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProfessionalToolButton("✋ Pan & Move", currentTool == DrawTool.PAN) { currentTool = DrawTool.PAN }
                        ProfessionalToolButton("✎ Free Draw", currentTool == DrawTool.PEN) { currentTool = DrawTool.PEN }
                        ProfessionalToolButton("➔ Sharp Arrow", currentTool == DrawTool.ARROW) { currentTool = DrawTool.ARROW }
                        ProfessionalToolButton("▭ Rectangle", currentTool == DrawTool.RECTANGLE) { currentTool = DrawTool.RECTANGLE }
                        ProfessionalToolButton("◯ Oval Circle", currentTool == DrawTool.CIRCLE) { currentTool = DrawTool.CIRCLE }
                    }

                    Spacer(modifier = Modifier.width(16.dp))
                    HorizontalDivider(modifier = Modifier.height(28.dp).width(1.dp), color = Color.White.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.width(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { if (shapes.isNotEmpty()) shapes = shapes.dropLast(1) }) {
                            Icon(Icons.Default.Refresh, "Step Backward (Undo)", tint = Color.White.copy(alpha = 0.8f))
                        }
                        IconButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
                            Icon(Icons.Default.Search, "Reset View (100%)", tint = Color.White.copy(alpha = 0.8f))
                        }
                        IconButton(onClick = { shapes = emptyList() }) {
                            Icon(Icons.Default.Delete, "Purge Canvas Data", tint = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Zoom: ${(zoom * 100).toInt()}%", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (activeFile == null) {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            onClick = {
                                val file = pickHighResImageFile()
                                if (file != null) {
                                    try {
                                        originalSkiaImage = SkiaImage.makeFromEncoded(file.readBytes())
                                        imageBitmap = originalSkiaImage!!.toComposeImageBitmap()
                                        imgRatio = originalSkiaImage!!.width.toFloat() / originalSkiaImage!!.height.toFloat()
                                        activeFile = file
                                        shapes = emptyList()
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Load Photo Asset", color = Color.White)
                        }
                    }

                    if (activeFile != null && originalSkiaImage != null) {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            onClick = {
                                val exportedBytes = burnShapesToImage(originalSkiaImage!!, canvasSize.toSize(), shapes)
                                if (exportedBytes != null) {
                                    val tempFile = File.createTempFile("studio_output_", ".jpg")
                                    tempFile.writeBytes(exportedBytes)
                                    val finalCompressedBytes = ImageUtils.compressImage(tempFile)

                                    if (finalCompressedBytes != null) {
                                        if (onSaveToSlot != null) {
                                            onSaveToSlot(finalCompressedBytes)
                                        } else {
                                            saveAnnotatedImageToDisk(finalCompressedBytes, activeFile!!.name)
                                        }
                                    }
                                    tempFile.delete()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (onSaveToSlot != null) "Apply to Inspection Card" else "Export File Output", color = Color.White)
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.Black.copy(alpha = 0.3f))

            // --- 2. LARGE CANVAS WORKSPACE (MAXIMIZED AREA) ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF14171A))
                    .clipToBounds()
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val delta = event.changes.first().scrollDelta.y
                        zoom = (zoom - delta * 0.12f).coerceIn(0.4f, 6.0f)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = pan.x,
                                translationY = pan.y
                            )
                    ) {
                        Canvas(
                            modifier = Modifier
                                .aspectRatio(imgRatio, matchHeightConstraintsFirst = false)
                                .align(Alignment.Center)
                                .onSizeChanged { canvasSize = it }
                                .pointerInput(currentTool, currentColor, zoom) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            if (currentTool != DrawTool.PAN) {
                                                currentShape = DrawnShape(tool = currentTool, color = currentColor, points = listOf(offset))
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (currentTool == DrawTool.PAN) {
                                                pan += dragAmount * zoom
                                            } else {
                                                currentShape?.let { currentShape = it.copy(points = it.points + change.position) }
                                            }
                                        },
                                        onDragEnd = {
                                            currentShape?.let { shapes = shapes + it }
                                            currentShape = null
                                        }
                                    )
                                }
                        ) {
                            drawImage(image = imageBitmap!!, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                            shapes.forEach { drawShapeOnCanvas(it) }
                            currentShape?.let { drawShapeOnCanvas(it) }
                        }
                    }
                } else {
                    Text("No active photo layer loaded in studio view.", color = Color.DarkGray, fontSize = 14.sp)
                }
            }
        }
    }
}

// Keep the rest of your helper functions (ProfessionalToolButton, drawShapeOnCanvas, burnShapesToImage, etc.) exactly the same below.


// --- TEXT-BASED RIBBON COMPONENT CONFIGURATION ---
@Composable
private fun ProfessionalToolButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else Color.Transparent
    val borderStrokeColor = if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f) else Color.Transparent
    val tint = if (isSelected) MaterialTheme.colorScheme.secondary else Color.White.copy(alpha = 0.75f)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, borderStrokeColor, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- CORE CRASH-PROOF VECTOR MATH LAYERS ---
private fun DrawScope.drawShapeOnCanvas(shape: DrawnShape) {
    if (shape.points.isEmpty()) return
    val stroke = Stroke(width = shape.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val start = shape.points.first()
    val end = shape.points.last()

    when (shape.tool) {
        DrawTool.PAN -> {}
        DrawTool.PEN -> {
            if (shape.points.size > 1) {
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    for (i in 1 until shape.points.size) lineTo(shape.points[i].x, shape.points[i].y)
                }
                drawPath(path = path, color = shape.color, style = stroke)
            }
        }
        DrawTool.RECTANGLE -> {
            val left = minOf(start.x, end.x)
            val top = minOf(start.y, end.y)
            val width = maxOf(start.x, end.x) - left
            val height = maxOf(start.y, end.y) - top
            drawRect(color = shape.color, topLeft = Offset(left, top), size = Size(width, height), style = stroke)
        }
        DrawTool.CIRCLE -> {
            val left = minOf(start.x, end.x)
            val top = minOf(start.y, end.y)
            val width = maxOf(start.x, end.x) - left
            val height = maxOf(start.y, end.y) - top
            drawOval(color = shape.color, topLeft = Offset(left, top), size = Size(width, height), style = stroke)
        }
        DrawTool.ARROW -> {
            drawLine(color = shape.color, start = start, end = end, strokeWidth = shape.strokeWidth, cap = StrokeCap.Round)
            val angle = atan2(end.y - start.y, end.x - start.x)
            val arrowLen = 24f; val arrowAngle = Math.PI / 6
            val p1 = Offset(end.x - arrowLen * cos(angle - arrowAngle).toFloat(), end.y - arrowLen * sin(angle - arrowAngle).toFloat())
            val p2 = Offset(end.x - arrowLen * cos(angle + arrowAngle).toFloat(), end.y - arrowLen * sin(angle + arrowAngle).toFloat())
            drawLine(color = shape.color, start = end, end = p1, strokeWidth = shape.strokeWidth, cap = StrokeCap.Round)
            drawLine(color = shape.color, start = end, end = p2, strokeWidth = shape.strokeWidth, cap = StrokeCap.Round)
        }
    }
}

// --- MATRICIAL SKIA IMAGE LAYER COMPILER ---
private fun burnShapesToImage(originalImage: SkiaImage, screenCanvasSize: Size, shapes: List<DrawnShape>): ByteArray? {
    if (screenCanvasSize.width == 0f || screenCanvasSize.height == 0f) return null
    try {
        val surface = Surface.makeRasterN32Premul(originalImage.width, originalImage.height)
        val canvas = surface.canvas
        canvas.drawImage(originalImage, 0f, 0f)

        val scaleX = originalImage.width.toFloat() / screenCanvasSize.width
        val scaleY = originalImage.height.toFloat() / screenCanvasSize.height

        val paint = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true; mode = org.jetbrains.skia.PaintMode.STROKE
            strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND; strokeJoin = org.jetbrains.skia.PaintStrokeJoin.ROUND
        }

        shapes.forEach { shape ->
            if (shape.points.isEmpty() || shape.tool == DrawTool.PAN) return@forEach
            paint.color = shape.color.toArgb(); paint.strokeWidth = shape.strokeWidth * scaleX
            val startX = shape.points.first().x * scaleX; val startY = shape.points.first().y * scaleY
            val endX = shape.points.last().x * scaleX; val endY = shape.points.last().y * scaleY

            when (shape.tool) {
                DrawTool.PEN -> {
                    if (shape.points.size > 1) {
                        val path = org.jetbrains.skia.PathBuilder().apply {
                            moveTo(startX, startY)
                            for (i in 1 until shape.points.size) lineTo(shape.points[i].x * scaleX, shape.points[i].y * scaleY)
                        }.detach()
                        canvas.drawPath(path, paint)
                    }
                }
                DrawTool.RECTANGLE -> {
                    val rect = org.jetbrains.skia.Rect.makeLTRB(minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY))
                    canvas.drawRect(rect, paint)
                }
                DrawTool.CIRCLE -> {
                    val rect = org.jetbrains.skia.Rect.makeLTRB(minOf(startX, endX), minOf(startY, endY), maxOf(startX, endX), maxOf(startY, endY))
                    canvas.drawOval(rect, paint)
                }
                DrawTool.ARROW -> {
                    canvas.drawLine(startX, startY, endX, endY, paint)
                    val angle = atan2(endY - startY, endX - startX)
                    val arrowLen = 24f * scaleX; val arrowAngle = Math.PI / 6
                    canvas.drawLine(endX, endY, endX - arrowLen * cos(angle - arrowAngle).toFloat(), endY - arrowLen * sin(angle - arrowAngle).toFloat(), paint)
                    canvas.drawLine(endX, endY, endX - arrowLen * cos(angle + arrowAngle).toFloat(), endY - arrowLen * sin(angle + arrowAngle).toFloat(), paint)
                }
                DrawTool.PAN -> {}
            }
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 95)?.bytes
    } catch (e: Exception) { return null }
}

private var lastVisitedDir: File? = null
fun pickHighResImageFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Select High-Res Original Photo"
        fileFilter = FileNameExtensionFilter("Images", "jpg", "jpeg", "png")
        lastVisitedDir?.let { currentDirectory = it }
    }
    val parentFrame = JFrame().apply {
        iconImage = getAwtAppIcon()
    }

    // 2. Pass the custom frame instead of 'null'
    val result = chooser.showOpenDialog(parentFrame)

    // 3. Immediately destroy the frame after the user closes the dialog
    parentFrame.dispose()
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        lastVisitedDir = chooser.currentDirectory
        chooser.selectedFile
    } else null
}

fun saveAnnotatedImageToDisk(bytes: ByteArray, originalName: String) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Annotated (Compressed) Image"
        selectedFile = File("Annotated_$originalName")
    }

    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile
        val finalFile = if (!file.name.lowercase().endsWith(".jpg")) File(file.absolutePath + ".jpg") else file
        finalFile.writeBytes(bytes)
    }
}