package com.example.offlinedocumentcomposer.domain.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.example.offlinedocumentcomposer.data.model.ImageLayer
import com.example.offlinedocumentcomposer.data.model.PageModel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfRenderer(private val context: Context) {

    /**
     * Renders a list of page models into a multi-page high-resolution PDF document (300 DPI).
     */
    suspend fun renderPagesToPdf(pages: List<PageModel>, outputFile: File): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val validPages = pages.ifEmpty { listOf(PageModel()) }
            val document = PdfDocument()
            val dpiScale = 300f / 72f // 300 DPI target

            for ((pageIndex, page) in validPages.withIndex()) {
                val pageWidthPx = page.getPageWidthPx().roundToInt()
                val pageHeightPx = page.getPageHeightPx().roundToInt()

                val scaledWidth = (page.getWidthMm() * 72f / 25.4f).roundToInt()
                val scaledHeight = (page.getHeightMm() * 72f / 25.4f).roundToInt()

                val pageInfo = PdfDocument.PageInfo.Builder(scaledWidth, scaledHeight, pageIndex + 1).create()
                val pdfPage = document.startPage(pageInfo)
                val canvas = pdfPage.canvas

                val paint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                    color = android.graphics.Color.WHITE
                }
                canvas.drawRect(0f, 0f, scaledWidth.toFloat(), scaledHeight.toFloat(), paint)

                val scaleFactor = scaledWidth.toFloat() / page.getPageWidthPx()

                for (layer in page.imageLayers) {
                    if (!layer.visible) continue
                    val bitmap = layer.workingBitmap ?: layer.originalBitmap ?: continue

                    val targetW = (layer.width * scaleFactor).roundToInt()
                    val targetH = (layer.height * scaleFactor).roundToInt()
                    if (targetW <= 0 || targetH <= 0) continue

                    val scaledBitmap = bitmap

                    val dstLeft = (layer.x * scaleFactor).roundToInt()
                    val dstTop = (layer.y * scaleFactor).roundToInt()

                    canvas.save()
                    if (layer.rotation != 0f) {
                        canvas.rotate(
                            layer.rotation,
                            dstLeft + targetW / 2f,
                            dstTop + targetH / 2f
                        )
                    }
                    canvas.drawBitmap(scaledBitmap, null, android.graphics.RectF(dstLeft.toFloat(), dstTop.toFloat(), (dstLeft + targetW).toFloat(), (dstTop + targetH).toFloat()), paint)
                    canvas.restore()

                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                }

                document.finishPage(pdfPage)
            }

            if (outputFile.parentFile?.exists() == false) {
                outputFile.parentFile?.mkdirs()
            }

            FileOutputStream(outputFile).use { fos ->
                document.writeTo(fos)
                fos.flush()
            }
            document.close()

            Result.success(outputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Renders a single page model containing document layers to a high-resolution PDF document (300 DPI).
     */
    suspend fun renderToPdf(page: PageModel, outputFile: File): Result<String> {
        return renderPagesToPdf(listOf(page), outputFile)
    }

    /**
     * Extracts and renders the first page of a PDF file to a Bitmap.
     * Useful for importing recent exported PDFs back into the composer canvas.
     */
    fun renderPdfFirstPage(file: File, maxDimension: Int = 1200): Bitmap? {
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val scale = maxDimension.toFloat() / maxOf(page.width, page.height).toFloat()
                val targetW = (page.width * scale).roundToInt().coerceAtLeast(100)
                val targetH = (page.height * scale).roundToInt().coerceAtLeast(100)

                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                pfd.close()
                bmp
            } else {
                renderer.close()
                pfd.close()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Renders a preview bitmap of the entire page at specified preview dimensions.
     */
    suspend fun renderPagePreview(page: PageModel, previewWidth: Int, previewHeight: Int): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            color = android.graphics.Color.WHITE
        }
        canvas.drawRect(0f, 0f, previewWidth.toFloat(), previewHeight.toFloat(), paint)

        val scaleX = previewWidth.toFloat() / page.getPageWidthPx()
        val scaleY = previewHeight.toFloat() / page.getPageHeightPx()
        val scale = min(scaleX, scaleY)

        val offsetX = (previewWidth - page.getPageWidthPx() * scale) / 2
        val offsetY = (previewHeight - page.getPageHeightPx() * scale) / 2

        for (layer in page.imageLayers) {
            if (!layer.visible) continue
            val layerBmp = layer.workingBitmap ?: layer.originalBitmap ?: continue
            val dstWidth = (layer.width * scale).roundToInt().coerceAtLeast(1)
            val dstHeight = (layer.height * scale).roundToInt().coerceAtLeast(1)

            val scaled = Bitmap.createScaledBitmap(layerBmp, dstWidth, dstHeight, true)
            val dstLeft = (offsetX + layer.x * scale).roundToInt()
            val dstTop = (offsetY + layer.y * scale).roundToInt()

            canvas.save()
            if (layer.rotation != 0f) {
                canvas.rotate(layer.rotation, dstLeft + dstWidth / 2f, dstTop + dstHeight / 2f)
            }
            canvas.drawBitmap(scaled, dstLeft.toFloat(), dstTop.toFloat(), paint)
            canvas.restore()

            if (scaled != layerBmp) {
                scaled.recycle()
            }
        }

        bitmap
    }

    /**
     * Exports the page directly to a high-resolution PNG or JPG image file.
     */
    suspend fun exportToImage(
        page: PageModel,
        outputFile: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val dpiScale = 300f / 72f
            val widthPx = (page.getPageWidthPx() * dpiScale).roundToInt().coerceIn(1200, 2480)
            val heightPx = (page.getPageHeightPx() * dpiScale).roundToInt().coerceIn(1600, 3508)

            val fullBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fullBitmap)

            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                color = android.graphics.Color.WHITE
            }
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)

            val scaleFactor = widthPx.toFloat() / page.getPageWidthPx()

            for (layer in page.imageLayers) {
                if (!layer.visible) continue
                val source = layer.workingBitmap ?: layer.originalBitmap ?: continue
                val dstWidth = (layer.width * scaleFactor).roundToInt().coerceAtLeast(1)
                val dstHeight = (layer.height * scaleFactor).roundToInt().coerceAtLeast(1)

                val scaled = Bitmap.createScaledBitmap(source, dstWidth, dstHeight, true)
                val dstLeft = (layer.x * scaleFactor).roundToInt()
                val dstTop = (layer.y * scaleFactor).roundToInt()

                canvas.save()
                if (layer.rotation != 0f) {
                    canvas.rotate(layer.rotation, dstLeft + dstWidth / 2f, dstTop + dstHeight / 2f)
                }
                canvas.drawBitmap(scaled, dstLeft.toFloat(), dstTop.toFloat(), paint)
                canvas.restore()

                if (scaled != source) {
                    scaled.recycle()
                }
            }

            if (outputFile.parentFile?.exists() == false) {
                outputFile.parentFile?.mkdirs()
            }

            FileOutputStream(outputFile).use { fos ->
                fullBitmap.compress(format, quality, fos)
                fos.flush()
            }
            fullBitmap.recycle()

            Result.success(outputFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Sends a list of A4 pages directly to the Android System Print Service via PrintManager
     * without saving to storage or requiring any external files.
     */
    fun printPages(context: Context, pages: List<PageModel>, jobName: String = "DocComposer_Print") {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val validPages = pages.ifEmpty { listOf(PageModel()) }
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
                    .setPageCount(validPages.size)
                    .build()
                callback?.onLayoutFinished(info, newAttributes != oldAttributes)
            }

            override fun onWrite(
                pageRanges: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                if (destination == null) {
                    callback?.onWriteFailed("Output destination is null")
                    return
                }
                try {
                    val document = PdfDocument()
                    val dpiScale = 300f / 72f

                    for ((pageIndex, page) in validPages.withIndex()) {
                        val pageWidthPx = page.getPageWidthPx().roundToInt()
                        val pageHeightPx = page.getPageHeightPx().roundToInt()

                        val scaledWidth = (page.getWidthMm() * 72f / 25.4f).roundToInt()
                        val scaledHeight = (page.getHeightMm() * 72f / 25.4f).roundToInt()

                        val pageInfo = PdfDocument.PageInfo.Builder(scaledWidth, scaledHeight, pageIndex + 1).create()
                        val pdfPage = document.startPage(pageInfo)
                        val canvas = pdfPage.canvas

                        val paint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                            color = android.graphics.Color.WHITE
                        }
                        canvas.drawRect(0f, 0f, scaledWidth.toFloat(), scaledHeight.toFloat(), paint)

                        val scaleFactor = scaledWidth.toFloat() / page.getPageWidthPx()

                        for (layer in page.imageLayers) {
                            if (!layer.visible) continue
                            val bitmap = layer.workingBitmap ?: layer.originalBitmap ?: continue

                            val targetW = (layer.width * scaleFactor).roundToInt()
                            val targetH = (layer.height * scaleFactor).roundToInt()
                            if (targetW <= 0 || targetH <= 0) continue

                            val scaledBitmap = bitmap

                            val dstLeft = (layer.x * scaleFactor).roundToInt()
                            val dstTop = (layer.y * scaleFactor).roundToInt()

                            canvas.save()
                            if (layer.rotation != 0f) {
                                canvas.rotate(layer.rotation, dstLeft + targetW / 2f, dstTop + targetH / 2f)
                            }
                            canvas.drawBitmap(scaledBitmap, null, android.graphics.RectF(dstLeft.toFloat(), dstTop.toFloat(), (dstLeft + targetW).toFloat(), (dstTop + targetH).toFloat()), paint)
                            canvas.restore()

                            if (scaledBitmap != bitmap) {
                                scaledBitmap.recycle()
                            }
                        }

                        document.finishPage(pdfPage)
                    }

                    FileOutputStream(destination.fileDescriptor).use { fos ->
                        document.writeTo(fos)
                        fos.flush()
                    }
                    document.close()

                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }

        val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(jobName, adapter, printAttributes)
    }

    /**
     * Sends the single A4 page directly to the Android System Print Service.
     */
    fun printDocument(context: Context, page: PageModel, jobName: String = "ID_Card_A4_Print") {
        printPages(context, listOf(page), jobName)
    }
}
