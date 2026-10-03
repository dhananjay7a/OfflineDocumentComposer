@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.offlinedocumentcomposer.domain.pdf.PdfAnnotationOverlay
import com.example.offlinedocumentcomposer.domain.pdf.PdfEditorService
import com.example.offlinedocumentcomposer.domain.pdf.PdfThumbnailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun PdfSignEditScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editorService = remember { PdfEditorService(context) }

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentPageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // List of active overlays placed on pages
    var overlays by remember { mutableStateOf<List<PdfAnnotationOverlay>>(emptyList()) }
    var selectedOverlayId by remember { mutableStateOf<String?>(null) }

    var showDrawSignDialog by remember { mutableStateOf(false) }
    var showAddTextDialog by remember { mutableStateOf(false) }
    var showUploadSignDialog by remember { mutableStateOf(false) }
    var pendingUploadBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var isSaving by remember { mutableStateOf(false) }
    var outputResult by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Initial PDF picker
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                errorMessage = null
                outputResult = null
                overlays = emptyList()
                selectedOverlayId = null

                val imported = copyUriToCache(context, uri)
                if (imported != null) {
                    sourceFile = imported
                    sourceName = queryFileName(context, uri) ?: imported.name
                    val count = PdfThumbnailHelper.getPageCount(imported)
                    pageCount = count
                    currentPageIndex = 0

                    val bmp = PdfThumbnailHelper.renderPage(imported, 0, maxDim = 1200)
                    currentPageBitmap = bmp
                } else {
                    errorMessage = "Failed to load selected PDF document"
                }
            }
        }
    }

    // Load page bitmap when page changes
    fun loadPage(index: Int) {
        val file = sourceFile ?: return
        if (index !in 0 until pageCount) return
        currentPageIndex = index
        selectedOverlayId = null
        currentPageBitmap = null
        scope.launch {
            val bmp = PdfThumbnailHelper.renderPage(file, index, maxDim = 1200)
            currentPageBitmap = bmp
        }
    }

    // Gallery signature picker (opens Crop & Clean dialog)
    val gallerySignPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val opts = android.graphics.BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            android.graphics.BitmapFactory.decodeStream(stream, null, opts)

                            // Downsample to max 1600px for high performance and low memory
                            val maxDim = 1600
                            var sample = 1
                            while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                                sample *= 2
                            }

                            val readOpts = android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            context.contentResolver.openInputStream(uri)?.use { s2 ->
                                android.graphics.BitmapFactory.decodeStream(s2, null, readOpts)
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (loaded != null) {
                    pendingUploadBitmap = loaded
                    showUploadSignDialog = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sign & Edit PDF", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (sourceFile == null) "Draw/upload signature & add text boxes"
                            else "$sourceName • Page ${currentPageIndex + 1} of $pageCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sourceFile != null) {
                        TextButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) {
                            Text("Change")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (sourceFile != null && outputResult == null) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Toolbar: Draw Sign, Upload Sign, Add Text
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showDrawSignDialog = true },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Draw Sign", style = MaterialTheme.typography.labelMedium)
                            }

                            OutlinedButton(
                                onClick = { gallerySignPicker.launch("image/*") },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Upload Sign", style = MaterialTheme.typography.labelMedium)
                            }

                            OutlinedButton(
                                onClick = { showAddTextDialog = true },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Text", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // Primary Save CTA
                        Button(
                            onClick = {
                                val src = sourceFile ?: return@Button
                                scope.launch {
                                    isSaving = true
                                    errorMessage = null
                                    try {
                                        val out = DocumentFiles.output(context, "Signed_${src.nameWithoutExtension}")
                                        val result = editorService.applyOverlaysToPdf(src, overlays, out)
                                        outputResult = result
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to sign & save PDF"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("Applying Annotations...")
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (overlays.isEmpty()) "Save PDF (Unmodified)" else "Save Signed PDF (${overlays.size} Items)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            outputResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Signed PDF Ready!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        PdfOutputActions(result)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { outputResult = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("Keep Editing")
                        }
                    }
                }
                return@Scaffold
            }

            if (sourceFile == null) {
                SignEmptyState(onPickClick = { pdfPicker.launch(arrayOf("application/pdf")) })
            } else {
                // Page switcher bar
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { loadPage(currentPageIndex - 1) },
                            enabled = currentPageIndex > 0,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Page")
                        }

                        Text(
                            "Page ${currentPageIndex + 1} of $pageCount",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        IconButton(
                            onClick = { loadPage(currentPageIndex + 1) },
                            enabled = currentPageIndex < pageCount - 1,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Page")
                        }
                    }
                }

                // Interactive Annotation Canvas Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val pageBmp = currentPageBitmap
                    if (pageBmp != null) {
                        PdfInteractiveCanvas(
                            pageBitmap = pageBmp,
                            currentPageIndex = currentPageIndex,
                            overlays = overlays,
                            selectedId = selectedOverlayId,
                            onSelectOverlay = { selectedOverlayId = it },
                            onUpdateOverlay = { updated ->
                                overlays = overlays.map { if (it.id == updated.id) updated else it }
                            },
                            onDeleteOverlay = { id ->
                                overlays = overlays.filter { it.id != id }
                                if (selectedOverlayId == id) selectedOverlayId = null
                            },
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }

    if (showDrawSignDialog) {
        SignaturePadDialog(
            onDismiss = { showDrawSignDialog = false },
            onSignatureDrawn = { signBmp ->
                val overlay = PdfAnnotationOverlay(
                    pageIndex = currentPageIndex,
                    normX = 0.35f,
                    normY = 0.65f,
                    normW = 0.30f,
                    normH = 0.12f,
                    signatureBitmap = signBmp
                )
                overlays = overlays + overlay
                selectedOverlayId = overlay.id
                showDrawSignDialog = false
            }
        )
    }

    if (showAddTextDialog) {
        AddTextDialog(
            onDismiss = { showAddTextDialog = false },
            onTextAdded = { text, sizePt, colorInt, bold ->
                val overlay = PdfAnnotationOverlay(
                    pageIndex = currentPageIndex,
                    normX = 0.30f,
                    normY = 0.50f,
                    normW = 0.40f,
                    normH = 0.08f,
                    text = text,
                    textSizePt = sizePt,
                    textColor = colorInt,
                    isBold = bold
                )
                overlays = overlays + overlay
                selectedOverlayId = overlay.id
                showAddTextDialog = false
            }
        )
    }

    if (showUploadSignDialog && pendingUploadBitmap != null) {
        UploadSignatureDialog(
            sourceBitmap = pendingUploadBitmap!!,
            onDismiss = {
                showUploadSignDialog = false
                pendingUploadBitmap = null
            },
            onSignatureReady = { signBmp ->
                val overlay = PdfAnnotationOverlay(
                    pageIndex = currentPageIndex,
                    normX = 0.35f,
                    normY = 0.65f,
                    normW = 0.32f,
                    normH = 0.12f,
                    signatureBitmap = signBmp
                )
                overlays = overlays + overlay
                selectedOverlayId = overlay.id
                showUploadSignDialog = false
                pendingUploadBitmap = null
            }
        )
    }
}

