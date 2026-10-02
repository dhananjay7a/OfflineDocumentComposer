package com.example.offlinedocumentcomposer.presentation.passport

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Image Zoom & Pan State
    var zoomScale by remember { mutableStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

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

    // Normalized crop box in range [0..1] relative to the base unzoomed render view
    var cropLeftNorm by remember { mutableStateOf(0.1f) }
    var cropTopNorm by remember { mutableStateOf(0.1f) }
    var cropRightNorm by remember { mutableStateOf(0.9f) }
    var cropBottomNorm by remember { mutableStateOf(0.9f) }

    // Reset crop box when aspect ratio changes
    LaunchedEffect(targetAspect, orientedBitmap.width, orientedBitmap.height) {
        val imgW = orientedBitmap.width.toFloat()
        val imgH = orientedBitmap.height.toFloat()
        val imgAspect = imgW / imgH

        if (imgAspect > targetAspect) {
            val h = 0.85f
            val w = (h * imgH * targetAspect) / imgW
            cropTopNorm = (1f - h) / 2f
            cropBottomNorm = cropTopNorm + h
            cropLeftNorm = (1f - w) / 2f
            cropRightNorm = cropLeftNorm + w
        } else {
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
                            contentDescription = "Toggle Face Guides",
                            tint = if (showGuides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    // Standards selector pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PassportPhotoStandard.values().forEach { standard ->
                            val isSelected = selectedStandard == standard
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (standard == PassportPhotoStandard.CUSTOM) {
                                        showCustomDialog = true
                                    } else {
                                        selectedStandard = standard
                                    }
                                },
                                label = {
                                    Text(
                                        when (standard) {
                                            PassportPhotoStandard.INDIAN_PASSPORT -> "Indian Passport (35×45)"
                                            PassportPhotoStandard.PAN_CARD -> "PAN Card (25×35)"
                                            PassportPhotoStandard.STAMP_SIZE -> "Stamp Size (20×25)"
                                            PassportPhotoStandard.US_VISA -> "US Visa (2×2\")"
                                            PassportPhotoStandard.CUSTOM -> "Custom Size"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = if (isSelected) {
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
                                val imgW = orientedBitmap.width.toFloat()
                                val imgH = orientedBitmap.height.toFloat()

                                val viewW = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val viewH = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val baseScale = min(viewW / imgW, viewH / imgH)
                                val renderedW = imgW * baseScale
                                val renderedH = imgH * baseScale
                                val baseOffsetX = (viewW - renderedW) / 2f
                                val baseOffsetY = (viewH - renderedH) / 2f

                                val zoomedW = renderedW * zoomScale
                                val zoomedH = renderedH * zoomScale
                                val maxPanX = (zoomedW - renderedW) / 2f
                                val maxPanY = (zoomedH - renderedH) / 2f
                                val clampedPanX = panOffset.x.coerceIn(-maxPanX, maxPanX)
                                val clampedPanY = panOffset.y.coerceIn(-maxPanY, maxPanY)

                                val imgScreenLeft = baseOffsetX - (zoomedW - renderedW) / 2f + clampedPanX
                                val imgScreenTop = baseOffsetY - (zoomedH - renderedH) / 2f + clampedPanY

                                val screenLeft = baseOffsetX + cropLeftNorm * renderedW
                                val screenTop = baseOffsetY + cropTopNorm * renderedH
                                val screenRight = baseOffsetX + cropRightNorm * renderedW
                                val screenBottom = baseOffsetY + cropBottomNorm * renderedH

                                val cropLeftInImg = (((screenLeft - imgScreenLeft) / zoomedW) * imgW).toInt().coerceIn(0, imgW.toInt() - 1)
                                val cropTopInImg = (((screenTop - imgScreenTop) / zoomedH) * imgH).toInt().coerceIn(0, imgH.toInt() - 1)
                                val cropRightInImg = (((screenRight - imgScreenLeft) / zoomedW) * imgW).toInt().coerceIn(cropLeftInImg + 10, imgW.toInt())
                                val cropBottomInImg = (((screenBottom - imgScreenTop) / zoomedH) * imgH).toInt().coerceIn(cropTopInImg + 10, imgH.toInt())

                                val cropW = max(10, cropRightInImg - cropLeftInImg)
                                val cropH = max(10, cropBottomInImg - cropTopInImg)

                                val rawCropped = Bitmap.createBitmap(orientedBitmap, cropLeftInImg, cropTopInImg, cropW, cropH)

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
                    val baseScale = min(viewW / imgW, viewH / imgH)

                    val renderedW = imgW * baseScale
                    val renderedH = imgH * baseScale
                    val baseOffsetX = (viewW - renderedW) / 2f
                    val baseOffsetY = (viewH - renderedH) / 2f

                    // Zoom and Pan calculations
                    val zoomedW = renderedW * zoomScale
                    val zoomedH = renderedH * zoomScale
                    val maxPanX = (zoomedW - renderedW) / 2f
                    val maxPanY = (zoomedH - renderedH) / 2f
                    val currentPanX = panOffset.x.coerceIn(-maxPanX, maxPanX)
                    val currentPanY = panOffset.y.coerceIn(-maxPanY, maxPanY)

                    val imgScreenLeft = baseOffsetX - (zoomedW - renderedW) / 2f + currentPanX
                    val imgScreenTop = baseOffsetY - (zoomedH - renderedH) / 2f + currentPanY

                    // Screen coordinate bounds of the crop box
                    val screenLeft = baseOffsetX + cropLeftNorm * renderedW
                    val screenTop = baseOffsetY + cropTopNorm * renderedH
                    val screenRight = baseOffsetX + cropRightNorm * renderedW
                    val screenBottom = baseOffsetY + cropBottomNorm * renderedH

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(canvasSize, zoomScale) {
                                // 1. Two-finger pinch to zoom & pan
                                detectTransformGestures { _, pan, zoom, _ ->
                                    zoomScale = (zoomScale * zoom).coerceIn(1.0f, 4.0f)
                                    val newZoomW = renderedW * zoomScale
                                    val newZoomH = renderedH * zoomScale
                                    val newMaxPanX = (newZoomW - renderedW) / 2f
                                    val newMaxPanY = (newZoomH - renderedH) / 2f
                                    panOffset = Offset(
                                        (panOffset.x + pan.x).coerceIn(-newMaxPanX, newMaxPanX),
                                        (panOffset.y + pan.y).coerceIn(-newMaxPanY, newMaxPanY)
                                    )
                                }
                            }
                            .pointerInput(canvasSize, targetAspect, cropLeftNorm, cropTopNorm, cropRightNorm, cropBottomNorm) {
                                // 2. Single-finger drag for corner resizing & box movement
                                detectDragGestures(
                                    onDragStart = { pos ->
                                        val handleHitRadius = 44.dp.toPx()

                                        // Prioritize corner handles first so resizing never gets hijacked by box move
                                        activeHandle = when {
                                            hypot(pos.x - screenLeft, pos.y - screenTop) < handleHitRadius -> "TL"
                                            hypot(pos.x - screenRight, pos.y - screenTop) < handleHitRadius -> "TR"
                                            hypot(pos.x - screenRight, pos.y - screenBottom) < handleHitRadius -> "BR"
                                            hypot(pos.x - screenLeft, pos.y - screenBottom) < handleHitRadius -> "BL"
                                            // Only trigger move if safely inside inner body
                                            pos.x in (screenLeft + 20.dp.toPx())..(screenRight - 20.dp.toPx()) &&
                                            pos.y in (screenTop + 20.dp.toPx())..(screenBottom - 20.dp.toPx()) -> "MOVE"
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

                                                val newL = (cropLeftNorm + dxNorm).coerceIn(0f, 1f - wNorm)
                                                val newT = (cropTopNorm + dyNorm).coerceIn(0f, 1f - hNorm)
                                                cropLeftNorm = newL
                                                cropRightNorm = newL + wNorm
                                                cropTopNorm = newT
                                                cropBottomNorm = newT + hNorm
                                            }
                                            "BR" -> {
                                                // Resizing Bottom-Right corner
                                                var newW = (cropRightNorm - cropLeftNorm + dxNorm).coerceIn(0.15f, 1f)
                                                var newH = (newW * imgW / targetAspect) / imgH
                                                if (newH > 1f) {
                                                    newH = 1f
                                                    newW = (newH * imgH * targetAspect) / imgW
                                                }

                                                var newTop = cropTopNorm
                                                var newBottom = newTop + newH
                                                if (newBottom > 1f) {
                                                    val overflow = newBottom - 1f
                                                    newTop = (newTop - overflow).coerceAtLeast(0f)
                                                    newBottom = newTop + newH
                                                }

                                                var newLeft = cropLeftNorm
                                                var newRight = newLeft + newW
                                                if (newRight > 1f) {
                                                    val overflow = newRight - 1f
                                                    newLeft = (newLeft - overflow).coerceAtLeast(0f)
                                                    newRight = newLeft + newW
                                                }

                                                cropLeftNorm = newLeft
                                                cropRightNorm = newRight
                                                cropTopNorm = newTop
                                                cropBottomNorm = newBottom
                                            }
                                            "BL" -> {
                                                // Resizing Bottom-Left corner
                                                var newW = (cropRightNorm - cropLeftNorm - dxNorm).coerceIn(0.15f, 1f)
                                                var newH = (newW * imgW / targetAspect) / imgH
                                                if (newH > 1f) {
                                                    newH = 1f
                                                    newW = (newH * imgH * targetAspect) / imgW
                                                }

                                                var newTop = cropTopNorm
                                                var newBottom = newTop + newH
                                                if (newBottom > 1f) {
                                                    val overflow = newBottom - 1f
                                                    newTop = (newTop - overflow).coerceAtLeast(0f)
                                                    newBottom = newTop + newH
                                                }

                                                var newRight = cropRightNorm
                                                var newLeft = newRight - newW
                                                if (newLeft < 0f) {
                                                    val underflow = -newLeft
                                                    newRight = (newRight + underflow).coerceAtMost(1f)
                                                    newLeft = newRight - newW
                                                }

                                                cropLeftNorm = newLeft
                                                cropRightNorm = newRight
                                                cropTopNorm = newTop
                                                cropBottomNorm = newBottom
                                            }
                                            "TR" -> {
                                                // Resizing Top-Right corner
                                                var newW = (cropRightNorm - cropLeftNorm + dxNorm).coerceIn(0.15f, 1f)
                                                var newH = (newW * imgW / targetAspect) / imgH
                                                if (newH > 1f) {
                                                    newH = 1f
                                                    newW = (newH * imgH * targetAspect) / imgW
                                                }

                                                var newBottom = cropBottomNorm
                                                var newTop = newBottom - newH
                                                if (newTop < 0f) {
                                                    val underflow = -newTop
                                                    newBottom = (newBottom + underflow).coerceAtMost(1f)
                                                    newTop = newBottom - newH
                                                }

                                                var newLeft = cropLeftNorm
                                                var newRight = newLeft + newW
                                                if (newRight > 1f) {
                                                    val overflow = newRight - 1f
                                                    newLeft = (newLeft - overflow).coerceAtLeast(0f)
                                                    newRight = newLeft + newW
                                                }

                                                cropLeftNorm = newLeft
                                                cropRightNorm = newRight
                                                cropTopNorm = newTop
                                                cropBottomNorm = newBottom
                                            }
                                            "TL" -> {
                                                // Resizing Top-Left corner
                                                var newW = (cropRightNorm - cropLeftNorm - dxNorm).coerceIn(0.15f, 1f)
                                                var newH = (newW * imgW / targetAspect) / imgH
                                                if (newH > 1f) {
                                                    newH = 1f
                                                    newW = (newH * imgH * targetAspect) / imgW
                                                }

                                                var newBottom = cropBottomNorm
                                                var newTop = newBottom - newH
                                                if (newTop < 0f) {
                                                    val underflow = -newTop
                                                    newBottom = (newBottom + underflow).coerceAtMost(1f)
                                                    newTop = newBottom - newH
                                                }

                                                var newRight = cropRightNorm
                                                var newLeft = newRight - newW
                                                if (newLeft < 0f) {
                                                    val underflow = -newLeft
                                                    newRight = (newRight + underflow).coerceAtMost(1f)
                                                    newLeft = newRight - newW
                                                }

                                                cropLeftNorm = newLeft
                                                cropRightNorm = newRight
                                                cropTopNorm = newTop
                                                cropBottomNorm = newBottom
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        // 1. Draw source bitmap with active zoom and pan
                        val srcRect = Rect(0, 0, orientedBitmap.width, orientedBitmap.height)
                        val dstRect = RectF(imgScreenLeft, imgScreenTop, imgScreenLeft + zoomedW, imgScreenTop + zoomedH)
                        drawContext.canvas.nativeCanvas.drawBitmap(orientedBitmap, srcRect, dstRect, null)

                        // 2. Draw dim dark scrim overlay outside crop box
                        val scrimColor = Color(0x99000000)
                        drawRect(scrimColor, Offset(0f, 0f), Size(viewW, screenTop))
                        drawRect(scrimColor, Offset(0f, screenBottom), Size(viewW, viewH - screenBottom))
                        drawRect(scrimColor, Offset(0f, screenTop), Size(screenLeft, screenBottom - screenTop))
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

                        // 4. Biometric Face Framing Guidelines (Standard Passport Oval + Eye Line)
                        if (showGuides) {
                            val guideColor = Color(0xAAFFFFFF)
                            val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)

                            // Head Oval (Biometric Standard: 70-80% of vertical height)
                            val ovalW = boxW * 0.58f
                            val ovalH = boxH * 0.72f
                            val ovalLeft = screenLeft + (boxW - ovalW) / 2f
                            val ovalTop = screenTop + boxH * 0.12f

                            drawOval(
                                color = guideColor,
                                topLeft = Offset(ovalLeft, ovalTop),
                                size = Size(ovalW, ovalH),
                                style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashedEffect)
                            )

                            // Eye Level Line (at ~45% from top of box)
                            val eyeY = screenTop + boxH * 0.44f
                            drawLine(
                                color = Color(0xCC00E676),
                                start = Offset(screenLeft + boxW * 0.2f, eyeY),
                                end = Offset(screenRight - boxW * 0.2f, eyeY),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = dashedEffect
                            )

                            // Chin Line (at ~82% from top of box)
                            val chinY = screenTop + boxH * 0.82f
                            drawLine(
                                color = Color(0xCC00E676),
                                start = Offset(screenLeft + boxW * 0.3f, chinY),
                                end = Offset(screenRight - boxW * 0.3f, chinY),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = dashedEffect
                            )
                        }

                        // 5. Draw 4 Corner Grab Handles
                        val cornerLen = 22.dp.toPx()
                        val cornerStroke = 4.dp.toPx()
                        val handleColor = Color(0xFF00E676)

                        // TL Corner
                        drawLine(handleColor, Offset(screenLeft, screenTop), Offset(screenLeft + cornerLen, screenTop), cornerStroke)
                        drawLine(handleColor, Offset(screenLeft, screenTop), Offset(screenLeft, screenTop + cornerLen), cornerStroke)

                        // TR Corner
                        drawLine(handleColor, Offset(screenRight, screenTop), Offset(screenRight - cornerLen, screenTop), cornerStroke)
                        drawLine(handleColor, Offset(screenRight, screenTop), Offset(screenRight, screenTop + cornerLen), cornerStroke)

                        // BR Corner
                        drawLine(handleColor, Offset(screenRight, screenBottom), Offset(screenRight - cornerLen, screenBottom), cornerStroke)
                        drawLine(handleColor, Offset(screenRight, screenBottom), Offset(screenRight, screenBottom - cornerLen), cornerStroke)

                        // BL Corner
                        drawLine(handleColor, Offset(screenLeft, screenBottom), Offset(screenLeft + cornerLen, screenBottom), cornerStroke)
                        drawLine(handleColor, Offset(screenLeft, screenBottom), Offset(screenLeft, screenBottom - cornerLen), cornerStroke)
                    }

                    // Floating Glassmorphic Zoom & Pan Controls
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xCC1E293B),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .shadow(8.dp, RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1.0f) },
                                enabled = zoomScale > 1.0f,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = if (zoomScale > 1.0f) Color.White else Color.Gray, modifier = Modifier.size(16.dp))
                            }

                            Text(
                                text = "${"%.1f".format(zoomScale)}x",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            IconButton(
                                onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(4.0f) },
                                enabled = zoomScale < 4.0f,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = if (zoomScale < 4.0f) Color.White else Color.Gray, modifier = Modifier.size(16.dp))
                            }

                            if (zoomScale > 1.05f || panOffset != Offset.Zero) {
                                TextButton(
                                    onClick = {
                                        zoomScale = 1.0f
                                        panOffset = Offset.Zero
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Reset", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
