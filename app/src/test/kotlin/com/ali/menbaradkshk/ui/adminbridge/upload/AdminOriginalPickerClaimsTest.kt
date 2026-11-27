package com.ali.menbaradkshk.ui.adminbridge.upload

import org.junit.Assert.*
import org.junit.Test

class AdminOriginalPickerClaimsTest {
    private val claim = AdminOriginalPickerClaims<String>()
    private fun deliver(route: String? = "owner", document: String? = "content://fixture/audio") =
        claim.accept(route, resultOk = true, contentOnly = true, readGrant = true, hasClipData = false, document)

    @Test fun routeClearKeepsOldCallbackSlotAndDropsLateActivityResultBeforeNextAccount() {
        assertNotNull(claim.begin("previous", "previous"))
        claim.revokeRoute()
        assertNull(claim.begin("owner", "owner"))
        assertNull(deliver("owner"))
        assertNotNull(claim.begin("owner", "owner"))
        assertEquals(AdminOriginalPickerClaims.Result("owner", "content://fixture/audio"), deliver())
    }

    @Test fun sameAccountReopeningCannotInheritClosedRouteResult() {
        assertNotNull(claim.begin("owner", "owner"))
        claim.revokeRoute()
        assertNull(claim.begin("owner", "owner"))
        assertNull(deliver())
        assertNotNull(claim.begin("owner", "owner"))
        assertNotNull(deliver())
    }

    @Test fun platformPauseDoesNotRevokeAndOnlyOneCallbackConsumes() {
        assertNotNull(claim.begin("owner", "owner"))
        assertNull(claim.begin("owner", "owner"))
        assertNotNull(deliver())
        assertNull(deliver())
    }

    @Test fun newRegistryClaimInstanceCannotInheritOutstandingLaunch() {
        assertNotNull(claim.begin("owner", "owner"))
        val next = AdminOriginalPickerClaims<String>()
        assertNull(next.accept("owner", true, true, true, false, "stale"))
        assertNotNull(next.begin("owner", "owner"))
        assertNotNull(next.accept("owner", true, true, true, false, "fresh"))
    }

    @Test fun requiresActualSameCurrentlyOpenRoute() {
        assertNull(claim.begin("owner", null))
        assertNull(claim.begin("owner", "different"))
        assertNotNull(claim.begin("owner", "owner"))
        assertNull(deliver("different"))
        assertNotNull(claim.begin("owner", "owner"))
        assertNull(deliver(null))
    }

    @Test fun malformedOrCanceledCallbacksDrainWithoutKeepingOrOpeningUri() {
        val cases = listOf(
            listOf(false, true, true, false), listOf(true, false, true, false),
            listOf(true, true, false, false), listOf(true, true, true, true),
        )
        for ((ok, content, read, clips) in cases) {
            assertNotNull(claim.begin("owner", "owner"))
            assertNull(claim.accept("owner", ok, content, read, clips, "object"))
        }
        assertNotNull(claim.begin("owner", "owner"))
        assertNull(deliver(document = null))
        assertNotNull(claim.begin("owner", "owner"))
    }

    @Test fun onlyTheExactFailedLaunchFreesItsSlot() {
        val first = claim.begin("owner", "owner")!!
        claim.launchFailed(first)
        val second = claim.begin("owner", "owner")!!
        claim.launchFailed(first)
        assertNull(claim.begin("owner", "owner"))
        assertNotNull(deliver())
        claim.launchFailed(second)
    }
}