/**
 * Interactive canvas displaying the rendered PDF page with draggable, resizable signature and text overlays.
 * Features 120 FPS latency-free local dragging without parent recomposition stutter.
 */
@Composable
private fun PdfInteractiveCanvas(
    pageBitmap: Bitmap,
    currentPageIndex: Int,
    overlays: List<PdfAnnotationOverlay>,
    selectedId: String?,
    onSelectOverlay: (String?) -> Unit,
    onUpdateOverlay: (PdfAnnotationOverlay) -> Unit,
    onDeleteOverlay: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Use rememberUpdatedState so pointerInput never restarts or drops ongoing gestures
    val currentOverlays by rememberUpdatedState(overlays)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentOnSelect by rememberUpdatedState(onSelectOverlay)
    val currentOnUpdate by rememberUpdatedState(onUpdateOverlay)
    val currentOnDelete by rememberUpdatedState(onDeleteOverlay)

    // Local state for the item being actively dragged/resized for 120 FPS buttery-smooth interaction
    var activeDraggedOverlay by remember { mutableStateOf<PdfAnnotationOverlay?>(null) }

    val pageOverlays = remember(overlays, currentPageIndex) {
        overlays.filter { it.pageIndex == currentPageIndex }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pos = down.position
                    val viewW = canvasSize.width.toFloat()
                    val viewH = canvasSize.height.toFloat()
                    if (viewW <= 0f || viewH <= 0f) return@awaitEachGesture

                    val imgW = pageBitmap.width.toFloat()
                    val imgH = pageBitmap.height.toFloat()
                    val scale = minOf(viewW / imgW, viewH / imgH)
                    val renderedW = imgW * scale
                    val renderedH = imgH * scale
                    if (renderedW <= 0f || renderedH <= 0f) return@awaitEachGesture
                    val offsetX = (viewW - renderedW) / 2f
                    val offsetY = (viewH - renderedH) / 2f

                    val currentList = currentOverlays.filter { it.pageIndex == currentPageIndex }
                    val selId = currentSelectedId
                    val selectedItem = currentList.find { it.id == selId }

                    val handleRadius = 24.dp.toPx()
                    var dragMode: String? = null
                    var targetOverlay: PdfAnnotationOverlay? = null

                    // 1. Check Delete button on currently selected item first (top-right corner)
                    if (selectedItem != null) {
                        val delX = offsetX + (selectedItem.normX + selectedItem.normW) * renderedW
                        val delY = offsetY + selectedItem.normY * renderedH
                        if (hypot(pos.x - delX, pos.y - delY) < handleRadius) {
                            down.consume()
                            currentOnDelete(selectedItem.id)
                            return@awaitEachGesture
                        }

                        // 2. Check Resize handle on currently selected item (bottom-right corner)
                        val resX = offsetX + (selectedItem.normX + selectedItem.normW) * renderedW
                        val resY = offsetY + (selectedItem.normY + selectedItem.normH) * renderedH
                        if (hypot(pos.x - resX, pos.y - resY) < handleRadius) {
                            down.consume()
                            dragMode = "RESIZE"
                            targetOverlay = selectedItem
                        }
                    }

                    // 3. Check inside any overlay bounds on the page (topmost first)
                    if (dragMode == null) {
                        for (item in currentList.asReversed()) {
                            val left = offsetX + item.normX * renderedW
                            val top = offsetY + item.normY * renderedH
                            val right = left + item.normW * renderedW
                            val bottom = top + item.normH * renderedH
                            if (pos.x in left..right && pos.y in top..bottom) {
                                down.consume()
                                dragMode = "MOVE"
                                targetOverlay = item
                                currentOnSelect(item.id)
                                break
                            }
                        }
                    }

                    var activeItem = targetOverlay ?: run {
                        // Tapped on background outside any overlay -> deselect
                        currentOnSelect(null)
                        return@awaitEachGesture
                    }

                    var curNormX = activeItem.normX
                    var curNormY = activeItem.normY
                    var curNormW = activeItem.normW
                    var curNormH = activeItem.normH
                    val initialAspect = (curNormW / curNormH).coerceIn(0.2f, 10f)
                    var lastPointerPos = pos
                    activeDraggedOverlay = activeItem

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            break
                        }
                        change.consume()
                        val dx = change.position.x - lastPointerPos.x
                        val dy = change.position.y - lastPointerPos.y
                        lastPointerPos = change.position

                        val dxNorm = dx / renderedW
                        val dyNorm = dy / renderedH

                        if (dragMode == "MOVE") {
                            curNormX = (curNormX + dxNorm).coerceIn(0f, 1f - curNormW)
                            curNormY = (curNormY + dyNorm).coerceIn(0f, 1f - curNormH)
                            activeItem = activeItem.copy(normX = curNormX, normY = curNormY, normW = curNormW, normH = curNormH)
                            activeDraggedOverlay = activeItem
                        } else if (dragMode == "RESIZE") {
                            curNormW = (curNormW + dxNorm).coerceIn(0.06f, 1f - curNormX)
                            // Scale height proportionally to maintain natural aspect ratio
                            curNormH = (curNormW / initialAspect).coerceIn(0.02f, 1f - curNormY)
                            activeItem = activeItem.copy(normX = curNormX, normY = curNormY, normW = curNormW, normH = curNormH)
                            activeDraggedOverlay = activeItem
                        }
                    }

                    // On gesture completion (finger up): commit updated overlay to persistent list
                    activeDraggedOverlay?.let { finalUpdated ->
                        currentOnUpdate(finalUpdated)
                    }
                    activeDraggedOverlay = null
                }
            }
    ) {
        val viewW = size.width
        val viewH = size.height
        val imgW = pageBitmap.width.toFloat()
        val imgH = pageBitmap.height.toFloat()
        val scale = minOf(viewW / imgW, viewH / imgH)
        val renderedW = imgW * scale
        val renderedH = imgH * scale
        val offsetX = (viewW - renderedW) / 2f
        val offsetY = (viewH - renderedH) / 2f

        // 1. Draw page bitmap with crisp white backing
        drawRect(Color.White, Offset(offsetX, offsetY), Size(renderedW, renderedH))
        val srcRect = android.graphics.Rect(0, 0, pageBitmap.width, pageBitmap.height)
        val dstRect = android.graphics.RectF(offsetX, offsetY, offsetX + renderedW, offsetY + renderedH)
        drawContext.canvas.nativeCanvas.drawBitmap(pageBitmap, srcRect, dstRect, null)

        // 2. Draw overlays on the current page
        val activeDrag = activeDraggedOverlay
        for (item in pageOverlays) {
            val drawItem = if (activeDrag != null && item.id == activeDrag.id) activeDrag else item
            val itemX = offsetX + drawItem.normX * renderedW
            val itemY = offsetY + drawItem.normY * renderedH
            val itemW = drawItem.normW * renderedW
            val itemH = drawItem.normH * renderedH
            val isSelected = drawItem.id == selectedId

            if (drawItem.signatureBitmap != null) {
                // Signature image: native Compose drawImage with SrcOver guarantees clean transparency without black box
                drawImage(
                    image = drawItem.signatureBitmap.asImageBitmap(),
                    dstOffset = IntOffset(itemX.toInt(), itemY.toInt()),
                    dstSize = IntSize(itemW.toInt().coerceAtLeast(1), itemH.toInt().coerceAtLeast(1)),
                    blendMode = BlendMode.SrcOver
                )
            } else if (!drawItem.text.isNullOrBlank()) {
                // Text box
                val paint = AndroidPaint().apply {
                    isAntiAlias = true
                    color = drawItem.textColor
                    textSize = drawItem.textSizePt * scale * 1.3f
                    isFakeBoldText = drawItem.isBold
                }
                drawContext.canvas.nativeCanvas.drawText(
                    drawItem.text,
                    itemX + 4f,
                    itemY + itemH / 2f + paint.textSize / 3f,
                    paint
                )
            }

            // Selection border and handles
            if (isSelected) {
                // Selection bounding box
                drawRect(
                    color = Color(0xFF2563EB),
                    topLeft = Offset(itemX, itemY),
                    size = Size(itemW, itemH),
                    style = Stroke(width = 2.dp.toPx())
                )
                // Subtle blue tint to indicate active item
                drawRect(
                    color = Color(0x122563EB),
                    topLeft = Offset(itemX, itemY),
                    size = Size(itemW, itemH)
                )

                // Resize handle at bottom-right corner (Blue circle with white inner dot)
                val brCenter = Offset(itemX + itemW, itemY + itemH)
                drawCircle(
                    color = Color(0xFF2563EB),
                    radius = 12.dp.toPx(),
                    center = brCenter
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = brCenter
                )

                // Delete handle (X) at top-right corner (Red circle with white cross)
                val trCenter = Offset(itemX + itemW, itemY)
                drawCircle(
                    color = Color(0xFFEF4444),
                    radius = 12.dp.toPx(),
                    center = trCenter
                )
                val xSize = 4.5.dp.toPx()
                drawLine(
                    color = Color.White,
                    start = Offset(trCenter.x - xSize, trCenter.y - xSize),
                    end = Offset(trCenter.x + xSize, trCenter.y + xSize),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(trCenter.x + xSize, trCenter.y - xSize),
                    end = Offset(trCenter.x - xSize, trCenter.y + xSize),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

/**
 * Dedicated hardware-accelerated signature canvas View that eliminates drawing lag completely.
 * Processes batched historical touch points with smooth Bézier interpolation at 120 FPS.
 */
private class SignatureDrawingView(context: Context) : android.view.View(context) {
    data class Stroke(val path: AndroidPath, val color: Int, val widthPx: Float)

    val strokes = mutableListOf<Stroke>()
    private var currentPath = AndroidPath()
    private var lastX = 0f
    private var lastY = 0f

    var strokeColor: Int = AndroidColor.BLACK
    var strokeWidthPx: Float = 6f
    var onStrokesChanged: ((Int) -> Unit)? = null

    private val guidelinePaint = AndroidPaint().apply {
        isAntiAlias = true
        color = AndroidColor.parseColor("#E2E8F0")
        strokeWidth = 3f
        style = AndroidPaint.Style.STROKE
    }

    private val strokePaint = AndroidPaint().apply {
        isAntiAlias = true
        isDither = true
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPath = AndroidPath()
                currentPath.moveTo(event.x, event.y)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val historySize = event.historySize
                for (i in 0 until historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    val midX = (lastX + hx) / 2f
                    val midY = (lastY + hy) / 2f
                    currentPath.quadTo(lastX, lastY, midX, midY)
                    lastX = hx
                    lastY = hy
                }
                val curX = event.x
                val curY = event.y
                val midX = (lastX + curX) / 2f
                val midY = (lastY + curY) / 2f
                currentPath.quadTo(lastX, lastY, midX, midY)
                lastX = curX
                lastY = curY
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(lastX, lastY)
                strokes.add(Stroke(currentPath, strokeColor, strokeWidthPx))
                currentPath = AndroidPath()
                invalidate()
                onStrokesChanged?.invoke(strokes.size)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                currentPath = AndroidPath()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        // Draw subtle signature baseline
        val lineY = height * 0.78f
        canvas.drawLine(40f, lineY, width - 40f, lineY, guidelinePaint)

        // Draw completed strokes
        for (s in strokes) {
            strokePaint.color = s.color
            strokePaint.strokeWidth = s.widthPx
            canvas.drawPath(s.path, strokePaint)
        }

        // Draw current in-progress stroke
        if (!currentPath.isEmpty) {
            strokePaint.color = strokeColor
            strokePaint.strokeWidth = strokeWidthPx
            canvas.drawPath(currentPath, strokePaint)
        }
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
            onStrokesChanged?.invoke(strokes.size)
        }
    }

    fun clear() {
        strokes.clear()
        currentPath = AndroidPath()
        invalidate()
        onStrokesChanged?.invoke(0)
    }

    fun exportBitmap(targetW: Int = 800, targetH: Int = 400): Bitmap {
        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AndroidColor.TRANSPARENT)
        bmp.setHasAlpha(true)
        val canvas = AndroidCanvas(bmp)

        if (strokes.isEmpty()) return bmp

        val bounds = RectF()
        val combined = AndroidPath()
        for (s in strokes) combined.addPath(s.path)
        combined.computeBounds(bounds, true)

        if (bounds.width() <= 1f || bounds.height() <= 1f) {
            for (s in strokes) {
                strokePaint.color = s.color
                strokePaint.strokeWidth = s.widthPx
                canvas.drawPath(s.path, strokePaint)
            }
            return bmp
        }

        val pad = 36f
        val availW = (targetW - pad * 2).coerceAtLeast(10f)
        val availH = (targetH - pad * 2).coerceAtLeast(10f)
        val scale = minOf(availW / bounds.width(), availH / bounds.height())

        val offsetX = (targetW - bounds.width() * scale) / 2f - bounds.left * scale
        val offsetY = (targetH - bounds.height() * scale) / 2f - bounds.top * scale

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        for (s in strokes) {
            strokePaint.color = s.color
            strokePaint.strokeWidth = s.widthPx / scale.coerceAtLeast(0.5f)
            canvas.drawPath(s.path, strokePaint)
        }
        canvas.restore()
        return bmp
    }
}

/**
 * Signature Pad Dialog allowing finger drawing with ink color choice and thickness.
 * Powered by hardware-accelerated SignatureDrawingView for zero-lag 120 FPS drawing.
 */
@Composable
private fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onSignatureDrawn: (Bitmap) -> Unit
) {
    val density = LocalDensity.current.density
    var penColor by remember { mutableStateOf(Color.Black) }
    var penWidthDp by remember { mutableFloatStateOf(4.5f) }
    var strokeCount by remember { mutableIntStateOf(0) }
    var signatureViewRef by remember { mutableStateOf<SignatureDrawingView?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with Title, Undo, and Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Draw Signature", fontWeight = FontWeight.Bold, fontSize = 17.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { signatureViewRef?.undo() },
                            enabled = strokeCount > 0,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = { signatureViewRef?.clear() },
                            enabled = strokeCount > 0,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Ultra-responsive hardware-accelerated signature canvas
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SignatureDrawingView(ctx).apply {
                                strokeColor = penColor.toArgb()
                                strokeWidthPx = penWidthDp * density
                                onStrokesChanged = { count -> strokeCount = count }
                                signatureViewRef = this
                            }
                        },
                        update = { view ->
                            view.strokeColor = penColor.toArgb()
                            view.strokeWidthPx = penWidthDp * density
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Ink Colors Palette
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val colors = listOf(
                        Color.Black to "Black",
                        Color(0xFF1E3A8A) to "Blue",
                        Color(0xFF991B1B) to "Red",
                        Color(0xFF166534) to "Green"
                    )
                    for ((c, _) in colors) {
                        Surface(
                            shape = CircleShape,
                            color = c,
                            border = if (penColor == c) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(32.dp)
                                .clickable { penColor = c }
                        ) {}
                    }
                }

                // Stroke thickness pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = penWidthDp == 3f,
                        onClick = { penWidthDp = 3f },
                        label = { Text("Fine") }
                    )
                    FilterChip(
                        selected = penWidthDp == 4.5f,
                        onClick = { penWidthDp = 4.5f },
                        label = { Text("Medium") }
                    )
                    FilterChip(
                        selected = penWidthDp == 7f,
                        onClick = { penWidthDp = 7f },
                        label = { Text("Bold") }
                    )
                }

                // Dialog action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val view = signatureViewRef
                            if (view == null || view.strokes.isEmpty()) {
                                onDismiss()
                                return@Button
                            }
                            val bmp = view.exportBitmap(800, 400)
                            onSignatureDrawn(bmp)
                        },
                        enabled = strokeCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

