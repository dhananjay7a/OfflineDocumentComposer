package com.example.offlinedocumentcomposer.presentation.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.example.offlinedocumentcomposer.domain.detector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScannerScreen(
    onCaptured: (Uri) -> Unit,
    onBack: () -> Unit,
    importing: Boolean = false,
    mode: DetectionMode = DetectionMode.DOCUMENT,
    pageCount: Int = 0,
    processingError: String? = null,
    initialThumbnailPath: String? = null
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    // By default auto capture is OFF as requested
    var automatic by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var outline by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var guidance by remember { mutableStateOf("Position the document inside the view") }
    var error by remember { mutableStateOf<String?>(null) }
    var takingPhoto by remember { mutableStateOf(false) }
    var latestThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var capturedCount by remember { mutableStateOf(pageCount) }
    var showFlash by remember { mutableStateOf(false) }
    var showCaptureBanner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val gate = remember { AutoCaptureGate() }
    val busy = remember { AtomicBoolean(false) }
    val importState by rememberUpdatedState(importing)
    val autoState by rememberUpdatedState(automatic)
    val currentCount by rememberUpdatedState(pageCount)
    val callback by rememberUpdatedState(onCaptured)
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(initialThumbnailPath) {
        if (initialThumbnailPath != null && latestThumbnail == null) {
            withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    latestThumbnail = BitmapFactory.decodeFile(initialThumbnailPath, opts)
                } catch (_: Exception) {}
            }
        }
    }

    fun takePhoto() {
        val useCase = capture ?: return
        if (importing || !busy.compareAndSet(false,true)) return
        takingPhoto = true
        gate.markCaptured()
        val file = File.createTempFile("scan_capture_", ".jpg", context.cacheDir)
        useCase.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),mainExecutor,object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                takingPhoto = false
                busy.set(false)
                // Decode small preview thumbnail for bottom-left display
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 6 }
                    val thumb = BitmapFactory.decodeFile(file.absolutePath, opts)
                    if (thumb != null) {
                        latestThumbnail?.recycle()
                        latestThumbnail = thumb
                    }
                } catch (_: Exception) {}
                capturedCount++
                showFlash = true
                showCaptureBanner = true
                scope.launch {
                    delay(80)
                    showFlash = false
                    delay(1500)
                    showCaptureBanner = false
                }
                callback(Uri.fromFile(file))
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                takingPhoto = false
                busy.set(false)
                gate.reset()
                error = "Could not capture photo: ${exception.message}"
            }
        })
    }
    val shutter by rememberUpdatedState({ takePhoto() })
    LaunchedEffect(Unit) { if (!permission) requestPermission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }

    DisposableEffect(permission, owner, mode, orientation) {
        val executor = Executors.newSingleThreadExecutor()
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var boundPreview: Preview? = null
        var boundCapture: ImageCapture? = null
        if (permission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    val p = future.get(); provider = p
                    val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                    val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640,480))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis = imageAnalysis; boundPreview = cameraPreview; boundCapture = imageCapture
                    val detector = DocumentDetector()
                    var lastFrame = 0L
                    imageAnalysis.setAnalyzer(executor) { frame ->
                        var bitmap: Bitmap? = null
                        try {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastFrame >= 280 && !busy.get()) {
                                lastFrame = now
                                bitmap = rgbaBitmap(frame)
                                val result = detector.detect(bitmap, mode, live = true)
                                val suitable = result.success && result.confidence >= .80f && readableFrame(bitmap)
                                val points = if (result.success) result.corners.map { it.x / frame.width to it.y / frame.height } else null
                                val xy = result.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
                                val transform = ImageProxyTransformFactory().apply {
                                    isUsingCropRect = false
                                    isUsingRotationDegrees = false
                                }.getOutputTransform(frame)
                                mainExecutor.execute {
                                    if (!disposed) {
                                        val target = preview.outputTransform
                                        outline = if (result.success && target != null) {
                                            CoordinateTransform(transform, target).mapPoints(xy)
                                            xy.toList().chunked(2).map { Offset(it[0], it[1]) }
                                        } else emptyList()
                                        guidance = when {
                                            importState -> "Processing captured page…"
                                            !result.success -> "Find all four edges, or use the shutter"
                                            !suitable -> "Improve lighting and hold the camera steady"
                                            else -> "Hold steady · ${currentCount} pages"
                                        }
                                        val ready = gate.update(points, suitable && !importState && autoState, now)
                                        if (ready && !busy.get()) shutter()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            mainExecutor.execute { if (!disposed) { outline = emptyList(); guidance = "Use the shutter to capture and adjust manually" } }
                        } finally { bitmap?.recycle(); frame.close() }
                    }
                    preview.doOnLayout {
                        if (!disposed) try {
                            val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                            imageCapture.targetRotation = rotation
                            imageAnalysis.targetRotation = rotation
                            cameraPreview.targetRotation = rotation
                            val group = UseCaseGroup.Builder().addUseCase(cameraPreview).addUseCase(imageCapture).addUseCase(imageAnalysis)
                                .setViewPort(requireNotNull(preview.viewPort)).build()
                            camera = p.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,group)
                            hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                            capture = imageCapture
                            preview.setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    val point = preview.meteringPointFactory.createPoint(event.x,event.y)
                                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                                    preview.performClick()
                                }
                                true
                            }
                        } catch (e: Exception) { error = "Camera unavailable. Import from gallery instead. ${e.message}" }
                    }
                } catch (e: Exception) { error = "Camera unavailable. You can import images from the gallery. ${e.message}" }
            },mainExecutor)
        }
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            val bound = listOfNotNull(boundPreview,boundCapture,analysis).toTypedArray()
            if (bound.isNotEmpty()) provider?.unbind(*bound)
            capture = null; camera = null
            executor.shutdown()
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Scan document") },navigationIcon = { TextButton(onClick = onBack, enabled = !takingPhoto) { Text("Done") } }) },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                Text(guidance)
                (processingError ?: error)?.let { Text(it,color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                    FilterChip(automatic,{ automatic = !automatic; gate.reset() },label = { Text("Auto capture") })
                    if (hasFlash) FilterChip(torch,{ torch = !torch },label = { Text("Torch") })
                    Button(onClick = { takePhoto() },enabled = capture != null && !takingPhoto && !importing) { Text(if (takingPhoto) "Saving…" else "Capture") }
                }
            }
        }) { padding ->
        DisposableEffect(Unit) {
            onDispose {
                latestThumbnail?.recycle()
                latestThumbnail = null
            }
        }
        if (permission) Box(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
            AndroidView(factory = { preview },modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                if (outline.size == 4) for (i in 0..3) drawLine(Color(0xFF00E676),outline[i],outline[(i+1)%4],3.dp.toPx())
            }

            // Bottom-Left Scanned Photos Preview - updates when another photo is clicked
            val thumb = latestThumbnail
            if (thumb != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1E293B),
                    border = BorderStroke(2.dp, Color.White),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 16.dp)
                        .size(68.dp, 90.dp)
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = "Scanned photo thumbnail",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Page count badge
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "$capturedCount",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Capture Confirmation Banner at Top
            androidx.compose.animation.AnimatedVisibility(
                visible = showCaptureBanner,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF10B981),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Photo Captured · Page $capturedCount", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Shutter Flash Effect
            val flashAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (showFlash) 0.85f else 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = if (showFlash) 40 else 220),
                label = "shutterFlash"
            )
            if (flashAlpha > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = flashAlpha))
                )
            }
        } else Column(Modifier.padding(padding).padding(24.dp)) {
            Text("Camera permission is needed for live scanning. Gallery import remains available from the previous screen.")
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
    }
}

