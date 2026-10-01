@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.tools

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.offlinedocumentcomposer.domain.pdf.*
import com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Domain Models for PDF Resizer
// ---------------------------------------------------------------------------

data class PdfMeta(
    val file: File,
    val fileName: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val firstPageWidth: Int,
    val firstPageHeight: Int
) {
    val formattedFileSize: String get() = formatBytes(fileSizeBytes)
}

enum class PdfCompressionMode(val label: String, val description: String) {
    TARGET_SIZE("Target File Size", "Compress to exact KB or MB limit for exams & job portals"),
    QUALITY("Quality / DPI", "Compress images with standard DPI presets without a hard byte ceiling")
}

data class PdfTargetPreset(
    val label: String,
    val sizeKb: Int,
    val title: String,
    val description: String
)

val DEFAULT_PDF_PRESETS = listOf(
    PdfTargetPreset("100 KB", 100, "UPSC & Govt Exams", "Strict 100 KB limit for exam portals"),
    PdfTargetPreset("200 KB", 200, "State PSC & PAN", "NSDL / UTI PAN & official application forms"),
    PdfTargetPreset("500 KB", 500, "CV & Job Portals", "Standard CV, transcripts & job portals"),
    PdfTargetPreset("1 MB", 1024, "Email & Web Upload", "Fast email sending & general web forms"),
    PdfTargetPreset("2 MB", 2048, "Legal Briefs", "Multi-page agreements, deeds and contracts")
)

