package com.example.offlinedocumentcomposer.presentation.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinedocumentcomposer.data.model.ImageAdjustments
import com.example.offlinedocumentcomposer.domain.image.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorState(
    val originalBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val rotation: Float = 0f,
    val selectedPreset: String = "Original",
    val isProcessing: Boolean = false
)

class ImageEditorViewModel : ViewModel() {
    private val processor = ImageProcessor()
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private var previewJob: Job? = null
    private var scaledPreviewBase: Bitmap? = null

    fun loadBitmap(bitmap: Bitmap) {
        val maxDim = kotlin.math.max(bitmap.width, bitmap.height)
        scaledPreviewBase = if (maxDim > 1080) {
            val scale = 1080f / maxDim
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        _state.value = EditorState(
            originalBitmap = bitmap,
            previewBitmap = scaledPreviewBase ?: bitmap
        )
    }

    private fun updatePreview() {
        val base = scaledPreviewBase ?: _state.value.originalBitmap ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val adj = ImageAdjustments(
                brightness = _state.value.brightness,
                contrast = _state.value.contrast,
                saturation = _state.value.saturation,
                sharpness = _state.value.sharpness
            )
            val rot = _state.value.rotation
            val updated = withContext(Dispatchers.Default) {
                var bmp = processor.applyAdjustments(base, adj)
                if (rot != 0f) {
                    val rotated = processor.rotate(bmp, rot)
                    if (rotated != bmp) bmp.recycle()
                    bmp = rotated
                }
                bmp
            }
            _state.value = _state.value.copy(previewBitmap = updated)
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewJob?.cancel()
        if (scaledPreviewBase !== _state.value.originalBitmap) {
            scaledPreviewBase?.recycle()
        }
    }

    fun setBrightness(value: Float) {
        _state.value = _state.value.copy(brightness = value, selectedPreset = "Custom")
        updatePreview()
    }

    fun setContrast(value: Float) {
        _state.value = _state.value.copy(contrast = value, selectedPreset = "Custom")
        updatePreview()
    }

    fun setSaturation(value: Float) {
        _state.value = _state.value.copy(saturation = value, selectedPreset = "Custom")
        updatePreview()
    }

    fun setSharpness(value: Float) {
        _state.value = _state.value.copy(sharpness = value, selectedPreset = "Custom")
        updatePreview()
    }

    fun applyPreset(presetName: String) {
        when (presetName) {
            "Document B&W" -> {
                _state.value = _state.value.copy(
                    brightness = ImageAdjustments.DOCUMENT_BW.brightness,
                    contrast = ImageAdjustments.DOCUMENT_BW.contrast,
                    saturation = ImageAdjustments.DOCUMENT_BW.saturation,
                    sharpness = 30f,
                    selectedPreset = presetName
                )
            }
            "Magic Color" -> {
                _state.value = _state.value.copy(
                    brightness = ImageAdjustments.MAGIC_COLOR.brightness,
                    contrast = ImageAdjustments.MAGIC_COLOR.contrast,
                    saturation = ImageAdjustments.MAGIC_COLOR.saturation,
                    sharpness = 25f,
                    selectedPreset = presetName
                )
            }
            "Grayscale" -> {
                _state.value = _state.value.copy(
                    brightness = ImageAdjustments.GRAYSCALE.brightness,
                    contrast = ImageAdjustments.GRAYSCALE.contrast,
                    saturation = ImageAdjustments.GRAYSCALE.saturation,
                    sharpness = 15f,
                    selectedPreset = presetName
                )
            }
            "Original" -> {
                _state.value = _state.value.copy(
                    brightness = 0f,
                    contrast = 0f,
                    saturation = 1f,
                    sharpness = 0f,
                    selectedPreset = presetName
                )
            }
        }
        updatePreview()
    }

    fun applyGrayscale() {
        applyPreset("Grayscale")
    }

    fun resetAdjustments() {
        applyPreset("Original")
    }

    fun rotateLeft() {
        val newRot = (_state.value.rotation - 90f + 360f) % 360f
        _state.value = _state.value.copy(rotation = newRot)
        updatePreview()
    }

    fun rotateRight() {
        val newRot = (_state.value.rotation + 90f) % 360f
        _state.value = _state.value.copy(rotation = newRot)
        updatePreview()
    }

    fun getProcessedBitmap(): Bitmap? {
        val orig = _state.value.originalBitmap ?: return null
        val adj = ImageAdjustments(
            brightness = _state.value.brightness,
            contrast = _state.value.contrast,
            saturation = _state.value.saturation,
            sharpness = _state.value.sharpness
        )
        var result = processor.applyAdjustments(orig, adj)
        if (_state.value.rotation != 0f) {
            result = processor.rotate(result, _state.value.rotation)
        }
        return result
    }

    fun getCurrentAdjustments(): ImageAdjustments {
        return ImageAdjustments(
            brightness = _state.value.brightness,
            contrast = _state.value.contrast,
            saturation = _state.value.saturation,
            sharpness = _state.value.sharpness
        )
    }
}
