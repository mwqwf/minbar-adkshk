package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus as Status
import org.junit.Assert.*
import org.junit.Test

class PlayPackStatusReconciliationTest {
    @Test fun staleForegroundProbeCannotHideCompletionOrMakePackEligibleForAnotherFetch() {
        val gate = PlayQueueWakeGate()
        val probe = gate.probe()
        assertEquals(setOf("core"), gate.live("core", Status.COMPLETED))
        val result = gate.reconcile(probe, mapOf("core" to Status.COMPLETED),
            mapOf("core" to Status.NOT_INSTALLED, "rest" to Status.NOT_INSTALLED), replace = true)
        assertEquals(mapOf("core" to Status.COMPLETED, "rest" to Status.NOT_INSTALLED), result.visible)
        assertEquals(mapOf("rest" to Status.NOT_INSTALLED), result.accepted)
        assertTrue(result.terminal.isEmpty())
    }

    @Test fun lateFetchFailureCannotReplaceLiveDownloadAndDoesNotLoseOtherPackFailure() {
        val gate = PlayQueueWakeGate()
        for (pack in listOf("core", "rest")) gate.live(pack, Status.PENDING)
        val fetch = gate.probe()
        gate.live("core", Status.DOWNLOADING)
        val result = gate.reconcile(fetch, mapOf("core" to Status.DOWNLOADING, "rest" to Status.PENDING),
            mapOf("core" to Status.FAILED, "rest" to Status.FAILED))
        assertEquals(mapOf("core" to Status.DOWNLOADING, "rest" to Status.FAILED), result.visible)
        assertEquals(mapOf("rest" to Status.FAILED), result.accepted)
        assertEquals(setOf("rest"), result.terminal)
    }

    @Test fun partialProbeDropsAbsentOldSnapshotButPreservesNewLiveObservation() {
        val gate = PlayQueueWakeGate()
        gate.live("rest", Status.DOWNLOADING)
        val probe = gate.probe()
        gate.live("core", Status.WAITING_FOR_WIFI)
        val previous = mapOf("core" to Status.WAITING_FOR_WIFI, "rest" to Status.DOWNLOADING)
        val result = gate.reconcile(probe, previous, emptyMap(), replace = true)
        assertEquals(mapOf("core" to Status.WAITING_FOR_WIFI), result.visible)
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.terminal.isEmpty())
    }

    @Test fun taskCancellationOnlyClearsStillOptimisticPendingNotANewerRealPendingEvent() {
        val gate = PlayQueueWakeGate()
        val prior = listOf("core", "rest").associateWith { Status.PENDING }
        prior.forEach { (pack, state) -> gate.live(pack, state) }
        val fetch = gate.probe()
        gate.live("core", Status.PENDING)
        val result = gate.reconcile(fetch, prior, canceledOptimisticLibraryFetch(prior, prior.keys.toList()))
        assertEquals(mapOf("core" to Status.PENDING, "rest" to Status.CANCELED), result.visible)
        assertEquals(setOf("rest"), result.terminal)
    }

    @Test fun freshStatusCanReplacePreviousStatusAndWakesOnce() {
        val gate = PlayQueueWakeGate()
        gate.live("core", Status.TRANSFERRING)
        val probe = gate.probe()
        val wire = mapOf("core" to Status.COMPLETED)
        val result = gate.reconcile(probe, mapOf("core" to Status.TRANSFERRING, "rest" to Status.PENDING), wire)
        assertEquals(wire, result.accepted)
        assertEquals(mapOf("core" to Status.COMPLETED, "rest" to Status.PENDING), result.visible)
        assertEquals(setOf("core"), result.terminal)
        assertTrue(gate.reconcile(gate.probe(), result.visible, wire).terminal.isEmpty())
    }
}
