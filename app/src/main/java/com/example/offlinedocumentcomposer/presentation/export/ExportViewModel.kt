package com.example.offlinedocumentcomposer.presentation.export

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinedocumentcomposer.data.model.PageModel
import com.example.offlinedocumentcomposer.domain.pdf.PdfRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ExportState(
    val isExporting: Boolean = false,
    val outputPath: String? = null,
    val outputUri: Uri? = null,
    val exportType: String? = null,
    val error: String? = null
)

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    private val pdfRenderer = PdfRenderer(application)
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private fun getDocumentsDir(): File {
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null), "ExportedDocuments")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getFileProviderUri(file: File): Uri {
        val app = getApplication<Application>()
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    fun exportPdfPages(pages: List<PageModel>) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true, error = null, outputPath = null)
            try {
                val outputFile = File(getDocumentsDir(), "Document_${System.currentTimeMillis()}.pdf")
                val result = pdfRenderer.renderPagesToPdf(pages, outputFile)
                if (result.isSuccess) {
                    val path = result.getOrNull() ?: outputFile.absolutePath
                    val uri = getFileProviderUri(outputFile)
                    _state.value = _state.value.copy(
                        isExporting = false,
                        outputPath = path,
                        outputUri = uri,
                        exportType = "PDF"
                    )
                } else {
                    _state.value = _state.value.copy(
                        isExporting = false,
                        error = result.exceptionOrNull()?.message ?: "PDF export failed"
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isExporting = false, error = e.message ?: "Export failed")
            }
        }
    }

    fun exportPdf(page: PageModel) {
        exportPdfPages(listOf(page))
    }

    fun exportImage(page: PageModel, format: String = "PNG") {
        viewModelScope.launch {
            _state.value = _state.value.copy(isExporting = true, error = null, outputPath = null)
            try {
                val ext = if (format == "JPG") "jpg" else "png"
                val outputFile = File(getDocumentsDir(), "Document_${System.currentTimeMillis()}.$ext")
                val compressFormat = if (format == "JPG") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                val result = pdfRenderer.exportToImage(page, outputFile, compressFormat, 100)
                if (result.isSuccess) {
                    val path = result.getOrNull() ?: outputFile.absolutePath
                    val uri = getFileProviderUri(outputFile)
                    _state.value = _state.value.copy(
                        isExporting = false,
                        outputPath = path,
                        outputUri = uri,
                        exportType = format
                    )
                } else {
                    _state.value = _state.value.copy(
                        isExporting = false,
                        error = result.exceptionOrNull()?.message ?: "$format export failed"
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isExporting = false, error = e.message ?: "Export failed")
            }
        }
    }

    fun shareFile(): Intent? {
        val uri = _state.value.outputUri ?: return null
        val type = if (_state.value.exportType == "PDF") "application/pdf" else "image/*"
        return Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun viewFile(): Intent? {
        val uri = _state.value.outputUri ?: return null
        val type = if (_state.value.exportType == "PDF") "application/pdf" else "image/*"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun printPagesDirectly(context: android.content.Context, pages: List<PageModel>) {
        pdfRenderer.printPages(context, pages)
    }

    fun printDirectly(context: android.content.Context, page: PageModel) {
        printPagesDirectly(context, listOf(page))
    }
}
