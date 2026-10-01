package com.example.offlinedocumentcomposer.opencv

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

object OpenCvUtils {
    private const val TAG = "OpenCvUtils"
    private var isInitialized = false

    fun initOpenCV(): Boolean {
        if (isInitialized) return true
        return try {
            isInitialized = OpenCVLoader.initLocal()
            Log.d(TAG, "OpenCV initialized via initLocal(): $isInitialized")
            isInitialized
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize OpenCV", e)
            false
        }
    }

    fun isReady(): Boolean = isInitialized

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        val bmp32 = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            bitmap
        }
        try { Utils.bitmapToMat(bmp32, mat); return mat }
        catch (e: Exception) { mat.release(); throw e }
        finally { if (bmp32 !== bitmap) bmp32.recycle() }
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    /**
     * High quality perspective warp using native OpenCV getPerspectiveTransform and warpPerspective.
     */
    fun warpPerspective(bitmap: Bitmap, corners: List<PointF>): Bitmap? {
        if (!initOpenCV() || corners.size != 4) return null
        val resources = mutableListOf<Mat>()
        return try {
            val p0 = corners[0] // TL
            val p1 = corners[1] // TR
            val p2 = corners[2] // BR
            val p3 = corners[3] // BL

            val topW = hypot(p1.x - p0.x, p1.y - p0.y)
            val botW = hypot(p2.x - p3.x, p2.y - p3.y)
            val leftH = hypot(p3.x - p0.x, p3.y - p0.y)
            val rightH = hypot(p2.x - p1.x, p2.y - p1.y)

            val width = max(topW, botW) + 1f
            val height = max(leftH, rightH) + 1f
            val outputScale = minOf(1f, 3508f / max(width, height))
            val targetWidth = (width * outputScale).toInt().coerceAtLeast(1)
            val targetHeight = (height * outputScale).toInt().coerceAtLeast(1)

            val srcMat = bitmapToMat(bitmap).also { resources.add(it) }
            val srcPoints = MatOfPoint2f(
                Point(p0.x.toDouble(), p0.y.toDouble()),
                Point(p1.x.toDouble(), p1.y.toDouble()),
                Point(p2.x.toDouble(), p2.y.toDouble()),
                Point(p3.x.toDouble(), p3.y.toDouble())
            )

            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(targetWidth.toDouble() - 1.0, 0.0),
                Point(targetWidth.toDouble() - 1.0, targetHeight.toDouble() - 1.0),
                Point(0.0, targetHeight.toDouble() - 1.0)
            )

            resources.add(srcPoints); resources.add(dstPoints)
            val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints).also { resources.add(it) }
            val dstMat = Mat().also { resources.add(it) }
            Imgproc.warpPerspective(
                srcMat,
                dstMat,
                transform,
                Size(targetWidth.toDouble(), targetHeight.toDouble()),
                Imgproc.INTER_CUBIC or Imgproc.WARP_FILL_OUTLIERS
            )

            val output = matToBitmap(dstMat)

            output
        } catch (e: Throwable) {
            Log.e(TAG, "Error in OpenCV warpPerspective", e)
            null
        } finally { resources.forEach { it.release() } }
    }
}