/**
 * Text Box dialog for adding typed text or date stamp annotations onto the PDF.
 */
@Composable
private fun AddTextDialog(
    onDismiss: () -> Unit,
    onTextAdded: (text: String, sizePt: Float, colorInt: Int, isBold: Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var sizePt by remember { mutableFloatStateOf(16f) }
    var isBold by remember { mutableStateOf(false) }
    var selectedColor by remember { mutableIntStateOf(AndroidColor.BLACK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Text Box", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Enter text") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Quick Date shortcut
                AssistChip(
                    onClick = {
                        val today = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        text = if (text.isBlank()) today else "$text $today"
                    },
                    leadingIcon = { Icon(Icons.Default.Today, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("+ Today's Date") }
                )

                // Font Size
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Font Size", style = MaterialTheme.typography.bodySmall)
                        Text("${sizePt.toInt()} pt", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = sizePt,
                        onValueChange = { sizePt = it },
                        valueRange = 10f..36f
                    )
                }

                // Text Style & Colors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = isBold,
                        onClick = { isBold = !isBold },
                        label = { Text("Bold", fontWeight = FontWeight.Bold) }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AndroidColor.BLACK, AndroidColor.BLUE, AndroidColor.RED).forEach { c ->
                            Surface(
                                shape = CircleShape,
                                color = Color(c),
                                border = if (selectedColor == c) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier.size(28.dp).clickable { selectedColor = c }
                            ) {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onTextAdded(text, sizePt, selectedColor, isBold)
                    }
                },
                enabled = text.isNotBlank()
            ) {
                Text("Add Text")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SignEmptyState(onPickClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(vertical = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Draw, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Sign & Annotate PDF", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)

            Spacer(Modifier.height(8.dp))

            Text(
                "Draw your digital signature with natural ink strokes, upload signature photos from gallery with automatic background removal, or add resizable text boxes and date stamps directly onto any PDF page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onPickClick,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select PDF to Sign", fontWeight = FontWeight.Bold)
            }
        }
    }
}