data class PdfResizerUiState(
    val sourceFile: File? = null,
    val meta: PdfMeta? = null,
    val previewBitmap: Bitmap? = null,
    val currentPageIndex: Int = 0,
    val isRenderingPreview: Boolean = false,
    val mode: PdfCompressionMode = PdfCompressionMode.TARGET_SIZE,
    val targetSizeInput: String = "200",
    val isMegabytes: Boolean = false,
    val selectedQuality: PdfQuality = PdfQuality.BALANCED,
    val selectedPreset: PdfTargetPreset? = DEFAULT_PDF_PRESETS[1], // 200 KB default
    val isBusy: Boolean = false,
    val progressMessage: String = "",
    val errorMessage: String? = null,
    val result: CompressionResult? = null
) {
    val targetBytes: Long?
        get() {
            if (mode != PdfCompressionMode.TARGET_SIZE) return null
            val value = targetSizeInput.toDoubleOrNull() ?: return null
            if (value <= 0) return null
            val multiplier = if (isMegabytes) 1024L * 1024L else 1024L
            return (value * multiplier).toLong()
        }

    val estimatedOutputBytes: Long
        get() {
            val original = meta?.fileSizeBytes ?: return 0L
            if (mode == PdfCompressionMode.TARGET_SIZE) {
                val target = targetBytes
                return if (target != null) minOf(original, target) else original
            } else {
                val factor = when (selectedQuality) {
                    PdfQuality.LIGHT -> 0.70
                    PdfQuality.BALANCED -> 0.45
                    PdfQuality.STRONG -> 0.28
                }
                return (original * factor).toLong().coerceAtLeast(1024L)
            }
        }

    val estimatedReductionPercent: Double
        get() {
            val original = meta?.fileSizeBytes ?: return 0.0
            val est = estimatedOutputBytes
            if (original <= 0 || est >= original) return 0.0
            return ((original - est).toDouble() / original.toDouble()) * 100.0
        }
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class PdfResizerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PdfResizerUiState())
    val state = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var previewJob: Job? = null

    fun selectUri(uri: Uri) {
        if (_uiState.value.isBusy) return
        activeJob?.cancel()

        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBusy = true,
                progressMessage = "Reading PDF Document...",
                errorMessage = null,
                result = null
            )

            var tempInputFile: File? = null
            try {
                val context = getApplication<Application>()
                val resolvedName = queryFileName(context, uri) ?: "Document.pdf"

                // 1. Copy to cache file
                tempInputFile = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("pdf_resizer_in_", ".pdf", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Could not open PDF file")
                    file
                }

                // 2. Read PDF metadata
                val meta = readPdfMeta(tempInputFile, resolvedName)
                    ?: error("Could not parse PDF. Ensure it is a valid, unencrypted PDF.")

                // 3. Render initial page 0 preview with RGB_565 (50% RAM savings)
                val initialBitmap = renderPdfPage(tempInputFile, 0, maxDim = 1080)

                // Delete old source file if existed
                _uiState.value.sourceFile?.let { old ->
                    if (old != tempInputFile) old.delete()
                }

                _uiState.value = _uiState.value.copy(
                    sourceFile = tempInputFile,
                    meta = meta,
                    previewBitmap = initialBitmap,
                    currentPageIndex = 0,
                    isBusy = false,
                    progressMessage = ""
                )
            } catch (e: CancellationException) {
                tempInputFile?.delete()
                throw e
            } catch (e: Exception) {
                tempInputFile?.delete()
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    progressMessage = "",
                    errorMessage = e.message ?: "Failed to read PDF document"
                )
            }
        }
    }

    fun changePage(pageIndex: Int) {
        val meta = _uiState.value.meta ?: return
        val file = _uiState.value.sourceFile ?: return
        if (pageIndex < 0 || pageIndex >= meta.pageCount || pageIndex == _uiState.value.currentPageIndex) return

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRenderingPreview = true)
            val bmp = renderPdfPage(file, pageIndex, maxDim = 1080)
            if (bmp != null) {
                _uiState.value = _uiState.value.copy(
                    previewBitmap = bmp,
                    currentPageIndex = pageIndex,
                    isRenderingPreview = false
                )
            } else {
                _uiState.value = _uiState.value.copy(isRenderingPreview = false)
            }
        }
    }

    fun nextPage() {
        val current = _uiState.value.currentPageIndex
        val max = (_uiState.value.meta?.pageCount ?: 1) - 1
        if (current < max) changePage(current + 1)
    }

    fun prevPage() {
        val current = _uiState.value.currentPageIndex
        if (current > 0) changePage(current - 1)
    }

    fun setMode(mode: PdfCompressionMode) {
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun setTargetSizeInput(text: String) {
        _uiState.value = _uiState.value.copy(
            targetSizeInput = text,
            selectedPreset = null
        )
    }

    fun setMegabytes(isMb: Boolean) {
        _uiState.value = _uiState.value.copy(
            isMegabytes = isMb,
            selectedPreset = null
        )
    }

    fun applyPreset(preset: PdfTargetPreset) {
        if (preset.sizeKb >= 1024) {
            val mb = preset.sizeKb / 1024.0
            val formatted = if (mb == mb.toLong().toDouble()) mb.toLong().toString() else "%.1f".format(mb)
            _uiState.value = _uiState.value.copy(
                mode = PdfCompressionMode.TARGET_SIZE,
                targetSizeInput = formatted,
                isMegabytes = true,
                selectedPreset = preset
            )
        } else {
            _uiState.value = _uiState.value.copy(
                mode = PdfCompressionMode.TARGET_SIZE,
                targetSizeInput = preset.sizeKb.toString(),
                isMegabytes = false,
                selectedPreset = preset
            )
        }
    }

    fun setQuality(quality: PdfQuality) {
        _uiState.value = _uiState.value.copy(selectedQuality = quality)
    }

    fun compress() {
        val input = _uiState.value.sourceFile ?: return
        if (_uiState.value.isBusy) return

        val targetBytes = _uiState.value.targetBytes
        val quality = _uiState.value.selectedQuality

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBusy = true,
                progressMessage = "Preparing compression...",
                errorMessage = null,
                result = null
            )

            try {
                val context = getApplication<Application>()
                val outputFile = DocumentFiles.output(context, "Compressed")
                val service = PdfCompressionService(context)

                val result = service.compress(
                    input = input,
                    output = outputFile,
                    quality = quality,
                    targetBytes = targetBytes
                ) { progress ->
                    _uiState.value = _uiState.value.copy(progressMessage = progress)
                }

                // Render page 0 of compressed result for immediate preview
                val resultBmp = renderPdfPage(result.file, 0, maxDim = 1080)

                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    progressMessage = "",
                    result = result,
                    previewBitmap = resultBmp ?: _uiState.value.previewBitmap
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    progressMessage = "",
                    errorMessage = e.message ?: "Compression failed"
                )
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(isBusy = false, progressMessage = "")
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun reset() {
        activeJob?.cancel()
        previewJob?.cancel()
        _uiState.value.sourceFile?.delete()
        _uiState.value = PdfResizerUiState()
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
        previewJob?.cancel()
        _uiState.value.sourceFile?.delete()
    }

    // Helper functions for PDF rendering and metadata
    private suspend fun readPdfMeta(file: File, originalName: String): PdfMeta? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            val (w, h) = if (count > 0) {
                page = renderer.openPage(0)
                page.width to page.height
            } else {
                0 to 0
            }
            PdfMeta(
                file = file,
                fileName = originalName,
                fileSizeBytes = file.length(),
                pageCount = count,
                firstPageWidth = w,
                firstPageHeight = h
            )
        } catch (e: SecurityException) {
            throw IllegalArgumentException("This PDF is password-protected. Please select an unencrypted copy.")
        } catch (e: Exception) {
            null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun renderPdfPage(file: File, pageIndex: Int, maxDim: Int = 1080): Bitmap? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null
            page = renderer.openPage(pageIndex)
            val w = page.width
            val h = page.height
            val scale = if (w > h) {
                if (w > maxDim) maxDim.toFloat() / w else 1f
            } else {
                if (h > maxDim) maxDim.toFloat() / h else 1f
            }
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)

            // Optimized memory allocation with RGB_565 (50% RAM savings)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            canvas.drawColor(AndroidColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (e: Exception) {
            null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.path?.let { File(it).name }
    }
}

