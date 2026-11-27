package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayPackRemovalTest {
    @Test fun installedCoreAndLateRestCompletionAreBothRemovedThroughPlay() = runBlocking {
        val state = mutableMapOf("core" to COMPLETED, "rest" to TRANSFERRING)
        val locations = mutableSetOf("core")
        val removals = mutableListOf<String>()
        val result = PlayPackRemoval.unconfirmed(listOf("core", "rest"), state::get, { it in locations },
            remove = { pack ->
                assertTrue(locations.remove(pack))
                removals += pack
                // Simulate successful removal with COMPLETED still cached by Play.
                true
            }, settle = {
                if (state["rest"] == TRANSFERRING) {
                    locations += "rest"
                    state["rest"] = COMPLETED
                }
            })
        assertEquals(emptySet<String>(), result)
        assertEquals(listOf("core", "rest"), removals)
        assertTrue(locations.isEmpty())
    }

    @Test fun failureOrNeverSettledTransferIsNotReportedAsRemoved() = runBlocking {
        val attempts = mutableListOf<String>()
        val result = PlayPackRemoval.unconfirmed(listOf("core", "rest"),
            { if (it == "core") COMPLETED else TRANSFERRING }, { it == "core" },
            remove = { attempts += it; false }, settle = {}, attempts = 3)
        assertEquals(setOf("core", "rest"), result)
        assertEquals(listOf("core"), attempts)
    }

    @Test fun unobservableAndUnrecognizedStatesAreNotProofOfAbsence() = runBlocking {
        assertEquals(setOf("core", "rest"), PlayPackRemoval.unconfirmed(listOf("core", "rest"),
            { if (it == "core") null else UNKNOWN }, { false },
            remove = { error("must not remove") }, settle = {}))
    }

    @Test fun canceledThenLateInstallDoesNotRacePastAbsenceCheck() = runBlocking {
        var progress = CANCELED
        var installed = false
        var removed = false
        val result = PlayPackRemoval.unconfirmed(listOf("rest"), { progress }, { installed },
            remove = { removed = true; installed = false; true },
            settle = {
                if (progress == CANCELED) { progress = COMPLETED; installed = true }
            })
        assertTrue(removed)
        assertTrue(result.isEmpty())
    }

    @Test fun aSuccessfulTaskWithRemainingLocationStillReportsUnconfirmed() = runBlocking {
        assertEquals(setOf("rest"), PlayPackRemoval.unconfirmed(listOf("rest"),
            { COMPLETED }, { true }, remove = { true }, settle = {}))
    }
}
