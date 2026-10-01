package com.example.offlinedocumentcomposer.data.model

import java.util.UUID

data class ProjectModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Project ${UUID.randomUUID().toString().take(8)}",
    val dateCreated: Long = System.currentTimeMillis(),
    val pageModel: PageModel = PageModel(),
    val imageLayers: MutableList<ImageLayer> = mutableListOf(),
    val lastModified: Long = System.currentTimeMillis()
) {
    fun addImageLayer(layer: ImageLayer) {
        imageLayers.add(layer)
        layer.zIndex = imageLayers.size - 1
    }

    fun removeImageLayer(layerId: String) {
        imageLayers.removeAll { it.id == layerId }
    }

    fun getImageLayer(id: String): ImageLayer? = imageLayers.find { it.id == id }

    fun duplicateImageLayer(layerId: String): ImageLayer? {
        val layer = getImageLayer(layerId) ?: return null
        val copy = layer.copy()
        copy.x = layer.x + 20f
        copy.y = layer.y + 20f
        addImageLayer(copy)
        return copy
    }
}
