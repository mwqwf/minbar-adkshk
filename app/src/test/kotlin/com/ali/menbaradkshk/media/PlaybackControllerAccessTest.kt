package com.ali.menbaradkshk.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerAccessTest {
    @Test fun ownUidAndPlatformTrustedControllersRetainAccess() {
        assertTrue(PlaybackControllerAccess.canConnect(10001, 10001, false))
        assertTrue(PlaybackControllerAccess.canConnect(10001, 1000, true))
        assertTrue(PlaybackControllerAccess.canReturnSession(10001, 10001, false, true))
        assertTrue(PlaybackControllerAccess.canReturnSession(10001, 1000, true, true))
    }

    @Test fun untrustedExternalOrUnknownUidIsNotAuthorized() {
        assertFalse(PlaybackControllerAccess.canConnect(10001, 10002, false))
        assertFalse(PlaybackControllerAccess.canConnect(10001, -1, false))
        assertFalse(PlaybackControllerAccess.canConnect(-1, -1, false))
        assertFalse(PlaybackControllerAccess.canReturnSession(10001, 10002, false, true))
    }

    @Test fun hardwareAndLegacyPlaceholderLookupDoesNotGrantConnection() {
        assertTrue(PlaybackControllerAccess.canReturnSession(10001, -1, false, false))
        assertFalse(PlaybackControllerAccess.canConnect(10001, -1, false))
    }
}
