package com.example.offlinedocumentcomposer.presentation.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileInputStream
import java.io.FileOutputStream

object DocumentFiles {
    fun exports(context: Context) = File(context.getExternalFilesDir(null), "ExportedDocuments").apply { mkdirs() }
    fun output(context: Context, name: String): File {
        val safe = name.trim().removeSuffix(".pdf").replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").take(80).ifBlank { "Document" }
        return File(exports(context), "${safe}_${System.currentTimeMillis()}.pdf")
    }
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    fun open(context: Context, file: File) {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(context,file), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun share(context: Context, file: File) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM,uri(context,file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))
    }
    fun print(context: Context, file: File) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: throw IllegalStateException("Print service unavailable on this device")
        val jobName = file.nameWithoutExtension.ifBlank { "Document" }
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(file.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback?.onLayoutFinished(info, newAttributes != oldAttributes)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                if (destination == null) {
                    callback?.onWriteFailed("Output destination is null")
                    return
                }
                try {
                    FileInputStream(file).use { input ->
                        FileOutputStream(destination.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        printManager.print(jobName, adapter, attributes)
    }
    suspend fun saveAs(context: Context, file: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Cannot write to the selected location")
    }
}
