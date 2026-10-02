@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.offlinedocumentcomposer.data.model.*
import com.example.offlinedocumentcomposer.domain.pdf.*
import com.example.offlinedocumentcomposer.presentation.detection.DetectionScreen
import com.example.offlinedocumentcomposer.presentation.tools.PdfOutputActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit, vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var camera by rememberSaveable { mutableStateOf(false) }
    var retakeId by rememberSaveable { mutableStateOf<String?>(null) }
    var cropId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val fallbackGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        if (it.isNotEmpty()) vm.add(it)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) vm.add(it)
    }

    val launchGallery = {
        try {
            gallery.launch(arrayOf("image/*"))
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                fallbackGallery.launch("image/*")
            } catch (e2: Exception) {
                vm.reportError("No image picker available. Enable the Files app and try again.")
            }
        } catch (e: Exception) {
            vm.reportError("Could not open images: ${e.message}")
        }
    }

    BackHandler(camera || cropId != null) {
        camera = false
        cropId = null
        retakeId = null
    }

    if (camera) {
        val lastThumbPath = state.session.pages.lastOrNull()?.let { vm.repository.thumbnail(it).absolutePath }
        LiveScannerScreen(
            onCaptured = { uri ->
                vm.add(listOf(uri), retakeId)
                if (retakeId != null) {
                    camera = false
                    retakeId = null
                }
            },
            onBack = {
                camera = false
                retakeId = null
            },
            importing = state.busy,
            pageCount = state.session.pages.size,
            processingError = state.error,
            initialThumbnailPath = lastThumbPath
        )
        return
    }

    val cropping = state.session.pages.firstOrNull { it.id == cropId }
    if (cropping != null) {
        CropScanPage(cropping, vm, onBack = { cropId = null }) { points ->
            vm.update(cropping.copy(corners = points, needsReview = false))
            cropId = null
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Image to PDF",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (state.session.pages.isEmpty()) "Convert photos into PDF"
                                else "${state.session.pages.size} ${if (state.session.pages.size == 1) "page" else "pages"} · Auto-saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.session.pages.isNotEmpty() && !state.busy) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "New document",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (state.session.pages.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.busy) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner(size = 20.dp)
                                Text(
                                    state.progress.ifBlank { "Processing..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = vm::cancel) {
                                    Text("Cancel")
                                }
                            }
                        }

                        state.error?.let { err ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    err,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        // Secondary actions: Scan more & Gallery
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { camera = true },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan More", style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = { launchGallery() },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Photos", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        // Primary Action Button: Review or Export
                        val reviewPages = state.session.pages.filter { it.needsReview }
                        val hasReview = reviewPages.isNotEmpty()

                        Button(
                            onClick = {
                                if (hasReview) {
                                    cropId = reviewPages.first().id
                                } else {
                                    exporting = true
                                }
                            },
                            enabled = !state.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = if (hasReview) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            } else ButtonDefaults.buttonColors()
                        ) {
                            Icon(
                                if (hasReview) Icons.Default.Warning else Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (hasReview) "Review Crops (${reviewPages.size} Pending)"
                                else "Export PDF (${state.session.pages.size} ${if (state.session.pages.size == 1) "Page" else "Pages"})",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (state.session.pages.isEmpty()) {
            EmptyScanPlaceholder(
                onScanClick = { camera = true },
                onGalleryClick = { launchGallery() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Spacer(Modifier.height(4.dp))
                    DocumentSummaryHeader(
                        pageCount = state.session.pages.size,
                        needsReviewCount = state.session.pages.count { it.needsReview },
                        onReviewClick = {
                            val pending = state.session.pages.firstOrNull { it.needsReview }
                            if (pending != null) cropId = pending.id
                        }
                    )
                }

                itemsIndexed(state.session.pages, key = { _, page -> page.id }) { index, page ->
                    ScanPageCard(
                        page = page,
                        index = index,
                        totalPages = state.session.pages.size,
                        thumbnailPath = vm.repository.thumbnail(page).absolutePath,
                        busy = state.busy,
                        onCrop = { cropId = page.id },
                        onFilter = { editId = page.id },
                        onMoveUp = { vm.move(page.id, -1) },
                        onMoveDown = { vm.move(page.id, 1) },
                        onRotate = { vm.update(page.copy(rotation = (page.rotation + 90) % 360)) },
                        onRetake = {
                            retakeId = page.id
                            camera = true
                        },
                        onDelete = { vm.delete(page.id) }
                    )
                }

                state.output?.let { output ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "PDF Exported Successfully!",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                PdfOutputActions(output)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    val editing = state.session.pages.firstOrNull { it.id == editId }
    if (editing != null) {
        EnhanceDialog(editing, vm, onDismiss = { editId = null }) {
            vm.update(it)
            editId = null
        }
    }

    if (exporting) {
        ExportOptionsDialog(
            reviewCount = state.session.pages.count { it.needsReview },
            onDismiss = { exporting = false }
        ) { name, options ->
            exporting = false
            vm.export(name, options)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("Start a new document?", fontWeight = FontWeight.Bold) },
            text = { Text("This will clear the current pages and edits. Saved or exported PDF files remain safe on your device.") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.clear()
                        confirmClear = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Keep Editing")
                }
            }
        )
    }
}

@Composable
private fun DocumentSummaryHeader(
    pageCount: Int,
    needsReviewCount: Int,
    onReviewClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$pageCount ${if (pageCount == 1) "Page" else "Pages"} in Document",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
                if (needsReviewCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.clickable { onReviewClick() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$needsReviewCount check needed",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
            Text(
                "Drag corners to crop, switch filters, or use arrow buttons to reorder pages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyScanPlaceholder(
    onScanClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(20.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Convert Images to PDF",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Scan physical papers with 8-point manual edge cropping or import photos from gallery. Combine multiple pages into a crisp offline PDF.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HighlightChip(icon = Icons.Default.Lock, text = "100% Offline")
                    HighlightChip(icon = Icons.Default.Crop, text = "Auto Edge Detect")
                    HighlightChip(icon = Icons.Default.AutoFixHigh, text = "Magic Color & B&W")
                }

                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = onScanClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan with Camera", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onGalleryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Select from Gallery", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun HighlightChip(icon: ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ScanPageCard(
    page: ScanPage,
    index: Int,
    totalPages: Int,
    thumbnailPath: String,
    busy: Boolean,
    onCrop: () -> Unit,
    onFilter: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRotate: () -> Unit,
    onRetake: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            // Card Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Page Number Chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "Page ${index + 1}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Filter Name Chip
                val filterName = when (page.filter) {
                    ScanFilter.BLACK_WHITE -> "Document B&W"
                    ScanFilter.COLOR -> "Magic Color"
                    ScanFilter.GRAYSCALE -> "Grayscale"
                    ScanFilter.ORIGINAL -> "Original"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        filterName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (page.needsReview) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "Check Crop",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Page",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Body: Thumbnail + Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Page Thumbnail
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(92.dp, 124.dp)
                ) {
                    PageThumbnail(
                        path = thumbnailPath,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(Modifier.width(14.dp))

                // Action Controls Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onCrop,
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Crop", style = MaterialTheme.typography.labelMedium)
                        }

                        OutlinedButton(
                            onClick = onFilter,
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f).height(38.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Filter", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // Toolbar for Reorder and Rotation
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onMoveUp,
                                enabled = !busy && index > 0,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Move Up",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (!busy && index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(
                                onClick = onMoveDown,
                                enabled = !busy && index < totalPages - 1,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = "Move Down",
                                    modifier = Modifier.size(18.dp),
                                    tint = if (!busy && index < totalPages - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(
                                onClick = onRotate,
                                enabled = !busy,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.RotateRight,
                                    contentDescription = "Rotate 90°",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = onRetake,
                                enabled = !busy,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = "Retake",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageThumbnail(path: String, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose { owned?.recycle() }
    }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = "Page preview",
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun CropScanPage(
    page: ScanPage,
    vm: ScanViewModel,
    onBack: () -> Unit,
    onApply: (List<ScanPoint>) -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, page.id) {
        try {
            value = withContext(Dispatchers.IO) { vm.repository.source(page) }
        } catch (e: Exception) {
            error = e.message ?: "Could not load source image"
        }
    }
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose { owned?.recycle() }
    }
    val source = bitmap
    if (source != null) {
        val corners = remember(page.id, source) {
            page.corners.map { PointF(it.x * (source.width - 1), it.y * (source.height - 1)) }
        }
        DetectionScreen(
            source,
            title = "Adjust Page Corners",
            onProceed = {},
            onBack = onBack,
            initialCorners = corners,
            onCornersConfirmed = { points ->
                onApply(points.map { ScanPoint(it.x / (source.width - 1), it.y / (source.height - 1)) })
            },
            viewModel = viewModel(key = "crop_${page.id}_${page.revision}")
        )
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(error ?: "Loading source image…", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text("Go Back")
            }
        }
    }
}

@Composable
private fun EnhanceDialog(
    page: ScanPage,
    vm: ScanViewModel,
    onDismiss: () -> Unit,
    onApply: (ScanPage) -> Unit
) {
    var draft by remember(page.id) { mutableStateOf(page) }
    var previewPage by remember(page.id) { mutableStateOf(page) }
    var error by remember { mutableStateOf<String?>(null) }

    val bitmap by produceState<Bitmap?>(null, previewPage) {
        try {
            value = withContext(Dispatchers.IO) { vm.repository.render(previewPage, 650) }
        } catch (e: Exception) {
            error = e.message
        }
    }
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose { owned?.recycle() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Enhance Page Filter", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Live Filter Preview Box
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "Filter preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner(size = 28.dp)
                        }
                    }
                }

                // Filter Mode Selection Chips
                Text("Filter Mode", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScanFilter.values().forEach { filter ->
                        val filterLabel = when (filter) {
                            ScanFilter.BLACK_WHITE -> "Document B&W"
                            ScanFilter.COLOR -> "Magic Color"
                            ScanFilter.GRAYSCALE -> "Grayscale"
                            ScanFilter.ORIGINAL -> "Original"
                        }
                        FilterChip(
                            selected = draft.filter == filter,
                            onClick = {
                                draft = draft.copy(filter = filter)
                                previewPage = draft
                            },
                            label = { Text(filterLabel) },
                            leadingIcon = if (draft.filter == filter) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }

                // Brightness Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Brightness", style = MaterialTheme.typography.bodyMedium)
                        Text("${draft.brightness.toInt()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = draft.brightness,
                        onValueChange = { draft = draft.copy(brightness = it) },
                        valueRange = -50f..50f,
                        onValueChangeFinished = { previewPage = draft }
                    )
                }

                // Contrast Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Contrast", style = MaterialTheme.typography.bodyMedium)
                        Text("${draft.contrast.toInt()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = draft.contrast,
                        onValueChange = { draft = draft.copy(contrast = it) },
                        valueRange = -50f..70f,
                        onValueChangeFinished = { previewPage = draft }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(draft) }) {
                Text("Apply Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ExportOptionsDialog(
    reviewCount: Int,
    onDismiss: () -> Unit,
    onExport: (String, ScanExportOptions) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("Scanned document") }
    var options by remember { mutableStateOf(ScanExportOptions()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Export PDF", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (reviewCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$reviewCount page(s) have uncertain boundaries. Review crops first if needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Document Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Page Format
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Page Format", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !options.letter,
                            onClick = { options = options.copy(letter = false) },
                            label = { Text("A4 Standard") },
                            leadingIcon = if (!options.letter) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = options.letter,
                            onClick = { options = options.copy(letter = true) },
                            label = { Text("US Letter") },
                            leadingIcon = if (options.letter) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }

                // Page Orientation
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Orientation", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !options.landscape,
                            onClick = { options = options.copy(landscape = false) },
                            label = { Text("Portrait") }
                        )
                        FilterChip(
                            selected = options.landscape,
                            onClick = { options = options.copy(landscape = true) },
                            label = { Text("Landscape") }
                        )
                    }
                }

                // Margin
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Page Margin", style = MaterialTheme.typography.bodyMedium)
                        Text("${options.marginMm.toInt()} mm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = options.marginMm,
                        onValueChange = { options = options.copy(marginMm = it) },
                        valueRange = 0f..40f,
                        steps = 39
                    )
                }

                // Quality
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quality & Compression", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        PdfQuality.values().forEach { q ->
                            val label = when (q) {
                                PdfQuality.LIGHT -> "High · 200 DPI (Best for printing)"
                                PdfQuality.BALANCED -> "Balanced · 150 DPI (Recommended)"
                                PdfQuality.STRONG -> "Small · 100 DPI (Email & Portals)"
                            }
                            FilterChip(
                                selected = options.quality == q,
                                onClick = { options = options.copy(quality = q) },
                                label = { Text(label) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onExport(name, options) },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Generate PDF")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