enum class SignatureInkMode(val label: String) {
    DEEP_BLACK("Deep Black"),
    ROYAL_BLUE("Royal Blue"),
    ORIGINAL_INK("Original Color")
}

/**
 * Extracts handwritten ink with 100% transparent paper background using Otsu binarization,
 * interactive sensitivity bias, and smooth anti-aliased edge falloff.
 */
private fun extractSignatureWithTransparency(
    source: Bitmap,
    cropRect: RectF? = null,
    sensitivityBias: Float = 0f,
    inkMode: SignatureInkMode = SignatureInkMode.DEEP_BLACK
): Bitmap {
    val srcW = source.width
    val srcH = source.height
    val cropLeft = if (cropRect != null) (cropRect.left * srcW).toInt().coerceIn(0, srcW - 1) else 0
    val cropTop = if (cropRect != null) (cropRect.top * srcH).toInt().coerceIn(0, srcH - 1) else 0
    val cropRight = if (cropRect != null) (cropRect.right * srcW).toInt().coerceIn(cropLeft + 1, srcW) else srcW
    val cropBottom = if (cropRect != null) (cropRect.bottom * srcH).toInt().coerceIn(cropTop + 1, srcH) else srcH

    val regionW = cropRight - cropLeft
    val regionH = cropBottom - cropTop
    if (regionW <= 0 || regionH <= 0) return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    val pixels = IntArray(regionW * regionH)
    source.getPixels(pixels, 0, regionW, cropLeft, cropTop, regionW, regionH)

    // Compute luminance histogram for Otsu's binarization
    val hist = IntArray(256)
    val totalPixels = regionW * regionH
    var totalLumSum = 0.0

    for (p in pixels) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val lum = (r * 77 + g * 150 + b * 29) shr 8
        hist[lum]++
        totalLumSum += lum
    }

    // Otsu optimal threshold calculation
    var sumB = 0.0
    var wB = 0
    var maxVariance = 0.0
    var otsuThreshold = 140

    for (t in 0..255) {
        wB += hist[t]
        if (wB == 0) continue
        val wF = totalPixels - wB
        if (wF == 0) break

        sumB += t.toDouble() * hist[t]
        val mB = sumB / wB
        val mF = (totalLumSum - sumB) / wF
        val variance = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)

        if (variance > maxVariance) {
            maxVariance = variance
            otsuThreshold = t
        }
    }

    // Apply sensitivity bias (-50 to +50) with safe bounds
    val adjustedThreshold = (otsuThreshold + sensitivityBias).coerceIn(40f, 250f)
    val softBand = 18f

    val outPixels = IntArray(regionW * regionH)
    var minX = regionW
    var maxX = 0
    var minY = regionH
    var maxY = 0
    var hasInk = false

    for (y in 0 until regionH) {
        for (x in 0 until regionW) {
            val idx = y * regionW + x
            val color = pixels[idx]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8

            if (lum >= adjustedThreshold) {
                // Background paper: 100% transparent
                outPixels[idx] = 0x00000000
            } else {
                // Ink pixel: smooth anti-aliased alpha
                val alphaFloat = if (lum < adjustedThreshold - softBand) {
                    1f
                } else {
                    ((adjustedThreshold - lum) / softBand).coerceIn(0f, 1f)
                }
                val alphaInt = (alphaFloat * 255f).toInt().coerceIn(0, 255)

                if (alphaInt > 25) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    hasInk = true
                }

                val outColor = when (inkMode) {
                    SignatureInkMode.DEEP_BLACK -> {
                        (alphaInt shl 24) or 0x000000
                    }
                    SignatureInkMode.ROYAL_BLUE -> {
                        (alphaInt shl 24) or 0x1E3A8A
                    }
                    SignatureInkMode.ORIGINAL_INK -> {
                        val cr = (r * 0.85f).toInt().coerceIn(0, 255)
                        val cg = (g * 0.85f).toInt().coerceIn(0, 255)
                        val cb = (b * 0.85f).toInt().coerceIn(0, 255)
                        (alphaInt shl 24) or (cr shl 16) or (cg shl 8) or cb
                    }
                }
                outPixels[idx] = outColor
            }
        }
    }

    val fullExtracted = Bitmap.createBitmap(regionW, regionH, Bitmap.Config.ARGB_8888).apply {
        setHasAlpha(true)
        setPixels(outPixels, 0, regionW, 0, 0, regionW, regionH)
    }

    if (!hasInk || maxX <= minX || maxY <= minY) {
        return fullExtracted
    }

    // Auto-crop tightly around actual ink bounds with padding
    val pad = 16
    val cropX = (minX - pad).coerceAtLeast(0)
    val cropY = (minY - pad).coerceAtLeast(0)
    val cropW = (maxX - minX + pad * 2).coerceAtMost(regionW - cropX)
    val cropH = (maxY - minY + pad * 2).coerceAtMost(regionH - cropY)

    if (cropW <= 0 || cropH <= 0) return fullExtracted

    val tightlyCropped = Bitmap.createBitmap(fullExtracted, cropX, cropY, cropW, cropH).apply {
        setHasAlpha(true)
    }
    if (tightlyCropped != fullExtracted) {
        fullExtracted.recycle()
    }
    return tightlyCropped
}

