@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.resizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.offlinedocumentcomposer.domain.image.ImageUtils
import com.example.offlinedocumentcomposer.domain.resizer.*
import com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ImageResizerScreen(
    onBack: () -> Unit,
    viewModel: ImageResizerViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var exportedDialogFile by remember { mutableStateOf<File?>(null) }
    var selectedPresetCategory by remember { mutableStateOf(PresetCategory.ALL) }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.loadImageFromUri(context, uri)
        }
    }

    // Camera capture
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = tempCameraUri
        if (success && uri != null) {
            viewModel.loadImageFromUri(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Image Resizer",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        )
                        Text(
                            text = if (state.sourceMeta != null) {
                                "${state.targetDimensions.first} × ${state.targetDimensions.second} px • ${state.config.format.label}"
                            } else {
                                "Exact dimensions & compression"
                            },
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

                    // Reset
                    if (state.sourceMeta != null) {
                        IconButton(
                            onClick = { viewModel.resetToOriginal() },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.sourceMeta != null) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
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
                            // 1. Save to Gallery Button
                            Button(
                                onClick = {
                                    viewModel.exportResizedImage(context) { savedFile ->
                                        exportedDialogFile = savedFile
                                        Toast.makeText(context, "Saved to Gallery / ResizedImages", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1.8f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Save to Gallery", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${state.config.format.label} • ${state.formattedEstimatedSize}",
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            // 2. Share Button
                            OutlinedButton(
                                onClick = {
                                    viewModel.exportResizedImage(context) { savedFile ->
                                        shareFile(context, savedFile, state.config.format.mimeType)
                                    }
                                },
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            // 3. Print Button
                            OutlinedButton(
                                onClick = {
                                    viewModel.printResizedImage(context)
                                },
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Print", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        ) {
            val meta = state.sourceMeta
            val preview = state.previewBitmap

            if (meta == null) {
                // Empty State Dropzone
                EmptyResizerState(
                    onPickGallery = { galleryLauncher.launch("image/*") },
                    onTakePhoto = {
                        val uri = ImageUtils.createTempCameraUri(context)
                        tempCameraUri = uri
                        cameraLauncher.launch(uri)
                    }
                )
            } else {
                // Active Image Controls
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Live Preview Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0F172A)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (preview != null) {
                                    Image(
                                        bitmap = preview.asImageBitmap(),
                                        contentDescription = "Resized Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    SafeLoadingSpinner(size = 28.dp)
                                }

                                // Quick change overlay button
                                FilledTonalButton(
                                    onClick = { galleryLauncher.launch("image/*") },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .height(30.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Change", fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Comparison Stats Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Original", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${meta.originalWidth} × ${meta.originalHeight} px", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(meta.formattedFileSize, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }

                                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)

                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Resized Output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${state.targetDimensions.first} × ${state.targetDimensions.second} px", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text("~ ${state.formattedEstimatedSize}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0F9D58), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 2. Curated Presets Card
                    PresetsCard(
                        selectedCategory = selectedPresetCategory,
                        onCategorySelected = { selectedPresetCategory = it },
                        activePreset = state.selectedPreset,
                        onSelectPreset = { viewModel.applyPreset(it) }
                    )

                    // 3. Dimensions & Unit Settings Card
                    DimensionsCard(
                        config = state.config,
                        sourceMeta = meta,
                        onUnitChanged = { viewModel.setUnit(it) },
                        onWidthChanged = { viewModel.updateWidth(it) },
                        onHeightChanged = { viewModel.updateHeight(it) },
                        onScaleChanged = { viewModel.updateScalePercent(it) },
                        onToggleLockAspect = { viewModel.toggleLockAspectRatio() },
                        onDpiChanged = { viewModel.setDpi(it) }
                    )

                    // 4. Target File Size (KB Constraint) Card
                    TargetFileSizeCard(
                        currentTargetKb = state.config.targetMaxKb,
                        onTargetKbChanged = { viewModel.setTargetMaxKb(it) }
                    )

                    // 5. Output Format & Quality Card
                    FormatAndQualityCard(
                        format = state.config.format,
                        quality = state.config.quality,
                        onFormatChanged = { viewModel.setOutputFormat(it) },
                        onQualityChanged = { viewModel.setQuality(it) }
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }

    // Success Dialog
    exportedDialogFile?.let { file ->
        AlertDialog(
            onDismissRequest = { exportedDialogFile = null },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF0F9D58), modifier = Modifier.size(32.dp)) },
            title = { Text("Image Resized Successfully!", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("File: ${file.name}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    val kb = (file.length() / 1024L)
                    Text("Final Size: $kb KB (${file.length()} bytes)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Saved to public Pictures/ResizedImages and available in your device Gallery.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = {
                    shareFile(context, file, state.config.format.mimeType)
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { exportedDialogFile = null }) {
                    Text("Done")
                }
            }
        )
    }

    // Error Alert
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            icon = { Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Resizing Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun EmptyResizerState(
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PhotoSizeSelectLarge,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Resize Images Easily",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Resize by exact pixel dimensions, percentage scale, or physical mm/cm with aspect ratio lock and target KB limits for online forms.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onPickGallery,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gallery")
            }

            OutlinedButton(
                onClick = onTakePhoto,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Camera")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Feature Highlights
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureRow(Icons.Default.AspectRatio, "Keep Aspect Ratio", "Locks width and height to prevent unwanted distortion")
                FeatureRow(Icons.Default.Compress, "Target File Size (KB)", "Strictly compress under 20 KB or 50 KB for online exam portals")
                FeatureRow(Icons.Default.Tune, "Preset Categories", "One-tap sizes for SSC/UPSC, Instagram, YouTube, and Full HD")
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PresetsCard(
    selectedCategory: PresetCategory,
    onCategorySelected: (PresetCategory) -> Unit,
    activePreset: ImagePreset?,
    onSelectPreset: (ImagePreset) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Quick Presets",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Tap any preset to automatically configure dimensions & upload limits",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PresetCategory.values().forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onCategorySelected(cat) },
                        label = { Text(cat.title, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Presets in category
            val filtered = if (selectedCategory == PresetCategory.ALL) {
                StandardImagePresets.PRESETS
            } else {
                StandardImagePresets.PRESETS.filter { it.category == selectedCategory }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filtered.forEach { preset ->
                    val isSelected = activePreset?.id == preset.id
                    Card(
                        modifier = Modifier
                            .widthIn(min = 140.dp)
                            .clickable { onSelectPreset(preset) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        ),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = preset.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val dimText = when (preset.unit) {
                                ResizeUnit.MILLIMETERS -> "${preset.width.toInt()}×${preset.height.toInt()} mm"
                                else -> "${preset.width.toInt()}×${preset.height.toInt()} px"
                            }
                            Text(
                                text = dimText,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (preset.targetMaxKb != null) {
                                Text(
                                    text = "Max ${preset.targetMaxKb} KB",
                                    fontSize = 10.sp,
                                    color = Color(0xFF0F9D58),
                                    fontWeight = FontWeight.Medium
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
private fun DimensionsCard(
    config: ResizeConfiguration,
    sourceMeta: ImageSourceMeta,
    onUnitChanged: (ResizeUnit) -> Unit,
    onWidthChanged: (Float) -> Unit,
    onHeightChanged: (Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
    onToggleLockAspect: () -> Unit,
    onDpiChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Resize Mode & Dimensions",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )

                // Aspect Ratio Lock Button
                FilledTonalIconToggleButton(
                    checked = config.lockAspectRatio,
                    onCheckedChange = { onToggleLockAspect() },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (config.lockAspectRatio) Icons.Default.Link else Icons.Default.LinkOff,
                        contentDescription = "Lock Ratio",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Unit Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val availableUnits = listOf(ResizeUnit.PIXELS, ResizeUnit.PERCENTAGE, ResizeUnit.MILLIMETERS, ResizeUnit.CENTIMETERS, ResizeUnit.INCHES)
                availableUnits.forEach { unit ->
                    FilterChip(
                        selected = config.unit == unit,
                        onClick = { onUnitChanged(unit) },
                        label = { Text(unit.label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (config.unit) {
                ResizeUnit.PERCENTAGE -> {
                    // Percentage Scale Slider & Chips
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Scale Percentage", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("${config.scalePercent.roundToInt()}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }

                        Slider(
                            value = config.scalePercent,
                            onValueChange = { onScaleChanged(it) },
                            valueRange = 10f..300f,
                            steps = 57
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val quickScales = listOf(25f, 50f, 75f, 100f, 125f, 150f, 200f)
                            quickScales.forEach { scale ->
                                SuggestionChip(
                                    onClick = { onScaleChanged(scale) },
                                    label = { Text("${scale.toInt()}%", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Width and Height inputs with unit suffix
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Width Field
                        OutlinedTextField(
                            value = if (config.width > 0) {
                                if (config.unit == ResizeUnit.PIXELS) config.width.roundToInt().toString()
                                else String.format("%.1f", config.width)
                            } else "",
                            onValueChange = {
                                val v = it.toFloatOrNull()
                                if (v != null && v > 0) onWidthChanged(v)
                            },
                            label = { Text("Width (${config.unit.shortSuffix})") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(10.dp)
                        )

                        // Lock indicator in middle
                        IconButton(
                            onClick = onToggleLockAspect,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (config.lockAspectRatio) Icons.Default.Link else Icons.Default.LinkOff,
                                contentDescription = "Aspect ratio",
                                tint = if (config.lockAspectRatio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Height Field
                        OutlinedTextField(
                            value = if (config.height > 0) {
                                if (config.unit == ResizeUnit.PIXELS) config.height.roundToInt().toString()
                                else String.format("%.1f", config.height)
                            } else "",
                            onValueChange = {
                                val v = it.toFloatOrNull()
                                if (v != null && v > 0) onHeightChanged(v)
                            },
                            label = { Text("Height (${config.unit.shortSuffix})") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    // DPI selector if physical unit
                    if (config.unit == ResizeUnit.MILLIMETERS || config.unit == ResizeUnit.CENTIMETERS || config.unit == ResizeUnit.INCHES) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Print Resolution (DPI)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(72 to "72 (Web)", 150 to "150 (Draft)", 300 to "300 (Print)").forEach { (dpi, label) ->
                                FilterChip(
                                    selected = config.dpi == dpi,
                                    onClick = { onDpiChanged(dpi) },
                                    label = { Text(label, fontSize = 11.sp) }
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
private fun TargetFileSizeCard(
    currentTargetKb: Int?,
    onTargetKbChanged: (Int?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Target Max File Size (KB)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Strictly compresses under portal limit (essential for SSC, UPSC, NTA forms)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (currentTargetKb != null) {
                    IconButton(onClick = { onTargetKbChanged(null) }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick target KB chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    null to "None (Auto)",
                    20 to "20 KB",
                    50 to "50 KB",
                    100 to "100 KB",
                    200 to "200 KB",
                    500 to "500 KB"
                ).forEach { (kb, label) ->
                    FilterChip(
                        selected = currentTargetKb == kb,
                        onClick = { onTargetKbChanged(kb) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatAndQualityCard(
    format: OutputFormat,
    quality: Int,
    onFormatChanged: (OutputFormat) -> Unit,
    onQualityChanged: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Export Format & Quality",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Format chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutputFormat.values().forEach { fmt ->
                    FilterChip(
                        selected = format == fmt,
                        onClick = { onFormatChanged(fmt) },
                        label = { Text(fmt.label, fontWeight = FontWeight.SemiBold) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (format != OutputFormat.PNG) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Compression Quality", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text("$quality%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                Slider(
                    value = quality.toFloat(),
                    onValueChange = { onQualityChanged(it.roundToInt()) },
                    valueRange = 10f..100f,
                    steps = 17
                )
            }
        }
    }
}

private fun shareFile(context: Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Resized Image"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
