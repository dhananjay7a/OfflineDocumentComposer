@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinedocumentcomposer.domain.pdf.PdfEditorService
import com.example.offlinedocumentcomposer.domain.pdf.PdfThumbnailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfSplitScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editorService = remember { PdfEditorService(context) }

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    val thumbnails = remember { mutableStateMapOf<Int, Bitmap>() }

    var splitMode by remember { mutableIntStateOf(0) } // 0: Visual, 1: Range, 2: Extract All
    val selectedPages = remember { mutableStateListOf<Int>() }
    var rangeInput by remember { mutableStateOf("") }

    var isProcessing by remember { mutableStateOf(false) }
    var singleResult by remember { mutableStateOf<File?>(null) }
    var multiResults by remember { mutableStateOf<List<File>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isProcessing = true
                errorMessage = null
                singleResult = null
                multiResults = null
                thumbnails.clear()
                selectedPages.clear()

                val imported = copyUriToCache(context, uri)
                if (imported != null) {
                    sourceFile = imported
                    sourceName = queryFileName(context, uri) ?: imported.name
                    val count = PdfThumbnailHelper.getPageCount(imported)
                    pageCount = count

                    // Render thumbnails asynchronously in batches
                    launch(Dispatchers.IO) {
                        for (i in 0 until count) {
                            val bmp = PdfThumbnailHelper.renderPage(imported, i, maxDim = 320)
                            if (bmp != null) {
                                thumbnails[i] = bmp
                            }
                        }
                    }
                } else {
                    errorMessage = "Failed to load selected PDF document"
                }
                isProcessing = false
            }
        }
    }

    // Parse range input into 0-indexed list of page numbers
    val parsedRangePages = remember(rangeInput, pageCount) {
        parsePageRanges(rangeInput, pageCount)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Split PDF", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (sourceFile == null) "Extract pages or split by range"
                            else "$sourceName • $pageCount pages",
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
                        TextButton(onClick = { filePicker.launch(arrayOf("application/pdf")) }) {
                            Text("Change")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (sourceFile != null && singleResult == null && multiResults == null) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        val canProceed = when (splitMode) {
                            0 -> selectedPages.isNotEmpty()
                            1 -> parsedRangePages.isNotEmpty()
                            2 -> pageCount > 0
                            else -> false
                        }

                        Button(
                            onClick = {
                                val src = sourceFile ?: return@Button
                                scope.launch {
                                    isProcessing = true
                                    errorMessage = null
                                    try {
                                        if (splitMode == 2) {
                                            // Extract All
                                            val dir = File(DocumentFiles.exports(context), "Split_${System.currentTimeMillis()}")
                                            val results = editorService.splitPdfAllPages(
                                                sourceFile = src,
                                                outputDir = dir,
                                                baseName = src.nameWithoutExtension.take(40)
                                            )
                                            multiResults = results
                                        } else {
                                            // Extract Pages (Visual or Range)
                                            val pagesToExtract = if (splitMode == 0) selectedPages.sorted() else parsedRangePages
                                            val out = DocumentFiles.output(context, "Extracted_${pagesToExtract.size}pages")
                                            val result = editorService.splitPdfByPages(src, pagesToExtract, out)
                                            singleResult = result
                                        }
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to split PDF"
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            enabled = canProceed && !isProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text("Processing...")
                            } else {
                                Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                val buttonText = when (splitMode) {
                                    0 -> "Extract ${selectedPages.size} Selected ${if (selectedPages.size == 1) "Page" else "Pages"}"
                                    1 -> "Extract ${parsedRangePages.size} Range ${if (parsedRangePages.size == 1) "Page" else "Pages"}"
                                    2 -> "Split All $pageCount Pages"
                                    else -> "Extract Pages"
                                }
                                Text(buttonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            singleResult?.let { result ->
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
                            Text("Pages Extracted Successfully!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        PdfOutputActions(result)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { singleResult = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Extract More Pages")
                        }
                    }
                }
                return@Scaffold
            }

            multiResults?.let { results ->
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
                            Text("All ${results.size} Pages Extracted!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Saved to ExportedDocuments directory.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        results.firstOrNull()?.let { firstFile ->
                            PdfOutputActions(firstFile)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { multiResults = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("Back to Document")
                        }
                    }
                }
                return@Scaffold
            }

            if (sourceFile == null) {
                SplitEmptyState(onPickClick = { filePicker.launch(arrayOf("application/pdf")) })
            } else {
                // Split Modes Selector
                PrimaryTabRow(
                    selectedTabIndex = splitMode,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = splitMode == 0,
                        onClick = { splitMode = 0 },
                        text = { Text("Select Pages") }
                    )
                    Tab(
                        selected = splitMode == 1,
                        onClick = { splitMode = 1 },
                        text = { Text("Page Range") }
                    )
                    Tab(
                        selected = splitMode == 2,
                        onClick = { splitMode = 2 },
                        text = { Text("Extract All") }
                    )
                }

                when (splitMode) {
                    0 -> {
                        // Visual Selection Mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${selectedPages.size} of $pageCount selected",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    selectedPages.clear()
                                    selectedPages.addAll(0 until pageCount)
                                }) {
                                    Text("Select All")
                                }
                                TextButton(onClick = { selectedPages.clear() }) {
                                    Text("Clear")
                                }
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items((0 until pageCount).toList()) { pageIdx ->
                                val isSelected = selectedPages.contains(pageIdx)
                                val thumb = thumbnails[pageIdx]

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.75f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isSelected) selectedPages.remove(pageIdx)
                                            else selectedPages.add(pageIdx)
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Box(Modifier.fillMaxSize()) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb.asImageBitmap(),
                                                contentDescription = "Page ${pageIdx + 1}",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize().padding(6.dp)
                                            )
                                        } else {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            }
                                        }

                                        // Page number pill
                                        Surface(
                                            shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                            modifier = Modifier.align(Alignment.TopStart)
                                        ) {
                                            Text(
                                                "${pageIdx + 1}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        // Selection checkmark
                                        if (isSelected) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .size(20.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.padding(3.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Page Range Mode
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "Enter Page Range",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Specify pages to extract separated by commas or hyphens.\nExample: 1-3, 5, 8-10 (Total pages: $pageCount)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = rangeInput,
                                onValueChange = { rangeInput = it },
                                label = { Text("Page Ranges (e.g. 1-3, 5)") },
                                placeholder = { Text("1-$pageCount") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            if (parsedRangePages.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            "Will extract ${parsedRangePages.size} pages:",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            parsedRangePages.map { it + 1 }.joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // Extract All Pages Mode
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Text(
                                "Extract Every Page to Separate PDFs",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "This will split all $pageCount pages into individual, 1-page PDF documents stored in your device's document exports folder.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitEmptyState(onPickClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(vertical = 16.dp),
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
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Extract Pages from PDF",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Pick a multi-page PDF document to extract specific pages by tapping thumbnails, entering page ranges, or splitting into separate files.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onPickClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select PDF File", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Parses page range string (e.g. "1-3, 5, 8-10") into a sorted, deduplicated 0-indexed page list.
 */
internal fun parsePageRanges(input: String, maxPages: Int): List<Int> {
    if (input.isBlank() || maxPages <= 0) return emptyList()
    val result = mutableSetOf<Int>()
    val tokens = input.split(",", ";", " ")

    for (token in tokens) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) continue

        if (trimmed.contains("-")) {
            val parts = trimmed.split("-")
            if (parts.size == 2) {
                val start = parts[0].trim().toIntOrNull()
                val end = parts[1].trim().toIntOrNull()
                if (start != null && end != null) {
                    val low = minOf(start, end).coerceIn(1, maxPages)
                    val high = maxOf(start, end).coerceIn(1, maxPages)
                    for (p in low..high) {
                        result.add(p - 1)
                    }
                }
            }
        } else {
            val pageNum = trimmed.toIntOrNull()
            if (pageNum != null && pageNum in 1..maxPages) {
                result.add(pageNum - 1)
            }
        }
    }
    return result.sorted()
}