/**
 * Checkerboard pattern component for clearly visualizing transparent background.
 */
@Composable
private fun CheckerboardPattern(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val squareSize = 12.dp.toPx()
        val numX = (size.width / squareSize).toInt() + 1
        val numY = (size.height / squareSize).toInt() + 1
        for (y in 0 until numY) {
            for (x in 0 until numX) {
                val isEven = (x + y) % 2 == 0
                val color = if (isEven) Color(0xFFF8FAFC) else Color(0xFFE2E8F0)
                drawRect(
                    color = color,
                    topLeft = Offset(x * squareSize, y * squareSize),
                    size = Size(squareSize, squareSize)
                )
            }
        }
    }
}

/**
 * Interactive crop canvas allowing corner and boundary adjustments to crop signature photos.
 */
@Composable
private fun InteractiveCropCanvas(
    bitmap: Bitmap,
    cropRect: RectF,
    onCropChanged: (RectF) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val currentCrop by rememberUpdatedState(cropRect)
    val currentOnChange by rememberUpdatedState(onCropChanged)

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pos = down.position
                    val cw = canvasSize.width.toFloat()
                    val ch = canvasSize.height.toFloat()
                    if (cw <= 0f || ch <= 0f) return@awaitEachGesture

                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()
                    val scale = minOf(cw / bw, ch / bh)
                    val rw = bw * scale
                    val rh = bh * scale
                    val ox = (cw - rw) / 2f
                    val oy = (ch - rh) / 2f

                    val cl = ox + currentCrop.left * rw
                    val ct = oy + currentCrop.top * rh
                    val cr = ox + currentCrop.right * rw
                    val cb = oy + currentCrop.bottom * rh

                    val hitRadius = 28.dp.toPx()
                    val dragTarget = when {
                        hypot(pos.x - cl, pos.y - ct) < hitRadius -> "TL"
                        hypot(pos.x - cr, pos.y - ct) < hitRadius -> "TR"
                        hypot(pos.x - cl, pos.y - cb) < hitRadius -> "BL"
                        hypot(pos.x - cr, pos.y - cb) < hitRadius -> "BR"
                        pos.x in cl..cr && pos.y in ct..cb -> "BOX"
                        else -> null
                    } ?: return@awaitEachGesture

                    down.consume()
                    var lastPos = pos
                    var localCrop = RectF(currentCrop)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()

                        val dx = (change.position.x - lastPos.x) / rw
                        val dy = (change.position.y - lastPos.y) / rh
                        lastPos = change.position

                        when (dragTarget) {
                            "TL" -> {
                                val nLeft = (localCrop.left + dx).coerceIn(0f, localCrop.right - 0.1f)
                                val nTop = (localCrop.top + dy).coerceIn(0f, localCrop.bottom - 0.1f)
                                localCrop = RectF(nLeft, nTop, localCrop.right, localCrop.bottom)
                            }
                            "TR" -> {
                                val nRight = (localCrop.right + dx).coerceIn(localCrop.left + 0.1f, 1f)
                                val nTop = (localCrop.top + dy).coerceIn(0f, localCrop.bottom - 0.1f)
                                localCrop = RectF(localCrop.left, nTop, nRight, localCrop.bottom)
                            }
                            "BL" -> {
                                val nLeft = (localCrop.left + dx).coerceIn(0f, localCrop.right - 0.1f)
                                val nBottom = (localCrop.bottom + dy).coerceIn(localCrop.top + 0.1f, 1f)
                                localCrop = RectF(nLeft, localCrop.top, localCrop.right, nBottom)
                            }
                            "BR" -> {
                                val nRight = (localCrop.right + dx).coerceIn(localCrop.left + 0.1f, 1f)
                                val nBottom = (localCrop.bottom + dy).coerceIn(localCrop.top + 0.1f, 1f)
                                localCrop = RectF(localCrop.left, localCrop.top, nRight, nBottom)
                            }
                            "BOX" -> {
                                val w = localCrop.width()
                                val h = localCrop.height()
                                val nLeft = (localCrop.left + dx).coerceIn(0f, 1f - w)
                                val nTop = (localCrop.top + dy).coerceIn(0f, 1f - h)
                                localCrop = RectF(nLeft, nTop, nLeft + w, nTop + h)
                            }
                        }
                        currentOnChange(localCrop)
                    }
                }
            }
    ) {
        val cw = size.width
        val ch = size.height
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = minOf(cw / bw, ch / bh)
        val rw = bw * scale
        val rh = bh * scale
        val ox = (cw - rw) / 2f
        val oy = (ch - rh) / 2f

        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = android.graphics.RectF(ox, oy, ox + rw, oy + rh)
        drawContext.canvas.nativeCanvas.drawBitmap(bitmap, srcRect, dstRect, null)

        val cl = ox + cropRect.left * rw
        val ct = oy + cropRect.top * rh
        val cr = ox + cropRect.right * rw
        val cb = oy + cropRect.bottom * rh

        val maskColor = Color(0x99000000)
        drawRect(maskColor, topLeft = Offset(ox, oy), size = Size(rw, (ct - oy).coerceAtLeast(0f)))
        drawRect(maskColor, topLeft = Offset(ox, cb), size = Size(rw, (oy + rh - cb).coerceAtLeast(0f)))
        drawRect(maskColor, topLeft = Offset(ox, ct), size = Size((cl - ox).coerceAtLeast(0f), cb - ct))
        drawRect(maskColor, topLeft = Offset(cr, ct), size = Size((ox + rw - cr).coerceAtLeast(0f), cb - ct))

        drawRect(
            color = Color(0xFF2563EB),
            topLeft = Offset(cl, ct),
            size = Size(cr - cl, cb - ct),
            style = Stroke(width = 2.dp.toPx())
        )

        val handleRadius = 9.dp.toPx()
        val corners = listOf(
            Offset(cl, ct),
            Offset(cr, ct),
            Offset(cl, cb),
            Offset(cr, cb)
        )
        for (corner in corners) {
            drawCircle(color = Color(0xFF2563EB), radius = handleRadius, center = corner)
            drawCircle(color = Color.White, radius = handleRadius - 2.5.dp.toPx(), center = corner)
        }
    }
}

