@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.passport

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.offlinedocumentcomposer.domain.image.ImageUtils
import com.example.offlinedocumentcomposer.domain.passport.PassportSheetConfig
import com.example.offlinedocumentcomposer.domain.passport.PassportSheetSize
import com.example.offlinedocumentcomposer.domain.passport.SheetOrientation
import com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner
import com.example.offlinedocumentcomposer.presentation.tools.DocumentFiles
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportPhotoScreen(
    onBack: () -> Unit,
    viewModel: PassportViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) } // 0: Photos & Copies, 1: Sheet Layout
    var exportedFileForActions by remember { mutableStateOf<File?>(null) }
    var singlePhotoSavedDialog by remember { mutableStateOf<File?>(null) }

    // Gallery picker for single/multiple photos
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPhotosFromUris(context, uris)
        }
    }

    // Camera capture
    var tempCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = tempCameraUri
        if (success && uri != null) {
            viewModel.addPhotosFromUris(context, listOf(uri))
        }
    }

    // If an item is actively being cropped, show PassportCropScreen full-screen
    val activeCropItem = state.activeCroppingItem
    if (activeCropItem != null) {
        PassportCropScreen(
            sourceBitmap = activeCropItem.originalBitmap,
            initialStandard = activeCropItem.standard,
            queueIndex = state.cropQueueIndex,
            queueTotal = state.cropQueueTotal,
            onBack = { viewModel.cancelCrop() },
            onSkip = { viewModel.cancelCrop() },
            onCropApplied = { croppedBmp, standard, customW, customH ->
                viewModel.applyCrop(croppedBmp, standard, customW, customH)
            }
        )
        return
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Passport Photos",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.sheetConfig.sheetSize.title} • ${if (state.sheetConfig.orientation == SheetOrientation.PORTRAIT) "Portrait" else "Landscape"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Undo
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = state.canUndo && !state.isBusy,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            modifier = Modifier.size(20.dp),
                            tint = if (state.canUndo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }

                    // Redo
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.canRedo && !state.isBusy,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            modifier = Modifier.size(20.dp),
                            tint = if (state.canRedo) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    if (state.photos.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { viewModel.fillSheetCopies() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Fill", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
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
                    if (state.isBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            SafeLoadingSpinner(size = 20.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = state.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Export JPEG Button
                        OutlinedButton(
                            onClick = {
                                viewModel.exportJpeg(context) { file ->
                                    exportedFileForActions = file
                                    Toast.makeText(context, "Saved 300 DPI JPEG Sheet", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = state.photos.isNotEmpty() && !state.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("JPEG", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // 2. Export PDF Button
                        OutlinedButton(
                            onClick = {
                                viewModel.exportPdf(context) { file ->
                                    exportedFileForActions = file
                                    Toast.makeText(context, "Saved PDF Sheet", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = state.photos.isNotEmpty() && !state.isBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PDF", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // 3. Direct Print Button
                        Button(
                            onClick = {
                                viewModel.printSheet(context)
                            },
                            enabled = state.photos.isNotEmpty() && !state.isBusy,
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Print", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
        ) {
            // Live Sheet Preview Card at Top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF1E293B))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                val preview = state.previewBitmap
                if (preview != null && state.photos.isNotEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(state.sheetConfig.effectiveSheetWidthMm / state.sheetConfig.effectiveSheetHeightMm)
                        ) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "Sheet Preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        val layout = state.layoutInfo
                        val capacity = layout?.maxSlotsPerSheet ?: 0
                        val totalCopies = state.photos.sumOf { it.copies }
                        val sheetCount = layout?.totalSheets ?: 1

                        Text(
                            text = "$totalCopies photos · ${layout?.columns ?: 0}×${layout?.rows ?: 0} grid ($capacity per sheet${if (sheetCount > 1) ", $sheetCount sheets" else ""})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.AccountBox,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add photos to generate your print sheet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Tab Navigation
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Photos & Copies (${state.photos.size})") },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Sheet Settings") },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            // Tab Content
            when (selectedTab) {
                0 -> PhotosAndCopiesTab(
                    photos = state.photos,
                    onAddGallery = { galleryLauncher.launch("image/*") },
                    onAddCamera = {
                        val uri = ImageUtils.createTempCameraUri(context)
                        tempCameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    onUpdateCopies = { id, count -> viewModel.updateCopies(id, count) },
                    onStartCrop = { item -> viewModel.startCrop(item) },
                    onRemovePhoto = { id -> viewModel.removePhoto(id) },
                    onExportSingle = { item ->
                        viewModel.exportSinglePhoto(item, context) { file ->
                            singlePhotoSavedDialog = file
                            Toast.makeText(context, "Saved single photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                1 -> SheetSettingsTab(
                    config = state.sheetConfig,
                    onConfigChanged = { viewModel.updateConfig(it) }
                )
            }
        }
    }

    // Export Action Sheet/Dialog
    exportedFileForActions?.let { file ->
        val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { exportedFileForActions = null },
            icon = {
                Icon(
                    if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text(if (isPdf) "PDF Sheet Ready" else "JPEG Sheet Ready") },
            text = {
                Column {
                    Text("File saved successfully:")
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text("Choose an action:", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        DocumentFiles.share(context, file)
                        exportedFileForActions = null
                    }) {
                        Text("Share")
                    }
                    if (isPdf) {
                        TextButton(onClick = {
                            DocumentFiles.print(context, file)
                            exportedFileForActions = null
                        }) {
                            Text("Print")
                        }
                    }
                    Button(onClick = {
                        DocumentFiles.open(context, file)
                        exportedFileForActions = null
                    }) {
                        Text("Open")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { exportedFileForActions = null }) {
                    Text("Done")
                }
            }
        )
    }

    // Single photo saved dialog
    singlePhotoSavedDialog?.let { file ->
        AlertDialog(
            onDismissRequest = { singlePhotoSavedDialog = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF0F9D58)) },
            title = { Text("Passport Photo Saved") },
            text = { Text("Saved digital passport photo for online application forms:\n${file.name}") },
            confirmButton = {
                Button(onClick = {
                    DocumentFiles.share(context, file)
                    singlePhotoSavedDialog = null
                }) {
                    Text("Share / Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { singlePhotoSavedDialog = null }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun PhotosAndCopiesTab(
    photos: List<com.example.offlinedocumentcomposer.domain.passport.PassportPhotoItem>,
    onAddGallery: () -> Unit,
    onAddCamera: () -> Unit,
    onUpdateCopies: (String, Int) -> Unit,
    onStartCrop: (com.example.offlinedocumentcomposer.domain.passport.PassportPhotoItem) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onExportSingle: (com.example.offlinedocumentcomposer.domain.passport.PassportPhotoItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Action Buttons to Add Photos
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onAddGallery,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gallery")
                }

                Button(
                    onClick = onAddCamera,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Camera")
                }
            }
        }

        if (photos.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No photos added yet. Tap Gallery or Camera above to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(photos, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Photo Thumbnail
                    Box(
                        modifier = Modifier
                            .size(72.dp, 92.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                    ) {
                        Image(
                            bitmap = item.croppedBitmap.asImageBitmap(),
                            contentDescription = "Passport Photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.standard.title,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            IconButton(
                                onClick = { onRemovePhoto(item.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Text(
                            text = "${item.effectiveWidthMm.toInt()} × ${item.effectiveHeightMm.toInt()} mm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Stepper for copies
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Copies:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)

                            FilledIconButton(
                                onClick = { onUpdateCopies(item.id, item.copies - 1) },
                                modifier = Modifier.size(28.dp),
                                enabled = item.copies > 1,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(14.dp))
                            }

                            Text(
                                text = "${item.copies}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.widthIn(min = 24.dp)
                            )

                            FilledIconButton(
                                onClick = { onUpdateCopies(item.id, item.copies + 1) },
                                modifier = Modifier.size(28.dp),
                                shape = CircleShape
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(14.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onStartCrop(item) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Re-Crop", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { onExportSingle(item) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Single Photo", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSettingsTab(
    config: PassportSheetConfig,
    onConfigChanged: (PassportSheetConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Sheet Paper Size
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Paper Sheet Size",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))

                PassportSheetSize.values().forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfigChanged(config.copy(sheetSize = size)) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = config.sheetSize == size,
                            onClick = { onConfigChanged(config.copy(sheetSize = size)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(size.title, fontWeight = FontWeight.Medium)
                            Text(size.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // 2. Sheet Orientation
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sheet Orientation",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterChip(
                        selected = config.orientation == SheetOrientation.PORTRAIT,
                        onClick = { onConfigChanged(config.copy(orientation = SheetOrientation.PORTRAIT)) },
                        label = { Text("Portrait") },
                        leadingIcon = { Icon(Icons.Default.StayCurrentPortrait, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = config.orientation == SheetOrientation.LANDSCAPE,
                        onClick = { onConfigChanged(config.copy(orientation = SheetOrientation.LANDSCAPE)) },
                        label = { Text("Landscape") },
                        leadingIcon = { Icon(Icons.Default.StayCurrentLandscape, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 3. Photos per Row (Columns)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Photos per Row (Columns)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Select how many photos fit horizontally across each row",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val columnOptions = listOf(
                        null to "Auto",
                        2 to "2",
                        3 to "3",
                        4 to "4",
                        5 to "5",
                        6 to "6 (Recommended)",
                        7 to "7",
                        8 to "8"
                    )
                    columnOptions.forEach { (count, label) ->
                        FilterChip(
                            selected = config.customColumns == count,
                            onClick = { onConfigChanged(config.copy(customColumns = count)) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // 4. Cutting Lines & Guides
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cutting Border Lines",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Draws 5px black scissor border around each photo for easy cutting",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.showCuttingBorder,
                        onCheckedChange = { onConfigChanged(config.copy(showCuttingBorder = it)) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Spacing gap between photos
                Text(
                    text = "Photo Spacing (Gap): ${config.gapMm.toInt()} mm",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = config.gapMm,
                    onValueChange = { onConfigChanged(config.copy(gapMm = it)) },
                    valueRange = 0f..8f,
                    steps = 7
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Sheet margin
                Text(
                    text = "Sheet Outer Margin: ${config.marginMm.toInt()} mm",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = config.marginMm,
                    onValueChange = { onConfigChanged(config.copy(marginMm = it)) },
                    valueRange = 0f..15f,
                    steps = 14
                )
            }
        }
    }
}
