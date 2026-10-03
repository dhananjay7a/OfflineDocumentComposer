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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinedocumentcomposer.domain.pdf.PageEditItem
import com.example.offlinedocumentcomposer.domain.pdf.PdfEditorService
import com.example.offlinedocumentcomposer.domain.pdf.PdfThumbnailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Composable
fun PdfOrganizeScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editorService = remember { PdfEditorService(context) }

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var pageItems by remember { mutableStateOf<List<PageEditItem>>(emptyList()) }
    val thumbnailCache = remember { mutableStateMapOf<String, Bitmap>() }

    var isSaving by remember { mutableStateOf(false) }
    var saveProgress by remember { mutableFloatStateOf(0f) }
    var outputResult by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pick main initial PDF
    val mainPdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                errorMessage = null
                outputResult = null
                thumbnailCache.clear()

                val imported = copyUriToCache(context, uri)
                if (imported != null) {
                    sourceFile = imported
                    sourceName = queryFileName(context, uri) ?: imported.name
                    val count = PdfThumbnailHelper.getPageCount(imported)
                    val items = (0 until count).map { idx ->
                        PageEditItem(sourceFile = imported, sourcePageIndex = idx)
                    }
                    pageItems = items

                    // Load thumbnails in background
                    launch(Dispatchers.IO) {
                        for (item in items) {
                            val bmp = PdfThumbnailHelper.renderPage(item.sourceFile, item.sourcePageIndex, maxDim = 320)
                            if (bmp != null) {
                                thumbnailCache[item.id] = bmp
                            }
                        }
                    }
                } else {
                    errorMessage = "Failed to load selected PDF document"
                }
            }
        }
    }

    // Append extra PDF pages
    val addPdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                for (uri in uris) {
                    val imported = copyUriToCache(context, uri)
                    if (imported != null) {
                        val count = PdfThumbnailHelper.getPageCount(imported)
                        val newItems = (0 until count).map { idx ->
                            PageEditItem(sourceFile = imported, sourcePageIndex = idx)
                        }
                        pageItems = pageItems + newItems

                        launch(Dispatchers.IO) {
                            for (item in newItems) {
                                val bmp = PdfThumbnailHelper.renderPage(item.sourceFile, item.sourcePageIndex, maxDim = 320)
                                if (bmp != null) {
                                    thumbnailCache[item.id] = bmp
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Append images as pages
    val addImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                for (uri in uris) {
                    val imgFile = copyImageUriToCache(context, uri)
                    if (imgFile != null) {
                        val item = PageEditItem(sourceFile = imgFile, sourcePageIndex = 0, isImage = true)
                        pageItems = pageItems + item

                        launch(Dispatchers.IO) {
                            val bmp = android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath)
                            if (bmp != null) {
                                thumbnailCache[item.id] = bmp
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Organize & Rotate Pages", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (sourceFile == null) "Reorder, rotate, add or delete PDF pages"
                            else "$sourceName • ${pageItems.size} pages",
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
                        TextButton(onClick = { mainPdfPicker.launch(arrayOf("application/pdf")) }) {
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
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (isSaving) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text("Saving reorganized PDF... (${(saveProgress * 100).toInt()}%)", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Button(
                            onClick = {
                                if (pageItems.isEmpty()) {
                                    errorMessage = "Add at least one page to save."
                                    return@Button
                                }
                                scope.launch {
                                    isSaving = true
                                    errorMessage = null
                                    try {
                                        val out = DocumentFiles.output(context, "Organized_${pageItems.size}pages")
                                        val result = editorService.organizePdf(
                                            pageItems = pageItems,
                                            outputFile = out,
                                            onProgress = { saveProgress = it }
                                        )
                                        outputResult = result
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Failed to save PDF"
                                    } finally {
                                        isSaving = false
                                    }
                                }
                            },
                            enabled = pageItems.isNotEmpty() && !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Save Reorganized PDF (${pageItems.size} ${if (pageItems.size == 1) "Page" else "Pages"})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
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
                            Text("PDF Saved Successfully!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
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
                OrganizeEmptyState(onPickClick = { mainPdfPicker.launch(arrayOf("application/pdf")) })
            } else {
                // Toolbar for global operations: Add pages, Rotate all
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { addPdfPicker.launch(arrayOf("application/pdf")) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Add PDF Pages", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = { addImagePicker.launch("image/*") },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Add Photos", style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            pageItems = pageItems.map { it.copy(rotation = (it.rotation + 90) % 360) }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Rotate All 90°", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Grid of page cards
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(pageItems, key = { _, item -> item.id }) { index, item ->
                        val thumb = thumbnailCache[item.id]

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                // Card Top: Page badge & Rotation Pill
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            "Page ${index + 1}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    if (item.rotation != 0) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                "${item.rotation}°",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Thumbnail with live rotation
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(4.dp)
                                                .graphicsLayer {
                                                    rotationZ = item.rotation.toFloat()
                                                }
                                        )
                                    } else {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // Controls: Move Left, Rotate, Move Right, Delete
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Move Left/Up
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val list = pageItems.toMutableList()
                                                val temp = list[index]
                                                list[index] = list[index - 1]
                                                list[index - 1] = temp
                                                pageItems = list
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Move Left",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    }

                                    // Rotate +90°
                                    IconButton(
                                        onClick = {
                                            val newRotation = (item.rotation + 90) % 360
                                            pageItems = pageItems.toMutableList().also {
                                                it[index] = item.copy(rotation = newRotation)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.RotateRight,
                                            contentDescription = "Rotate 90°",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    // Move Right/Down
                                    IconButton(
                                        onClick = {
                                            if (index < pageItems.size - 1) {
                                                val list = pageItems.toMutableList()
                                                val temp = list[index]
                                                list[index] = list[index + 1]
                                                list[index + 1] = temp
                                                pageItems = list
                                            }
                                        },
                                        enabled = index < pageItems.size - 1,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Move Right",
                                            modifier = Modifier.size(18.dp),
                                            tint = if (index < pageItems.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        )
                                    }

                                    // Delete page
                                    IconButton(
                                        onClick = {
                                            pageItems = pageItems.filterIndexed { i, _ -> i != index }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete Page",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                        )
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

@Composable
private fun OrganizeEmptyState(onPickClick: () -> Unit) {
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
                        Icons.Default.ViewAgenda,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Organize & Rotate PDF",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Rearrange pages with visual shift buttons, rotate pages 90° clockwise, delete unwanted pages, or insert new pages from other PDFs and photos.",
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
                Text("Select PDF to Organize", fontWeight = FontWeight.Bold)
            }
        }
    }
}

suspend fun copyImageUriToCache(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
    try {
        val cacheFile = File(context.cacheDir, "img_page_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        cacheFile
    } catch (_: Exception) {
        null
    }
}