// ---------------------------------------------------------------------------
// Composable Screen
// ---------------------------------------------------------------------------

@Composable
fun PdfResizerScreen(
    onBack: () -> Unit,
    vm: PdfResizerViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // File Pickers
    val openDocPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::selectUri)
    }
    val getContentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::selectUri)
    }

    fun launchPdfPicker() {
        try {
            openDocPicker.launch(arrayOf("application/pdf"))
        } catch (e: Exception) {
            try {
                getContentPicker.launch("application/pdf")
            } catch (e2: Exception) {
                Toast.makeText(context, "Could not open file picker", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Save As Picker
    var fileToSaveAs by remember { mutableStateOf<File?>(null) }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destUri ->
        val file = fileToSaveAs
        if (destUri != null && file != null) {
            scope.launch {
                try {
                    DocumentFiles.saveAs(context, file, destUri)
                    Toast.makeText(context, "Saved successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    fileToSaveAs = null
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PDF Resizer & Compressor",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        )
                        Text(
                            text = if (state.meta != null) {
                                "${state.meta?.fileName} • ${state.meta?.pageCount} Pages • ${formatBytes(state.meta?.fileSizeBytes ?: 0)}"
                            } else {
                                "Compress to exact KB or standard DPI"
                            },
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
                    if (state.meta != null) {
                        IconButton(
                            onClick = { vm.reset() },
                            enabled = !state.isBusy
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.meta != null) {
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
                                    text = state.progressMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.cancel() }) {
                                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Primary Compress Button
                            Button(
                                onClick = { vm.compress() },
                                enabled = !state.isBusy,
                                modifier = Modifier.weight(1.8f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text("Compress PDF", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    val est = formatBytes(state.estimatedOutputBytes)
                                    val modeLabel = if (state.mode == PdfCompressionMode.TARGET_SIZE) "Target" else state.selectedQuality.name
                                    Text(
                                        "$modeLabel • ~ $est",
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            // Share Button
                            val activeFile = state.result?.file ?: state.sourceFile
                            OutlinedButton(
                                onClick = {
                                    activeFile?.let { file ->
                                        try {
                                            DocumentFiles.share(context, file)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No sharing app available", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !state.isBusy && activeFile != null,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            // Print Button
                            OutlinedButton(
                                onClick = {
                                    activeFile?.let { file ->
                                        try {
                                            DocumentFiles.print(context, file)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Printing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !state.isBusy && activeFile != null,
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
            val meta = state.meta
            val preview = state.previewBitmap

            if (meta == null) {
                // Empty State Dropzone
                EmptyPdfResizerState(onSelectPdf = { launchPdfPicker() })
            } else {
                // Active State Controls
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Live Visual Preview Card with Multi-Page Navigation
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0F172A)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (preview != null) {
                                    Image(
                                        bitmap = preview.asImageBitmap(),
                                        contentDescription = "PDF Preview Page",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    SafeLoadingSpinner(size = 32.dp)
                                }

                                if (state.isRenderingPreview) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SafeLoadingSpinner(size = 24.dp)
                                    }
                                }

                                // Quick Change Button Overlay
                                FilledTonalButton(
                                    onClick = { launchPdfPicker() },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .height(30.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Change PDF", fontSize = 11.sp)
                                }

                                // Multi-Page Navigation Pill Overlay
                                if (meta.pageCount > 1) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 8.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.Black.copy(alpha = 0.75f)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            IconButton(
                                                onClick = { vm.prevPage() },
                                                enabled = state.currentPageIndex > 0,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ChevronLeft,
                                                    contentDescription = "Previous Page",
                                                    tint = if (state.currentPageIndex > 0) Color.White else Color.Gray,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            Text(
                                                text = "Page ${state.currentPageIndex + 1} of ${meta.pageCount}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )

                                            IconButton(
                                                onClick = { vm.nextPage() },
                                                enabled = state.currentPageIndex < meta.pageCount - 1,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = "Next Page",
                                                    tint = if (state.currentPageIndex < meta.pageCount - 1) Color.White else Color.Gray,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Comparison Stats Row (Live Real-Time)
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
                                    Text("Original PDF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(meta.formattedFileSize, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("${meta.pageCount} ${if (meta.pageCount == 1) "Page" else "Pages"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }

                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Target / Estimated", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    val est = formatBytes(state.estimatedOutputBytes)
                                    val pct = "%.1f".format(state.estimatedReductionPercent)
                                    Text("~ $est", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F9D58))
                                    Text(
                                        if (state.estimatedReductionPercent > 0) "-$pct% Smaller" else "Preserve Text",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (state.estimatedReductionPercent > 0) Color(0xFF0F9D58) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 2. Result Banner (Shown after compression finishes)
                    state.result?.let { res ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81C784))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Compression Completed!", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1B5E20))
                                }

                                val reduction = if (res.originalBytes > 0) 100.0 * (res.originalBytes - res.outputBytes) / res.originalBytes else 0.0
                                Text(
                                    "${formatBytes(res.originalBytes)} → ${formatBytes(res.outputBytes)} (${"%.1f".format(reduction)}% smaller)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF2E7D32)
                                )

                                if (res.targetReached == true) {
                                    Text("✓ Target size requirement successfully achieved!", fontSize = 12.sp, color = Color(0xFF2E7D32))
                                } else if (res.targetReached == false) {
                                    Text("Maximum visual compression applied. Text, links, and forms were safely preserved.", fontSize = 12.sp, color = Color(0xFF558B2F))
                                }

                                // Direct Action Buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            try {
                                                DocumentFiles.open(context, res.file)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "No PDF viewer available", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Open PDF", fontSize = 12.sp)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            fileToSaveAs = res.file
                                            saveAsLauncher.launch(res.file.name)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Save As", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                DocumentFiles.share(context, res.file)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "No sharing app available", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Share", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                DocumentFiles.print(context, res.file)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Printing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Print", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 3. Quick Target Presets Card (Government Exam & Portals)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Popular Target Presets", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(
                                "Quick presets tailored for government exams, NSDL forms, and job portals.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DEFAULT_PDF_PRESETS.forEach { preset ->
                                    val isSelected = state.selectedPreset == preset && state.mode == PdfCompressionMode.TARGET_SIZE
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { vm.applyPreset(preset) },
                                        label = {
                                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                Text(preset.label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Text(preset.title, fontSize = 9.sp, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        } else null,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Compression Mode & Fine-Tuning Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Compression Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = state.mode == PdfCompressionMode.TARGET_SIZE,
                                    onClick = { vm.setMode(PdfCompressionMode.TARGET_SIZE) },
                                    label = { Text("Exact Target Size") },
                                    leadingIcon = { Icon(Icons.Default.DataUsage, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = state.mode == PdfCompressionMode.QUALITY,
                                    onClick = { vm.setMode(PdfCompressionMode.QUALITY) },
                                    label = { Text("Quality / DPI") },
                                    leadingIcon = { Icon(Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (state.mode == PdfCompressionMode.TARGET_SIZE) {
                                // Exact Target KB/MB Input
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = state.targetSizeInput,
                                        onValueChange = { vm.setTargetSizeInput(it) },
                                        label = { Text("Max Target File Size") },
                                        placeholder = { Text("e.g. 200") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    )

                                    // KB / MB Toggle Chips
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilterChip(
                                            selected = !state.isMegabytes,
                                            onClick = { vm.setMegabytes(false) },
                                            label = { Text("KB", fontWeight = FontWeight.Bold) },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        FilterChip(
                                            selected = state.isMegabytes,
                                            onClick = { vm.setMegabytes(true) },
                                            label = { Text("MB", fontWeight = FontWeight.Bold) },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }

                                Text(
                                    "Algorithm iteratively optimizes embedded images to stay strictly under the chosen limit while keeping text perfectly sharp.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                // Quality & DPI Selection
                                Text("Select Target Resolution & Quality Preset:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PdfQuality.values().forEach { q ->
                                        val isSel = state.selectedQuality == q
                                        FilterChip(
                                            selected = isSel,
                                            onClick = { vm.setQuality(q) },
                                            label = {
                                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                    Text(
                                                        q.name.lowercase().replaceFirstChar { it.uppercase() },
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp
                                                    )
                                                    Text("${q.dpi} DPI", fontSize = 10.sp)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }
                                }

                                when (state.selectedQuality) {
                                    PdfQuality.LIGHT -> Text("Light (200 DPI): Retains high-fidelity imagery. Recommended for certificates and legal documents.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PdfQuality.BALANCED -> Text("Balanced (150 DPI): The optimal standard. Crisp on phone/tablet displays while cutting file size by ~50%.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PdfQuality.STRONG -> Text("Strong (100 DPI): Aggressive compression. Excellent for uploading massive multi-page documents over slow connections.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }

    // Error Alert
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            icon = { Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("PDF Resizer Notice") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { vm.clearError() }) {
                    Text("OK")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Empty State View
// ---------------------------------------------------------------------------

@Composable
private fun EmptyPdfResizerState(
    onSelectPdf: () -> Unit
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
                Icons.Default.PictureAsPdf,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Choose a PDF to Resize & Compress",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Reduce PDF file size for government exams, online forms, emails, and job applications while preserving vector text and formatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onSelectPdf,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select PDF Document", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Feature Highlights Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            HighlightPill(icon = Icons.Default.Lock, text = "100% Offline")
            HighlightPill(icon = Icons.Default.DataUsage, text = "Exact KB Target")
            HighlightPill(icon = Icons.Default.TextFields, text = "Crisp Text")
        }
    }
}

@Composable
private fun HighlightPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------------------
// Common PDF Output Actions (used by ScanScreen and others)
// ---------------------------------------------------------------------------

@Composable
fun PdfOutputActions(file: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember(file) { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            try {
                DocumentFiles.saveAs(context, file, uri)
                message = "Saved"
            } catch (e: Exception) {
                message = e.message ?: "Save failed"
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = {
            try { DocumentFiles.open(context, file) } catch (e: Exception) { message = "No PDF viewer available" }
        }) {
            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Preview")
        }
        Button(onClick = { save.launch(file.name) }) {
            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Save As")
        }
        OutlinedButton(onClick = {
            try { DocumentFiles.share(context, file) } catch (e: Exception) { message = "No sharing app available" }
        }) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Share")
        }
        OutlinedButton(onClick = {
            try {
                DocumentFiles.print(context, file)
            } catch (e: Exception) {
                message = "Printing failed: ${e.message}"
            }
        }) {
            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Print")
        }
    }
    message?.let { Text(it) }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
