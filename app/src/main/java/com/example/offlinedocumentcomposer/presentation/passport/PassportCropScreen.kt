package com.example.offlinedocumentcomposer.presentation.passport

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinedocumentcomposer.data.model.ImageAdjustments
import com.example.offlinedocumentcomposer.domain.image.ImageProcessor
import com.example.offlinedocumentcomposer.domain.passport.PassportPhotoStandard
import kotlin.math.*

/**
 * High-performance state holder for Passport Crop & Zoom.
 * Uses primitive mutableFloatStateOf to eliminate boxing allocations and
 * ensures 120 FPS buttery-smooth gestures without recomposing outer UI trees.
 */
@Stable
class PassportCropState(
    val imgWidth: Float,
    val imgHeight: Float,
    initialAspect: Float
) {
    var zoomScale by mutableFloatStateOf(1.0f)
    var panOffsetX by mutableFloatStateOf(0f)
    var panOffsetY by mutableFloatStateOf(0f)

    var cropLeftNorm by mutableFloatStateOf(0.1f)
    var cropTopNorm by mutableFloatStateOf(0.1f)
    var cropRightNorm by mutableFloatStateOf(0.9f)
    var cropBottomNorm by mutableFloatStateOf(0.9f)

    var activeHandle by mutableStateOf<String?>(null)

    init {
        updateAspect(initialAspect)
    }

    fun updateAspect(targetAspect: Float) {
        val imgAspect = imgWidth / imgHeight
        if (imgAspect > targetAspect) {
            val h = 0.85f
            val w = (h * imgHeight * targetAspect) / imgWidth
            cropTopNorm = (1f - h) / 2f
            cropBottomNorm = cropTopNorm + h
            cropLeftNorm = (1f - w) / 2f
            cropRightNorm = cropLeftNorm + w
        } else {
            val w = 0.85f
            val h = (w * imgWidth / targetAspect) / imgHeight
            cropLeftNorm = (1f - w) / 2f
            cropRightNorm = cropLeftNorm + w
            cropTopNorm = (1f - h) / 2f
            cropBottomNorm = cropTopNorm + h
        }
    }

    fun applyZoom(factor: Float, renderedW: Float, renderedH: Float) {
        val newZoom = (zoomScale * factor).coerceIn(1.0f, 5.0f)
        zoomScale = newZoom
        clampPan(renderedW, renderedH)
    }

    fun setZoom(newZoom: Float, renderedW: Float, renderedH: Float) {
        zoomScale = newZoom.coerceIn(1.0f, 5.0f)
        clampPan(renderedW, renderedH)
    }

    fun resetZoom() {
        zoomScale = 1.0f
        panOffsetX = 0f
        panOffsetY = 0f
    }

    fun applyPan(delta: Offset, renderedW: Float, renderedH: Float) {
        panOffsetX += delta.x
        panOffsetY += delta.y
        clampPan(renderedW, renderedH)
    }

    private fun clampPan(renderedW: Float, renderedH: Float) {
        val zoomedW = renderedW * zoomScale
        val zoomedH = renderedH * zoomScale
        val maxPanX = maxOf(0f, (zoomedW - renderedW) / 2f)
        val maxPanY = maxOf(0f, (zoomedH - renderedH) / 2f)
        panOffsetX = panOffsetX.coerceIn(-maxPanX, maxPanX)
        panOffsetY = panOffsetY.coerceIn(-maxPanY, maxPanY)
    }

    fun moveCrop(dxNorm: Float, dyNorm: Float) {
        val wNorm = cropRightNorm - cropLeftNorm
        val hNorm = cropBottomNorm - cropTopNorm
        val newL = (cropLeftNorm + dxNorm).coerceIn(0f, 1f - wNorm)
        val newT = (cropTopNorm + dyNorm).coerceIn(0f, 1f - hNorm)
        cropLeftNorm = newL
        cropRightNorm = newL + wNorm
        cropTopNorm = newT
        cropBottomNorm = newT + hNorm
    }

    fun resizeCorner(
        corner: String,
        dragDelta: Offset,
        renderedW: Float,
        renderedH: Float,
        targetAspect: Float
    ) {
        val dxNorm = dragDelta.x / renderedW
        val dyNorm = dragDelta.y / renderedH

        // Project movement along dominant drag direction for natural tracking
        val delta = when (corner) {
            "BR" -> if (abs(dragDelta.x) >= abs(dragDelta.y)) dxNorm else dyNorm * (imgHeight / imgWidth) * targetAspect
            "BL" -> if (abs(dragDelta.x) >= abs(dragDelta.y)) -dxNorm else dyNorm * (imgHeight / imgWidth) * targetAspect
            "TR" -> if (abs(dragDelta.x) >= abs(dragDelta.y)) dxNorm else -dyNorm * (imgHeight / imgWidth) * targetAspect
            "TL" -> if (abs(dragDelta.x) >= abs(dragDelta.y)) -dxNorm else -dyNorm * (imgHeight / imgWidth) * targetAspect
            else -> 0f
        }

        var newW = (cropRightNorm - cropLeftNorm + delta).coerceIn(0.12f, 1f)
        var newH = (newW * imgWidth / targetAspect) / imgHeight
        if (newH > 1f) {
            newH = 1f
            newW = (newH * imgHeight * targetAspect) / imgWidth
        }

        when (corner) {
            "BR" -> {
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

    fun computeFinalCrop(
        orientedBitmap: Bitmap,
        viewW: Float,
        viewH: Float
    ): Bitmap {
        val imgW = orientedBitmap.width.toFloat()
        val imgH = orientedBitmap.height.toFloat()
        val baseScale = minOf(viewW / imgW, viewH / imgH)
        val renderedW = imgW * baseScale
        val renderedH = imgH * baseScale
        val baseOffsetX = (viewW - renderedW) / 2f
        val baseOffsetY = (viewH - renderedH) / 2f

        val zoomedW = renderedW * zoomScale
        val zoomedH = renderedH * zoomScale
        val maxPanX = maxOf(0f, (zoomedW - renderedW) / 2f)
        val maxPanY = maxOf(0f, (zoomedH - renderedH) / 2f)
        val clampedPanX = panOffsetX.coerceIn(-maxPanX, maxPanX)
        val clampedPanY = panOffsetY.coerceIn(-maxPanY, maxPanY)

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

        val cropW = maxOf(10, cropRightInImg - cropLeftInImg)
        val cropH = maxOf(10, cropBottomInImg - cropTopInImg)

        return Bitmap.createBitmap(orientedBitmap, cropLeftInImg, cropTopInImg, cropW, cropH)
    }
}

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
    var customWidthMm by remember { mutableFloatStateOf(35f) }
    var customHeightMm by remember { mutableFloatStateOf(45f) }
    var showCustomDialog by remember { mutableStateOf(false) }

    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
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

    // State holder persists across frames and encapsulates zoom/pan/crop gestures
    val cropState = remember(orientedBitmap) {
        PassportCropState(
            imgWidth = orientedBitmap.width.toFloat(),
            imgHeight = orientedBitmap.height.toFloat(),
            initialAspect = targetAspect
        )
    }

    // Update aspect ratio when standard changes
    LaunchedEffect(targetAspect) {
        cropState.updateAspect(targetAspect)
    }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

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
                                val viewW = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val viewH = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val rawCropped = cropState.computeFinalCrop(orientedBitmap, viewW, viewH)

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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // High-performance hardware-accelerated canvas for 120 FPS gestures
                PassportCropCanvas(
                    orientedBitmap = orientedBitmap,
                    cropState = cropState,
                    targetAspect = targetAspect,
                    showGuides = showGuides,
                    onCanvasSizeChanged = { canvasSize = it },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating Glassmorphic Zoom & Pan Controls
                FloatingZoomBar(
                    cropState = cropState,
                    canvasSize = canvasSize,
                    imgWidth = orientedBitmap.width.toFloat(),
                    imgHeight = orientedBitmap.height.toFloat(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                )
            }
        }
    }

    if (showCustomDialog) {
        CustomPassportSizeDialog(
            initialW = customWidthMm,
            initialH = customHeightMm,
            onDismiss = { showCustomDialog = false },
            onConfirm = { w, h ->
                customWidthMm = w
                customHeightMm = h
                selectedStandard = PassportPhotoStandard.CUSTOM
                showCustomDialog = false
            }
        )
    }
}

/**
 * Dedicated Hardware-Accelerated Canvas for 120 FPS multi-touch gestures.
 * Handles unified pinch-to-zoom + pan and single-finger corner resizing & box movement
 * within a single uninterrupted pointerInput loop without recreation or dropped frames.
 */
@Composable
private fun PassportCropCanvas(
    orientedBitmap: Bitmap,
    cropState: PassportCropState,
    targetAspect: Float,
    showGuides: Boolean,
    onCanvasSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleHitRadius = remember(density) { with(density) { 48.dp.toPx() } }
    val cornerLen = remember(density) { with(density) { 22.dp.toPx() } }
    val cornerStroke = remember(density) { with(density) { 4.dp.toPx() } }
    val boxStroke = remember(density) { with(density) { 2.5.dp.toPx() } }

    val srcRect = remember(orientedBitmap) { Rect(0, 0, orientedBitmap.width, orientedBitmap.height) }
    val dstRect = remember { RectF() }
    val bitmapPaint = remember { Paint().apply { isFilterBitmap = true; isAntiAlias = true } }
    val dashedEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { onCanvasSizeChanged(it) }
            .pointerInput(orientedBitmap, targetAspect) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val viewW = size.width.toFloat()
                    val viewH = size.height.toFloat()
                    val imgW = orientedBitmap.width.toFloat()
                    val imgH = orientedBitmap.height.toFloat()
                    val baseScale = minOf(viewW / imgW, viewH / imgH)
                    val renderedW = imgW * baseScale
                    val renderedH = imgH * baseScale
                    val baseOffsetX = (viewW - renderedW) / 2f
                    val baseOffsetY = (viewH - renderedH) / 2f

                    val screenLeft = baseOffsetX + cropState.cropLeftNorm * renderedW
                    val screenTop = baseOffsetY + cropState.cropTopNorm * renderedH
                    val screenRight = baseOffsetX + cropState.cropRightNorm * renderedW
                    val screenBottom = baseOffsetY + cropState.cropBottomNorm * renderedH

                    val downPos = down.position

                    val dTL = hypot(downPos.x - screenLeft, downPos.y - screenTop)
                    val dTR = hypot(downPos.x - screenRight, downPos.y - screenTop)
                    val dBR = hypot(downPos.x - screenRight, downPos.y - screenBottom)
                    val dBL = hypot(downPos.x - screenLeft, downPos.y - screenBottom)

                    val minCornerDist = minOf(dTL, dTR, dBR, dBL)

                    // 1. Prioritize corners for resizing
                    // 2. Center of box for moving
                    // 3. Outside box for photo panning
                    cropState.activeHandle = when {
                        minCornerDist < handleHitRadius -> {
                            when (minCornerDist) {
                                dTL -> "TL"
                                dTR -> "TR"
                                dBR -> "BR"
                                else -> "BL"
                            }
                        }
                        downPos.x in screenLeft..screenRight && downPos.y in screenTop..screenBottom -> "MOVE"
                        else -> "PAN_PHOTO"
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.filter { it.pressed }
                        if (pressedPointers.isEmpty()) break

                        if (pressedPointers.size >= 2) {
                            // Two-finger smooth pinch-to-zoom and pan
                            val p1 = pressedPointers[0]
                            val p2 = pressedPointers[1]
                            val currDist = hypot(p1.position.x - p2.position.x, p1.position.y - p2.position.y)
                            val prevDist = hypot(p1.previousPosition.x - p2.previousPosition.x, p1.previousPosition.y - p2.previousPosition.y)

                            if (prevDist > 0f && currDist > 0f) {
                                val zoomFactor = currDist / prevDist
                                cropState.applyZoom(zoomFactor, renderedW, renderedH)
                            }

                            val currCentroid = (p1.position + p2.position) / 2f
                            val prevCentroid = (p1.previousPosition + p2.previousPosition) / 2f
                            val panDelta = currCentroid - prevCentroid
                            cropState.applyPan(panDelta, renderedW, renderedH)

                            event.changes.forEach { it.consume() }
                        } else if (pressedPointers.size == 1) {
                            // Single-finger drag for corner resizing, box moving, or photo panning
                            val pointer = pressedPointers[0]
                            val dragDelta = pointer.position - pointer.previousPosition
                            if (dragDelta != Offset.Zero) {
                                when (cropState.activeHandle) {
                                    "MOVE" -> cropState.moveCrop(dragDelta.x / renderedW, dragDelta.y / renderedH)
                                    "BR", "BL", "TR", "TL" -> cropState.resizeCorner(
                                        cropState.activeHandle!!,
                                        dragDelta,
                                        renderedW,
                                        renderedH,
                                        targetAspect
                                    )
                                    "PAN_PHOTO" -> cropState.applyPan(dragDelta, renderedW, renderedH)
                                }
                                pointer.consume()
                            }
                        }
                    }

                    cropState.activeHandle = null
                }
            }
    ) {
        val viewW = size.width
        val viewH = size.height
        val imgW = orientedBitmap.width.toFloat()
        val imgH = orientedBitmap.height.toFloat()
        val baseScale = minOf(viewW / imgW, viewH / imgH)
        val renderedW = imgW * baseScale
        val renderedH = imgH * baseScale
        val baseOffsetX = (viewW - renderedW) / 2f
        val baseOffsetY = (viewH - renderedH) / 2f

        val zoomedW = renderedW * cropState.zoomScale
        val zoomedH = renderedH * cropState.zoomScale
        val maxPanX = maxOf(0f, (zoomedW - renderedW) / 2f)
        val maxPanY = maxOf(0f, (zoomedH - renderedH) / 2f)
        val clampedPanX = cropState.panOffsetX.coerceIn(-maxPanX, maxPanX)
        val clampedPanY = cropState.panOffsetY.coerceIn(-maxPanY, maxPanY)

        val imgScreenLeft = baseOffsetX - (zoomedW - renderedW) / 2f + clampedPanX
        val imgScreenTop = baseOffsetY - (zoomedH - renderedH) / 2f + clampedPanY

        val screenLeft = baseOffsetX + cropState.cropLeftNorm * renderedW
        val screenTop = baseOffsetY + cropState.cropTopNorm * renderedH
        val screenRight = baseOffsetX + cropState.cropRightNorm * renderedW
        val screenBottom = baseOffsetY + cropState.cropBottomNorm * renderedH

        // 1. Draw source bitmap with active zoom and pan
        dstRect.set(imgScreenLeft, imgScreenTop, imgScreenLeft + zoomedW, imgScreenTop + zoomedH)
        drawContext.canvas.nativeCanvas.drawBitmap(orientedBitmap, srcRect, dstRect, bitmapPaint)

        // 2. Draw dim dark scrim overlay outside crop box
        val scrimColor = Color(0x99000000)
        drawRect(scrimColor, Offset.Zero, Size(viewW, screenTop))
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
            style = Stroke(width = boxStroke)
        )

        // 4. Biometric Face Framing Guidelines (Standard Passport Oval + Eye Line + Chin Line)
        if (showGuides) {
            val guideColor = Color(0xAAFFFFFF)

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

            // Eye Level Line (at ~44% from top of box)
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
}


