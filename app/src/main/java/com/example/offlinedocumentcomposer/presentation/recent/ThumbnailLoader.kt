package com.example.offlinedocumentcomposer.presentation.recent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance, memory-efficient thumbnail cache and generator for Recent Exports.
 * Supports image files (JPEG, PNG, WEBP) and PDF files (renders page 0 via PdfRenderer).
 * Optimized for low-end Android devices with bounded memory allocation and RGB_565 config.
 */
object ThumbnailLoader {

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    // Cap LRU cache to 15MB or 1/16th of heap, whichever is smaller
    private val cacheSizeKb = min(15 * 1024, max(2 * 1024, maxMemoryKb / 16))

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return (bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    /**
     * Retrieve cached thumbnail or null if not yet rendered.
     */
    fun getCached(file: File): Bitmap? {
        val key = cacheKey(file)
        return memoryCache.get(key)?.takeIf { !it.isRecycled }
    }

    /**
     * Loads/decodes a thumbnail for the specified file.
     * Call this from Dispatchers.IO.
     */
    fun getThumbnail(
        file: File,
        isPdf: Boolean,
        targetWidth: Int = 160,
        targetHeight: Int = 160
    ): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null

        val key = cacheKey(file)
        val cached = memoryCache.get(key)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        val bitmap = if (isPdf) {
            renderPdfThumbnail(file, targetWidth, targetHeight)
        } else {
            decodeImageThumbnail(file, targetWidth, targetHeight)
        }

        if (bitmap != null && !bitmap.isRecycled) {
            memoryCache.put(key, bitmap)
        }
        return bitmap
    }

    private fun cacheKey(file: File): String {
        return "${file.absolutePath}_${file.lastModified()}_${file.length()}"
    }

    /**
     * Decodes downsampled bitmap from image file using sample size and RGB_565 for low memory.
     */
    private fun decodeImageThumbnail(file: File, targetW: Int, targetH: Int): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            val w = boundsOpts.outWidth
            val h = boundsOpts.outHeight
            if (w <= 0 || h <= 0) return null

            var sampleSize = 1
            while (w / (sampleSize * 2) >= targetW && h / (sampleSize * 2) >= targetH) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    /**
     * Renders the first page of a PDF document at thumbnail dimensions.
     */
    private fun renderPdfThumbnail(file: File, targetW: Int, targetH: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null

        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return null

            page = renderer.openPage(0)
            val pageWidth = page.width
            val pageHeight = page.height
            if (pageWidth <= 0 || pageHeight <= 0) return null

            // Scale to fit bounds
            val scale = min(
                targetW.toFloat() / pageWidth,
                targetH.toFloat() / pageHeight
            ).coerceAtLeast(0.1f)

            val renderW = (pageWidth * scale).toInt().coerceIn(40, targetW * 2)
            val renderH = (pageHeight * scale).toInt().coerceIn(40, targetH * 2)

            val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        } finally {
            try { page?.close() } catch (_: Throwable) {}
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Clear thumbnail cache if memory trim is requested.
     */
    fun clearCache() {
        memoryCache.evictAll()
    }
}
