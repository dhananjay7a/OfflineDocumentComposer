package com.example.offlinedocumentcomposer.domain.passport

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Position and dimensions of a single passport photo slot on the sheet in millimeters.
 */
data class PassportSlot(
    val photoItem: PassportPhotoItem,
    val copyIndex: Int,
    val xMm: Float,
    val yMm: Float,
    val widthMm: Float,
    val heightMm: Float,
    val pageIndex: Int = 0
)

data class SheetLayoutResult(
    val slots: List<PassportSlot>,
    val totalSheets: Int,
    val maxSlotsPerSheet: Int,
    val columns: Int,
    val rows: Int
)

object PassportSheetRenderer {

    // 1 mm in typographical points (72 points / 25.4 mm)
    const val POINTS_PER_MM = 72f / 25.4f

    /**
     * Calculates the layout grid of photos across one or more sheets.
     */
    fun calculateLayout(
        items: List<PassportPhotoItem>,
        config: PassportSheetConfig
    ): SheetLayoutResult {
        if (items.isEmpty()) {
            return SheetLayoutResult(emptyList(), 0, 0, 0, 0)
        }

        val sheetWMm = config.effectiveSheetWidthMm
        val sheetHMm = config.effectiveSheetHeightMm
        val margin = config.marginMm
        val gap = config.gapMm

        val availableW = max(1f, sheetWMm - 2 * margin)
        val availableH = max(1f, sheetHMm - 2 * margin)

        // Expand all photos based on their requested copy count
        val allInstances = mutableListOf<PassportPhotoItem>()
        for (item in items) {
            val count = max(1, item.copies)
            repeat(count) {
                allInstances.add(item)
            }
        }

        if (allInstances.isEmpty()) {
            return SheetLayoutResult(emptyList(), 0, 0, 0, 0)
        }

        val sampleItem = allInstances.first()
        var slotWMm = sampleItem.effectiveWidthMm
        var slotHMm = sampleItem.effectiveHeightMm

        // Determine number of columns:
        // If customColumns specified, use that;
        // Else for A4 Portrait, default to 6 columns as requested!
        val cols = config.customColumns ?: if (config.sheetSize == PassportSheetSize.A4 && config.orientation == SheetOrientation.PORTRAIT) {
            6
        } else {
            max(1, ((availableW + gap) / (slotWMm + gap)).toInt())
        }

        // When columns are custom-set or 6 columns on A4 portrait, fit photo width across available width
        if (config.customColumns != null || (config.sheetSize == PassportSheetSize.A4 && config.orientation == SheetOrientation.PORTRAIT)) {
            val totalGaps = (cols - 1) * gap
            val fittedW = max(10f, (availableW - totalGaps) / cols)
            val aspect = sampleItem.effectiveAspectRatio
            slotWMm = fittedW
            slotHMm = fittedW / aspect
        }

        val rows = max(1, ((availableH + gap) / (slotHMm + gap)).toInt())
        val slotsPerSheet = max(1, cols * rows)

        val totalGridW = cols * slotWMm + (cols - 1) * gap
        val totalGridH = rows * slotHMm + (rows - 1) * gap

        // Strictly start at marginMm! No unwanted extra offset when centerGrid is false
        val startX = if (config.centerGrid) margin + max(0f, (availableW - totalGridW) / 2f) else margin
        val startY = if (config.centerGrid) margin + max(0f, (availableH - totalGridH) / 2f) else margin

        val slots = mutableListOf<PassportSlot>()
        var currentSheet = 0
        var currentSlotInSheet = 0

        for ((idx, item) in allInstances.withIndex()) {
            if (currentSlotInSheet >= slotsPerSheet) {
                currentSheet++
                currentSlotInSheet = 0
            }

            val r = currentSlotInSheet / cols
            val c = currentSlotInSheet % cols

            val x = startX + c * (slotWMm + gap)
            val y = startY + r * (slotHMm + gap)

            slots.add(
                PassportSlot(
                    photoItem = item,
                    copyIndex = idx,
                    xMm = x,
                    yMm = y,
                    widthMm = slotWMm,
                    heightMm = slotHMm,
                    pageIndex = currentSheet
                )
            )

            currentSlotInSheet++
        }

        val totalSheets = if (slots.isEmpty()) 0 else (currentSheet + 1)
        return SheetLayoutResult(slots, totalSheets, slotsPerSheet, cols, rows)
    }

    /**
     * Renders a single sheet page into a Bitmap at the given DPI (e.g. 300 for studio printing).
     */
    fun renderSheetPageBitmap(
        items: List<PassportPhotoItem>,
        config: PassportSheetConfig,
        pageIndex: Int = 0,
        dpi: Int = 300
    ): Bitmap {
        val layout = calculateLayout(items, config)
        val slotsOnPage = layout.slots.filter { it.pageIndex == pageIndex }

        val sheetWMm = config.effectiveSheetWidthMm
        val sheetHMm = config.effectiveSheetHeightMm

        val pixelW = (sheetWMm / 25.4f * dpi).toInt().coerceAtLeast(100)
        val pixelH = (sheetHMm / 25.4f * dpi).toInt().coerceAtLeast(100)

        val bitmap = Bitmap.createBitmap(pixelW, pixelH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Fill clean white paper background
        canvas.drawColor(Color.WHITE)

        val mmToPx = dpi / 25.4f

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = config.cuttingBorderColorHex.toInt() // Solid Black
            style = Paint.Style.STROKE
            strokeWidth = config.cuttingBorderWidthPx // 5px
        }

        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }

