package com.example.offlinedocumentcomposer.domain.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

object ImageUtils {

    /**
     * Creates a temporary file in the app cache and returns its FileProvider content URI
     * for taking full-resolution camera photos safely.
     */
    fun createTempCameraUri(context: Context): Uri {
        val cameraDir = File(context.cacheDir, "camera_photos")
        if (!cameraDir.exists()) cameraDir.mkdirs()
        val tempFile = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }

    /**
     * Decodes a Bitmap from an Android content URI, downsamples to maxDimension to prevent OOM,
     * and automatically rotates according to EXIF orientation.
     */
    fun decodeBitmapFromUri(
        context: Context,
        uri: Uri,
        maxDimension: Int = 2048
    ): Bitmap? {
        require(maxDimension in 1..4000)
        return try {
            // Step 1: Query dimensions
            var inputStream: InputStream? = context.contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            // Step 2: Calculate sample size safely for device heap
            var sampleSize = 1
            val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
            val effectiveMaxDim = if (maxHeapMb < 256) kotlin.math.min(maxDimension, 1600) else maxDimension

            val maxRaw = max(rawWidth, rawHeight)
            while (maxRaw / (sampleSize * 2) >= effectiveMaxDim || rawWidth.toLong() * rawHeight / sampleSize / sampleSize > 8_000_000L) {
                sampleSize *= 2
            }

            // Step 3: Decode scaled bitmap with memory-saving config
            val isJpeg = options.outMimeType?.lowercase()?.contains("jp") == true
            inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (isJpeg) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream.close()

            if (sampledBitmap == null) return null

            // Step 4: Correct EXIF orientation
            val orientation = getExifRotation(context, uri)
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE -> 270
                else -> 0
            }
            val mirrored = orientation in listOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
            val upright = if (rotationDegrees != 0 || mirrored) {
                val matrix = Matrix().apply {
                    if (orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL) postScale(1f, -1f)
                    else if (mirrored) postScale(-1f, 1f)
                    postRotate(rotationDegrees.toFloat())
                }
                val rotated = Bitmap.createBitmap(
                    sampledBitmap,
                    0,
                    0,
                    sampledBitmap.width,
                    sampledBitmap.height,
                    matrix,
                    true
                )
                if (rotated != sampledBitmap) {
                    sampledBitmap.recycle()
                }
                rotated
            } else {
                sampledBitmap
            }
            val factor = minOf(1f, maxDimension.toFloat() / max(upright.width, upright.height))
            if (factor < 1f) {
                val bounded = Bitmap.createScaledBitmap(upright, (upright.width * factor).toInt().coerceAtLeast(1), (upright.height * factor).toInt().coerceAtLeast(1), true)
                if (bounded !== upright) upright.recycle()
                bounded
            } else upright
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Reads EXIF orientation from Uri and returns rotation in degrees.
     */
    private fun getExifRotation(context: Context, uri: Uri): Int {
        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: return 0
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            stream.close()
            orientation
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Saves a bitmap to cache and returns the file.
     */
    fun saveBitmapToCache(
        context: Context,
        bitmap: Bitmap,
        filenamePrefix: String = "doc_img"
    ): File {
        val cacheDir = File(context.cacheDir, "composer_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, "${filenamePrefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}