@Composable
private fun FloatingZoomBar(
    cropState: PassportCropState,
    canvasSize: IntSize,
    imgWidth: Float,
    imgHeight: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC1E293B),
        modifier = modifier.shadow(8.dp, RoundedCornerShape(20.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            val viewW = canvasSize.width.toFloat().coerceAtLeast(1f)
            val viewH = canvasSize.height.toFloat().coerceAtLeast(1f)
            val baseScale = minOf(viewW / imgWidth, viewH / imgHeight)
            val renderedW = imgWidth * baseScale
            val renderedH = imgHeight * baseScale

            IconButton(
                onClick = { cropState.setZoom(cropState.zoomScale - 0.25f, renderedW, renderedH) },
                enabled = cropState.zoomScale > 1.0f,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = if (cropState.zoomScale > 1.0f) Color.White else Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = "${"%.1f".format(cropState.zoomScale)}x",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            IconButton(
                onClick = { cropState.setZoom(cropState.zoomScale + 0.25f, renderedW, renderedH) },
                enabled = cropState.zoomScale < 5.0f,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Zoom In",
                    tint = if (cropState.zoomScale < 5.0f) Color.White else Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (cropState.zoomScale > 1.02f || cropState.panOffsetX != 0f || cropState.panOffsetY != 0f) {
                TextButton(
                    onClick = { cropState.resetZoom() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Reset", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CustomPassportSizeDialog(
    initialW: Float,
    initialH: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float) -> Unit
) {
    var widthStr by remember { mutableStateOf(initialW.toInt().toString()) }
    var heightStr by remember { mutableStateOf(initialH.toInt().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Custom Photo Dimensions", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter target passport photo dimensions in millimeters (mm):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = widthStr,
                    onValueChange = { widthStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Width (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = heightStr,
                    onValueChange = { heightStr = it.filter { c -> c.isDigit() } },
                    label = { Text("Height (mm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = widthStr.toFloatOrNull() ?: initialW
                    val h = heightStr.toFloatOrNull() ?: initialH
                    onConfirm(w.coerceIn(10f, 200f), h.coerceIn(10f, 200f))
                }
            ) {
                Text("Set Size")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
