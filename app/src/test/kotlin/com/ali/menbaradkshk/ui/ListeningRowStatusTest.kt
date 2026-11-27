package com.ali.menbaradkshk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningRowStatusTest {
    @Test fun completionUsesPersistedFlagEvenAfterPositionIsCleared() {
        assertEquals("أتممت الاستماع", listeningRowStatus(true, 0L, false))
        assertEquals("أتممت الاستماع", listeningRowStatus(true, 90_000L, true))
    }

    @Test fun savedPositionIsShownAsStopPointNotCompletion() {
        assertEquals("توقّفت عند 1:30", listeningRowStatus(false, 90_000L, false))
    }

    @Test fun briefOrActivePlaybackDoesNotDisplayStaleStopPoint() {
        assertNull(listeningRowStatus(false, 0L, false))
        assertNull(listeningRowStatus(false, 3_000L, false))
        assertNull(listeningRowStatus(false, 90_000L, true))
    }
}
