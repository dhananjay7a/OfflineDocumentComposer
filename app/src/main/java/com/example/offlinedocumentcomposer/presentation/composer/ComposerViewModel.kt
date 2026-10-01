package com.example.offlinedocumentcomposer.presentation.composer

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.example.offlinedocumentcomposer.data.model.ImageLayer
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.data.model.PageSize
import com.example.offlinedocumentcomposer.domain.layout.EditCommand
import com.example.offlinedocumentcomposer.domain.layout.SideBySideLayout
import com.example.offlinedocumentcomposer.domain.layout.UndoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ComposerState(
    val pages: List<PageModel> = listOf(PageModel()),
    val currentPageIndex: Int = 0,
    val selectedLayerId: String? = null,
    val zoomLevel: Float = 1f,
    val showCutGuides: Boolean = true,
    val isLoading: Boolean = false,
    val revision: Int = 0
) {
    val pageModel: PageModel
        get() = pages.getOrElse(currentPageIndex) { pages.firstOrNull() ?: PageModel() }

    val imageLayers: List<ImageLayer>
        get() = pageModel.imageLayers

    val totalPages: Int
        get() = pages.size

    val currentPageNumber: Int
        get() = currentPageIndex + 1
}

class ComposerViewModel : ViewModel() {
    private val sideBySideLayout = SideBySideLayout()
    private val undoManager = UndoManager()
    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    /**
     * Lightweight recomposition trigger — does NOT copy the layer list.
     * Since ImageLayer fields (x, y, width, height) are mutable vars,
     * we mutate in-place and only bump the revision counter so Compose redraws.
     * This avoids O(n) heap allocation on every drag frame, preventing lag.
     */
    private fun notifyLayersChanged() {
        _state.value = _state.value.copy(revision = _state.value.revision + 1)
    }

    fun setLayers(layers: List<ImageLayer>) {
        val currPage = _state.value.pageModel
        currPage.imageLayers.clear()
        currPage.imageLayers.addAll(layers)
        _state.value = _state.value.copy(revision = _state.value.revision + 1)
    }

    fun addImageLayer(layer: ImageLayer) {
        val currLayers = _state.value.pageModel.imageLayers
        currLayers.add(layer)
        layer.zIndex = currLayers.size - 1
        _state.value = _state.value.copy(revision = _state.value.revision + 1)
        undoManager.execute(EditCommand.AddImage(layer))
    }

    fun removeImageLayer(layerId: String) {
        val currLayers = _state.value.pageModel.imageLayers
        val layer = currLayers.find { it.id == layerId } ?: return
        currLayers.removeAll { it.id == layerId }
        _state.value = _state.value.copy(
            selectedLayerId = if (_state.value.selectedLayerId == layerId) null else _state.value.selectedLayerId,
            revision = _state.value.revision + 1
        )
        undoManager.execute(EditCommand.DeleteImage(layer))
    }

