package com.example.offlinedocumentcomposer.presentation.detection

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner
import kotlinx.coroutines.launch
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
    bitmap: Bitmap?,
    title: String = "Detect & Crop Document",
    mode: com.example.offlinedocumentcomposer.domain.detector.DetectionMode = com.example.offlinedocumentcomposer.domain.detector.DetectionMode.DOCUMENT,
    initialCorners: List<PointF>? = null,
    onCornersConfirmed: ((List<PointF>) -> Unit)? = null,
    onProceed: (Bitmap) -> Unit,
    onBack: () -> Unit,
    viewModel: DetectionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var activeDraggingCorner by remember { mutableStateOf<Int?>(null) }
    var touchScreenPos by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Local corners state for butter-smooth 60/120fps dragging without gesture interruption
    var localCorners by remember { mutableStateOf<List<PointF>>(emptyList()) }

    LaunchedEffect(bitmap) {
        bitmap?.let {
            viewModel.loadImage(it, mode)
        }
    }

    var restoredCorners by remember(bitmap) { mutableStateOf(false) }
    LaunchedEffect(state.isDetecting, bitmap) {
        if (!state.isDetecting && state.originalBitmap === bitmap && !restoredCorners && initialCorners?.size == 4) {
            initialCorners.forEachIndexed { i, p -> viewModel.updateCorner(i, p) }
            restoredCorners = true
        }
    }

    LaunchedEffect(state.corners) {
        if (activeDraggingCorner == null && state.corners.isNotEmpty()) {
            localCorners = state.corners
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = state.errorMessage ?: if (state.detected) "Check detected corners" else "Drag corners to adjust frame",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onCornersConfirmed == null) IconButton(onClick = {
                        viewModel.snapToIdCardRatio()
                    }) {
                        Icon(Icons.Default.CreditCard, contentDescription = "Snap to ID Card")
                    }
                    IconButton(onClick = { viewModel.resetToDefaultCorners() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Corners")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // Quick Preset Chips (CamScanner / DeshKit style)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                state.originalBitmap?.let { viewModel.detectDocument(it) }
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Auto Detect", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // ID Card Standard Ratio
                        if (onCornersConfirmed == null) Button(
                            onClick = { viewModel.snapToIdCardRatio() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (state.idRatio) "ID ratio: On" else "ID ratio: Off", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = { viewModel.snapToFullImage() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Full Photo", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.resetToDefaultCorners() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset", fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Main Proceed Button
                    if (state.errorMessage != null) Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                if (onCornersConfirmed != null) {
                                    val source = state.originalBitmap
                                    if (source != null && com.example.offlinedocumentcomposer.domain.detector.CropGeometry.valid(localCorners, source.width, source.height)) onCornersConfirmed(localCorners)
                                    else viewModel.reportInvalidCrop()
                                    return@launch
                                }
                                val cropped = viewModel.getCroppedBitmap()
                                if (cropped != null) {
                                    onProceed(cropped)
                                }
                            }
                        },
                        enabled = localCorners.size == 4 && !state.isDetecting && !state.isCropping && activeDraggingCorner == null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(if (onCornersConfirmed != null) "Save crop" else "Crop & Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0F172A))
        ) {
            val bmp = state.originalBitmap
            if (bmp != null) {
                val cachedImageBitmap = remember(bmp) { bmp.asImageBitmap() }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(bmp) {
                            // Stable gesture recognizer: does NOT restart during dragging
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (state.isDetecting || state.isCropping || canvasSize.width <= 0 || canvasSize.height <= 0 || localCorners.size != 4) return@detectDragGestures
                                    val scale = minOf(
                                        canvasSize.width.toFloat() / bmp.width,
                                        canvasSize.height.toFloat() / bmp.height
                                    )
                                    val ox = (canvasSize.width - bmp.width * scale) / 2f
                                    val oy = (canvasSize.height - bmp.height * scale) / 2f

                                    var closestIdx = -1
                                    var minDist = 56.dp.toPx()
                                    for (i in 0 until 4) {
                                        val p = localCorners[i]
                                        val sx = ox + p.x * scale
                                        val sy = oy + p.y * scale
                                        val d = hypot(offset.x - sx, offset.y - sy)
                                        if (d < minDist) {
                                            minDist = d
                                            closestIdx = i
                                        }
                                    }
                                    if (closestIdx != -1) {
                                        activeDraggingCorner = closestIdx
                                        touchScreenPos = offset
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val idx = activeDraggingCorner ?: return@detectDragGestures
                                    touchScreenPos = change.position
                                    val scale = minOf(
                                        canvasSize.width.toFloat() / bmp.width,
                                        canvasSize.height.toFloat() / bmp.height
                                    )
                                    val ox = (canvasSize.width - bmp.width * scale) / 2f
                                    val oy = (canvasSize.height - bmp.height * scale) / 2f

                                    val newImgX = ((change.position.x - ox) / scale).coerceIn(0f, (bmp.width - 1).toFloat())
                                    val newImgY = ((change.position.y - oy) / scale).coerceIn(0f, (bmp.height - 1).toFloat())

                                    val updated = localCorners.toMutableList()
                                    updated[idx] = PointF(newImgX, newImgY)
                                    localCorners = updated
                                },
                                onDragEnd = {
                                    val idx = activeDraggingCorner
                                    if (idx != null && idx in 0..3 && localCorners.size == 4) {
                                        viewModel.updateCorner(idx, localCorners[idx])
                                    }
                                    activeDraggingCorner = null
                                    touchScreenPos = null
                                },
                                onDragCancel = {
                                    localCorners = state.corners
                                    activeDraggingCorner = null
                                    touchScreenPos = null
                                }
                            )
                        }
                ) {
                    val cw = size.width
                    val ch = size.height
                    if (cw <= 0f || ch <= 0f) return@Canvas

                    val scale = minOf(cw / bmp.width, ch / bmp.height)
                    val ox = (cw - bmp.width * scale) / 2f
                    val oy = (ch - bmp.height * scale) / 2f

                    // 1. Draw source image
                    val dstSize = IntSize((bmp.width * scale).toInt(), (bmp.height * scale).toInt())
                    val dstOffset = IntOffset(ox.toInt(), oy.toInt())
                    drawImage(
                        image = cachedImageBitmap,
                        dstOffset = dstOffset,
                        dstSize = dstSize
                    )

                    // 2. Draw quadrilateral and 4 Corner Handles
                    if (localCorners.size == 4) {
                        val screenCorners = localCorners.map { p ->
                            Offset(ox + p.x * scale, oy + p.y * scale)
                        }

                        val path = Path().apply {
                            moveTo(screenCorners[0].x, screenCorners[0].y)
                            lineTo(screenCorners[1].x, screenCorners[1].y)
                            lineTo(screenCorners[2].x, screenCorners[2].y)
                            lineTo(screenCorners[3].x, screenCorners[3].y)
                            close()
                        }

                        // Semi-transparent interior fill
                        drawPath(
                            path = path,
                            color = Color(0x2810B981)
                        )

                        // Outline boundary line
                        drawPath(
                            path = path,
                            color = Color(0xFF10B981),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Draw Corner Handles
                        screenCorners.forEachIndexed { i, pt ->
                            val isDragging = (activeDraggingCorner == i)
                            val radius = if (isDragging) 18.dp.toPx() else 14.dp.toPx()

                            // Outer glow halo
                            drawCircle(
                                color = if (isDragging) Color(0x6610B981) else Color(0x44FFFFFF),
                                radius = radius + 6.dp.toPx(),
                                center = pt
                            )
                            // White background ring
                            drawCircle(
                                color = Color.White,
                                radius = radius,
                                center = pt
                            )
                            // Emerald center dot
                            drawCircle(
                                color = Color(0xFF10B981),
                                radius = radius - 4.dp.toPx(),
                                center = pt
                            )
                        }
                    }
                }

                // Magnifier Loupe when dragging a corner
                val activeCornerIdx = activeDraggingCorner
                val touchPos = touchScreenPos
                if (activeCornerIdx != null && touchPos != null && localCorners.size == 4) {
                    val cornerPoint = localCorners[activeCornerIdx]
                    val loupeRadius = 55.dp
                    val loupeOffsetY = (-90).dp

                    Box(
                        modifier = Modifier
                            .offset(
                                x = (touchPos.x / (canvasSize.width.takeIf { it > 0 } ?: 1) * 200).dp.coerceIn(16.dp, 240.dp),
                                y = 20.dp
                            )
                            .size(110.dp)
                            .shadow(16.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color(0xFF0F172A))
                            .border(3.dp, Color(0xFF10B981), CircleShape)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val zoom = 2.5f
                            val centerOnBmpX = cornerPoint.x
                            val centerOnBmpY = cornerPoint.y

                            val dstW = bmp.width * zoom
                            val dstH = bmp.height * zoom
                            val left = size.width / 2f - centerOnBmpX * zoom
                            val top = size.height / 2f - centerOnBmpY * zoom

                            drawImage(
                                image = cachedImageBitmap,
                                dstOffset = IntOffset(left.toInt(), top.toInt()),
                                dstSize = IntSize(dstW.toInt(), dstH.toInt())
                            )

                            // Crosshair
                            val chLen = 14.dp.toPx()
                            drawLine(
                                color = Color(0xFF10B981),
                                start = Offset(size.width / 2f - chLen, size.height / 2f),
                                end = Offset(size.width / 2f + chLen, size.height / 2f),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(
                                color = Color(0xFF10B981),
                                start = Offset(size.width / 2f, size.height / 2f - chLen),
                                end = Offset(size.width / 2f, size.height / 2f + chLen),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                }
            }

            // Detecting progress indicator
            if (state.isDetecting) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SafeLoadingSpinner(size = 28.dp, color = Color(0xFF10B981))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Detecting Document Edges...",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
