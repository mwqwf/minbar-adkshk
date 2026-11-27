package com.ali.menbaradkshk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveOrbitPolicyTest {
    @Test fun playsOnlyVisiblyWithMotionPermitted() {
        for (playing in listOf(false, true)) for (foreground in listOf(false, true)) {
            for (systemAllows in listOf(false, true)) for (appAllows in listOf(false, true)) {
                assertEquals(
                    playing && foreground && systemAllows && appAllows,
                    ArchiveOrbitPolicy.shouldRotate(playing, foreground, systemAllows, appAllows),
                )
            }
        }
    }

    @Test fun reducedMotionAndUnreadableScaleStayStill() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(ArchiveOrbitPolicy.motionAllowed(scale))
        }
        assertTrue(ArchiveOrbitPolicy.motionAllowed(1f))
    }

    @Test fun orbitRetainsPhaseAndDoesNotJumpAfterStalledFrame() {
        assertEquals(42f, ArchiveOrbitPolicy.advance(42f, 0L), .0001f)
        assertEquals(42f, ArchiveOrbitPolicy.advance(42f, -1L), .0001f)
        assertEquals(ArchiveOrbitPolicy.advance(42f, 100_000_000L), ArchiveOrbitPolicy.advance(42f, 900_000_000L), .0001f)
        assertEquals(.56f, ArchiveOrbitPolicy.advance(359.5f, 100_000_000L), .01f)
    }
}
