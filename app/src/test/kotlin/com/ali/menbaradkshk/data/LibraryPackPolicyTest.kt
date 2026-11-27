package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.*
import org.junit.Test

class LibraryPackPolicyTest {
    @Test fun defaultAutomaticallyFetchesOnlyInForeground() {
        assertEquals(LibraryPackMode.AUTOMATIC, LibraryPackMode.restore(""))
        assertTrue(LibraryPackPolicy.canFetch(LibraryPackMode.AUTOMATIC, foreground = true, explicit = false))
        assertFalse(LibraryPackPolicy.canFetch(LibraryPackMode.AUTOMATIC, foreground = false, explicit = false))
    }

    @Test fun userStopAndPauseSurviveNextLaunchAndManualRequestDoesNotRequireOptIn() {
        for (mode in listOf(LibraryPackMode.PAUSED, LibraryPackMode.DISABLED)) {
            val restored = LibraryPackMode.restore(mode.name)
            assertEquals(mode, restored)
            assertFalse(LibraryPackPolicy.canFetch(restored, foreground = true, explicit = false))
            assertFalse(LibraryPackPolicy.canFetch(restored, foreground = false, explicit = true))
            assertTrue(LibraryPackPolicy.canFetch(restored, foreground = true, explicit = true))
            assertTrue(LibraryPackPolicy.deferSnapshot(restored, emptyList()))
            assertTrue(LibraryPackPolicy.deferSnapshot(restored, listOf(AssetPackStatus.COMPLETED)))
        }
        assertEquals(LibraryPackMode.DISABLED, LibraryPackMode.restore("UNSUPPORTED"))
    }

    @Test fun manuallyRequestedArchiveCanBeCanceledWithoutReenablingAutomatic() {
        for (mode in listOf(LibraryPackMode.PAUSED, LibraryPackMode.DISABLED)) {
            assertTrue(LibraryPackPolicy.canCancelManual(mode, starting = true, emptyList()))
            assertTrue(LibraryPackPolicy.canCancelManual(mode, starting = false,
                listOf(AssetPackStatus.COMPLETED, AssetPackStatus.WAITING_FOR_WIFI)))
            assertFalse(LibraryPackPolicy.canCancelManual(mode, starting = false,
                listOf(AssetPackStatus.COMPLETED, AssetPackStatus.CANCELED)))
            assertFalse(LibraryPackPolicy.canFetch(mode, foreground = true, explicit = false))
        }
        assertFalse(LibraryPackPolicy.canCancelManual(LibraryPackMode.AUTOMATIC,
            starting = true, listOf(AssetPackStatus.DOWNLOADING)))
    }

    @Test fun activePlayStatusNotOriginalInstallAgeControlsDefaultArchiveDeferral() {
        val auto = LibraryPackMode.AUTOMATIC
        for (state in listOf(AssetPackStatus.PENDING, AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING,
            AssetPackStatus.WAITING_FOR_WIFI, AssetPackStatus.REQUIRES_USER_CONFIRMATION)) {
            assertTrue(LibraryPackPolicy.deferSnapshot(auto, listOf(AssetPackStatus.PENDING, state)))
            assertTrue(LibraryPackPolicy.deferSnapshot(auto, listOf(state)))
            assertFalse(LibraryPackPolicy.deferSnapshot(auto, listOf(AssetPackStatus.COMPLETED, state)))
            assertFalse(LibraryPackPolicy.deferSnapshot(auto, listOf(null, state)))
        }
        for (state in listOf(AssetPackStatus.NOT_INSTALLED, AssetPackStatus.UNKNOWN,
            AssetPackStatus.CANCELED, AssetPackStatus.FAILED, AssetPackStatus.COMPLETED)) {
            assertFalse(LibraryPackPolicy.deferSnapshot(auto, listOf(state)))
        }
        assertFalse(LibraryPackPolicy.deferSnapshot(auto, emptyList()))
    }
}
