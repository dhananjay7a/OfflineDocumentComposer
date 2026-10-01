@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.offlinedocumentcomposer.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
    val fallbackGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) vm.add(it) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.add(it) }
    BackHandler(camera || cropId != null) { camera = false; cropId = null; retakeId = null }
    if (camera) {
        val lastThumbPath = state.session.pages.lastOrNull()?.let { vm.repository.thumbnail(it).absolutePath }
        LiveScannerScreen(
            onCaptured = { uri ->
                vm.add(listOf(uri),retakeId)
                if (retakeId != null) { camera = false; retakeId = null }
            },
            onBack = { camera = false; retakeId = null },
            importing = state.busy,
            pageCount = state.session.pages.size,
            processingError = state.error,
            initialThumbnailPath = lastThumbPath
        )
        return
    }
    val cropping = state.session.pages.firstOrNull { it.id == cropId }
    if (cropping != null) {
        CropScanPage(cropping,vm,onBack = { cropId = null }) { points ->
            vm.update(cropping.copy(corners = points,needsReview = false)); cropId = null
        }
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Image to PDF") },navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },actions = {
        TextButton(onClick = { confirmClear = true },enabled = state.session.pages.isNotEmpty() && !state.busy) { Text("New") }
    }) },bottomBar = {
        Column(Modifier.padding(12.dp)) {
            if (state.busy) { com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner(size = 24.dp); Text(state.progress); TextButton(onClick = vm::cancel) { Text("Cancel") } }
            state.error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = { camera = true },enabled = !state.busy) { Text("Scan") }
                OutlinedButton(onClick = {
                    try { gallery.launch(arrayOf("image/*")) }
                    catch (e: android.content.ActivityNotFoundException) {
                        try { fallbackGallery.launch("image/*") } catch (e: Exception) { vm.reportError("No image picker available. Enable the Files app and try again.") }
                    } catch (e: Exception) { vm.reportError("Could not open images: ${e.message}") }
                },enabled = !state.busy) { Text("Gallery") }
                Button(onClick = {
                    val pending = state.session.pages.firstOrNull { it.needsReview }
                    if (pending != null) cropId = pending.id else exporting = true
                },enabled = state.session.pages.isNotEmpty() && !state.busy) { Text(if (state.session.pages.any { it.needsReview }) "Review crops" else "Export PDF") }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(if (state.session.pages.isEmpty()) "Scan documents or select images. Each image becomes one PDF page. Your unfinished session is saved automatically." else "${state.session.pages.size} pages · Changes are saved automatically",Modifier.padding(vertical = 12.dp))
            }
            itemsIndexed(state.session.pages,key = { _,page -> page.id }) { index,page ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            PageThumbnail(vm.repository.thumbnail(page).absolutePath,Modifier.size(88.dp,112.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Page ${index+1}",style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    IconButton(onClick = { vm.delete(page.id) }, enabled = !state.busy) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                }
                                val filterName = when (page.filter) {
                                    ScanFilter.BLACK_WHITE -> "Document B&W"
                                    ScanFilter.COLOR -> "Magic Color"
                                    ScanFilter.GRAYSCALE -> "Grayscale"
                                    ScanFilter.ORIGINAL -> "Original"
                                }
                                Text(filterName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (page.needsReview) Text("Check crop corners",color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { cropId = page.id },
                                        enabled = !state.busy,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Crop", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = { editId = page.id },
                                        enabled = !state.busy,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Filter", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Move Up Symbol Icon
                            IconButton(
                                onClick = { vm.move(page.id, -1) },
                                enabled = !state.busy && index > 0,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(22.dp))
                            }
                            // Move Down Symbol Icon
                            IconButton(
                                onClick = { vm.move(page.id, 1) },
                                enabled = !state.busy && index < state.session.pages.lastIndex,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(22.dp))
                            }
                            // Rotate Symbol Icon
                            IconButton(
                                onClick = { vm.update(page.copy(rotation = (page.rotation + 90) % 360)) },
                                enabled = !state.busy,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.RotateRight, contentDescription = "Rotate", modifier = Modifier.size(22.dp))
                            }
                            // Retake Symbol Icon
                            IconButton(
                                onClick = { retakeId = page.id; camera = true },
                                enabled = !state.busy,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Retake", modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
            state.output?.let { output -> item { Text("PDF ready"); PdfOutputActions(output) } }
        }
    }
    val editing = state.session.pages.firstOrNull { it.id == editId }
    if (editing != null) EnhanceDialog(editing,vm,onDismiss = { editId = null }) { vm.update(it); editId = null }
    if (exporting) ExportOptionsDialog(state.session.pages.count { it.needsReview },onDismiss = { exporting = false }) { name,options ->
        exporting = false; vm.export(name,options)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },title = { Text("Start a new document?") },
        text = { Text("Remove this session's images and edits? Exported PDFs remain saved.") },
        confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("New document") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep editing") } })
}

