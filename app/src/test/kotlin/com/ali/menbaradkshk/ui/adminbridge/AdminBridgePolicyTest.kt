package com.ali.menbaradkshk.ui.adminbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminBridgePolicyTest {
    @Test fun onlyExactServerRolesGrantUiAccess() {
        assertEquals(AdminRole.Owner, verifiedAdminRole("owner"))
        assertEquals(AdminRole.Supervisor, verifiedAdminRole("admin"))
        assertEquals(AdminRole.Supervisor, verifiedAdminRole("supervisor"))
        for (role in listOf("blocked", "", "OWNER", "guest", "super-admin", " owner")) {
            assertNull(verifiedAdminRole(role))
        }
    }

    @Test fun codeAlphabetMatchesCurrentServerIssuer() {
        assertEquals("ABCDEFG2", normalizedAdminCode("  abcdefg2 "))
        for (code in listOf("ABCDEF12", "ABCDEFI2", "ABCDEFO2", "ABC DEF2", "١٢٣٤٥٦٧٨", "ABCDEF23x")) {
            assertNull(normalizedAdminCode(code))
        }
    }
}