        // 2. Draw each photo slot
        for (slot in slotsOnPage) {
            val left = slot.xMm * mmToPx
            val top = slot.yMm * mmToPx
            val right = (slot.xMm + slot.widthMm) * mmToPx
            val bottom = (slot.yMm + slot.heightMm) * mmToPx
            val dstRect = RectF(left, top, right, bottom)

            val srcBitmap = slot.photoItem.croppedBitmap
            val srcRect = Rect(0, 0, srcBitmap.width, srcBitmap.height)
            canvas.drawBitmap(srcBitmap, srcRect, dstRect, imagePaint)

            // 3. Draw cutting border line around photo (5px black line)
            if (config.showCuttingBorder) {
                canvas.drawRect(dstRect, borderPaint)
            }
        }

        return bitmap
    }

    /**
     * Exports all sheets to a multi-page PDF with exact physical dimensions in points.
     */
    suspend fun exportPdf(
        items: List<PassportPhotoItem>,
        config: PassportSheetConfig,
        outputFile: File
    ): File = withContext(Dispatchers.IO) {
        val layout = calculateLayout(items, config)
        val totalSheets = max(1, layout.totalSheets)

        val sheetWPoints = (config.effectiveSheetWidthMm * POINTS_PER_MM).toInt().coerceAtLeast(100)
        val sheetHPoints = (config.effectiveSheetHeightMm * POINTS_PER_MM).toInt().coerceAtLeast(100)

        val pdfDocument = PdfDocument()

        try {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = config.cuttingBorderColorHex.toInt()
                style = Paint.Style.STROKE
                strokeWidth = max(0.5f, config.cuttingBorderWidthPt)
            }

            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }

            for (pageIdx in 0 until totalSheets) {
                val pageInfo = PdfDocument.PageInfo.Builder(sheetWPoints, sheetHPoints, pageIdx + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Fill white background
                canvas.drawColor(Color.WHITE)

                val slotsOnPage = layout.slots.filter { it.pageIndex == pageIdx }

                for (slot in slotsOnPage) {
                    val left = slot.xMm * POINTS_PER_MM
                    val top = slot.yMm * POINTS_PER_MM
                    val right = (slot.xMm + slot.widthMm) * POINTS_PER_MM
                    val bottom = (slot.yMm + slot.heightMm) * POINTS_PER_MM
                    val dstRect = RectF(left, top, right, bottom)

                    val srcBitmap = slot.photoItem.croppedBitmap
                    val srcRect = Rect(0, 0, srcBitmap.width, srcBitmap.height)
                    canvas.drawBitmap(srcBitmap, srcRect, dstRect, imagePaint)

                    if (config.showCuttingBorder) {
                        canvas.drawRect(dstRect, borderPaint)
                    }
                }

                pdfDocument.finishPage(page)
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }

            outputFile
        } finally {
            pdfDocument.close()
        }
    }

    /**
     * Directly launches the Android system Print Dialog for the passport photo sheet.
     */
    fun printSheet(
        context: Context,
        items: List<PassportPhotoItem>,
        config: PassportSheetConfig,
        jobName: String = "Passport_Photos"
    ) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: throw IllegalStateException("Print service is unavailable on this device")

        // Temporary PDF generation for printing
        val tempPdfFile = File(context.cacheDir, "passport_print_${System.currentTimeMillis()}.pdf")

        val adapter = object : PrintDocumentAdapter() {
            private var totalPages = 1

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

                val layout = calculateLayout(items, config)
                totalPages = max(1, layout.totalSheets)

                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(totalPages)
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
                    callback?.onWriteFailed("Destination is null")
                    return
                }

                try {
                    // Export to temporary PDF synchronously in print thread
                    val sheetWPoints = (config.effectiveSheetWidthMm * POINTS_PER_MM).toInt().coerceAtLeast(100)
                    val sheetHPoints = (config.effectiveSheetHeightMm * POINTS_PER_MM).toInt().coerceAtLeast(100)
                    val pdfDocument = PdfDocument()

                    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = config.cuttingBorderColorHex.toInt()
                        style = Paint.Style.STROKE
                        strokeWidth = max(0.5f, config.cuttingBorderWidthPt)
                    }

                    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        isDither = true
                    }

                    val layout = calculateLayout(items, config)

                    for (pageIdx in 0 until totalPages) {
                        if (cancellationSignal?.isCanceled == true) {
                            pdfDocument.close()
                            callback?.onWriteCancelled()
                            return
                        }

                        val pageInfo = PdfDocument.PageInfo.Builder(sheetWPoints, sheetHPoints, pageIdx + 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas
                        canvas.drawColor(Color.WHITE)

                        val slotsOnPage = layout.slots.filter { it.pageIndex == pageIdx }
                        for (slot in slotsOnPage) {
                            val left = slot.xMm * POINTS_PER_MM
                            val top = slot.yMm * POINTS_PER_MM
                            val right = (slot.xMm + slot.widthMm) * POINTS_PER_MM
                            val bottom = (slot.yMm + slot.heightMm) * POINTS_PER_MM
                            val dstRect = RectF(left, top, right, bottom)

                            val srcBitmap = slot.photoItem.croppedBitmap
                            val srcRect = Rect(0, 0, srcBitmap.width, srcBitmap.height)
                            canvas.drawBitmap(srcBitmap, srcRect, dstRect, imagePaint)

                            if (config.showCuttingBorder) {
                                canvas.drawRect(dstRect, borderPaint)
                            }
                        }

                        pdfDocument.finishPage(page)
                    }

                    FileOutputStream(destination.fileDescriptor).use { out ->
                        pdfDocument.writeTo(out)
                    }
                    pdfDocument.close()

                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                } finally {
                    tempPdfFile.delete()
                }
            }
        }

        // Set matching media size
        val mediaSize = when (config.sheetSize) {
            PassportSheetSize.PHOTO_4X6 -> {
                if (config.orientation == SheetOrientation.PORTRAIT)
                    PrintAttributes.MediaSize("PHOTO_4X6_P", "4x6 inch", 4000, 6000)
                else
                    PrintAttributes.MediaSize("PHOTO_4X6_L", "6x4 inch", 6000, 4000)
            }
            PassportSheetSize.PHOTO_5X7 -> {
                if (config.orientation == SheetOrientation.PORTRAIT)
                    PrintAttributes.MediaSize("PHOTO_5X7_P", "5x7 inch", 5000, 7000)
                else
                    PrintAttributes.MediaSize("PHOTO_5X7_L", "7x5 inch", 7000, 5000)
            }
            PassportSheetSize.A4 -> {
                if (config.orientation == SheetOrientation.PORTRAIT)
                    PrintAttributes.MediaSize.ISO_A4
                else
                    PrintAttributes.MediaSize.ISO_A4.asLandscape()
            }
            PassportSheetSize.A3 -> {
                if (config.orientation == SheetOrientation.PORTRAIT)
                    PrintAttributes.MediaSize.ISO_A3
                else
                    PrintAttributes.MediaSize.ISO_A3.asLandscape()
            }
        }

        val attributes = PrintAttributes.Builder()
            .setMediaSize(mediaSize)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(jobName, adapter, attributes)
    }

    /**
     * Saves the sheet as a 300 DPI high-resolution JPEG file to the app directory
     * AND directly into device MediaStore / Pictures / PassportPhotos so it immediately appears in the Gallery!
     */
    suspend fun saveJpeg(
        context: Context,
        items: List<PassportPhotoItem>,
        config: PassportSheetConfig,
        outputFile: File,
        pageIndex: Int = 0,
        quality: Int = 95
    ): File = withContext(Dispatchers.IO) {
        val bitmap = renderSheetPageBitmap(items, config, pageIndex, dpi = 300)
        try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Could not compress JPEG" }
            }

            // 1. Save directly into device MediaStore (Pictures/PassportPhotos) so Gallery immediately detects it
            val fileName = outputFile.name
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PassportPhotos")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, contentValues, null, null)
                    }
                }
            } catch (e: Exception) {
                Log.w("PassportSheetRenderer", "MediaStore insert warning: ${e.message}")
            }

            // 2. Also save to public Pictures/PassportPhotos folder
            try {
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PassportPhotos")
                publicDir.mkdirs()
                val publicFile = File(publicDir, fileName)
                FileOutputStream(publicFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath, outputFile.absolutePath), arrayOf("image/jpeg"), null)
            } catch (e: Exception) {
                Log.w("PassportSheetRenderer", "Public storage save warning: ${e.message}")
            }

            outputFile
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Saves a single cropped passport photo as a high-quality JPEG for online digital forms,
     * indexed directly in MediaStore and Gallery.
     */
    suspend fun saveSinglePhoto(
        context: Context,
        bitmap: Bitmap,
        outputFile: File,
        quality: Int = 98
    ): File = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "Could not save photo" }
        }

        val fileName = outputFile.name
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PassportPhotos")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
            }
        } catch (e: Exception) {
            Log.w("PassportSheetRenderer", "MediaStore single photo warning: ${e.message}")
        }

        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PassportPhotos")
            publicDir.mkdirs()
            val publicFile = File(publicDir, fileName)
            FileOutputStream(publicFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath, outputFile.absolutePath), arrayOf("image/jpeg"), null)
        } catch (e: Exception) {
            Log.w("PassportSheetRenderer", "Public storage single photo warning: ${e.message}")
        }

        outputFile
    }
}
