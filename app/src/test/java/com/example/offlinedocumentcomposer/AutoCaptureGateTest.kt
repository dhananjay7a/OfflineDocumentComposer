package com.example.offlinedocumentcomposer

import com.example.offlinedocumentcomposer.presentation.scanner.AutoCaptureGate
import org.junit.Assert.*
import org.junit.Test

class AutoCaptureGateTest {
    private val corners = listOf(.1f to .1f,.9f to .1f,.9f to .9f,.1f to .9f)
    @Test fun capturesOnlyAfterStableInterval() {
        val gate = AutoCaptureGate()
        assertFalse(gate.update(corners,true,1000))
        assertFalse(gate.update(corners,true,1600))
        assertTrue(gate.update(corners,true,1800))
        assertFalse(gate.update(corners,true,5000))
    }
    @Test fun movementOrPoorQualityRestartsStability() {
        val gate = AutoCaptureGate()
        gate.update(corners,true,1000)
        val moved = corners.map { (x,y) -> x+.04f to y }
        assertFalse(gate.update(moved,true,1800))
        assertFalse(gate.update(moved,false,2400))
        assertFalse(gate.update(moved,true,2600))
        assertTrue(gate.update(moved,true,3400))
    }
    @Test fun documentMustLeaveBeforeSameFrameCanCaptureAgain() {
        val gate = AutoCaptureGate()
        gate.update(corners,true,1000); assertTrue(gate.update(corners,true,1800))
        gate.update(null,false,2000)
        assertFalse(gate.update(corners,true,2200))
        gate.update(null,false,4000)
        assertFalse(gate.update(corners,true,4200))
        assertTrue(gate.update(corners,true,5000))
    }
}
