package com.example.offlinedocumentcomposer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import com.example.offlinedocumentcomposer.data.model.ImageLayer
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.domain.image.ImageUtils
import com.example.offlinedocumentcomposer.presentation.composer.ComposerViewModel
import com.example.offlinedocumentcomposer.presentation.composer.PageComposerScreen
import com.example.offlinedocumentcomposer.presentation.detection.DetectionScreen
import com.example.offlinedocumentcomposer.presentation.editor.ImageEditorScreen
import com.example.offlinedocumentcomposer.presentation.export.ExportScreen
import com.example.offlinedocumentcomposer.presentation.export.ExportViewModel
import com.example.offlinedocumentcomposer.presentation.home.HomeScreen
import com.example.offlinedocumentcomposer.presentation.recent.RecentProjectsScreen
import com.example.offlinedocumentcomposer.ui.theme.OfflineDocumentComposerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize heavy native OpenCV library asynchronously off main thread for instant app launch
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            com.example.offlinedocumentcomposer.opencv.OpenCvUtils.initOpenCV()
        }
        setContent {
            OfflineDocumentComposerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

enum class FlowMode {
    ID_CARD_2_SIDED,
    SINGLE_DOC,
    CUSTOM
}

class AppFlowViewModel : ViewModel() {
    var flowMode by mutableStateOf(FlowMode.ID_CARD_2_SIDED)

    // Raw input bitmaps
    var currentRawBitmap by mutableStateOf<Bitmap?>(null)

    // Front card
    var frontProcessedBitmap by mutableStateOf<Bitmap?>(null)

    // Back card
    var backProcessedBitmap by mutableStateOf<Bitmap?>(null)

    // Selected layer for editing in composer
    var editingLayer: ImageLayer? = null

    // Page model ready for export
    var exportPageModel by mutableStateOf(PageModel())
    var exportPages by mutableStateOf<List<PageModel>>(emptyList())

    fun resetForIdCard() {
        flowMode = FlowMode.ID_CARD_2_SIDED
        currentRawBitmap = null
        frontProcessedBitmap = null
        backProcessedBitmap = null
        editingLayer = null
    }

    fun resetForSingleDoc() {
        flowMode = FlowMode.SINGLE_DOC
        currentRawBitmap = null
        frontProcessedBitmap = null
        backProcessedBitmap = null
        editingLayer = null
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val appViewModel: AppFlowViewModel = viewModel()
    val composerViewModel: ComposerViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onStartIdCardFlow = {
                    appViewModel.resetForIdCard()
                    navController.navigate("capture_front")
                },
                onStartSingleDocFlow = {
                    appViewModel.resetForSingleDoc()
                    navController.navigate("capture_single")
                },
                onOpenComposer = {
                    appViewModel.flowMode = FlowMode.CUSTOM
                    navController.navigate("composer")
                },
                onOpenRecent = { navController.navigate("recent") },
                onOpenSettings = { navController.navigate("settings") },
                onOpenScanner = { navController.navigate("scanner") },
                onOpenPdfResizer = { navController.navigate("pdf_resizer") },
                onOpenPassportPhoto = { navController.navigate("passport_photo") },
                onOpenImageResizer = { navController.navigate("image_resizer") },
                onOpenPdfMerge = { navController.navigate("pdf_merge") },
                onOpenPdfSplit = { navController.navigate("pdf_split") },
                onOpenPdfOrganize = { navController.navigate("pdf_organize") },
                onOpenPdfSign = { navController.navigate("pdf_sign") }
            )
        }

        composable("pdf_merge") {
            com.example.offlinedocumentcomposer.presentation.tools.PdfMergeScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("pdf_split") {
            com.example.offlinedocumentcomposer.presentation.tools.PdfSplitScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("pdf_organize") {
            com.example.offlinedocumentcomposer.presentation.tools.PdfOrganizeScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("pdf_sign") {
            com.example.offlinedocumentcomposer.presentation.tools.PdfSignEditScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("scanner") {
            com.example.offlinedocumentcomposer.presentation.scanner.ScanScreen(onBack = { navController.popBackStack() })
        }
        composable("pdf_resizer") {
            com.example.offlinedocumentcomposer.presentation.tools.PdfResizerScreen(onBack = { navController.popBackStack() })
        }
        composable("passport_photo") {
            com.example.offlinedocumentcomposer.presentation.passport.PassportPhotoScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable("image_resizer") {
            com.example.offlinedocumentcomposer.presentation.resizer.ImageResizerScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ================= ID CARD 2-SIDED FLOW =================
        // Step 1: Capture Front
        composable("capture_front") {
            PhotoIngestScreen(
                title = "Front of Card",
                stepBadge = "Step 1 of 2: Front Side",
                instructions = "Capture or select the FRONT side of your ID card (e.g. Aadhaar, Driver License)",
                onPhotoReady = { bmp ->
                    appViewModel.currentRawBitmap = bmp
                    navController.navigate("crop_front")
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Step 1: Crop Front
        composable("crop_front") {
            DetectionScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Crop Front Side",
                mode = com.example.offlinedocumentcomposer.domain.detector.DetectionMode.ID_CARD,
                onProceed = { croppedBmp ->
                    appViewModel.currentRawBitmap = croppedBmp
                    navController.navigate("filter_front")
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Step 1: Filter Front
        composable("filter_front") {
            ImageEditorScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Enhance Front Side",
                onApply = { filteredBmp ->
                    appViewModel.frontProcessedBitmap = filteredBmp
                    navController.navigate("capture_back")
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Step 2: Capture Back
        composable("capture_back") {
            PhotoIngestScreen(
                title = "Back of Card",
                stepBadge = "Step 2 of 2: Back Side",
                instructions = "Now capture or select the BACK side of your ID card",
                onPhotoReady = { bmp ->
                    appViewModel.currentRawBitmap = bmp
                    navController.navigate("crop_back")
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Step 2: Crop Back
        composable("crop_back") {
            DetectionScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Crop Back Side",
                mode = com.example.offlinedocumentcomposer.domain.detector.DetectionMode.ID_CARD,
                onProceed = { croppedBmp ->
                    appViewModel.currentRawBitmap = croppedBmp
                    navController.navigate("filter_back")
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Step 2: Filter Back & Proceed to Composer
        composable("filter_back") {
            ImageEditorScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Enhance Back Side",
                onApply = { filteredBmp ->
                    appViewModel.backProcessedBitmap = filteredBmp

                    // Build 2 layers for Front and Back
                    val front = appViewModel.frontProcessedBitmap
                    val back = filteredBmp
                    val layers = mutableListOf<ImageLayer>()

                    if (front != null) {
                        layers.add(ImageLayer(workingBitmap = front, name = "Front Side"))
                    }
                    layers.add(ImageLayer(workingBitmap = back, name = "Back Side"))

                    composerViewModel.setLayers(layers)
                    // Auto arrange side by side on A4!
                    composerViewModel.arrangeIdCardsSideBySide()

                    navController.navigate("composer") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ================= SINGLE DOCUMENT FLOW =================
        composable("capture_single") {
            PhotoIngestScreen(
                title = "Scan Document",
                stepBadge = "Single Document",
                instructions = "Capture or select document photo to crop, enhance and place on A4",
                onPhotoReady = { bmp ->
                    appViewModel.currentRawBitmap = bmp
                    navController.navigate("crop_single")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("crop_single") {
            DetectionScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Crop Document",
                onProceed = { croppedBmp ->
                    appViewModel.currentRawBitmap = croppedBmp
                    navController.navigate("filter_single")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("filter_single") {
            ImageEditorScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Enhance Document",
                onApply = { filteredBmp ->
                    val layer = ImageLayer(
                        workingBitmap = filteredBmp,
                        width = 400f,
                        height = 400f / (filteredBmp.width.toFloat() / filteredBmp.height.toFloat()),
                        name = "Document"
                    )
                    composerViewModel.setLayers(listOf(layer))
                    composerViewModel.alignCenter()

                    navController.navigate("composer") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ================= PAGE COMPOSER =================
        composable("composer") {
            PageComposerScreen(
                viewModel = composerViewModel,
                onExportClicked = { pageModel ->
                    appViewModel.exportPageModel = pageModel
                    appViewModel.exportPages = composerViewModel.state.value.pages
                    navController.navigate("export")
                },
                onEditLayerClicked = { layer ->
                    appViewModel.editingLayer = layer
                    appViewModel.currentRawBitmap = layer.workingBitmap ?: layer.originalBitmap
                    navController.navigate("edit_existing_layer")
                },
                onAddImageClicked = {
                    navController.navigate("capture_additional")
                },
                onBack = { navController.navigate("home") }
            )
        }

        // Ingest additional image into composer
        composable("capture_additional") {
            PhotoIngestScreen(
                title = "Add Another Image",
                stepBadge = "Add Image",
                instructions = "Select or snap an extra photo to add to your A4 page canvas",
                onPhotoReady = { bmp ->
                    appViewModel.currentRawBitmap = bmp
                    navController.navigate("crop_additional")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("crop_additional") {
            DetectionScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Crop Image",
                onProceed = { croppedBmp ->
                    val layer = ImageLayer(
                        workingBitmap = croppedBmp,
                        width = 300f,
                        height = 300f / (croppedBmp.width.toFloat() / croppedBmp.height.toFloat()),
                        name = "Photo ${System.currentTimeMillis().toString().takeLast(4)}"
                    )
                    composerViewModel.addImageLayer(layer)
                    navController.popBackStack("composer", inclusive = false)
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Edit existing layer adjustments
        composable("edit_existing_layer") {
            ImageEditorScreen(
                bitmap = appViewModel.currentRawBitmap,
                title = "Adjust Layer",
                onApply = { updatedBmp ->
                    appViewModel.editingLayer?.let { layer ->
                        composerViewModel.updateLayerBitmap(layer.id, updatedBmp)
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ================= EXPORT =================
        composable("export") {
            val exportViewModel: ExportViewModel = viewModel()
            ExportScreen(
                pageModel = appViewModel.exportPageModel,
                pages = appViewModel.exportPages.ifEmpty { listOf(appViewModel.exportPageModel) },
                onBack = { navController.popBackStack() },
                onHome = {
                    navController.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                },
                viewModel = exportViewModel
            )
        }

        composable("recent") {
            RecentProjectsScreen(navController = navController)
        }

        composable("settings") {
            SettingsScreen(navController = navController)
        }
    }
}

/**
 * Modern photo ingest screen that handles:
 * - Camera capture with runtime permission checking and full-resolution FileProvider URI
 * - Gallery selection
 * - Immediate high-res image decoding and orientation fixing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoIngestScreen(
    title: String,
    stepBadge: String,
    instructions: String,
    onPhotoReady: (Bitmap) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showScanner by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val ingestScope = rememberCoroutineScope()
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    if (showScanner) {
        androidx.activity.compose.BackHandler { showScanner = false }
        com.example.offlinedocumentcomposer.presentation.scanner.LiveScannerScreen(
            onCaptured = { uri ->
                showScanner = false
                ingestScope.launch {
                    isLoading = true
                    selectedBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val decoded = ImageUtils.decodeBitmapFromUri(context, uri, maxDimension = 3000)
                        if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
                        decoded
                    }
                    isLoading = false
                }
            },
            onBack = { showScanner = false },
            mode = if (title.contains("Card")) com.example.offlinedocumentcomposer.domain.detector.DetectionMode.ID_CARD else com.example.offlinedocumentcomposer.domain.detector.DetectionMode.DOCUMENT
        )
        return
    }

    // Gallery picker launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            ingestScope.launch {
                isLoading = true
                val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ImageUtils.decodeBitmapFromUri(context, uri, maxDimension = 3000)
                }
                isLoading = false
                if (bmp != null) selectedBitmap = bmp
                else Toast.makeText(context, "Could not decode selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(stepBadge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Step badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text(
                    text = stepBadge.uppercase(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = instructions,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Image Preview or Empty Placeholder
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = selectedBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Selected Photo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else if (isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            com.example.offlinedocumentcomposer.presentation.common.SafeLoadingSpinner(
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Loading image...", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AddAPhoto,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No photo chosen yet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Take a photo with your camera or pick an existing image from gallery",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take Photo", fontWeight = FontWeight.Bold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Gallery", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Proceed Button
            Button(
                onClick = {
                    selectedBitmap?.let { onPhotoReady(it) }
                },
                enabled = selectedBitmap != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981)
                )
            ) {
                Text("Proceed to Edge Detection", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("100% Offline Processing", fontWeight = FontWeight.Bold, color = Color(0xFF065F46))
                        Text(
                            "All image processing, perspective cropping, and PDF compilation run entirely on your phone's processor. No internet needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF047857)
                        )
                    }
                }
            }

            Card(shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Target PDF Quality: 300 DPI (High Resolution)", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Standard A4 page format (210 x 297 mm)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
