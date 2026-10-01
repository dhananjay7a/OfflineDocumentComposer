package com.example.offlinedocumentcomposer.domain.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.*

enum class PdfQuality(val dpi: Int, val jpeg: Float) {
    LIGHT(200, .85f), BALANCED(150, .75f), STRONG(100, .60f)
}
data class CompressionResult(val file: File, val originalBytes: Long, val outputBytes: Long,
    val targetReached: Boolean?, val changedImages: Int)

class PdfCompressionService(context: Context) {
    private val scratchDirectory = context.cacheDir
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    suspend fun compress(input: File, output: File, quality: PdfQuality, targetBytes: Long?,
        progress: (String) -> Unit): CompressionResult = withContext(Dispatchers.IO) {
        require(input.canonicalPath != output.canonicalPath) { "Choose a new output file" }
        require(targetBytes == null || targetBytes > 0) { "Enter a positive target size" }
        val attempts = if (targetBytes == null) listOf(quality) else PdfQuality.values().filter { it.ordinal >= quality.ordinal }
        val candidate = File(output.parentFile, output.name + ".partial")
        val best = File(output.parentFile, output.name + ".best")
        require(!output.exists()) { "Output already exists" }
        var count = 0
        output.parentFile?.mkdirs()
        try {
            input.copyTo(best, overwrite = true)
            for ((attempt, preset) in attempts.withIndex()) {
                currentCoroutineContext().ensureActive()
                // Always recompress the original, avoiding cumulative JPEG degradation.
                val coroutine = currentCoroutineContext()
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDirectory)).use { document ->
                    require(!document.isEncrypted) { "Encrypted PDFs are not supported. Select an unencrypted copy." }
                    require(document.signatureDictionaries.isEmpty()) { "Signed PDFs cannot be compressed without invalidating signatures." }
                    val usage = IdentityHashMap<COSStream, Pair<Float, Float>>()
                    for (page in document.pages) {
                        coroutine.ensureActive()
                        ImageUsage(page, usage).processPage(page)
                    }
                    val visited = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                    val replacements = IdentityHashMap<COSStream, PDImageXObject?>()
                    var changed = 0
                    fun optimize(resources: PDResources?) {
                        if (resources == null || !visited.add(resources.cosObject)) return
                        for (name in resources.xObjectNames.toList()) {
                            coroutine.ensureActive()
                            when (val obj = resources.getXObject(name)) {
                                is PDFormXObject -> optimize(obj.resources)
                                is PDImageXObject -> {
                                    val stream = obj.cosObject
                                    if (!replacements.containsKey(stream)) {
                                        val dimensions = usage[stream]
                                        replacements[stream] = if (dimensions == null) null else recompress(document, obj, dimensions, preset)
                                        if (replacements[stream] != null) changed++
                                    }
                                    replacements[stream]?.let { resources.put(name, it) }
                                }
                            }
                        }
                    }
                    for ((index, page) in document.pages.withIndex()) {
                        progress("Pass ${attempt + 1}/${attempts.size} · Page ${index + 1}/${document.numberOfPages}")
                        optimize(page.resources)
                    }
                    coroutine.ensureActive()
                    document.save(candidate)
                    coroutine.ensureActive()
                    if (candidate.length() < best.length()) {
                        candidate.copyTo(best, overwrite = true)
                        count = changed
                    }
                }
                candidate.delete()
                if (targetBytes != null && best.length() <= targetBytes) break
            }
            currentCoroutineContext().ensureActive()
            check(best.renameTo(output)) { "Could not finalize compressed PDF" }
            CompressionResult(output, input.length(), output.length(), targetBytes?.let { output.length() <= it }, count)
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw IllegalArgumentException("Encrypted PDFs are not supported. Select an unencrypted copy.", e)
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally { candidate.delete(); best.delete() }
    }

    private fun recompress(document: PDDocument, image: PDImageXObject, size: Pair<Float, Float>, preset: PdfQuality): PDImageXObject? {
        val stream = image.cosObject
        // Preserve masks, custom decode arrays, non-device colors, and unsupported codecs exactly.
        if (image.isStencil || stream.containsKey(COSName.SMASK) || stream.containsKey(COSName.MASK) || stream.containsKey(COSName.DECODE)) return null
        if (image.bitsPerComponent != 8 || image.colorSpace.name !in listOf("DeviceRGB", "DeviceGray")) return null
        if (stream.filters?.toString()?.let { it.contains("JPX") || it.contains("JBIG2") } == true) return null
        if (image.width.toLong() * image.height > 12_000_000L) return null
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            bitmap = image.image ?: return null
            val factor = min(1f, max(size.first * preset.dpi / 72f / image.width, size.second * preset.dpi / 72f / image.height))
            scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1), (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
            val replacement = JPEGFactory.createFromImage(document, scaled, preset.jpeg)
            if (replacement.cosObject.length < stream.length) replacement else null
        } catch (e: Exception) { null
        } finally { if (scaled !== bitmap) scaled?.recycle(); bitmap?.recycle() }
    }

    private class ImageUsage(page: PDPage, val usage: IdentityHashMap<COSStream, Pair<Float, Float>>) : PDFGraphicsStreamEngine(page) {
        private var point = PointF()
        override fun drawImage(image: PDImage) {
            if (image !is PDImageXObject) return
            val m = graphicsState.currentTransformationMatrix
            val size = abs(m.scalingFactorX) to abs(m.scalingFactorY)
            val old = usage[image.cosObject]
            usage[image.cosObject] = max(old?.first ?: 0f, size.first) to max(old?.second ?: 0f, size.second)
        }
        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) { point = p0 }
        override fun clip(windingRule: android.graphics.Path.FillType) {}
        override fun moveTo(x: Float, y: Float) { point = PointF(x,y) }
        override fun lineTo(x: Float, y: Float) { point = PointF(x,y) }
        override fun curveTo(x1: Float,y1: Float,x2: Float,y2: Float,x3: Float,y3: Float) { point = PointF(x3,y3) }
        override fun getCurrentPoint(): PointF = point
        override fun closePath() {}
        override fun endPath() {}
        override fun strokePath() {}
        override fun fillPath(windingRule: android.graphics.Path.FillType) {}
        override fun fillAndStrokePath(windingRule: android.graphics.Path.FillType) {}
        override fun shadingFill(shadingName: COSName) {}
    }
}