/**
 * Upload Signature Dialog featuring interactive crop, Otsu paper background cleaning,
 * sensitivity slider, ink mode presets, and live transparent checkerboard preview.
 */
@Composable
private fun UploadSignatureDialog(
    sourceBitmap: Bitmap,
    onDismiss: () -> Unit,
    onSignatureReady: (Bitmap) -> Unit
) {
    var cropRect by remember { mutableStateOf(RectF(0.05f, 0.05f, 0.95f, 0.95f)) }
    var sensitivityBias by remember { mutableFloatStateOf(0f) }
    var inkMode by remember { mutableStateOf(SignatureInkMode.DEEP_BLACK) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(cropRect, sensitivityBias, inkMode) {
        isProcessing = true
        val result = withContext(Dispatchers.Default) {
            extractSignatureWithTransparency(sourceBitmap, cropRect, sensitivityBias, inkMode)
        }
        previewBitmap = result
        isProcessing = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Clean & Extract Signature", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Crop around signature & remove paper background", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { cropRect = RectF(0f, 0f, 1f, 1f) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Crop, contentDescription = "Full Image", modifier = Modifier.size(18.dp))
                    }
                }

                // Interactive Crop View
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    InteractiveCropCanvas(
                        bitmap = sourceBitmap,
                        cropRect = cropRect,
                        onCropChanged = { cropRect = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Live Transparent Preview
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Transparent Result Preview", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        if (isProcessing) {
                            Text("Updating...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        CheckerboardPattern(modifier = Modifier.fillMaxSize())

                        val preview = previewBitmap
                        if (preview != null) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "Signature Preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(10.dp)
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                // Clean Paper Threshold Slider
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Clean Paper Background", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                sensitivityBias < -10f -> "Keep Light Ink"
                                sensitivityBias > 10f -> "Aggressive Clean"
                                else -> "Auto (Otsu)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = sensitivityBias,
                        onValueChange = { sensitivityBias = it },
                        valueRange = -50f..50f
                    )
                }

                // Ink Style Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SignatureInkMode.entries.forEach { mode ->
                        FilterChip(
                            selected = inkMode == mode,
                            onClick = { inkMode = mode },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val finalBmp = previewBitmap ?: extractSignatureWithTransparency(sourceBitmap, cropRect, sensitivityBias, inkMode)
                            onSignatureReady(finalBmp)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add to PDF")
                    }
                }
            }
        }
    }
}

