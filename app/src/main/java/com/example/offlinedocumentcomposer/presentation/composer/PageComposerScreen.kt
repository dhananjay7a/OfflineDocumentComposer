package com.example.offlinedocumentcomposer.presentation.composer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.offlinedocumentcomposer.data.model.ImageLayer
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.domain.layout.SideBySideLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

data class RecentFileItem(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isPdf: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageComposerScreen(
    viewModel: ComposerViewModel,
    onExportClicked: (PageModel) -> Unit,
    onEditLayerClicked: (ImageLayer) -> Unit,
    onAddImageClicked: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val page = state.pageModel
    val pageWidthPx = page.getPageWidthPx()
    val pageHeightPx = page.getPageHeightPx()

    val density = LocalDensity.current
    val context = LocalContext.current

    var showNewCanvasSheet by remember { mutableStateOf(false) }
    var recentFiles by remember { mutableStateOf<List<RecentFileItem>>(emptyList()) }

    LaunchedEffect(showNewCanvasSheet) {
        if (showNewCanvasSheet) {
            val list = withContext(Dispatchers.IO) {
                val directories = listOfNotNull(
                    File(context.getExternalFilesDir(null), "ExportedDocuments"),
                    File(context.filesDir, "ExportedDocuments"),
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "DocComposer") },
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DocComposer"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DocComposer")
                )
                val results = mutableListOf<RecentFileItem>()
                val seen = mutableSetOf<String>()
                for (dir in directories) {
                    if (dir.exists() && dir.isDirectory) {
                        dir.listFiles()?.forEach { file ->
                            if (file.isFile && !seen.contains(file.absolutePath)) {
                                val name = file.name
                                if (!file.isHidden &&
                                    !name.startsWith(".") &&
                                    !name.contains("trashed", ignoreCase = true) &&
                                    !name.contains("pending", ignoreCase = true) &&
                                    file.length() > 0L
                                ) {
                                    val ext = file.extension.lowercase(Locale.ROOT)
                                    if (ext in listOf("pdf", "png", "jpg", "jpeg", "webp")) {
                                        seen.add(file.absolutePath)
                                        results.add(
                                            RecentFileItem(
                                                file = file,
                                                name = file.name,
                                                sizeBytes = file.length(),
                                                lastModified = file.lastModified(),
                                                isPdf = ext == "pdf"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                results.sortedByDescending { it.lastModified }.take(10)
            }
            recentFiles = list
        }
    }

    // Calculate canvas render scale so A4 sheet fits inside container with neat padding
    val canvasScale = remember(containerSize, pageWidthPx, pageHeightPx, density) {
        if (containerSize.width > 0 && containerSize.height > 0) {
            val padPx = with(density) { 20.dp.toPx() }
            val availW = (containerSize.width.toFloat() - padPx * 2).coerceAtLeast(100f)
            val availH = (containerSize.height.toFloat() - padPx * 2).coerceAtLeast(100f)
            minOf(availW / pageWidthPx, availH / pageHeightPx).coerceIn(0.05f, 4.0f)
        } else {
            0.5f
        }
    }

    // Exact DP size on screen matching physical pixels
    val canvasRenderW = with(density) { (pageWidthPx * canvasScale).toDp() }
    val canvasRenderH = with(density) { (pageHeightPx * canvasScale).toDp() }

    var activeLayerId by remember { mutableStateOf<String?>(null) }
    var isResizing by remember { mutableStateOf(false) }
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }
    var initialLayerX by remember { mutableStateOf(0f) }
    var initialLayerY by remember { mutableStateOf(0f) }
    var initialLayerW by remember { mutableStateOf(0f) }
    var initialLayerH by remember { mutableStateOf(0f) }
    // Slider-specific size capture: saved when user FIRST moves the slider
    var sliderInitialW by remember { mutableStateOf(0f) }
    var sliderInitialH by remember { mutableStateOf(0f) }

    // Cache ImageBitmap wrappers — keyed by (id, bitmap identity) so they only rebuild
    // when a bitmap is actually swapped, NOT on every position/size drag frame.
    val bitmapCacheKey = remember(state.imageLayers, state.revision) {
        state.imageLayers.map { it.id to (it.workingBitmap ?: it.originalBitmap) }
    }
    val cachedImageBitmaps = remember(bitmapCacheKey) {
        state.imageLayers.mapNotNull { layer ->
            val bmp = layer.workingBitmap ?: layer.originalBitmap
            bmp?.let { layer.id to it.asImageBitmap() }
        }.toMap()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "A4 Composer",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = viewModel.canUndo()
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    // Redo
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = viewModel.canRedo()
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    // Rotate orientation
                    IconButton(onClick = { viewModel.togglePageOrientation() }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate")
                    }
                    // New Canvas (icon only)
                    IconButton(onClick = { showNewCanvasSheet = true }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New Canvas", tint = Color(0xFF38BDF8))
                    }
                    // Print (icon only)
                    IconButton(onClick = { viewModel.printDirectly(context) }) {
                        Icon(Icons.Default.Print, contentDescription = "Print")
                    }
                    // Export PDF — small tonal button so it's always visible
                    FilledTonalButton(
                        onClick = { onExportClicked(state.pageModel) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF10B981),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .padding(end = 6.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PDF", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

                    // ── Primary action row: fixed 3 buttons that never scroll or clip ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Add Photo
                        Button(
                            onClick = onAddImageClicked,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6366F1),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        // + New Page
                        OutlinedButton(
                            onClick = {
                                viewModel.addNewPage()
                                Toast.makeText(context, "Page ${state.totalPages + 1} added", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Page", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        // Export PDF
                        Button(
                            onClick = { onExportClicked(state.pageModel) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Scrollable layout preset chips row ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Side-by-Side
                        Button(
                            onClick = { viewModel.arrangeSideBySide() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ViewColumn, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Side by Side", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        // Card size presets (only when a layer is selected)
                        val selected = state.imageLayers.find { it.id == state.selectedLayerId }
                        if (selected != null) {
                            FilledTonalButton(
                                onClick = { viewModel.applyEnlargedIdCardSize(selected.id) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("92×58 mm", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.applyTrueIdCardSize(selected.id) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("85.6×54 mm", fontSize = 13.sp)
                            }
                        }

                        // Center Both
                        OutlinedButton(
                            onClick = { viewModel.arrangeIdCardsSideBySideCenter() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AlignHorizontalCenter, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Center", fontSize = 13.sp)
                        }

                        // Stack Vertical
                        OutlinedButton(
                            onClick = { viewModel.arrangeVertical() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ViewStream, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stack", fontSize = 13.sp)
                        }

                        // 2 Copies
                        OutlinedButton(
                            onClick = { viewModel.duplicateFor2Copies() },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("×2 Copies", fontSize = 13.sp)
                        }

                        // Cut Guides toggle
                        FilterChip(
                            selected = state.showCutGuides,
                            onClick = { viewModel.toggleCutGuides() },
                            label = { Text("Cut Guides", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }

                    // ── Selected layer controls ──
                    val sel = state.imageLayers.find { it.id == state.selectedLayerId }
                    if (sel != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Dimension Pill
                            val widthMm = (sel.width / SideBySideLayout.MM_TO_PX).roundToInt()
                            val heightMm = (sel.height / SideBySideLayout.MM_TO_PX).roundToInt()
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "${widthMm}×${heightMm}mm",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }

                            // Resize Slider (aspect-ratio locked)
                            Slider(
                                value = sel.width,
                                onValueChange = { newW ->
                                    if (sliderInitialW == 0f) {
                                        sliderInitialW = sel.width
                                        sliderInitialH = sel.height
                                    }
                                    val aspect = sel.getAspectRatio()
                                    val newH = newW / aspect
                                    viewModel.updateLayerSizeInteractive(sel.id, newW, newH)
                                },
                                onValueChangeFinished = {
                                    val fromW = sliderInitialW.takeIf { it > 0f } ?: sel.width
                                    val fromH = sliderInitialH.takeIf { it > 0f } ?: sel.height
                                    viewModel.commitResize(sel.id, fromW, fromH, sel.width, sel.height)
                                    sliderInitialW = 0f
                                    sliderInitialH = 0f
                                },
                                valueRange = 100f..pageWidthPx,
                                modifier = Modifier.weight(1f)
                            )

                            // Rotate 90
                            IconButton(
                                onClick = { viewModel.rotateLayer(sel.id, 90f) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.RotateRight, contentDescription = "Rotate", modifier = Modifier.size(20.dp))
                            }

                            // Adjust Filters
                            IconButton(
                                onClick = { onEditLayerClicked(sel) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Adjust", modifier = Modifier.size(20.dp))
                            }

                            // Delete
                            IconButton(
                                onClick = { viewModel.removeImageLayer(sel.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0F172A)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Page Switcher row — page nav pill only, no duplicate New Canvas/Page buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page Navigation Pill: < Page 1/2 >
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E293B),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.switchPage(state.currentPageIndex - 1) },
                            enabled = state.currentPageIndex > 0,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = "Prev Page",
                                tint = if (state.currentPageIndex > 0) Color.White else Color(0xFF475569),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "Page ${state.currentPageNumber}/${state.totalPages}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        IconButton(
                            onClick = { viewModel.switchPage(state.currentPageIndex + 1) },
                            enabled = state.currentPageIndex < state.totalPages - 1,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "Next Page",
                                tint = if (state.currentPageIndex < state.totalPages - 1) Color.White else Color(0xFF475569),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (state.totalPages > 1) {
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(
                                onClick = {
                                    viewModel.deleteCurrentPage()
                                    Toast.makeText(context, "Page removed", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Page",
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // New Canvas icon button on the right side of page row
                IconButton(
                    onClick = { showNewCanvasSheet = true }
                ) {
                    Icon(
                        Icons.Default.NoteAdd,
                        contentDescription = "New Canvas",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Canvas Display Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { containerSize = it },
                contentAlignment = Alignment.Center
            ) {
            // White A4 Sheet Canvas
            Box(
                modifier = Modifier
                    .size(canvasRenderW, canvasRenderH)
                    .shadow(16.dp, shape = RoundedCornerShape(4.dp))
                    .background(Color.White, shape = RoundedCornerShape(4.dp))
                    .pointerInput(pageWidthPx, pageHeightPx, canvasScale) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startOffset = down.position
                            val pageX = startOffset.x / canvasScale
                            val pageY = startOffset.y / canvasScale

                            // Always read LIVE layers from the ViewModel to avoid stale snapshots
                            val layers = viewModel.state.value.imageLayers
                            val currentSel = layers.find { it.id == viewModel.state.value.selectedLayerId }

                            // 1. Check if user grabbed the bottom-right resize handle of selected layer
                            val handleHit = if (currentSel != null) {
                                val handleX = currentSel.x + currentSel.width
                                val handleY = currentSel.y + currentSel.height
                                hypot(pageX - handleX, pageY - handleY) * canvasScale < 52.dp.toPx()
                            } else false

                            // 2. Hit test layers to drag or select (topmost first)
                            val hitLayer = if (handleHit) currentSel else {
                                layers.reversed().find { layer ->
                                    pageX in layer.x..(layer.x + layer.width) &&
                                    pageY in layer.y..(layer.y + layer.height)
                                }
                            }

                            var isDrag = false
                            val activeId = hitLayer?.id
                            val isResizingNow = handleHit

                            if (hitLayer != null) {
                                dragStartPos = startOffset
                                initialLayerX = hitLayer.x
                                initialLayerY = hitLayer.y
                                initialLayerW = hitLayer.width
                                initialLayerH = hitLayer.height
                                isResizing = isResizingNow
                                activeLayerId = activeId
                            }

                            val pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.find { it.id == pointerId } ?: break

                                if (!change.pressed) {
                                    // Pointer released
                                    if (!isDrag) {
                                        // Tap detected
                                        if (hitLayer != null) {
                                            viewModel.selectLayer(hitLayer.id)
                                        } else {
                                            viewModel.selectLayer(null)
                                        }
                                    } else {
                                        // Drag completed
                                        if (activeId != null) {
                                            // Read live state for final position
                                            val finalLayer = viewModel.state.value.imageLayers.find { it.id == activeId }
                                            if (finalLayer != null) {
                                                if (isResizingNow) {
                                                    viewModel.commitResize(activeId, initialLayerW, initialLayerH, finalLayer.width, finalLayer.height)
                                                } else {
                                                    viewModel.commitMove(activeId, initialLayerX, initialLayerY, finalLayer.x, finalLayer.y)
                                                }
                                            }
                                        }
                                    }
                                    change.consume()
                                    activeLayerId = null
                                    isResizing = false
                                    break
                                }

                                val dragDist = (change.position - startOffset).getDistance()
                                if (!isDrag && dragDist > viewConfiguration.touchSlop) {
                                    isDrag = true
                                    if (activeId != null && activeId != viewModel.state.value.selectedLayerId) {
                                        viewModel.selectLayer(activeId)
                                    }
                                }

                                if (isDrag && activeId != null) {
                                    change.consume()
                                    val totalDx = (change.position.x - dragStartPos.x) / canvasScale
                                    val totalDy = (change.position.y - dragStartPos.y) / canvasScale

                                    if (isResizingNow) {
                                        val aspect = initialLayerH.takeIf { it > 0f }?.let { initialLayerW / it } ?: 1f
                                        val newW = (initialLayerW + totalDx).coerceIn(60f, pageWidthPx)
                                        val newH = newW / aspect
                                        viewModel.updateLayerSizeInteractive(activeId, newW, newH)
                                    } else {
                                        val newX = (initialLayerX + totalDx).coerceIn(0f, pageWidthPx - 20f)
                                        val newY = (initialLayerY + totalDy).coerceIn(0f, pageHeightPx - 20f)
                                        viewModel.updateLayerPositionInteractive(activeId, newX, newY)
                                    }
                                }
                            }
                        }
                    }
            ) {
                // Render Canvas Layers using Compose Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Margin guide line
                    val marginPx = page.margin * SideBySideLayout.MM_TO_PX * canvasScale
                    drawRect(
                        color = Color(0x2294A3B8),
                        topLeft = Offset(marginPx, marginPx),
                        size = Size(size.width - 2 * marginPx, size.height - 2 * marginPx),
                        style = Stroke(
                            width = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    )

                    // Draw image layers
                    for (layer in state.imageLayers) {
                        if (!layer.visible) continue
                        val imgBmp = cachedImageBitmaps[layer.id] ?: layer.workingBitmap?.asImageBitmap() ?: layer.originalBitmap?.asImageBitmap() ?: continue

                        val lx = layer.x * canvasScale
                        val ly = layer.y * canvasScale
                        val lw = (layer.width * canvasScale).toInt().coerceAtLeast(1)
                        val lh = (layer.height * canvasScale).toInt().coerceAtLeast(1)

                        drawImage(
                            image = imgBmp,
                            dstOffset = IntOffset(lx.toInt(), ly.toInt()),
                            dstSize = IntSize(lw, lh)
                        )

                        // DeshKit Cut Guides (thin dashed boundary around each card for scissors)
                        if (state.showCutGuides) {
                            drawRect(
                                color = Color(0x8894A3B8),
                                topLeft = Offset(lx, ly),
                                size = Size(lw.toFloat(), lh.toFloat()),
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                )
                            )
                        }

                        // Selection bounding box
                        if (layer.id == state.selectedLayerId) {
                            drawRect(
                                color = Color(0xFF2563EB),
                                topLeft = Offset(lx, ly),
                                size = Size(lw.toFloat(), lh.toFloat()),
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // Bottom-right resize handle (magnified touch target)
                            val handleCenter = Offset(lx + lw, ly + lh)
                            drawCircle(
                                color = Color(0xFF2563EB),
                                radius = 10.dp.toPx(),
                                center = handleCenter
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 6.dp.toPx(),
                                center = handleCenter
                            )
                        }
                    }
                }
            }

            // Quick instruction badge at bottom
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "Touch card to move • Drag blue handle to resize • DeshKit true scale",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            }
        }
    }

    if (showNewCanvasSheet) {
        val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
        Dialog(
            onDismissRequest = { showNewCanvasSheet = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(24.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFE0F2FE),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.DashboardCustomize,
                                        contentDescription = null,
                                        tint = Color(0xFF0284C7),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "New Canvas & Documents",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Create new canvas, add pages, or open recent files",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { showNewCanvasSheet = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Section 1: Creation Options
                        Text(
                            text = "CANVAS TEMPLATES & PAGES",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        // 1. New Blank Canvas
                        Card(
                            onClick = {
                                viewModel.createNewCanvas()
                                showNewCanvasSheet = false
                                Toast.makeText(context, "New Blank Canvas created", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFE0F2FE),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.NoteAdd, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("New Blank Canvas", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Resets current canvas to a pristine white A4 page", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // 2. Add New Page
                        Card(
                            onClick = {
                                viewModel.addNewPage()
                                showNewCanvasSheet = false
                                Toast.makeText(context, "Added Page ${state.totalPages + 1} of ${state.totalPages + 1}", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFDCFCE7),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.PostAdd, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Add New Page (+)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Adds Page ${state.totalPages + 1} to this document for multi-page export", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // 3. 2-Sided ID Card Template
                        Card(
                            onClick = {
                                viewModel.resetToIdCardTemplate()
                                showNewCanvasSheet = false
                                Toast.makeText(context, "2-Sided ID Card template created", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFF3E8FF),
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.CreditCard, contentDescription = null, tint = Color(0xFF9333EA), modifier = Modifier.size(24.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("2-Sided ID Card Template", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Standard front & back card layout side-by-side with 5% margin", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Section 2: Recent Files & Exports
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RECENT FILES & EXPORTS (${recentFiles.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (recentFiles.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("No Recent Exports Found", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Documents exported as PDF or Images will appear here for fast re-import.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            recentFiles.forEach { item ->
                                val sizeStr = if (item.sizeBytes < 1024 * 1024) {
                                    "${(item.sizeBytes / 1024.0).let { String.format(Locale.ROOT, "%.1f", it) }} KB"
                                } else {
                                    "${(item.sizeBytes / (1024.0 * 1024.0)).let { String.format(Locale.ROOT, "%.1f", it) }} MB"
                                }
                                val dateStr = dateFormat.format(Date(item.lastModified))

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (item.isPdf) Color(0xFFFEE2E2) else Color(0xFFE0F2FE),
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        if (item.isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                                                        contentDescription = null,
                                                        tint = if (item.isPdf) Color(0xFFDC2626) else Color(0xFF0284C7),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "$dateStr • $sizeStr",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val success = viewModel.importFileToCanvas(item.file, context, asNewCanvas = true)
                                                    showNewCanvasSheet = false
                                                    Toast.makeText(
                                                        context,
                                                        if (success) "Opened ${item.name} as new canvas" else "Could not load ${item.name}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                contentPadding = PaddingValues(vertical = 6.dp)
                                            ) {
                                                Text("Open as Canvas", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    val success = viewModel.importFileToCanvas(item.file, context, asNewCanvas = false)
                                                    showNewCanvasSheet = false
                                                    Toast.makeText(
                                                        context,
                                                        if (success) "Added ${item.name} as layer" else "Could not load ${item.name}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(vertical = 6.dp)
                                            ) {
                                                Text("+ Add as Layer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