@Composable
private fun PageThumbnail(path: String,modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null,path) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(),"Page preview",modifier,contentScale = ContentScale.Fit)
    else Box(modifier) { Text("Preview") }
}

@Composable
private fun CropScanPage(page: ScanPage,vm: ScanViewModel,onBack: () -> Unit,onApply: (List<ScanPoint>) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null,page.id) {
        try { value = withContext(Dispatchers.IO) { vm.repository.source(page) } }
        catch (e: Exception) { error = e.message ?: "Could not load source image" }
    }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    val source = bitmap
    if (source != null) {
        val corners = remember(page.id,source) { page.corners.map { PointF(it.x*(source.width-1),it.y*(source.height-1)) } }
        DetectionScreen(source,title = "Adjust page corners",onProceed = {},onBack = onBack,initialCorners = corners,
            onCornersConfirmed = { points -> onApply(points.map { ScanPoint(it.x/(source.width-1),it.y/(source.height-1)) }) },
            viewModel = viewModel(key = "crop_${page.id}_${page.revision}"))
    } else Column(Modifier.padding(24.dp)) { Text(error ?: "Loading source image…"); TextButton(onClick = onBack) { Text("Back") } }
}

@Composable
private fun EnhanceDialog(page: ScanPage,vm: ScanViewModel,onDismiss: () -> Unit,onApply: (ScanPage) -> Unit) {
    var draft by remember(page.id) { mutableStateOf(page) }
    var previewPage by remember(page.id) { mutableStateOf(page) }
    var error by remember { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null,previewPage) {
        try { value = withContext(Dispatchers.IO) { vm.repository.render(previewPage,650) } }
        catch (e: Exception) { error = e.message }
    }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    AlertDialog(onDismissRequest = onDismiss,title = { Text("Enhance page") },text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            bitmap?.let { Image(it.asImageBitmap(),"Filter preview",Modifier.fillMaxWidth().height(220.dp),contentScale = ContentScale.Fit) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.values().forEach { filter ->
                    val filterLabel = when (filter) {
                        ScanFilter.BLACK_WHITE -> "Document B&W"
                        ScanFilter.COLOR -> "Magic Color"
                        ScanFilter.GRAYSCALE -> "Grayscale"
                        ScanFilter.ORIGINAL -> "Original"
                    }
                    FilterChip(draft.filter == filter, { draft = draft.copy(filter = filter); previewPage = draft }, label = { Text(filterLabel) })
                }
            }
            Text("Brightness")
            Slider(draft.brightness,{ draft = draft.copy(brightness = it) },valueRange = -50f..50f,onValueChangeFinished = { previewPage = draft })
            Text("Contrast")
            Slider(draft.contrast,{ draft = draft.copy(contrast = it) },valueRange = -50f..70f,onValueChangeFinished = { previewPage = draft })
        }
    },confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("Apply") } },dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ExportOptionsDialog(reviewCount: Int,onDismiss: () -> Unit,onExport: (String,ScanExportOptions) -> Unit) {
    var name by rememberSaveable { mutableStateOf("Scanned document") }
    var options by remember { mutableStateOf(ScanExportOptions()) }
    AlertDialog(onDismissRequest = onDismiss,title = { Text("Export PDF") },text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (reviewCount>0) Text("$reviewCount pages have uncertain boundaries. Review their crops if needed before exporting.")
            OutlinedTextField(name,{ name = it },label = { Text("Filename") },singleLine = true)
            Row {
                FilterChip(!options.letter,{ options = options.copy(letter = false) },label = { Text("A4") })
                Spacer(Modifier.width(8.dp)); FilterChip(options.letter,{ options = options.copy(letter = true) },label = { Text("Letter") })
            }
            FilterChip(options.landscape,{ options = options.copy(landscape = !options.landscape) },label = { Text(if (options.landscape) "Landscape" else "Portrait") })
            Text("Margin: ${options.marginMm.toInt()} mm")
            Slider(options.marginMm,{ options = options.copy(marginMm = it) },valueRange = 0f..40f,steps = 39)
            Text("Image quality")
            PdfQuality.values().forEach { q -> FilterChip(options.quality == q,{ options = options.copy(quality = q) },label = { Text(when(q) { PdfQuality.LIGHT -> "High · 200 DPI"; PdfQuality.BALANCED -> "Balanced · 150 DPI"; PdfQuality.STRONG -> "Small · 100 DPI" }) }) }
        }
    },confirmButton = { TextButton(onClick = { onExport(name,options) }) { Text("Export") } },dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
