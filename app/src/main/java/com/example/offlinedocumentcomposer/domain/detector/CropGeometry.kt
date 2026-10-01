package com.example.offlinedocumentcomposer.domain.detector

import android.graphics.PointF
import kotlin.math.abs

object CropGeometry {
    fun area(points: List<PointF>): Float = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2

    fun valid(points: List<PointF>, width: Int, height: Int): Boolean {
        if (points.size != 4 || points.any {
            !it.x.isFinite() || !it.y.isFinite() || it.x < 0 || it.y < 0 || it.x > width - 1 || it.y > height - 1
        }) return false
        if (area(points) < 16f) return false
        val turns = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return turns.all { it > 1f } || turns.all { it < -1f }
    }
}