private fun rgbaBitmap(frame: ImageProxy): Bitmap {
    return try {
        frame.toBitmap()
    } catch (_: Throwable) {
        val plane = frame.planes[0]
        val buffer = plane.buffer
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        if (plane.pixelStride == 4 && plane.rowStride == frame.width * 4) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } else {
            val pixels = IntArray(frame.width * frame.height)
            for (y in 0 until frame.height) {
                val rowStart = y * plane.rowStride
                for (x in 0 until frame.width) {
                    val i = rowStart + x * plane.pixelStride
                    val r = buffer.get(i).toInt() and 255
                    val g = buffer.get(i + 1).toInt() and 255
                    val b = buffer.get(i + 2).toInt() and 255
                    pixels[y * frame.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            bitmap
        }
    }
}

private fun readableFrame(bitmap: Bitmap): Boolean {
    var sum = 0.0
    var difference = 0.0
    var count = 0
    fun gray(x: Int, y: Int): Int {
        val c = bitmap.getPixel(x, y)
        return ((c shr 16 and 255) * 3 + (c shr 8 and 255) * 6 + (c and 255)) / 10
    }
    val step = 16
    for (y in bitmap.height / 5 until bitmap.height * 4 / 5 step step) {
        for (x in bitmap.width / 5 until bitmap.width * 4 / 5 step step) {
            val a = gray(x, y)
            sum += a
            difference += abs(a - gray((x + 2).coerceAtMost(bitmap.width - 1), y))
            count++
        }
    }
    return count > 0 && sum / count in 35.0..245.0 && difference / count > 2.0
}
