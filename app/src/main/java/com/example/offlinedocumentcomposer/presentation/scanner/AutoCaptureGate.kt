package com.example.offlinedocumentcomposer.presentation.scanner

import kotlin.math.hypot

/** One capture per stable document. Coordinates are normalized to the analysis image. */
class AutoCaptureGate {
    private var anchor: List<Pair<Float, Float>>? = null
    private var stableSince = 0L
    private var lastSeen = 0L
    private var captured: List<Pair<Float, Float>>? = null

    fun reset() { anchor = null; stableSince = 0; captured = null; lastSeen = 0 }
    fun markCaptured() { captured = anchor; stableSince = 0 }
    fun update(points: List<Pair<Float, Float>>?, suitable: Boolean, now: Long): Boolean {
        if (points == null || points.size != 4) {
            anchor = null; stableSince = 0
            if (now - lastSeen > 1200) captured = null
            return false
        }
        lastSeen = now
        fun movement(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>) = a.indices.maxOf {
            hypot(a[it].first-b[it].first,a[it].second-b[it].second)
        }
        captured?.let {
            if (movement(it,points) < .12f) return false
            captured = null; anchor = null
        }
        if (!suitable) { anchor = null; stableSince = 0; return false }
        val old = anchor
        if (old == null || movement(old,points) > .018f) {
            anchor = points; stableSince = now; return false
        }
        if (now - stableSince < 800) return false
        markCaptured()
        return true
    }
}
