package com.example.offlinedocumentcomposer.domain.detector

import android.graphics.Bitmap
import android.graphics.PointF
import com.example.offlinedocumentcomposer.opencv.OpenCvUtils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Fits high-precision edge lines near candidate boundaries using Huber robust line fitting.
 * Refines edges independently so partial obstructions or shadows on one edge do not
 * prevent the other crisp edges from snapping perfectly.
 */
internal object BoundaryRefiner {
    fun refine(bitmap: Bitmap, corners: List<PointF>): List<PointF> {
        if (corners.size != 4) return corners
        val maxDim = max(bitmap.width, bitmap.height).toFloat()
        val scale = min(1f, 1600f / maxDim)
        val procW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val procH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, procW, procH, true)
        val src = OpenCvUtils.bitmapToMat(small)
        val gray = Mat()
        val equalized = Mat()
        val blurred = Mat()
        val edges = Mat()
        data class Line(val x: Double, val y: Double, val vx: Double, val vy: Double)
        val lines = mutableListOf<Line>()

        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // 1. Contrast-Limited Adaptive Histogram Equalization for uniform edge response
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, equalized)

            // 2. Bilateral filter to smooth texture while preserving sharp boundary edges
            Imgproc.bilateralFilter(equalized, blurred, 7, 50.0, 50.0)
            Imgproc.Canny(blurred, edges, 30.0, 90.0)

            val cols = edges.cols()
            val rows = edges.rows()

            for (i in 0..3) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val length = hypot(dx, dy).coerceAtLeast(1f)

                val hits = mutableListOf<Point>()
                val offsets = intArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5, -6, 6, -7, 7, -8, 8, -10, 10, -12, 12)

                // Sample along the segment in 50 probe points
                for (n in 4..46) {
                    val t = n / 50f
                    val sampleX = (a.x + dx * t) * scale
                    val sampleY = (a.y + dy * t) * scale

                    for (offset in offsets) {
                        val px = (sampleX - dy / length * offset).roundToInt()
                        val py = (sampleY + dx / length * offset).roundToInt()
                        if (px in 0 until cols && py in 0 until rows && edges.get(py, px)[0] > 0) {
                            hits.add(Point(px.toDouble(), py.toDouble()))
                            break
                        }
                    }
                }

                if (hits.size >= 12) {
                    val points = MatOfPoint2f(*hits.toTypedArray())
                    val fit = Mat()
                    try {
                        Imgproc.fitLine(points, fit, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                        val vx = fit.get(0, 0)[0]
                        val vy = fit.get(1, 0)[0]
                        val x0 = fit.get(2, 0)[0]
                        val y0 = fit.get(3, 0)[0]
                        lines.add(Line(x0, y0, vx, vy))
                    } catch (e: Exception) {
                        val len = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
                        lines.add(Line((a.x * scale).toDouble(), (a.y * scale).toDouble(), dx / len, dy / len))
                    } finally {
                        points.release()
                        fit.release()
                    }
                } else {
                    // Retain initial baseline vector when edge has weak contrast
                    val len = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
                    lines.add(Line((a.x * scale).toDouble(), (a.y * scale).toDouble(), dx / len, dy / len))
                }
            }

            // 3. Compute 4 refined intersection corners
            val refined = (0..3).map { i ->
                val prevLine = lines[(i + 3) % 4]
                val currLine = lines[i]
                val det = prevLine.vx * currLine.vy - prevLine.vy * currLine.vx

                if (abs(det) < 1e-4) {
                    corners[i]
                } else {
                    val t = ((currLine.x - prevLine.x) * currLine.vy - (currLine.y - prevLine.y) * currLine.vx) / det
                    val newX = ((prevLine.x + prevLine.vx * t) / scale).toFloat()
                    val newY = ((prevLine.y + prevLine.vy * t) / scale).toFloat()

                    // Ensure point didn't jump unrealistically far (clamp to within 5% of image diagonal)
                    val original = corners[i]
                    val dist = hypot(newX - original.x, newY - original.y)
                    if (dist < maxDim * 0.05f && newX in 0f..bitmap.width.toFloat() && newY in 0f..bitmap.height.toFloat()) {
                        PointF(newX, newY)
                    } else {
                        original
                    }
                }
            }

            return if (CropGeometry.valid(refined, bitmap.width, bitmap.height)) refined else corners
        } catch (e: Exception) {
            return corners
        } finally {
            src.release()
            gray.release()
            equalized.release()
            blurred.release()
            edges.release()
            if (small !== bitmap) small.recycle()
        }
    }
}
