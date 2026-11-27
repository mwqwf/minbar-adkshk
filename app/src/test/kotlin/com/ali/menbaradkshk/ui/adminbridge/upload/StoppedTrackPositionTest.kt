package com.ali.menbaradkshk.ui.adminbridge.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class StoppedTrackPositionTest {
    @Test fun headResetToZeroAfterStopStillReachesTotal() {
        val p = StoppedTrackPosition(headAtStop = 950_000, stoppedAtMs = 1_000, totalFrames = 960_000)
        assertEquals(950_000, p.position(rawHead = 0, nowMs = 1_000))
        assertEquals(950_000 + 4_800, p.position(rawHead = 0, nowMs = 1_100))
        assertEquals(960_000, p.position(rawHead = 0, nowMs = 2_000))
        assertEquals(960_000, p.position(rawHead = 0, nowMs = 60_000))
    }

    @Test fun realHeadAheadOfEstimateWinsAndImplausibleHeadIsIgnored() {
        val p = StoppedTrackPosition(100, 0, 1_000)
        assertEquals(900, p.position(rawHead = 900, nowMs = 1))
        assertEquals(900, p.position(rawHead = 5_000, nowMs = 2))
        assertEquals(1_000, p.position(rawHead = 0, nowMs = 1_000))
    }

    @Test fun neverGoesBackwards() {
        val p = StoppedTrackPosition(500, 0, 10_000)
        assertEquals(5_000, p.position(rawHead = 5_000, nowMs = 0))
        assertEquals(5_000, p.position(rawHead = 0, nowMs = 10))
        assertEquals(5_000, p.position(rawHead = 0, nowMs = -50))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHeadBeyondTotal() { StoppedTrackPosition(11, 0, 10) }
}
