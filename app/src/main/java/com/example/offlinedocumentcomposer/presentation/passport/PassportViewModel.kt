package com.example.offlinedocumentcomposer.presentation.passport

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinedocumentcomposer.domain.image.ImageUtils
import com.example.offlinedocumentcomposer.domain.passport.*
import com.example.offlinedocumentcomposer.presentation.tools.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

data class PassportHistorySnapshot(
    val photos: List<PassportPhotoItem>,
    val sheetConfig: PassportSheetConfig
)

data class PassportUiState(
    val photos: List<PassportPhotoItem> = emptyList(),
    val sheetConfig: PassportSheetConfig = PassportSheetConfig(),
    val activeCroppingItem: PassportPhotoItem? = null,
    val cropQueueTotal: Int = 0,
    val cropQueueIndex: Int = 0,
    val previewBitmap: Bitmap? = null,
    val isBusy: Boolean = false,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val lastExportedFile: File? = null,
    val layoutInfo: SheetLayoutResult? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class PassportViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PassportUiState())
    val uiState = _uiState.asStateFlow()

    private var renderJob: Job? = null
    private val undoStack = mutableListOf<PassportHistorySnapshot>()
    private val redoStack = mutableListOf<PassportHistorySnapshot>()
    private val maxHistorySize = 30

    private fun pushSnapshot() {
        val currentPhotos = _uiState.value.photos
        val currentConfig = _uiState.value.sheetConfig
        if (currentPhotos.isEmpty() && undoStack.isEmpty()) return

        val current = PassportHistorySnapshot(currentPhotos, currentConfig)
        if (undoStack.isNotEmpty() && undoStack.last() == current) {
            return
        }
        undoStack.add(current)
        if (undoStack.size > maxHistorySize) {
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
        val current = PassportHistorySnapshot(_uiState.value.photos, _uiState.value.sheetConfig)
        redoStack.add(current)

        val previous = undoStack.removeAt(undoStack.lastIndex)
        _uiState.value = _uiState.value.copy(
            photos = previous.photos,
            sheetConfig = previous.sheetConfig,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        refreshPreview()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = PassportHistorySnapshot(_uiState.value.photos, _uiState.value.sheetConfig)
        undoStack.add(current)

        val next = redoStack.removeAt(redoStack.lastIndex)
        _uiState.value = _uiState.value.copy(
            photos = next.photos,
            sheetConfig = next.sheetConfig,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        refreshPreview()
    }

    private val cropQueue = mutableListOf<PassportPhotoItem>()

    fun addPhotosFromUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Loading photos...")
            val newItems = mutableListOf<PassportPhotoItem>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val bmp = ImageUtils.decodeBitmapFromUri(context, uri, maxDimension = 1800)
                    if (bmp != null) {
                        newItems.add(
                            PassportPhotoItem(
                                originalBitmap = bmp,
                                croppedBitmap = bmp,
                                copies = 4
                            )
                        )
                    }
                }
            }

            if (newItems.isNotEmpty()) {
                pushSnapshot()
                val updatedList = _uiState.value.photos + newItems

                // Queue all selected photos for sequential cropping
                cropQueue.clear()
                cropQueue.addAll(newItems)
                val firstToCrop = cropQueue.removeAt(0)

                _uiState.value = _uiState.value.copy(
                    photos = updatedList,
                    isBusy = false,
                    activeCroppingItem = firstToCrop,
                    cropQueueTotal = newItems.size,
                    cropQueueIndex = 1
                )
                refreshPreview()
            } else {
                _uiState.value = _uiState.value.copy(isBusy = false, errorMessage = "Could not load selected images")
            }
        }
    }

    fun addPhotoBitmap(bitmap: Bitmap) {
        pushSnapshot()
        val item = PassportPhotoItem(
            originalBitmap = bitmap,
            croppedBitmap = bitmap,
            copies = 4
        )
        cropQueue.clear()
        _uiState.value = _uiState.value.copy(
            photos = _uiState.value.photos + item,
            activeCroppingItem = item,
            cropQueueTotal = 1,
            cropQueueIndex = 1
        )
        refreshPreview()
    }

    fun startCrop(item: PassportPhotoItem) {
        cropQueue.clear()
        _uiState.value = _uiState.value.copy(
            activeCroppingItem = item,
            cropQueueTotal = 1,
            cropQueueIndex = 1
        )
    }

    fun cancelCrop() {
        if (cropQueue.isNotEmpty()) {
            val nextItem = cropQueue.removeAt(0)
            _uiState.value = _uiState.value.copy(
                activeCroppingItem = nextItem,
                cropQueueIndex = _uiState.value.cropQueueIndex + 1
            )
        } else {
            _uiState.value = _uiState.value.copy(
                activeCroppingItem = null,
                cropQueueTotal = 0,
                cropQueueIndex = 0
            )
        }
    }

    fun cancelAllCrops() {
        cropQueue.clear()
        _uiState.value = _uiState.value.copy(
            activeCroppingItem = null,
            cropQueueTotal = 0,
            cropQueueIndex = 0
        )
    }

    fun applyCrop(
        croppedBitmap: Bitmap,
        standard: PassportPhotoStandard,
        customW: Float,
        customH: Float
    ) {
        val active = _uiState.value.activeCroppingItem ?: return
        pushSnapshot()
        val updatedPhotos = _uiState.value.photos.map {
            if (it.id == active.id) {
                it.copy(
                    croppedBitmap = croppedBitmap,
                    standard = standard,
                    customWidthMm = customW,
                    customHeightMm = customH
                )
            } else it
        }

        if (cropQueue.isNotEmpty()) {
            val nextItem = cropQueue.removeAt(0)
            _uiState.value = _uiState.value.copy(
                photos = updatedPhotos,
                activeCroppingItem = nextItem,
                cropQueueIndex = _uiState.value.cropQueueIndex + 1
            )
        } else {
            _uiState.value = _uiState.value.copy(
                photos = updatedPhotos,
                activeCroppingItem = null,
                cropQueueTotal = 0,
                cropQueueIndex = 0
            )
        }
        refreshPreview()
    }

    fun updateCopies(photoId: String, newCopies: Int) {
        val clamped = newCopies.coerceIn(1, 100)
        pushSnapshot()
        val updated = _uiState.value.photos.map {
            if (it.id == photoId) it.copy(copies = clamped) else it
        }
        _uiState.value = _uiState.value.copy(photos = updated)
        refreshPreview()
    }

    fun removePhoto(photoId: String) {
        pushSnapshot()
        val updated = _uiState.value.photos.filter { it.id != photoId }
        _uiState.value = _uiState.value.copy(photos = updated)
        refreshPreview()
    }

    fun updateConfig(config: PassportSheetConfig) {
        if (_uiState.value.sheetConfig == config) return
        pushSnapshot()
        _uiState.value = _uiState.value.copy(sheetConfig = config)
        refreshPreview()
    }

    fun fillSheetCopies() {
        val photos = _uiState.value.photos
        if (photos.isEmpty()) return
        pushSnapshot()
        val config = _uiState.value.sheetConfig
        val layout = PassportSheetRenderer.calculateLayout(photos, config)
        val capacity = max(1, layout.maxSlotsPerSheet)

        // Distribute capacity among photos
        val copiesPerPhoto = max(1, capacity / photos.size)
        val updated = photos.map { it.copy(copies = copiesPerPhoto) }
        _uiState.value = _uiState.value.copy(photos = updated)
        refreshPreview()
    }

    private fun refreshPreview() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val photos = _uiState.value.photos
            val config = _uiState.value.sheetConfig
            if (photos.isEmpty()) {
                _uiState.value.previewBitmap?.recycle()
                _uiState.value = _uiState.value.copy(previewBitmap = null, layoutInfo = null)
                return@launch
            }

            val layout = PassportSheetRenderer.calculateLayout(photos, config)
            val preview = withContext(Dispatchers.Default) {
                PassportSheetRenderer.renderSheetPageBitmap(photos, config, pageIndex = 0, dpi = 140)
            }

            _uiState.value.previewBitmap?.recycle()
            _uiState.value = _uiState.value.copy(
                previewBitmap = preview,
                layoutInfo = layout
            )
        }
    }

    fun exportJpeg(context: Context, onComplete: (File) -> Unit) {
        val photos = _uiState.value.photos
        if (photos.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Rendering 300 DPI photo sheet...")
            try {
                val config = _uiState.value.sheetConfig
                val exportsDir = DocumentFiles.exports(context)
                val outName = "PassportSheet_${config.sheetSize.name}_${System.currentTimeMillis()}.jpg"
                val outFile = File(exportsDir, outName)

                val result = PassportSheetRenderer.saveJpeg(context, photos, config, outFile, pageIndex = 0, quality = 95)
                _uiState.value = _uiState.value.copy(isBusy = false, lastExportedFile = result)
                onComplete(result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isBusy = false, errorMessage = "Failed to export JPEG: ${e.message}")
            }
        }
    }

    fun exportPdf(context: Context, onComplete: (File) -> Unit) {
        val photos = _uiState.value.photos
        if (photos.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Generating PDF document...")
            try {
                val config = _uiState.value.sheetConfig
                val exportsDir = DocumentFiles.exports(context)
                val outName = "PassportSheet_${config.sheetSize.name}_${System.currentTimeMillis()}.pdf"
                val outFile = File(exportsDir, outName)

                val result = PassportSheetRenderer.exportPdf(photos, config, outFile)
                _uiState.value = _uiState.value.copy(isBusy = false, lastExportedFile = result)
                onComplete(result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isBusy = false, errorMessage = "Failed to export PDF: ${e.message}")
            }
        }
    }

    fun printSheet(context: Context) {
        val photos = _uiState.value.photos
        if (photos.isEmpty()) return
        try {
            val config = _uiState.value.sheetConfig
            PassportSheetRenderer.printSheet(context, photos, config)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(errorMessage = "Printing error: ${e.message}")
        }
    }

    fun exportSinglePhoto(photo: PassportPhotoItem, context: Context, onComplete: (File) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isBusy = true, statusMessage = "Saving passport photo...")
            try {
                val exportsDir = DocumentFiles.exports(context)
                val outName = "PassportPhoto_${photo.standard.name}_${System.currentTimeMillis()}.jpg"
                val outFile = File(exportsDir, outName)

                val result = PassportSheetRenderer.saveSinglePhoto(context, photo.croppedBitmap, outFile)
                _uiState.value = _uiState.value.copy(isBusy = false, lastExportedFile = result)
                onComplete(result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isBusy = false, errorMessage = "Failed to save photo: ${e.message}")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.recycle()
        _uiState.value.photos.forEach {
            if (it.croppedBitmap !== it.originalBitmap) {
                it.croppedBitmap.recycle()
            }
            it.originalBitmap.recycle()
        }
    }
}
