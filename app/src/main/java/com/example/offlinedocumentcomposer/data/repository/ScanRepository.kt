package com.example.offlinedocumentcomposer.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.util.AtomicFile
import com.example.offlinedocumentcomposer.data.model.*
import com.example.offlinedocumentcomposer.domain.detector.DocumentDetector
import com.example.offlinedocumentcomposer.domain.image.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Originals and normalized edits survive process death; no bitmap is saved in ViewModel state. */
class ScanRepository(private val context: Context, private val directory: File = File(context.filesDir, "scan_session")) {
    init { check(directory.exists() || directory.mkdirs()) { "Cannot create scan storage" } }
    private val manifest = AtomicFile(File(directory, "session.json"))
    fun thumbnail(page: ScanPage) = File(directory, "${page.id}_thumb_${page.revision}.jpg")
    fun load(): ScanSession {
        if (!manifest.baseFile.exists()) return ScanSession()
        val json = JSONObject(manifest.openRead().use { it.reader().readText() })
        val array = json.getJSONArray("pages")
        return ScanSession((0 until array.length()).map { i ->
            val p = array.getJSONObject(i); val corners = p.getJSONArray("corners")
            ScanPage(p.getString("id"), File(directory, p.getString("source")).absolutePath,
                (0 until corners.length()).map { val xy = corners.getJSONArray(it); ScanPoint(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()) },
                p.optInt("rotation"), ScanFilter.valueOf(p.optString("filter", "ORIGINAL")),
                p.optDouble("brightness", 0.0).toFloat(), p.optDouble("contrast", 0.0).toFloat(), p.optBoolean("needsReview"), p.optInt("revision"))
        })
    }
    fun save(session: ScanSession) {
        val pages = JSONArray()
        session.pages.forEach { p ->
            pages.put(JSONObject().put("id",p.id).put("source",File(p.source).name)
                .put("corners",JSONArray().apply { p.corners.forEach { put(JSONArray().put(it.x).put(it.y)) } })
                .put("rotation",p.rotation).put("filter",p.filter.name).put("brightness",p.brightness)
                .put("contrast",p.contrast).put("needsReview",p.needsReview).put("revision",p.revision))
        }
        val stream = manifest.startWrite()
        try { stream.write(JSONObject().put("version",1).put("pages",pages).toString().toByteArray()); manifest.finishWrite(stream) }
        catch (e: Exception) { manifest.failWrite(stream); throw e }
    }
    fun import(uri: Uri): ScanPage {
        val source = File(directory, "${UUID.randomUUID()}.source")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { input.copyTo(it) } }
                ?: error("Cannot read image")
            val bitmap = ImageUtils.decodeBitmapFromUri(context, Uri.fromFile(source), 1800) ?: error("Unsupported or damaged image")
            val page = try {
                val detection = DocumentDetector().detect(bitmap)
                ScanPage(source = source.absolutePath, corners = detection.corners.map { ScanPoint(it.x / (bitmap.width-1).coerceAtLeast(1), it.y / (bitmap.height-1).coerceAtLeast(1)) }, needsReview = !detection.success)
            } finally { bitmap.recycle() }
            makeThumbnail(page)
            return page
        } catch (e: Exception) { source.delete(); throw e }
    }
    fun source(page: ScanPage, dimension: Int = 1600): Bitmap = ImageUtils.decodeBitmapFromUri(context, Uri.fromFile(File(page.source)), dimension) ?: error("Source image is unavailable")
    fun render(page: ScanPage, dimension: Int): Bitmap {
        var bitmap = source(page, dimension)
        try {
            val points = page.corners.map { PointF(it.x * (bitmap.width-1),it.y * (bitmap.height-1)) }
            val cropped = PerspectiveCorrector().correct(bitmap, points)
            if (cropped !== bitmap) bitmap.recycle()
            bitmap = cropped
            val processor = ImageProcessor()
            if (page.rotation != 0) {
                val rotated = processor.rotate(bitmap,page.rotation.toFloat()); if (rotated !== bitmap) bitmap.recycle(); bitmap = rotated
            }
            val preset = when (page.filter) {
                ScanFilter.ORIGINAL -> ImageAdjustments.ORIGINAL
                ScanFilter.COLOR -> ImageAdjustments.MAGIC_COLOR.copy(sharpness = 25f)
                ScanFilter.GRAYSCALE -> ImageAdjustments.GRAYSCALE.copy(sharpness = 15f)
                ScanFilter.BLACK_WHITE -> ImageAdjustments.DOCUMENT_BW.copy(sharpness = 30f)
            }
            val adjusted = processor.applyAdjustments(
                bitmap,
                preset.copy(
                    brightness = preset.brightness + page.brightness,
                    contrast = preset.contrast + page.contrast,
                    sharpness = preset.sharpness
                )
            )
            bitmap.recycle()
            return adjusted
        } catch (e: Exception) { bitmap.recycle(); throw e }
    }
    fun makeThumbnail(page: ScanPage) {
        val bitmap = render(page,500)
        try { thumbnail(page).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,80,it)) } }
        finally { bitmap.recycle() }
    }
    fun deleteFiles(page: ScanPage) {
        File(page.source).takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }?.delete()
        directory.listFiles()?.filter { it.name.startsWith("${page.id}_thumb_") }?.forEach { it.delete() }
    }
    fun clearOldThumbnails(page: ScanPage) {
        directory.listFiles()?.filter { it.name.startsWith("${page.id}_thumb_") && it != thumbnail(page) }?.forEach { it.delete() }
    }
}
