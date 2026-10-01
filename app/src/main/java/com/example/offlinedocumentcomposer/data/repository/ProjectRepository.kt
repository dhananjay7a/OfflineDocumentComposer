package com.example.offlinedocumentcomposer.data.repository

import android.content.Context
import com.example.offlinedocumentcomposer.data.model.ProjectModel
import com.example.offlinedocumentcomposer.data.model.ImageLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ProjectRepository(private val context: Context) {

    suspend fun saveProject(project: ProjectModel): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(getProjectsDir(), project.id)
            if (!dir.exists()) dir.mkdirs()
            Result.success(dir.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadProject(projectId: String): Result<ProjectModel> = withContext(Dispatchers.IO) {
        try {
            val dir = File(getProjectsDir(), projectId)
            if (!dir.exists()) return@withContext Result.failure(Exception("Project not found"))
            Result.success(ProjectModel(id = projectId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = File(getProjectsDir(), projectId)
            deleteRecursively(dir)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveImage(layer: ImageLayer): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(getTempDir(), layer.id)
            if (!dir.exists()) dir.mkdirs()
            Result.success(dir.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getProjectsDir(): File {
        return File(context.getExternalFilesDir(null), "projects")
    }

    private fun getTempDir(): File {
        return File(context.getExternalFilesDir(null), "temp")
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
