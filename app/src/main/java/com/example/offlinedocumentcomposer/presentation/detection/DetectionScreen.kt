package com.example.offlinedocumentcomposer.presentation.detection

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.example.offlinedocumentcomposer.domain.detector.DetectionMode
import com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner
import kotlinx.coroutines.launch
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
    bitmap: Bitmap?,
    mode: DetectionMode = DetectionMode.DOCUMENT,
    title: String = "Adjust Document Crop",
    onBack: () -> Unit,
    onProceed: (Bitmap) -> Unit,
    initialCorners: List<PointF>? = null,
    onCornersConfirmed: ((List<PointF>) -> Unit)? = null,
    viewModel: DetectionViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Active dragging handle: 0..3 = Corners (TL, TR, BR, BL), 4 = Top Mid, 5 = Bottom Mid, 6 = Left Mid, 7 = Right Mid
    var activeDraggingHandle by remember { mutableStateOf<Int?>(null) }
    var touchScreenPos by remember { mutableStateOf<Offset?>(null) }
    var lastTouchPos by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Local corners state for butter-smooth 60/120fps dragging
    var localCorners by remember { mutableStateOf<List<PointF>>(emptyList()) }

    LaunchedEffect(bitmap) {
        bitmap?.let {
            viewModel.loadImage(it, mode)
        }
    }

    var restoredCorners by remember(bitmap) { mutableStateOf(false) }
    LaunchedEffect(state.isDetecting, bitmap) {
        if (!state.isDetecting && state.originalBitmap === bitmap && !restoredCorners && initialCorners?.size == 4) {
            viewModel.setCorners(initialCorners)
            restoredCorners = true
        }
    }

    LaunchedEffect(state.corners) {
        if (activeDraggingHandle == null && state.corners.isNotEmpty()) {
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
                            text = state.errorMessage ?: if (state.detected) "AI detected boundary • 8 handles available" else "Drag 4 corners or 4 edge handles to adjust",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.snapToFullImage() }) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Full Image")
                    }
                    IconButton(onClick = {
                        bitmap?.let { viewModel.detectDocument(it) }
                    }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Re-detect", tint = Color(0xFF10B981))
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Quick Action Helper Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { viewModel.snapToFullImage() },
                            label = { Text("Whole Image", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            shape = RoundedCornerShape(8.dp)
                        )

                        if (mode == DetectionMode.ID_CARD) {
                            FilterChip(
                                selected = state.idRatio,
                                onClick = { viewModel.snapToIdCardRatio() },
                                label = { Text("Standard ID Ratio", fontSize = 12.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        OutlinedButton(
                            onClick = { bitmap?.let { viewModel.detectDocument(it) } },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Main Proceed Button
                    if (state.errorMessage != null) {
                        Text(
                            text = state.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                if (onCornersConfirmed != null) {
                                    val source = state.originalBitmap
                                    if (source != null && com.example.offlinedocumentcomposer.domain.detector.CropGeometry.valid(localCorners, source.width, source.height)) {
                                        onCornersConfirmed(localCorners)
                                    } else {
                                        viewModel.reportInvalidCrop()
                                    }
                                    return@launch
                                }
                                val cropped = viewModel.getCroppedBitmap()
                                if (cropped != null) {
                                    onProceed(cropped)
                                }
                            }
                        },
                        enabled = localCorners.size == 4 && !state.isDetecting && !state.isCropping && activeDraggingHandle == null,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            if (onCornersConfirmed != null) "Save Crop" else "Crop & Continue",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
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
                val density = LocalDensity.current
                val cornerHitPx = remember(density) { with(density) { 48.dp.toPx() } }
                val midHitPx = remember(density) { with(density) { 52.dp.toPx() } }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(bmp) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    if (state.isDetecting || state.isCropping || canvasSize.width <= 0 || canvasSize.height <= 0 || localCorners.size != 4) return@detectDragGestures
                                    val scale = minOf(
                                        canvasSize.width.toFloat() / bmp.width,
                                        canvasSize.height.toFloat() / bmp.height
                                    )
                                    val ox = (canvasSize.width - bmp.width * scale) / 2f
                                    val oy = (canvasSize.height - bmp.height * scale) / 2f

                                    val screenCorners = localCorners.map { p -> Offset(ox + p.x * scale, oy + p.y * scale) }
                                    val topMid = Offset((screenCorners[0].x + screenCorners[1].x) / 2f, (screenCorners[0].y + screenCorners[1].y) / 2f)
                                    val botMid = Offset((screenCorners[3].x + screenCorners[2].x) / 2f, (screenCorners[3].y + screenCorners[2].y) / 2f)
                                    val leftMid = Offset((screenCorners[0].x + screenCorners[3].x) / 2f, (screenCorners[0].y + screenCorners[3].y) / 2f)
                                    val rightMid = Offset((screenCorners[1].x + screenCorners[2].x) / 2f, (screenCorners[1].y + screenCorners[2].y) / 2f)
                                    val midpoints = listOf(topMid, botMid, leftMid, rightMid)

                                    // 1. Check corner handles first (highest precision)
                                    var closestIdx = -1
                                    var minDist = cornerHitPx
                                    for (i in 0 until 4) {
                                        val dx = (offset.x - screenCorners[i].x).toDouble()
                                        val dy = (offset.y - screenCorners[i].y).toDouble()
                                        val d = hypot(dx, dy).toFloat()
                                        if (d < minDist) {
                                            minDist = d
                                            closestIdx = i
                                        }
                                    }

                                    // 2. If no corner hit, check the 4 edge midpoint handles
                                    if (closestIdx == -1) {
                                        var minMidDist = midHitPx
                                        for (m in 0 until 4) {
                                            val dx = (offset.x - midpoints[m].x).toDouble()
                                            val dy = (offset.y - midpoints[m].y).toDouble()
                                            val d = hypot(dx, dy).toFloat()
                                            if (d < minMidDist) {
                                                minMidDist = d
                                                closestIdx = m + 4 // 4 = Top, 5 = Bottom, 6 = Left, 7 = Right
                                            }
                                        }
                                    }

                                    if (closestIdx != -1) {
                                        activeDraggingHandle = closestIdx
                                        touchScreenPos = offset
                                        lastTouchPos = offset
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val handle = activeDraggingHandle ?: return@detectDragGestures
                                    val scale = minOf(
                                        canvasSize.width.toFloat() / bmp.width,
                                        canvasSize.height.toFloat() / bmp.height
                                    )
                                    val ox = (canvasSize.width - bmp.width * scale) / 2f
                                    val oy = (canvasSize.height - bmp.height * scale) / 2f

                                    val prevTouch = lastTouchPos ?: change.position
                                    val dxImg = (change.position.x - prevTouch.x) / scale
                                    val dyImg = (change.position.y - prevTouch.y) / scale
                                    lastTouchPos = change.position
                                    touchScreenPos = change.position

                                    val updated = localCorners.toMutableList()
                                    val maxW = (bmp.width - 1).toFloat()
                                    val maxH = (bmp.height - 1).toFloat()

                                    when (handle) {
                                        in 0..3 -> {
                                            // Free 2D Corner Drag
                                            val newImgX = ((change.position.x - ox) / scale).coerceIn(0f, maxW)
                                            val newImgY = ((change.position.y - oy) / scale).coerceIn(0f, maxH)
                                            updated[handle] = PointF(newImgX, newImgY)
                                        }
                                        4 -> {
                                            // Top Midpoint: Shifts entire Top Edge (TL and TR) straight Up/Down
                                            val limitY = minOf(updated[3].y, updated[2].y) - 25f
                                            val newTL_y = (updated[0].y + dyImg).coerceIn(0f, limitY.coerceAtLeast(0f))
                                            val newTR_y = (updated[1].y + dyImg).coerceIn(0f, limitY.coerceAtLeast(0f))
                                            updated[0] = PointF(updated[0].x, newTL_y)
                                            updated[1] = PointF(updated[1].x, newTR_y)
                                        }
                                        5 -> {
                                            // Bottom Midpoint: Shifts entire Bottom Edge (BL and BR) straight Up/Down
                                            val limitY = maxOf(updated[0].y, updated[1].y) + 25f
                                            val newBL_y = (updated[3].y + dyImg).coerceIn(limitY.coerceAtMost(maxH), maxH)
                                            val newBR_y = (updated[2].y + dyImg).coerceIn(limitY.coerceAtMost(maxH), maxH)
                                            updated[3] = PointF(updated[3].x, newBL_y)
                                            updated[2] = PointF(updated[2].x, newBR_y)
                                        }
                                        6 -> {
                                            // Left Midpoint: Shifts entire Left Edge (TL and BL) straight Left/Right
                                            val limitX = minOf(updated[1].x, updated[2].x) - 25f
                                            val newTL_x = (updated[0].x + dxImg).coerceIn(0f, limitX.coerceAtLeast(0f))
                                            val newBL_x = (updated[3].x + dxImg).coerceIn(0f, limitX.coerceAtLeast(0f))
                                            updated[0] = PointF(newTL_x, updated[0].y)
                                            updated[3] = PointF(newBL_x, updated[3].y)
                                        }
                                        7 -> {
                                            // Right Midpoint: Shifts entire Right Edge (TR and BR) straight Left/Right
                                            val limitX = maxOf(updated[0].x, updated[3].x) + 25f
                                            val newTR_x = (updated[1].x + dxImg).coerceIn(limitX.coerceAtMost(maxW), maxW)
                                            val newBR_x = (updated[2].x + dxImg).coerceIn(limitX.coerceAtMost(maxW), maxW)
                                            updated[1] = PointF(newTR_x, updated[1].y)
                                            updated[2] = PointF(newBR_x, updated[2].y)
                                        }
                                    }
                                    localCorners = updated
                                },
                                onDragEnd = {
                                    if (localCorners.size == 4) {
                                        viewModel.setCorners(localCorners)
                                    }
                                    activeDraggingHandle = null
                                    touchScreenPos = null
                                    lastTouchPos = null
                                },
                                onDragCancel = {
                                    localCorners = state.corners
                                    activeDraggingHandle = null
                                    touchScreenPos = null
                                    lastTouchPos = null
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

                    // 2. Draw quadrilateral, grid lines, and all 8 handles
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

                        // Interior tint
                        drawPath(
                            path = path,
                            color = Color(0x2810B981)
                        )

                        // Outer boundary outline
                        drawPath(
                            path = path,
                            color = Color(0xFF10B981),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // 3. Draw 4 Midpoint Edge Handles (4=Top, 5=Bottom, 6=Left, 7=Right)
                        val topMid = Offset((screenCorners[0].x + screenCorners[1].x) / 2f, (screenCorners[0].y + screenCorners[1].y) / 2f)
                        val botMid = Offset((screenCorners[3].x + screenCorners[2].x) / 2f, (screenCorners[3].y + screenCorners[2].y) / 2f)
                        val leftMid = Offset((screenCorners[0].x + screenCorners[3].x) / 2f, (screenCorners[0].y + screenCorners[3].y) / 2f)
                        val rightMid = Offset((screenCorners[1].x + screenCorners[2].x) / 2f, (screenCorners[1].y + screenCorners[2].y) / 2f)

                        // Top & Bottom Horizontal Midpoint Pills (Drag Up/Down)
                        listOf(4 to topMid, 5 to botMid).forEach { (hIdx, pt) ->
                            val isDragging = (activeDraggingHandle == hIdx)
                            val pillW = if (isDragging) 38.dp.toPx() else 30.dp.toPx()
                            val pillH = if (isDragging) 18.dp.toPx() else 14.dp.toPx()
                            val pillTopLeft = Offset(pt.x - pillW / 2f, pt.y - pillH / 2f)

                            // Outer shadow
                            drawRoundRect(
                                color = if (isDragging) Color(0x8810B981) else Color(0x66000000),
                                topLeft = Offset(pillTopLeft.x - 2.dp.toPx(), pillTopLeft.y - 2.dp.toPx()),
                                size = Size(pillW + 4.dp.toPx(), pillH + 4.dp.toPx()),
                                cornerRadius = CornerRadius(10.dp.toPx())
                            )
                            // White pill body
                            drawRoundRect(
                                color = Color.White,
                                topLeft = pillTopLeft,
                                size = Size(pillW, pillH),
                                cornerRadius = CornerRadius(8.dp.toPx())
                            )
                            // Emerald center grip
                            drawRoundRect(
                                color = Color(0xFF10B981),
                                topLeft = Offset(pt.x - 8.dp.toPx(), pt.y - 2.dp.toPx()),
                                size = Size(16.dp.toPx(), 4.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }

                        // Left & Right Vertical Midpoint Pills (Drag Left/Right)
                        listOf(6 to leftMid, 7 to rightMid).forEach { (hIdx, pt) ->
                            val isDragging = (activeDraggingHandle == hIdx)
                            val pillW = if (isDragging) 18.dp.toPx() else 14.dp.toPx()
                            val pillH = if (isDragging) 38.dp.toPx() else 30.dp.toPx()
                            val pillTopLeft = Offset(pt.x - pillW / 2f, pt.y - pillH / 2f)

                            // Outer shadow
                            drawRoundRect(
                                color = if (isDragging) Color(0x8810B981) else Color(0x66000000),
                                topLeft = Offset(pillTopLeft.x - 2.dp.toPx(), pillTopLeft.y - 2.dp.toPx()),
                                size = Size(pillW + 4.dp.toPx(), pillH + 4.dp.toPx()),
                                cornerRadius = CornerRadius(10.dp.toPx())
                            )
                            // White pill body
                            drawRoundRect(
                                color = Color.White,
                                topLeft = pillTopLeft,
                                size = Size(pillW, pillH),
                                cornerRadius = CornerRadius(8.dp.toPx())
                            )
                            // Emerald center grip
                            drawRoundRect(
                                color = Color(0xFF10B981),
                                topLeft = Offset(pt.x - 2.dp.toPx(), pt.y - 8.dp.toPx()),
                                size = Size(4.dp.toPx(), 16.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }

                        // 4. Draw 4 Corner Handles (TL, TR, BR, BL)
                        screenCorners.forEachIndexed { i, pt ->
                            val isDragging = (activeDraggingHandle == i)
                            val radius = if (isDragging) 18.dp.toPx() else 14.dp.toPx()

                            // Outer glow halo
                            drawCircle(
                                color = if (isDragging) Color(0x6610B981) else Color(0x44FFFFFF),
                                radius = radius + 6.dp.toPx(),
                                center = pt
                            )
                            // White ring
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

                // Magnifier Loupe when dragging a corner (0..3)
                val activeHandle = activeDraggingHandle
                val touchPos = touchScreenPos
                if (activeHandle != null && activeHandle in 0..3 && touchPos != null && localCorners.size == 4) {
                    val cornerPoint = localCorners[activeHandle]

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

                // Midpoint Dragging Helper Badge (4..7)
                if (activeHandle != null && activeHandle in 4..7) {
                    val helperText = when (activeHandle) {
                        4 -> "↕ Moving Top Edge"
                        5 -> "↕ Moving Bottom Edge"
                        6 -> "↔ Moving Left Edge"
                        7 -> "↔ Moving Right Edge"
                        else -> ""
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF10B981),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp)
                            .shadow(8.dp, RoundedCornerShape(20.dp))
                    ) {
                        Text(
                            text = helperText,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Detecting progress indicator
            if (state.isDetecting) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.85f),
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
