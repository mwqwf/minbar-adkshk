package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewProgressWatchdogTest {
    private var now = 1_000L
    private fun dog(frames: Long = 20 * 48_000L) = PreviewProgressWatchdog(frames, { now })

    @Test fun slowButSteadyProgressNeverFailsBeforeTheCap() {
        val d = dog()
        assertEquals(3 * 20_000L + 30_000L, d.capMs)
        // 2.5× slower than real time (a slow phone): 50 s of wall time, progress every 2 s.
        repeat(25) { now += 2_000; d.progress(); d.check() }
    }

    @Test fun noProgressForTenSecondsIsAStall() {
        val d = dog()
        now += 9_999; d.check()
        now += 1
        assertTrue(assertThrows(IOException::class.java) { d.check() }.message!!.contains("stalled"))
    }

    @Test fun progressResetsTheStallTimer() {
        val d = dog()
        now += 9_000; d.progress()
        now += 9_000; d.check()
    }

    @Test fun overallCapStopsEvenWithProgress() {
        val d = dog()
        while (now - 1_000L < d.capMs - 1_000) { now += 1_000; d.progress(); d.check() }
        now += 1_000; d.progress()
        assertTrue(assertThrows(IOException::class.java) { d.check() }.message!!.contains("cap"))
    }

    @Test fun oldFixedDeadlineWouldHaveFailedWhereWatchdogPasses() {
        // Old rule: 20 s excerpt + 20 s → 40 s. A device needing 45 s with steady progress now passes.
        val d = dog()
        repeat(45) { now += 1_000; d.progress(); d.check() }
    }

    @Test fun preBufferTakesExactlyTheExcerptAndRejectsMisalignedOrExtraFrames() {
        val excerpt = PreviewPcmBuffer(3, exact = false)
        assertTrue(excerpt.append(ByteArray(8) { 1 }, 8))
        assertFalse(excerpt.full)
        assertTrue(excerpt.append(ByteArray(8) { 2 }, 8)) // only 4 bytes fit; the rest is dropped
        assertTrue(excerpt.full)
        assertFalse(excerpt.append(ByteArray(4), 4))
        assertEquals(listOf<Byte>(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2), excerpt.bytes.toList())
        assertThrows(IOException::class.java) { PreviewPcmBuffer(3, exact = false).append(ByteArray(6), 6) }
        val whole = PreviewPcmBuffer(1, exact = true)
        assertThrows(IOException::class.java) { whole.append(ByteArray(8), 8) }
    }
}
