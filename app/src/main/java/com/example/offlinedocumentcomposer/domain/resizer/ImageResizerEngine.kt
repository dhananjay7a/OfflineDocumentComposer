package com.example.offlinedocumentcomposer.domain.resizer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.example.offlinedocumentcomposer.presentation.tools.DocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageResizerEngine {

    private const val TAG = "ImageResizerEngine"

    /**
     * Resolves metadata of a selected image from its Uri.
     */
    suspend fun resolveImageMeta(context: Context, uri: Uri): ImageSourceMeta = withContext(Dispatchers.IO) {
        var fileName = "image_${System.currentTimeMillis()}.jpg"
        var fileSize = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        fileName = cursor.getString(nameIdx) ?: fileName
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Uri metadata: ${e.message}")
        }

        if (fileSize == 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    fileSize = pfd.statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get statSize: ${e.message}")
            }
        }

        // Determine dimensions
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode bounds: ${e.message}")
        }

        ImageSourceMeta(
            uri = uri,
            originalWidth = max(1, options.outWidth),
            originalHeight = max(1, options.outHeight),
            fileSizeBytes = fileSize,
            fileName = fileName
        )
    }

    /**
     * Resizes a Bitmap to exact width and height with high-quality filtering.
     */
    fun resizeBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val w = max(1, targetWidth)
        val h = max(1, targetHeight)

        if (source.width == w && source.height == h) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }

        val srcRect = android.graphics.Rect(0, 0, source.width, source.height)
        val dstRect = android.graphics.Rect(0, 0, w, h)
        canvas.drawBitmap(source, srcRect, dstRect, paint)
        return result
    }

    /**
     * Compresses bitmap to byte array according to format, quality, and optional target KB constraint.
     */
    suspend fun compressBitmapWithTargetKb(
        bitmap: Bitmap,
        format: OutputFormat,
        initialQuality: Int,
        targetMaxKb: Int?
    ): Pair<ByteArray, Bitmap> = withContext(Dispatchers.Default) {
        val compressFormat = when (format) {
            OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            OutputFormat.PNG -> Bitmap.CompressFormat.PNG
            OutputFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
        }

        // Case 1: No target KB constraint, standard compression
        if (targetMaxKb == null || targetMaxKb <= 0) {
            val bos = ByteArrayOutputStream()
            bitmap.compress(compressFormat, initialQuality.coerceIn(5, 100), bos)
            return@withContext Pair(bos.toByteArray(), bitmap)
        }

        // Case 2: Target KB constraint is active
        val maxBytes = targetMaxKb * 1024L
        var currentQuality = initialQuality.coerceIn(10, 100)
        var workingBitmap = bitmap
        var ownsWorkingBitmap = false

        var bos = ByteArrayOutputStream()
        workingBitmap.compress(compressFormat, currentQuality, bos)

        // Iteration A: Reduce quality if possible (lossy formats)
        if (format != OutputFormat.PNG) {
            while (bos.size() > maxBytes && currentQuality > 15) {
                currentQuality = max(10, currentQuality - 10)
                bos = ByteArrayOutputStream()
                workingBitmap.compress(compressFormat, currentQuality, bos)
            }
        }

        // Iteration B: If still exceeds maxBytes (or PNG), scale dimensions down in 10% steps
        var scaleAttempt = 0
        while (bos.size() > maxBytes && scaleAttempt < 10) {
            scaleAttempt++
            val nextW = max(10, (workingBitmap.width * 0.90f).roundToInt())
            val nextH = max(10, (workingBitmap.height * 0.90f).roundToInt())
            val downscaled = resizeBitmap(workingBitmap, nextW, nextH)
            if (ownsWorkingBitmap) {
                workingBitmap.recycle()
            }
            workingBitmap = downscaled
            ownsWorkingBitmap = true

            bos = ByteArrayOutputStream()
            workingBitmap.compress(compressFormat, currentQuality, bos)
        }

        Pair(bos.toByteArray(), workingBitmap)
    }

    /**
     * Estimates output file size based on dimensions, format, and quality.
     */
    fun estimateOutputBytes(
        origW: Int,
        origH: Int,
        targetW: Int,
        targetH: Int,
        origSizeBytes: Long,
        format: OutputFormat,
        quality: Int,
        targetMaxKb: Int?
    ): Long {
        if (targetMaxKb != null && targetMaxKb > 0) {
            val targetBytes = targetMaxKb * 1024L
            return if (origSizeBytes > 0) min(origSizeBytes, targetBytes) else targetBytes
        }

        val pixelRatio = (targetW.toDouble() * targetH) / max(1.0, origW.toDouble() * origH)

        return when (format) {
            OutputFormat.JPEG -> {
                val qualityFactor = (quality.toDouble() / 100.0) * 0.9
                val base = if (origSizeBytes > 0) origSizeBytes * pixelRatio * qualityFactor else (targetW * targetH * 0.25 * qualityFactor)
                base.toLong().coerceAtLeast(1024L)
            }
            OutputFormat.PNG -> {
                val base = if (origSizeBytes > 0) origSizeBytes * pixelRatio * 1.2 else (targetW * targetH * 1.5)
                base.toLong().coerceAtLeast(1024L)
            }
            OutputFormat.WEBP -> {
                val qualityFactor = (quality.toDouble() / 100.0) * 0.75
                val base = if (origSizeBytes > 0) origSizeBytes * pixelRatio * qualityFactor else (targetW * targetH * 0.18 * qualityFactor)
                base.toLong().coerceAtLeast(1024L)
            }
        }
    }

    /**
     * Saves resized image to private export cache and device MediaStore (Pictures/ResizedImages).
     */
    suspend fun saveResizedImage(
        context: Context,
        sourceBitmap: Bitmap,
        config: ResizeConfiguration,
        originalFileName: String
    ): File = withContext(Dispatchers.IO) {
        val (targetW, targetH) = config.resolvePixelDimensions(sourceBitmap.width, sourceBitmap.height)
        val resized = resizeBitmap(sourceBitmap, targetW, targetH)

        val (compressedBytes, finalBitmap) = compressBitmapWithTargetKb(
            resized,
            config.format,
            config.quality,
            config.targetMaxKb
        )

        val baseName = originalFileName.substringBeforeLast(".")
        val outFileName = "${baseName}_resized_${finalBitmap.width}x${finalBitmap.height}.${config.format.extension}"

        val exportsDir = DocumentFiles.exports(context)
        exportsDir.mkdirs()
        val appExportFile = File(exportsDir, outFileName)
        FileOutputStream(appExportFile).use { fos ->
            fos.write(compressedBytes)
        }

        // 1. Insert into device MediaStore (Pictures/ResizedImages) so it appears in Gallery
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, outFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, config.format.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ResizedImages")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(compressedBytes)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert warning: ${e.message}")
        }

        // 2. Also write to public Pictures/ResizedImages folder directly
        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ResizedImages")
            publicDir.mkdirs()
            val publicFile = File(publicDir, outFileName)
            FileOutputStream(publicFile).use { fos ->
                fos.write(compressedBytes)
            }
            MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath, appExportFile.absolutePath), arrayOf(config.format.mimeType), null)
        } catch (e: Exception) {
            Log.w(TAG, "Public directory write warning: ${e.message}")
        }

        if (finalBitmap !== resized && finalBitmap !== sourceBitmap) {
            finalBitmap.recycle()
        }
        if (resized !== sourceBitmap) {
            resized.recycle()
        }

        appExportFile
    }

    /**
     * Launches Android system Print Dialog for the resized image.
     */
    fun printImage(context: Context, bitmap: Bitmap, jobName: String = "Resized_Image") {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: throw IllegalStateException("Print service is unavailable on this device")

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

                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()

                callback?.onLayoutFinished(info, newAttributes != oldAttributes)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                if (destination == null) {
                    callback?.onWriteFailed("Destination is null")
                    return
                }

                try {
                    val pdfDocument = PdfDocument()
                    val pageW = 595
                    val pageH = 842
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

                    canvas.drawColor(Color.WHITE)

                    val margin = 36f
                    val availW = pageW - 2 * margin
                    val availH = pageH - 2 * margin

                    val imgW = bitmap.width.toFloat()
                    val imgH = bitmap.height.toFloat()
                    val scale = min(availW / imgW, availH / imgH)

                    val drawW = imgW * scale
                    val drawH = imgH * scale
                    val left = margin + (availW - drawW) / 2f
                    val top = margin + (availH - drawH) / 2f

                    val dstRect = RectF(left, top, left + drawW, top + drawH)
                    val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        isDither = true
                    }

                    canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
                    pdfDocument.finishPage(page)

                    FileOutputStream(destination.fileDescriptor).use { out ->
                        pdfDocument.writeTo(out)
                    }
                    pdfDocument.close()
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }

        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }
}
