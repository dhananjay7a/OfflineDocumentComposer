package com.example.offlinedocumentcomposer.domain.detector

import android.graphics.Bitmap
import android.graphics.PointF
import com.example.offlinedocumentcomposer.opencv.OpenCvUtils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Fits four edge lines near the proposal; weak or inconsistent refinements keep the proposal. */
internal object BoundaryRefiner {
    fun refine(bitmap: Bitmap, corners: List<PointF>): List<PointF> {
        val scale = min(1f,1800f/max(bitmap.width,bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).roundToInt().coerceAtLeast(1),(bitmap.height*scale).roundToInt().coerceAtLeast(1),true)
        val src = OpenCvUtils.bitmapToMat(small)
        val gray = Mat(); val edges = Mat()
        data class Line(val x: Double,val y: Double,val vx: Double,val vy: Double)
        val lines = mutableListOf<Line>()
        try {
            Imgproc.cvtColor(src,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(gray,edges,35.0,110.0)
            for (i in 0..3) {
                val a = corners[i]; val b = corners[(i+1)%4]
                val dx = b.x-a.x; val dy = b.y-a.y; val length = hypot(dx,dy).coerceAtLeast(1f)
                val hits = mutableListOf<Point>()
                for (n in 3..57) {
                    val t = n/60f; val x = (a.x+dx*t)*scale; val y = (a.y+dy*t)*scale
                    for (offset in listOf(0,-1,1,-2,2,-3,3,-4,4,-5,5,-6,6,-7,7,-8,8)) {
                        val px = (x-dy/length*offset).roundToInt(); val py = (y+dx/length*offset).roundToInt()
                        if (px in 0 until edges.cols() && py in 0 until edges.rows() && edges.get(py,px)[0]>0) {
                            hits.add(Point(px.toDouble(),py.toDouble())); break
                        }
                    }
                }
                if (hits.size<28) return corners
                val points = MatOfPoint2f(*hits.toTypedArray()); val fit = Mat()
                try {
                    Imgproc.fitLine(points,fit,Imgproc.DIST_HUBER,0.0,.01,.01)
                    lines.add(Line(fit.get(2,0)[0],fit.get(3,0)[0],fit.get(0,0)[0],fit.get(1,0)[0]))
                } finally { points.release(); fit.release() }
            }
            val result = (0..3).map { i ->
                val a = lines[(i+3)%4]; val b = lines[i]
                val det = a.vx*b.vy-a.vy*b.vx
                if (abs(det)<1e-5) return corners
                val t = ((b.x-a.x)*b.vy-(b.y-a.y)*b.vx)/det
                PointF(((a.x+a.vx*t)/scale).toFloat(),((a.y+a.vy*t)/scale).toFloat())
            }
            return if (CropGeometry.valid(result,bitmap.width,bitmap.height) && result.indices.all {
                hypot(result[it].x-corners[it].x,result[it].y-corners[it].y) < max(bitmap.width,bitmap.height)*.025f
            }) result else corners
        } catch (e: Exception) { return corners
        } finally { src.release(); gray.release(); edges.release(); if (small !== bitmap) small.recycle() }
    }
}