    fun duplicateLayer(layerId: String) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        val copy = layer.duplicate()
        copy.x = layer.x + 25f
        copy.y = layer.y + 25f
        addImageLayer(copy)
    }

    fun duplicateFor2Copies() {
        val current = _state.value.imageLayers.toList()
        if (current.isEmpty()) return
        val offset = SideBySideLayout.ENLARGED_CARD_HEIGHT_PX + 25f
        val newCopies = current.map { layer ->
            val dup = layer.duplicate()
            dup.y = layer.y + offset
            dup
        }
        val combined = current + newCopies
        setLayers(combined)
    }

    fun toggleCutGuides() {
        _state.value = _state.value.copy(showCutGuides = !_state.value.showCutGuides)
    }

    /**
     * Interactive position update during drag - smooth, no undo flood.
     * Mutates layer in-place and bumps revision for a zero-allocation recompose.
     */
    fun updateLayerPositionInteractive(layerId: String, newX: Float, newY: Float) {
        val layer = _state.value.pageModel.imageLayers.find { it.id == layerId } ?: return
        layer.x = newX
        layer.y = newY
        notifyLayersChanged()
    }

    /**
     * Called on drag completion to commit a single undo command.
     */
    fun commitMove(layerId: String, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (fromX != toX || fromY != toY) {
            undoManager.execute(EditCommand.MoveImage(layerId, fromX, fromY, toX, toY))
        }
    }

    fun moveLayer(layerId: String, newX: Float, newY: Float) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        undoManager.execute(EditCommand.MoveImage(layerId, layer.x, layer.y, newX, newY))
        layer.x = newX
        layer.y = newY
        notifyLayersChanged()
    }

    /**
     * Interactive resize during drag/pinch - zero-allocation, in-place mutation.
     */
    fun updateLayerSizeInteractive(layerId: String, newWidth: Float, newHeight: Float) {
        val layer = _state.value.pageModel.imageLayers.find { it.id == layerId } ?: return
        layer.width = newWidth.coerceAtLeast(40f)
        layer.height = newHeight.coerceAtLeast(30f)
        notifyLayersChanged()
    }

    fun commitResize(layerId: String, fromW: Float, fromH: Float, toW: Float, toH: Float) {
        if (fromW != toW || fromH != toH) {
            undoManager.execute(EditCommand.ResizeImage(layerId, fromW, fromH, toW, toH))
        }
    }

    fun resizeLayer(layerId: String, newWidth: Float, newHeight: Float) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        undoManager.execute(EditCommand.ResizeImage(layerId, layer.width, layer.height, newWidth, newHeight))
        layer.width = newWidth.coerceAtLeast(40f)
        layer.height = newHeight.coerceAtLeast(30f)
        notifyLayersChanged()
    }

    /**
     * Snap selected layer to true physical ID Card size (85.6mm x 54.0mm).
     */
    fun applyTrueIdCardSize(layerId: String) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        resizeLayer(
            layerId,
            SideBySideLayout.TRUE_CARD_WIDTH_PX,
            SideBySideLayout.TRUE_CARD_HEIGHT_PX
        )
    }

    /**
     * Snap selected layer to enlarged ID Card size (89.0mm x 56.1mm).
     */
    fun applyEnlargedIdCardSize(layerId: String) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        resizeLayer(
            layerId,
            SideBySideLayout.ENLARGED_CARD_WIDTH_PX,
            SideBySideLayout.ENLARGED_CARD_HEIGHT_PX
        )
    }

    fun rotateLayer(layerId: String, deltaDegrees: Float = 90f) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        val newRotation = (layer.rotation + deltaDegrees) % 360f
        undoManager.execute(EditCommand.RotateImage(layerId, layer.rotation, newRotation))
        layer.rotation = newRotation
        notifyLayersChanged()
    }

    fun updateLayerBitmap(layerId: String, bitmap: Bitmap) {
        val layer = _state.value.imageLayers.find { it.id == layerId } ?: return
        layer.workingBitmap = bitmap
        notifyLayersChanged()
    }

    fun selectLayer(layerId: String?) {
        _state.value = _state.value.copy(selectedLayerId = layerId)
    }

    fun arrangeSideBySide() {
        val images = _state.value.imageLayers
        if (images.isNotEmpty()) {
            sideBySideLayout.arrangeIdCardSideBySideTop(images, _state.value.pageModel)
            notifyLayersChanged()
        }
    }

    fun arrangeIdCardsSideBySide() {
        arrangeSideBySide()
    }

    fun arrangeIdCardsSideBySideCenter() {
        val images = _state.value.imageLayers
        if (images.isNotEmpty()) {
            sideBySideLayout.arrangeIdCardSideBySideCenter(images, _state.value.pageModel)
            notifyLayersChanged()
        }
    }

    fun arrangeVertical() {
        val images = _state.value.imageLayers
        if (images.isNotEmpty()) {
            sideBySideLayout.arrangeIdCardsStacked(images, _state.value.pageModel)
            notifyLayersChanged()
        }
    }

    fun alignCenter() {
        sideBySideLayout.alignCenter(_state.value.imageLayers, _state.value.pageModel)
        notifyLayersChanged()
    }

    fun togglePageOrientation() {
        val currPage = _state.value.pageModel
        val currentSize = currPage.pageSize
        val newSize = if (currentSize == PageSize.A4_PORTRAIT) PageSize.A4_LANDSCAPE else PageSize.A4_PORTRAIT
        val newPage = currPage.copy(pageSize = newSize)
        val updatedPages = _state.value.pages.toMutableList()
        updatedPages[_state.value.currentPageIndex] = newPage
        _state.value = _state.value.copy(pages = updatedPages, revision = _state.value.revision + 1)
    }

    fun undo() {
        val cmd = undoManager.undo() ?: return
        applyUndo(cmd)
        notifyLayersChanged()
    }

    fun redo() {
        val cmd = undoManager.redo() ?: return
        applyRedo(cmd)
        notifyLayersChanged()
    }

    private fun applyUndo(cmd: EditCommand) {
        when (cmd) {
            is EditCommand.MoveImage -> {
                val layer = _state.value.imageLayers.find { it.id == cmd.layerId }
                layer?.let { it.x = cmd.fromX; it.y = cmd.fromY }
            }
            is EditCommand.ResizeImage -> {
                val layer = _state.value.imageLayers.find { it.id == cmd.layerId }
                layer?.let { it.width = cmd.fromW; it.height = cmd.fromH }
            }
            is EditCommand.RotateImage -> {
                val layer = _state.value.imageLayers.find { it.id == cmd.layerId }
                layer?.let { it.rotation = cmd.fromRotation }
            }
            is EditCommand.AddImage -> {
                val updatedLayers = _state.value.imageLayers.filter { it.id != cmd.layer.id }
                _state.value = _state.value.copy(revision = _state.value.revision + 1)
            }
            is EditCommand.DeleteImage -> {
                val updatedLayers = _state.value.imageLayers.toMutableList()
                updatedLayers.add(cmd.layer)
                _state.value = _state.value.copy(revision = _state.value.revision + 1)
            }
            else -> {}
        }
    }

    private fun applyRedo(cmd: EditCommand) {
        when (cmd) {
            is EditCommand.MoveImage -> {
                val layer = _state.value.imageLayers.find { it.id == cmd.layerId }
                layer?.let { it.x = cmd.toX; it.y = cmd.toY }
            }
            is EditCommand.ResizeImage -> {
                val layer = _state.value.imageLayers.find { it.id == cmd.layerId }
                layer?.let { it.width = cmd.toW; it.height = cmd.toH }
            }
            is EditCommand.RotateImage -> {
                val layer = _state.value.imageLayers.find { it.id == cmd.layerId }
                layer?.let { it.rotation = cmd.toRotation }
            }
            else -> {}
        }
    }

    fun canUndo() = undoManager.canUndo()
    fun canRedo() = undoManager.canRedo()

    fun printDirectly(context: android.content.Context) {
        val pdfRenderer = com.example.offlinedocumentcomposer.domain.pdf.PdfRenderer(context)
        pdfRenderer.printPages(context, _state.value.pages)
    }

    // ================= NEW CANVAS & MULTI-PAGE MANAGEMENT =================

    fun createNewCanvas(pageSize: PageSize = PageSize.A4_PORTRAIT) {
        val newPage = PageModel(pageSize = pageSize)
        _state.value = _state.value.copy(
            pages = listOf(newPage),
            currentPageIndex = 0,
            selectedLayerId = null,
            revision = _state.value.revision + 1
        )
        undoManager.clear()
    }

    fun addNewPage(pageSize: PageSize = PageSize.A4_PORTRAIT) {
        val newPage = PageModel(pageSize = pageSize)
        val updatedPages = _state.value.pages + newPage
        _state.value = _state.value.copy(
            pages = updatedPages,
            currentPageIndex = updatedPages.size - 1,
            selectedLayerId = null,
            revision = _state.value.revision + 1
        )
    }

    fun switchPage(index: Int) {
        if (_state.value.pages.isEmpty()) return
        val target = index.coerceIn(0, _state.value.pages.size - 1)
        _state.value = _state.value.copy(
            currentPageIndex = target,
            selectedLayerId = null,
            revision = _state.value.revision + 1
        )
    }

    fun deleteCurrentPage() {
        if (_state.value.pages.size <= 1) {
            clearCurrentPage()
            return
        }
        val currIdx = _state.value.currentPageIndex
        val updatedPages = _state.value.pages.filterIndexed { i, _ -> i != currIdx }
        val newIdx = currIdx.coerceAtMost(updatedPages.size - 1)
        _state.value = _state.value.copy(
            pages = updatedPages,
            currentPageIndex = newIdx,
            selectedLayerId = null,
            revision = _state.value.revision + 1
        )
    }

    fun duplicateCurrentPage() {
        val curr = _state.value.pageModel
        val newPage = curr.copyPage()
        val updatedPages = _state.value.pages.toMutableList()
        val nextIdx = _state.value.currentPageIndex + 1
        updatedPages.add(nextIdx, newPage)
        _state.value = _state.value.copy(
            pages = updatedPages,
            currentPageIndex = nextIdx,
            selectedLayerId = null,
            revision = _state.value.revision + 1
        )
    }

    fun clearCurrentPage() {
        _state.value.pageModel.imageLayers.clear()
        _state.value = _state.value.copy(
            selectedLayerId = null,
            revision = _state.value.revision + 1
        )
    }

    fun resetToIdCardTemplate() {
        createNewCanvas()
        val w = SideBySideLayout.ENLARGED_CARD_WIDTH_PX
        val h = SideBySideLayout.ENLARGED_CARD_HEIGHT_PX
        val page = _state.value.pageModel
        val gap = 15f * SideBySideLayout.MM_TO_PX
        val totalW = 2 * w + gap
        val startX = (page.getPageWidthPx() - totalW) / 2f
        val startY = page.getTopMarginPx()

        val bmpFront = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.LTGRAY
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(2f, 2f, w - 2f, h - 2f, paint)
        }
        val bmpBack = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.LTGRAY
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(2f, 2f, w - 2f, h - 2f, paint)
        }
        val layerFront = ImageLayer(
            workingBitmap = bmpFront,
            x = startX,
            y = startY,
            width = w,
            height = h,
            name = "Front Card Slot"
        )
        val layerBack = ImageLayer(
            workingBitmap = bmpBack,
            x = startX + w + gap,
            y = startY,
            width = w,
            height = h,
            name = "Back Card Slot"
        )
        setLayers(listOf(layerFront, layerBack))
    }

    fun importFileToCanvas(file: java.io.File, context: android.content.Context, asNewCanvas: Boolean = false): Boolean {
        return try {
            val ext = file.extension.lowercase(java.util.Locale.ROOT)
            val bmp: Bitmap? = if (ext == "pdf") {
                val renderer = com.example.offlinedocumentcomposer.domain.pdf.PdfRenderer(context)
                renderer.renderPdfFirstPage(file)
            } else {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            }
            if (bmp != null) {
                if (asNewCanvas) {
                    createNewCanvas()
                }
                val page = _state.value.pageModel
                val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                val targetW = (page.getPageWidthPx() * 0.7f).coerceAtMost(bmp.width.toFloat())
                val targetH = targetW / aspect
                val posX = (page.getPageWidthPx() - targetW) / 2f
                val posY = page.getTopMarginPx() + 20f

                val layer = ImageLayer(
                    workingBitmap = bmp,
                    x = posX,
                    y = posY,
                    width = targetW,
                    height = targetH,
                    name = file.nameWithoutExtension
                )
                addImageLayer(layer)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
