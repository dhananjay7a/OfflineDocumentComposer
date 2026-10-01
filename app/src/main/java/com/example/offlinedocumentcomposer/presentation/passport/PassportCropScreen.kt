package com.example.offlinedocumentcomposer.presentation.passport

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import com.example.offlinedocumentcomposer.data.model.ImageAdjustments
import com.example.offlinedocumentcomposer.domain.image.ImageProcessor
import com.example.offlinedocumentcomposer.domain.passport.PassportPhotoStandard
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportCropScreen(
    sourceBitmap: Bitmap,
    initialStandard: PassportPhotoStandard = PassportPhotoStandard.INDIAN_PASSPORT,
    queueIndex: Int = 1,
    queueTotal: Int = 1,
    onBack: () -> Unit,
    onSkip: (() -> Unit)? = null,
    onCropApplied: (croppedBitmap: Bitmap, standard: PassportPhotoStandard, customW: Float, customH: Float) -> Unit
) {
    var selectedStandard by remember { mutableStateOf(initialStandard) }
    var customWidthMm by remember { mutableStateOf(35f) }
    var customHeightMm by remember { mutableStateOf(45f) }
    var showCustomDialog by remember { mutableStateOf(false) }

    var rotationDegrees by remember { mutableStateOf(0f) }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var showGuides by remember { mutableStateOf(true) }

    // Transform bitmap if rotated
    val orientedBitmap = remember(sourceBitmap, rotationDegrees) {
        if (rotationDegrees == 0f) {
            sourceBitmap
        } else {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(sourceBitmap, 0, 0, sourceBitmap.width, sourceBitmap.height, matrix, true)
        }
    }

    val targetAspect = remember(selectedStandard, customWidthMm, customHeightMm) {
        if (selectedStandard == PassportPhotoStandard.CUSTOM) {
            customWidthMm / customHeightMm.coerceAtLeast(1f)
        } else {
            selectedStandard.aspectRatio
        }
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Normalized crop box in range [0..1] relative to the image
    var cropLeftNorm by remember { mutableStateOf(0.1f) }
    var cropTopNorm by remember { mutableStateOf(0.1f) }
    var cropRightNorm by remember { mutableStateOf(0.9f) }
    var cropBottomNorm by remember { mutableStateOf(0.9f) }

    // Reset crop box when aspect ratio or orientation changes
    LaunchedEffect(targetAspect, orientedBitmap.width, orientedBitmap.height) {
        val imgW = orientedBitmap.width.toFloat()
        val imgH = orientedBitmap.height.toFloat()
        val imgAspect = imgW / imgH

        if (imgAspect > targetAspect) {
            // Image is wider than target aspect ratio
            val h = 0.85f
            val w = (h * imgH * targetAspect) / imgW
            cropTopNorm = (1f - h) / 2f
            cropBottomNorm = cropTopNorm + h
            cropLeftNorm = (1f - w) / 2f
            cropRightNorm = cropLeftNorm + w
        } else {
            // Image is taller than target aspect ratio
            val w = 0.85f
            val h = (w * imgW / targetAspect) / imgH
            cropLeftNorm = (1f - w) / 2f
            cropRightNorm = cropLeftNorm + w
            cropTopNorm = (1f - h) / 2f
            cropBottomNorm = cropTopNorm + h
        }
    }

    var activeHandle by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (queueTotal > 1) "Crop Photo ($queueIndex of $queueTotal)" else "Crop Passport Photo",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = when (selectedStandard) {
                                PassportPhotoStandard.INDIAN_PASSPORT -> "Indian Passport • 35 × 45 mm"
                                PassportPhotoStandard.PAN_CARD -> "PAN Card • 25 × 35 mm"
                                PassportPhotoStandard.STAMP_SIZE -> "Stamp Size • 20 × 25 mm"
                                PassportPhotoStandard.US_VISA -> "US Visa • 2 × 2 in (51 × 51 mm)"
                                PassportPhotoStandard.CUSTOM -> "Custom • ${customWidthMm.toInt()} × ${customHeightMm.toInt()} mm"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (queueTotal > 1 && onSkip != null) {
                        TextButton(onClick = onSkip) {
                            Text("Skip", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    IconButton(onClick = { showGuides = !showGuides }) {
                        Icon(
                            if (showGuides) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Face Guide",
                            tint = if (showGuides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Standard Preset Selector Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PassportPhotoStandard.values().forEach { std ->
                            FilterChip(
                                selected = selectedStandard == std,
                                onClick = {
                                    if (std == PassportPhotoStandard.CUSTOM) {
                                        showCustomDialog = true
                                    }
                                    selectedStandard = std
                                },
                                label = {
                                    Text(
                                        when (std) {
                                            PassportPhotoStandard.INDIAN_PASSPORT -> "Indian Passport (35×45 mm)"
                                            PassportPhotoStandard.PAN_CARD -> "PAN Card (25×35 mm)"
                                            PassportPhotoStandard.STAMP_SIZE -> "Stamp Size (20×25 mm)"
                                            PassportPhotoStandard.US_VISA -> "US Visa (2×2 in)"
                                            PassportPhotoStandard.CUSTOM -> "Custom (${customWidthMm.toInt()}×${customHeightMm.toInt()} mm)"
                                        }
                                    )
                                },
                                leadingIcon = if (selectedStandard == std) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                // Extract cropped bitmap
                                val imgW = orientedBitmap.width
                                val imgH = orientedBitmap.height

                                val leftPx = (cropLeftNorm * imgW).toInt().coerceIn(0, imgW - 1)
                                val topPx = (cropTopNorm * imgH).toInt().coerceIn(0, imgH - 1)
                                val rightPx = (cropRightNorm * imgW).toInt().coerceIn(leftPx + 10, imgW)
                                val bottomPx = (cropBottomNorm * imgH).toInt().coerceIn(topPx + 10, imgH)

                                val cropW = max(10, rightPx - leftPx)
                                val cropH = max(10, bottomPx - topPx)

                                val rawCropped = Bitmap.createBitmap(orientedBitmap, leftPx, topPx, cropW, cropH)

                                // Apply image adjustments if any
                                val finalCropped = if (brightness != 0f || contrast != 0f) {
                                    val processor = ImageProcessor()
                                    val adj = ImageAdjustments(
                                        brightness = brightness,
                                        contrast = contrast,
                                        sharpness = 10f
                                    )
                                    val adjusted = processor.applyAdjustments(rawCropped, adj)
                                    rawCropped.recycle()
                                    adjusted
                                } else {
                                    rawCropped
                                }

                                onCropApplied(finalCropped, selectedStandard, customWidthMm, customHeightMm)
                            },
                            modifier = Modifier.weight(1.5f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0F9D58)
                            )
                        ) {
                            val isLast = queueTotal <= 1 || queueIndex >= queueTotal
                            Icon(
                                if (isLast) Icons.Default.Crop else Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (isLast) "Save Crop" else "Next Photo",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF121212)),
            contentAlignment = Alignment.Center
        ) {
            val imgW = orientedBitmap.width.toFloat()
            val imgH = orientedBitmap.height.toFloat()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .onSizeChanged { canvasSize = it },
                contentAlignment = Alignment.Center
            ) {
                if (canvasSize.width > 0 && canvasSize.height > 0) {
                    val viewW = canvasSize.width.toFloat()
                    val viewH = canvasSize.height.toFloat()
                    val scale = min(viewW / imgW, viewH / imgH)

                    val renderedW = imgW * scale
                    val renderedH = imgH * scale
                    val offsetX = (viewW - renderedW) / 2f
                    val offsetY = (viewH - renderedH) / 2f

                    // Screen coordinate bounds of the crop box
                    val screenLeft = offsetX + cropLeftNorm * renderedW
                    val screenTop = offsetY + cropTopNorm * renderedH
                    val screenRight = offsetX + cropRightNorm * renderedW
                    val screenBottom = offsetY + cropBottomNorm * renderedH

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(canvasSize, targetAspect) {
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val handleHitRadius = 40.dp.toPx()
                                        activeHandle = when {
                                            hypot(pos.x - screenLeft, pos.y - screenTop) < handleHitRadius -> "TL"
                                            hypot(pos.x - screenRight, pos.y - screenTop) < handleHitRadius -> "TR"
                                            hypot(pos.x - screenRight, pos.y - screenBottom) < handleHitRadius -> "BR"
                                            hypot(pos.x - screenLeft, pos.y - screenBottom) < handleHitRadius -> "BL"
                                            pos.x in screenLeft..screenRight && pos.y in screenTop..screenBottom -> "MOVE"
                                            else -> null
                                        }
                                    },
                                    onDragEnd = { activeHandle = null },
                                    onDragCancel = { activeHandle = null },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val dxNorm = dragAmount.x / renderedW
                                        val dyNorm = dragAmount.y / renderedH

                                        when (activeHandle) {
                                            "MOVE" -> {
                                                val wNorm = cropRightNorm - cropLeftNorm
                                                val hNorm = cropBottomNorm - cropTopNorm

                                                var newL = (cropLeftNorm + dxNorm).coerceIn(0f, 1f - wNorm)
                                                var newT = (cropTopNorm + dyNorm).coerceIn(0f, 1f - hNorm)
                                                cropLeftNorm = newL
                                                cropRightNorm = newL + wNorm
                                                cropTopNorm = newT
                                                cropBottomNorm = newT + hNorm
                                            }
                                            "BR" -> {
                                                var newR = (cropRightNorm + dxNorm).coerceIn(cropLeftNorm + 0.15f, 1f)
                                                val newW = newR - cropLeftNorm
                                                val newH = (newW * imgW / targetAspect) / imgH
                                                if (cropTopNorm + newH <= 1f) {
                                                    cropRightNorm = newR
                                                    cropBottomNorm = cropTopNorm + newH
                                                }
                                            }
                                            "BL" -> {
                                                var newL = (cropLeftNorm + dxNorm).coerceIn(0f, cropRightNorm - 0.15f)
                                                val newW = cropRightNorm - newL
                                                val newH = (newW * imgW / targetAspect) / imgH
                                                if (cropTopNorm + newH <= 1f) {
                                                    cropLeftNorm = newL
                                                    cropBottomNorm = cropTopNorm + newH
                                                }
                                            }
                                            "TR" -> {
                                                var newR = (cropRightNorm + dxNorm).coerceIn(cropLeftNorm + 0.15f, 1f)
                                                val newW = newR - cropLeftNorm
                                                val newH = (newW * imgW / targetAspect) / imgH
                                                if (cropBottomNorm - newH >= 0f) {
                                                    cropRightNorm = newR
                                                    cropTopNorm = cropBottomNorm - newH
                                                }
                                            }
                                            "TL" -> {
                                                var newL = (cropLeftNorm + dxNorm).coerceIn(0f, cropRightNorm - 0.15f)
                                                val newW = cropRightNorm - newL
                                                val newH = (newW * imgW / targetAspect) / imgH
                                                if (cropBottomNorm - newH >= 0f) {
                                                    cropLeftNorm = newL
                                                    cropTopNorm = cropBottomNorm - newH
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        // 1. Draw source bitmap
                        val srcRect = android.graphics.Rect(0, 0, orientedBitmap.width, orientedBitmap.height)
                        val dstRect = android.graphics.RectF(offsetX, offsetY, offsetX + renderedW, offsetY + renderedH)
                        drawContext.canvas.nativeCanvas.drawBitmap(orientedBitmap, srcRect, dstRect, null)

                        // 2. Draw dim dark scrim overlay outside crop box
                        val scrimColor = Color(0x99000000)
                        // Top scrim
                        drawRect(scrimColor, Offset(0f, 0f), Size(viewW, screenTop))
                        // Bottom scrim
                        drawRect(scrimColor, Offset(0f, screenBottom), Size(viewW, viewH - screenBottom))
                        // Left scrim
                        drawRect(scrimColor, Offset(0f, screenTop), Size(screenLeft, screenBottom - screenTop))
                        // Right scrim
                        drawRect(scrimColor, Offset(screenRight, screenTop), Size(viewW - screenRight, screenBottom - screenTop))

                        // 3. Draw crop box border
                        val boxW = screenRight - screenLeft
                        val boxH = screenBottom - screenTop
                        drawRect(
                            color = Color(0xFF00E676),
                            topLeft = Offset(screenLeft, screenTop),
                            size = Size(boxW, boxH),
                            style = Stroke(width = 2.5.dp.toPx())
                        )

                        // 4. Draw ICAO Passport Face Alignment Guidelines (Head & Chin guide overlay)
                        if (showGuides) {
                            val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                            val guideStroke = Stroke(width = 1.5.dp.toPx(), pathEffect = dashedEffect)

                            // Crown Line (Top of head, approx 12% from top)
                            val crownY = screenTop + boxH * 0.12f
                            drawLine(
                                color = Color(0xDDFFD54F),
                                start = Offset(screenLeft, crownY),
                                end = Offset(screenRight, crownY),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = dashedEffect
                            )

                            // Eyes Line (Approx 42% from top)
                            val eyeY = screenTop + boxH * 0.42f
                            drawLine(
                                color = Color(0x88FFFFFF),
                                start = Offset(screenLeft + boxW * 0.2f, eyeY),
                                end = Offset(screenRight - boxW * 0.2f, eyeY),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = dashedEffect
                            )

                            // Chin Line (Bottom of chin, approx 80% from top for 70-80% face coverage)
                            val chinY = screenTop + boxH * 0.80f
                            drawLine(
                                color = Color(0xDDFFD54F),
                                start = Offset(screenLeft, chinY),
                                end = Offset(screenRight, chinY),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = dashedEffect
                            )

                            // Face Oval Outline Guide
                            val ovalCenterX = screenLeft + boxW / 2f
                            val ovalCenterY = screenTop + boxH * 0.46f
                            val ovalRadiusX = boxW * 0.28f
                            val ovalRadiusY = boxH * 0.34f
                            drawOval(
                                color = Color(0xBBFFD54F),
                                topLeft = Offset(ovalCenterX - ovalRadiusX, ovalCenterY - ovalRadiusY),
                                size = Size(ovalRadiusX * 2f, ovalRadiusY * 2f),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashedEffect)
                            )
                        }

                        // 5. Draw 4 Corner Handles
                        val cornerRadius = 12.dp.toPx()
                        val handleColor = Color.White
                        val handleBorder = Color(0xFF00E676)

                        listOf(
                            Offset(screenLeft, screenTop),
                            Offset(screenRight, screenTop),
                            Offset(screenRight, screenBottom),
                            Offset(screenLeft, screenBottom)
                        ).forEach { pt ->
                            drawCircle(color = handleBorder, radius = cornerRadius + 2.dp.toPx(), center = pt)
                            drawCircle(color = handleColor, radius = cornerRadius, center = pt)
                        }
                    }
                }
            }
        }
    }

    // Custom dimensions dialog
    if (showCustomDialog) {
        var tempW by remember { mutableStateOf(customWidthMm.toInt().toString()) }
        var tempH by remember { mutableStateOf(customHeightMm.toInt().toString()) }

        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom Photo Size (mm)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter width and height in millimeters:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = tempW,
                        onValueChange = { tempW = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Width (mm)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = tempH,
                        onValueChange = { tempH = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("Height (mm)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val w = tempW.toFloatOrNull() ?: 35f
                        val h = tempH.toFloatOrNull() ?: 45f
                        customWidthMm = w.coerceIn(15f, 150f)
                        customHeightMm = h.coerceIn(15f, 150f)
                        selectedStandard = PassportPhotoStandard.CUSTOM
                        showCustomDialog = false
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
