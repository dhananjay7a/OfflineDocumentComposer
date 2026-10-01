package com.example.offlinedocumentcomposer.presentation.resizer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinedocumentcomposer.domain.image.ImageUtils
import com.example.offlinedocumentcomposer.domain.resizer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ImageResizerUiState(
    val sourceMeta: ImageSourceMeta? = null,
    val sourceBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val config: ResizeConfiguration = ResizeConfiguration(),
    val selectedPreset: ImagePreset? = null,
    val estimatedOutputBytes: Long = 0L,
    val isBusy: Boolean = false,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val exportedFile: File? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val targetDimensions: Pair<Int, Int>
        get() {
            val srcW = sourceMeta?.originalWidth ?: 1
            val srcH = sourceMeta?.originalHeight ?: 1
            return config.resolvePixelDimensions(srcW, srcH)
        }

    val formattedEstimatedSize: String
        get() = when {
            estimatedOutputBytes >= 1024 * 1024 -> String.format("%.2f MB", estimatedOutputBytes.toDouble() / (1024 * 1024))
            estimatedOutputBytes >= 1024 -> String.format("%.1f KB", estimatedOutputBytes.toDouble() / 1024)
            estimatedOutputBytes > 0 -> "$estimatedOutputBytes B"
            else -> "Calculating..."
        }
}

class ImageResizerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ImageResizerUiState())
    val uiState = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private val undoStack = mutableListOf<ResizeConfiguration>()
    private val redoStack = mutableListOf<ResizeConfiguration>()
    private val maxHistory = 30

    private fun pushSnapshot() {
        val current = _uiState.value.config
        if (undoStack.isNotEmpty() && undoStack.last() == current) return
        undoStack.add(current)
        if (undoStack.size > maxHistory) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        updateUndoRedoAvailability()
    }

    private fun updateUndoRedoAvailability() {
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = _uiState.value.config
        redoStack.add(current)

        val previous = undoStack.removeAt(undoStack.lastIndex)
        _uiState.value = _uiState.value.copy(
            config = previous,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        refreshPreviewAndEstimate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = _uiState.value.config
        undoStack.add(current)

        val next = redoStack.removeAt(redoStack.lastIndex)
        _uiState.value = _uiState.value.copy(
            config = next,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        refreshPreviewAndEstimate()
    }

    fun loadImageFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Loading image...")
            try {
                val meta = ImageResizerEngine.resolveImageMeta(context, uri)
                val bmp = withContext(Dispatchers.IO) {
                    ImageUtils.decodeBitmapFromUri(context, uri, maxDimension = 3000)
                }

                if (bmp != null) {
                    undoStack.clear()
                    redoStack.clear()

                    val initialConfig = ResizeConfiguration(
                        unit = ResizeUnit.PIXELS,
                        width = meta.originalWidth.toFloat(),
                        height = meta.originalHeight.toFloat(),
                        scalePercent = 100f,
                        lockAspectRatio = true,
                        dpi = 300,
                        format = OutputFormat.JPEG,
                        quality = 90
                    )

                    _uiState.value = _uiState.value.copy(
                        sourceMeta = meta,
                        sourceBitmap = bmp,
                        config = initialConfig,
                        selectedPreset = null,
                        isBusy = false,
                        canUndo = false,
                        canRedo = false
                    )
                    refreshPreviewAndEstimate()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isBusy = false,
                        errorMessage = "Could not decode image from file"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    errorMessage = "Failed to load image: ${e.message}"
                )
            }
        }
    }

    fun loadImageBitmap(bitmap: Bitmap, fileName: String = "photo.jpg") {
        val meta = ImageSourceMeta(
            uri = Uri.EMPTY,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            fileSizeBytes = (bitmap.allocationByteCount / 4).toLong(),
            fileName = fileName
        )

        undoStack.clear()
        redoStack.clear()

        val initialConfig = ResizeConfiguration(
            unit = ResizeUnit.PIXELS,
            width = bitmap.width.toFloat(),
            height = bitmap.height.toFloat(),
            scalePercent = 100f,
            lockAspectRatio = true,
            dpi = 300,
            format = OutputFormat.JPEG,
            quality = 90
        )

        _uiState.value = _uiState.value.copy(
            sourceMeta = meta,
            sourceBitmap = bitmap,
            config = initialConfig,
            selectedPreset = null,
            isBusy = false,
            canUndo = false,
            canRedo = false
        )
        refreshPreviewAndEstimate()
    }

    fun updateWidth(newWidth: Float) {
        val meta = _uiState.value.sourceMeta ?: return
        val current = _uiState.value.config
        if (newWidth <= 0 || newWidth == current.width) return

        pushSnapshot()
        val aspect = meta.aspectRatio
        val newHeight = if (current.lockAspectRatio && aspect > 0) {
            when (current.unit) {
                ResizeUnit.PERCENTAGE -> newWidth
                else -> newWidth / aspect
            }
        } else {
            current.height
        }

        val scale = when (current.unit) {
            ResizeUnit.PIXELS -> (newWidth / meta.originalWidth.coerceAtLeast(1) * 100f)
            ResizeUnit.PERCENTAGE -> newWidth
            else -> current.scalePercent
        }

        _uiState.value = _uiState.value.copy(
            config = current.copy(width = newWidth, height = newHeight, scalePercent = scale),
            selectedPreset = null
        )
        refreshPreviewAndEstimate()
    }

    fun updateHeight(newHeight: Float) {
        val meta = _uiState.value.sourceMeta ?: return
        val current = _uiState.value.config
        if (newHeight <= 0 || newHeight == current.height) return

        pushSnapshot()
        val aspect = meta.aspectRatio
        val newWidth = if (current.lockAspectRatio && aspect > 0) {
            when (current.unit) {
                ResizeUnit.PERCENTAGE -> newHeight
                else -> newHeight * aspect
            }
        } else {
            current.width
        }

        val scale = when (current.unit) {
            ResizeUnit.PIXELS -> (newHeight / meta.originalHeight.coerceAtLeast(1) * 100f)
            ResizeUnit.PERCENTAGE -> newHeight
            else -> current.scalePercent
        }

        _uiState.value = _uiState.value.copy(
            config = current.copy(width = newWidth, height = newHeight, scalePercent = scale),
            selectedPreset = null
        )
        refreshPreviewAndEstimate()
    }

    fun updateScalePercent(newScale: Float) {
        val meta = _uiState.value.sourceMeta ?: return
        val clamped = newScale.coerceIn(5f, 500f)
        val current = _uiState.value.config
        if (clamped == current.scalePercent && current.unit == ResizeUnit.PERCENTAGE) return

        pushSnapshot()
        val factor = clamped / 100f
        val newW = meta.originalWidth * factor
        val newH = meta.originalHeight * factor

        _uiState.value = _uiState.value.copy(
            config = current.copy(
                width = newW,
                height = newH,
                scalePercent = clamped
            ),
            selectedPreset = null
        )
        refreshPreviewAndEstimate()
    }

    fun toggleLockAspectRatio() {
        val current = _uiState.value.config
        pushSnapshot()
        _uiState.value = _uiState.value.copy(
            config = current.copy(lockAspectRatio = !current.lockAspectRatio)
        )
    }

    fun setUnit(newUnit: ResizeUnit) {
        val meta = _uiState.value.sourceMeta ?: return
        val current = _uiState.value.config
        if (current.unit == newUnit) return

        pushSnapshot()
        val dpi = current.dpi.toFloat()

        val (newW, newH) = when (newUnit) {
            ResizeUnit.PIXELS -> {
                val (w, h) = current.resolvePixelDimensions(meta.originalWidth, meta.originalHeight)
                Pair(w.toFloat(), h.toFloat())
            }
            ResizeUnit.PERCENTAGE -> {
                Pair(current.scalePercent, current.scalePercent)
            }
            ResizeUnit.MILLIMETERS -> {
                val (w, h) = current.resolvePixelDimensions(meta.originalWidth, meta.originalHeight)
                Pair((w / dpi) * 25.4f, (h / dpi) * 25.4f)
            }
            ResizeUnit.CENTIMETERS -> {
                val (w, h) = current.resolvePixelDimensions(meta.originalWidth, meta.originalHeight)
                Pair((w / dpi) * 2.54f, (h / dpi) * 2.54f)
            }
            ResizeUnit.INCHES -> {
                val (w, h) = current.resolvePixelDimensions(meta.originalWidth, meta.originalHeight)
                Pair(w / dpi, h / dpi)
            }
        }

        _uiState.value = _uiState.value.copy(
            config = current.copy(unit = newUnit, width = newW, height = newH)
        )
        refreshPreviewAndEstimate()
    }

    fun setDpi(newDpi: Int) {
        val current = _uiState.value.config
        if (current.dpi == newDpi) return
        pushSnapshot()
        _uiState.value = _uiState.value.copy(
            config = current.copy(dpi = newDpi)
        )
        refreshPreviewAndEstimate()
    }

    fun applyPreset(preset: ImagePreset) {
        pushSnapshot()
        val current = _uiState.value.config
        val updated = current.copy(
            unit = preset.unit,
            width = preset.width,
            height = preset.height,
            dpi = preset.dpi,
            targetMaxKb = preset.targetMaxKb ?: current.targetMaxKb,
            lockAspectRatio = false // Allow preset to set exact required aspect ratio
        )
        _uiState.value = _uiState.value.copy(
            config = updated,
            selectedPreset = preset
        )
        refreshPreviewAndEstimate()
    }

    fun setTargetMaxKb(maxKb: Int?) {
        val current = _uiState.value.config
        if (current.targetMaxKb == maxKb) return
        pushSnapshot()
        _uiState.value = _uiState.value.copy(
            config = current.copy(targetMaxKb = maxKb)
        )
        refreshPreviewAndEstimate()
    }

    fun setFormat(format: OutputFormat) {
        val current = _uiState.value.config
        if (current.format == format) return
        pushSnapshot()
        _uiState.value = _uiState.value.copy(
            config = current.copy(format = format)
        )
        refreshPreviewAndEstimate()
    }

    fun setOutputFormat(format: OutputFormat) = setFormat(format)

    fun setQuality(quality: Int) {
        val current = _uiState.value.config
        val clamped = quality.coerceIn(10, 100)
        if (current.quality == clamped) return
        pushSnapshot()
        _uiState.value = _uiState.value.copy(
            config = current.copy(quality = clamped)
        )
        refreshPreviewAndEstimate()
    }

    fun resetToOriginal() {
        val meta = _uiState.value.sourceMeta ?: return
        val current = _uiState.value.config
        pushSnapshot()
        _uiState.value = _uiState.value.copy(
            config = current.copy(
                unit = ResizeUnit.PIXELS,
                width = meta.originalWidth.toFloat(),
                height = meta.originalHeight.toFloat(),
                scalePercent = 100f,
                targetMaxKb = null,
                quality = 90
            ),
            selectedPreset = null
        )
        refreshPreviewAndEstimate()
    }

    private fun refreshPreviewAndEstimate() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val srcBmp = _uiState.value.sourceBitmap ?: return@launch
            val meta = _uiState.value.sourceMeta ?: return@launch
            val config = _uiState.value.config

            val (targetW, targetH) = config.resolvePixelDimensions(meta.originalWidth, meta.originalHeight)

            // Estimate file size
            val estBytes = ImageResizerEngine.estimateOutputBytes(
                origW = meta.originalWidth,
                origH = meta.originalHeight,
                targetW = targetW,
                targetH = targetH,
                origSizeBytes = meta.fileSizeBytes,
                format = config.format,
                quality = config.quality,
                targetMaxKb = config.targetMaxKb
            )

            // Render scaled preview bitmap (clamped to max 800px for smooth UI preview)
            val maxPreviewDim = 800
            val previewScale = min(1f, maxPreviewDim.toFloat() / max(targetW, targetH).toFloat())
            val prevW = max(1, (targetW * previewScale).roundToInt())
            val prevH = max(1, (targetH * previewScale).roundToInt())

            val previewBmp = withContext(Dispatchers.Default) {
                ImageResizerEngine.resizeBitmap(srcBmp, prevW, prevH)
            }

            _uiState.value.previewBitmap?.recycle()
            _uiState.value = _uiState.value.copy(
                previewBitmap = previewBmp,
                estimatedOutputBytes = estBytes
            )
        }
    }

    fun exportResizedImage(context: Context, onComplete: (File) -> Unit) {
        val srcBmp = _uiState.value.sourceBitmap ?: return
        val meta = _uiState.value.sourceMeta ?: return
        val config = _uiState.value.config

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Resizing and saving image...")
            try {
                val outFile = withContext(Dispatchers.IO) {
                    ImageResizerEngine.saveResizedImage(
                        context = context,
                        sourceBitmap = srcBmp,
                        config = config,
                        originalFileName = meta.fileName
                    )
                }
                _uiState.value = _uiState.value.copy(isBusy = false, exportedFile = outFile)
                onComplete(outFile)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    errorMessage = "Failed to export image: ${e.message}"
                )
            }
        }
    }

    fun printResizedImage(context: Context) {
        val srcBmp = _uiState.value.sourceBitmap ?: return
        val meta = _uiState.value.sourceMeta ?: return
        val config = _uiState.value.config

        try {
            val (targetW, targetH) = config.resolvePixelDimensions(meta.originalWidth, meta.originalHeight)
            val resized = ImageResizerEngine.resizeBitmap(srcBmp, targetW, targetH)
            ImageResizerEngine.printImage(context, resized, "Resized_${meta.fileName}")
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(errorMessage = "Printing error: ${e.message}")
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
        _uiState.value.sourceBitmap?.recycle()
    }
}
