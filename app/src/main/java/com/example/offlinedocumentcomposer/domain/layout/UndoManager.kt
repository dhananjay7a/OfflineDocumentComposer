package com.example.offlinedocumentcomposer.domain.layout

sealed class EditCommand {
    data class MoveImage(val layerId: String, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : EditCommand()
    data class ResizeImage(val layerId: String, val fromW: Float, val fromH: Float, val toW: Float, val toH: Float) : EditCommand()
    data class RotateImage(val layerId: String, val fromRotation: Float, val toRotation: Float) : EditCommand()
    data class AddImage(val layer: com.example.offlinedocumentcomposer.data.model.ImageLayer) : EditCommand()
    data class DeleteImage(val layer: com.example.offlinedocumentcomposer.data.model.ImageLayer) : EditCommand()
    data class AdjustImage(val layerId: String, val fromBrightness: Float, val fromContrast: Float, val fromSaturation: Float, val toBrightness: Float, val toContrast: Float, val toSaturation: Float, val fromSharpness: Float = 0f, val toSharpness: Float = 0f) : EditCommand()
    data class SetCrop(val layerId: String, val fromCrop: com.example.offlinedocumentcomposer.data.model.CropData?, val toCrop: com.example.offlinedocumentcomposer.data.model.CropData?) : EditCommand()
    data class SetPageSize(val fromSize: com.example.offlinedocumentcomposer.data.model.PageSize, val toSize: com.example.offlinedocumentcomposer.data.model.PageSize) : EditCommand()
}

class UndoManager(private val maxDepth: Int = 50) {
    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    fun execute(command: EditCommand) {
        undoStack.addLast(command)
        if (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): EditCommand? {
        val command = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(command)
        return command
    }

    fun redo(): EditCommand? {
        val command = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(command)
        return command
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
