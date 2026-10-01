package com.example.offlinedocumentcomposer.presentation.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    bitmap: Bitmap?,
    title: String = "Enhance & Adjust",
    onApply: (Bitmap) -> Unit,
    onBack: () -> Unit,
    viewModel: ImageEditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(bitmap) {
        bitmap?.let { viewModel.loadBitmap(it) }
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
                            text = "Clean up document, text contrast & colors",
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
                    IconButton(onClick = { viewModel.rotateLeft() }) {
                        Icon(Icons.Default.RotateLeft, contentDescription = "Rotate Left")
                    }
                    IconButton(onClick = { viewModel.rotateRight() }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate Right")
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.resetAdjustments() },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reset")
                    }

                    Button(
                        onClick = {
                            val processed = viewModel.getProcessedBitmap() ?: state.originalBitmap
                            if (processed != null) {
                                onApply(processed)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Confirm & Continue", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Live Transformed Image Preview Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFF1E293B))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                val preview = state.previewBitmap ?: state.originalBitmap
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Document Preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner(color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Presets row
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Document Filters",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = listOf(
                        "Document B&W" to "Sharp Text & Clean White",
                        "Magic Color" to "Vivid & Clean",
                        "Original" to "Default Colors",
                        "Grayscale" to "Neutral Grey"
                    )

                    presets.forEach { (name, desc) ->
                        val isSelected = state.selectedPreset == name
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.applyPreset(name) },
                            label = { Text(name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Fine tuning sliders
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "Manual Fine-Tuning",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Brightness Slider
                SliderRow(
                    label = "Brightness",
                    icon = Icons.Default.Brightness6,
                    value = state.brightness,
                    valueRange = -60f..60f,
                    onValueChange = { viewModel.setBrightness(it) },
                    displayValue = "${state.brightness.toInt()}"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Contrast Slider
                SliderRow(
                    label = "Contrast",
                    icon = Icons.Default.Contrast,
                    value = state.contrast,
                    valueRange = -50f..80f,
                    onValueChange = { viewModel.setContrast(it) },
                    displayValue = "${state.contrast.toInt()}"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Saturation Slider
                SliderRow(
                    label = "Saturation / Colors",
                    icon = Icons.Default.ColorLens,
                    value = state.saturation,
                    valueRange = 0f..2.5f,
                    onValueChange = { viewModel.setSaturation(it) },
                    displayValue = "${(state.saturation * 100).toInt()}%"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Sharpness Slider
                SliderRow(
                    label = "Sharpness & Text Clarity",
                    icon = Icons.Default.AutoFixHigh,
                    value = state.sharpness,
                    valueRange = 0f..100f,
                    onValueChange = { viewModel.setSharpness(it) },
                    displayValue = "${state.sharpness.toInt()}%"
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    displayValue: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = displayValue,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
