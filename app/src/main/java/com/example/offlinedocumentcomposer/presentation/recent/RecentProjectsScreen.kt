package com.example.offlinedocumentcomposer.presentation.recent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

data class ExportedItem(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isPdf: Boolean
) {
    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${String.format(Locale.US, "%.1f", sizeBytes / 1024.0)} KB"
            else -> "${String.format(Locale.US, "%.1f", sizeBytes / (1024.0 * 1024.0))} MB"
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return sdf.format(Date(lastModified))
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentProjectsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<ExportedItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "PDF", "Images"
    var fileToDelete by remember { mutableStateOf<ExportedItem?>(null) }

    val scope = rememberCoroutineScope()

    fun refreshFiles() {
        isLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val directories = listOfNotNull(
                    File(context.getExternalFilesDir(null), "ExportedDocuments"),
                    File(context.filesDir, "ExportedDocuments"),
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "DocComposer") },
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DocComposer"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DocComposer"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ResizedImages"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PassportPhotos")
                )

                val found = mutableListOf<ExportedItem>()
                val seenPaths = mutableSetOf<String>()

                for (dir in directories) {
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles() ?: continue
                        for (file in files) {
                            if (file.isFile && !seenPaths.contains(file.absolutePath)) {
                                val name = file.name
                                // Exclude hidden files, Android MediaStore trashed files, pending files, or empty files
                                if (file.isHidden ||
                                    name.startsWith(".") ||
                                    name.contains("trashed", ignoreCase = true) ||
                                    name.contains("pending", ignoreCase = true) ||
                                    file.length() <= 0L
                                ) {
                                    continue
                                }
                                val ext = file.extension.lowercase(Locale.ROOT)
                                if (ext in listOf("pdf", "png", "jpg", "jpeg", "webp")) {
                                    seenPaths.add(file.absolutePath)
                                    found.add(
                                        ExportedItem(
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
                found.sortedByDescending { it.lastModified }
            }
            items = list
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshFiles()
    }

    val filteredItems = remember(items, selectedFilter) {
        when (selectedFilter) {
            "PDF" -> items.filter { it.isPdf }
            "Images" -> items.filter { !it.isPdf }
            else -> items
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Recent Exports",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${items.size} exported ${if (items.size == 1) "file" else "files"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshFiles() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF8FAFC))
        ) {
            // Filter Chips
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == "All",
                        onClick = { selectedFilter = "All" },
                        label = { Text("All (${items.size})") }
                    )
                    FilterChip(
                        selected = selectedFilter == "PDF",
                        onClick = { selectedFilter = "PDF" },
                        label = { Text("PDFs (${items.count { it.isPdf }})") }
                    )
                    FilterChip(
                        selected = selectedFilter == "Images",
                        onClick = { selectedFilter = "Images" },
                        label = { Text("Images (${items.count { !it.isPdf }})") }
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Loading",
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scanning exported files...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = Color(0xFF64748B)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = if (items.isEmpty()) "No Exported Documents Yet" else "No matching files",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1E293B)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (items.isEmpty()) {
                                "Documents and ID cards you export to PDF or Image will automatically appear here."
                            } else {
                                "Try selecting a different filter above."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { navController.popBackStack() },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Document")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.file.absolutePath }) { item ->
                        ExportItemCard(
                            item = item,
                            onOpen = { openFile(context, item.file, item.isPdf) },
                            onShare = { shareFile(context, item.file, item.isPdf) },
                            onPrint = { printFile(context, item.file, item.isPdf) },
                            onDelete = { fileToDelete = item }
                        )
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (fileToDelete != null) {
        val target = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Document?") },
            text = { Text("Are you sure you want to delete '${target.name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                target.file.delete()
                                val fileName = target.file.name
                                listOfNotNull(
                                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ResizedImages/$fileName"),
                                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PassportPhotos/$fileName"),
                                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "DocComposer/$fileName"),
                                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DocComposer/$fileName")
                                ).forEach { pubFile ->
                                    if (pubFile.exists()) pubFile.delete()
                                }
                            }
                            refreshFiles()
                            fileToDelete = null
                            Toast.makeText(context, "Document deleted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FileThumbnailView(
    file: File,
    isPdf: Boolean,
    modifier: Modifier = Modifier
) {
    var thumbnail by remember(file.absolutePath, file.lastModified()) {
        mutableStateOf<Bitmap?>(ThumbnailLoader.getCached(file))
    }

    LaunchedEffect(file.absolutePath, file.lastModified()) {
        if (thumbnail == null) {
            val bmp = withContext(Dispatchers.IO) {
                ThumbnailLoader.getThumbnail(file, isPdf, targetWidth = 160, targetHeight = 160)
            }
            thumbnail = bmp
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPdf) Color(0xFFFEE2E2) else Color(0xFFE0F2FE))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumbnail
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Document Thumbnail",
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
            // Miniature format indicator badge on bottom-right corner
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (isPdf) Color(0xFFDC2626) else Color(0xFF0284C7)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        } else {
            // Elegant placeholder icon while generating/loading thumbnail
            Icon(
                imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image,
                contentDescription = null,
                tint = if (isPdf) Color(0xFFDC2626) else Color(0xFF0284C7),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ExportItemCard(
    item: ExportedItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Document Thumbnail Preview
                FileThumbnailView(
                    file = item.file,
                    isPdf = item.isPdf,
                    modifier = Modifier.size(54.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.formattedDate} • ${item.formattedSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B)
                    )
                }

                // Delete Button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = Color(0xFFF1F5F9))
            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Open Button
                FilledTonalButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFF1F5F9),
                        contentColor = Color(0xFF334155)
                    )
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Print Button
                    FilledTonalButton(
                        onClick = onPrint,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFEFF6FF),
                            contentColor = Color(0xFF2563EB)
                        )
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Print", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Share Button
                    FilledTonalButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFFECFDF5),
                            contentColor = Color(0xFF059669)
                        )
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun getFileUri(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun openFile(context: Context, file: File, isPdf: Boolean) {
    try {
        val uri = getFileUri(context, file)
        val mime = if (isPdf) "application/pdf" else "image/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: File, isPdf: Boolean) {
    try {
        val uri = getFileUri(context, file)
        val mime = if (isPdf) "application/pdf" else "image/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Document"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
    }
}

private fun printFile(context: Context, file: File, isPdf: Boolean) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = file.nameWithoutExtension
    if (isPdf) {
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(file.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()
                callback?.onLayoutFinished(info, newAttributes != oldAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                if (destination == null) {
                    callback?.onWriteFailed("Output destination is null")
                    return
                }
                try {
                    FileInputStream(file).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, adapter, attributes)
    } else {
        // Print image via native PrintDocumentAdapter and PdfDocument (A4)
        try {
            val imageAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder("$jobName.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1)
                        .build()
                    callback?.onLayoutFinished(info, newAttributes != oldAttributes)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    if (destination == null) {
                        callback?.onWriteFailed("Output destination is null")
                        return
                    }
                    try {
                        val pdfDoc = android.graphics.pdf.PdfDocument()
                        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                        val pdfPage = pdfDoc.startPage(pageInfo)
                        val canvas = pdfPage.canvas

                        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            val scaleX = 595f / bitmap.width
                            val scaleY = 842f / bitmap.height
                            val scale = kotlin.math.min(scaleX, scaleY)
                            val w = bitmap.width * scale
                            val h = bitmap.height * scale
                            val x = (595f - w) / 2f
                            val y = (842f - h) / 2f
                            val rect = android.graphics.RectF(x, y, x + w, y + h)
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                            canvas.drawBitmap(bitmap, null, rect, paint)
                            bitmap.recycle()
                        }
                        pdfDoc.finishPage(pdfPage)

                        java.io.FileOutputStream(destination.fileDescriptor).use { fos ->
                            pdfDoc.writeTo(fos)
                            fos.flush()
                        }
                        pdfDoc.close()
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }
            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            printManager.print(jobName, imageAdapter, attributes)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Printing not supported for this image: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
