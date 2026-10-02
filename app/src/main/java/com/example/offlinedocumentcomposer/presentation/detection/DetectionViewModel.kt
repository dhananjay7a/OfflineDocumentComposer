package com.example.offlinedocumentcomposer.presentation.detection

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinedocumentcomposer.domain.detector.DocumentDetector
import com.example.offlinedocumentcomposer.domain.image.PerspectiveCorrector
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.example.offlinedocumentcomposer.domain.detector.DetectionMode
import com.example.offlinedocumentcomposer.domain.detector.CropGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetectionState(
    val originalBitmap: Bitmap? = null,
    val corners: List<PointF> = emptyList(),
    val confidence: Float = 0f,
    val detected: Boolean = false,
    val isDetecting: Boolean = false,
    val isCropping: Boolean = false,
    val idRatio: Boolean = false,
    val errorMessage: String? = null
)

class DetectionViewModel : ViewModel() {
    private val detector = DocumentDetector()
    private var detectionJob: Job? = null
    private var mode = DetectionMode.DOCUMENT
    private val perspectiveCorrector = PerspectiveCorrector()
    private val _state = MutableStateFlow(DetectionState())
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    fun loadImage(bitmap: Bitmap, detectionMode: DetectionMode = DetectionMode.DOCUMENT) {
        mode = detectionMode
        _state.value = DetectionState(originalBitmap = bitmap, isDetecting = true)
        detectDocument(bitmap)
    }

    fun detectDocument(bitmap: Bitmap) {
        detectionJob?.cancel()
        detectionJob = viewModelScope.launch {
            _state.value = _state.value.copy(isDetecting = true, errorMessage = null)
            val result = withContext(Dispatchers.Default) {
                detector.detect(bitmap, mode)
            }
            _state.value = _state.value.copy(
                isDetecting = false,
                corners = result.corners,
                confidence = result.confidence,
                detected = result.success,
                errorMessage = if (result.success) null else result.message
            )
        }
    }

    fun updateCorner(index: Int, newPoint: PointF) {
        if (index !in 0..3 || _state.value.corners.size != 4) return
        val updated = _state.value.corners.toMutableList()
        updated[index] = newPoint
        _state.value = _state.value.copy(corners = updated, detected = false, confidence = 0f, errorMessage = null)
    }

    fun setCorners(newCorners: List<PointF>) {
        if (newCorners.size != 4) return
        _state.value = _state.value.copy(corners = newCorners, detected = false, confidence = 0f, errorMessage = null)
    }

    fun reportInvalidCrop() {
        _state.value = _state.value.copy(errorMessage = "Keep four corners inside the image without crossing edges.")
    }

    fun snapToIdCardRatio() {
        _state.value = _state.value.copy(idRatio = !_state.value.idRatio)
    }

    fun snapToFullImage() {
        val bmp = _state.value.originalBitmap ?: return
        val fullCorners = detector.getFullImageCorners(bmp.width, bmp.height)
        _state.value = _state.value.copy(corners = fullCorners, confidence = 0f, detected = false)
    }

    fun resetToDefaultCorners() {
        val bmp = _state.value.originalBitmap ?: return
        val defaultCorners = detector.getFullImageCorners(bmp.width, bmp.height)
        _state.value = _state.value.copy(corners = defaultCorners, confidence = 0f, detected = false)
    }

    suspend fun getCroppedBitmap(): Bitmap? {
        if (_state.value.isCropping) return null
        val snapshot = _state.value
        val bmp = snapshot.originalBitmap ?: return null
        if (!CropGeometry.valid(snapshot.corners, bmp.width, bmp.height)) {
            _state.value = snapshot.copy(errorMessage = "Keep four corners inside the image without crossing edges.")
            return null
        }
        _state.value = snapshot.copy(isCropping = true, errorMessage = null)
        return try {
            withContext(Dispatchers.Default) {
                val cropped = perspectiveCorrector.correct(bmp, snapshot.corners)
                if (!snapshot.idRatio) cropped else {
                    val portrait = cropped.height > cropped.width
                    val ratio = if (portrait) 1f / DocumentDetector.ID_CARD_ASPECT_RATIO else DocumentDetector.ID_CARD_ASPECT_RATIO
                    val output = Bitmap.createScaledBitmap(cropped, cropped.width, (cropped.width / ratio).toInt().coerceAtLeast(1), true)
                    if (output !== cropped) cropped.recycle()
                    output
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(errorMessage = "Could not crop this image. Adjust corners and try again.")
            null
        } finally { _state.value = _state.value.copy(isCropping = false) }
    }
}
