package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.*
import org.junit.Test

class LibraryPackRequestGateTest {
    @Test fun manualTapPromotesPendingPassiveProbeInOptOutWithoutDuplicateFetch() {
        val gate = LibraryPackRequestGate()
        val ticket = requireNotNull(gate.request(explicit = false))
        assertFalse(gate.startingManual)
        assertFalse(LibraryPackPolicy.canFetch(LibraryPackMode.DISABLED, true, gate.explicitForProbe(ticket)!!))
        assertNull(gate.request(explicit = true))
        assertTrue(gate.startingManual)
        for (mode in listOf(LibraryPackMode.DISABLED, LibraryPackMode.PAUSED)) {
            assertTrue(LibraryPackPolicy.canFetch(mode, true, gate.explicitForProbe(ticket)!!))
        }
        assertTrue(gate.startFetch(ticket))
        assertNull(gate.request(explicit = true))
        assertFalse(gate.startFetch(ticket))
        gate.finish(ticket)
        assertFalse(gate.startingManual)
        val next = requireNotNull(gate.request(explicit = false))
        assertFalse(gate.explicitForProbe(next)!!)
    }

    @Test fun pauseCancelOrBackgroundInvalidatesOldCallbacksAndManualPromotion() {
        val gate = LibraryPackRequestGate()
        val previous = requireNotNull(gate.request(explicit = false))
        gate.request(explicit = true)
        gate.invalidate()
        assertNull(gate.explicitForProbe(previous))
        assertFalse(gate.startFetch(previous))
        assertFalse(gate.startingManual)
        val current = requireNotNull(gate.request(explicit = false))
        assertNotEquals(previous, current)
        gate.finish(previous)
        assertTrue(gate.isCurrent(current))
        assertFalse(gate.explicitForProbe(current)!!)
        assertTrue(LibraryPackPolicy.canFetch(LibraryPackMode.AUTOMATIC, true, false))
    }

    @Test fun cancelledTaskClearsOnlyOptimisticPendingNotRealDeliveryOrCompletedPacks() {
        val pendingAndActive = mapOf("core" to AssetPackStatus.PENDING, "rest" to AssetPackStatus.TRANSFERRING)
        assertEquals(mapOf("core" to AssetPackStatus.CANCELED, "rest" to AssetPackStatus.TRANSFERRING),
            canceledOptimisticLibraryFetch(pendingAndActive, listOf("core", "rest")))
        val completedAndHeld = mapOf("core" to AssetPackStatus.COMPLETED, "rest" to AssetPackStatus.WAITING_FOR_WIFI)
        assertEquals(completedAndHeld, canceledOptimisticLibraryFetch(completedAndHeld, listOf("core", "rest")))
    }
}
