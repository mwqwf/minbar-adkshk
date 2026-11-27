package com.ali.menbaradkshk.data

import com.google.android.play.core.assetpacks.model.AssetPackStatus as Status
import org.junit.Assert.*
import org.junit.Test

/** Native Gradle tests use the actual Google Play constants, not the standalone harness fakes. */
class SplitPackPolicyTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val packs = listOf("core", "rest")
    private fun read(body: String, known: Set<String> = setOf(a,b)) =
        LibraryPackMembership.read(("MINBAR-PAD-MEMBERSHIP-1\n" + body).byteInputStream(), packs, known)

    @Test fun exactCanonicalMembershipCoversEachHashAndPack() {
        assertEquals(mapOf(a to "core", b to "rest"), read("$a core\n$b rest\n"))
        assertNull(read("$a core\n", setOf(a)))
        assertNull(read("$a core\n$b rest\n", setOf(a)))
        assertNull(read("$b rest\n$a core\n"))
        assertNull(read("$a core\n$a rest\n"))
        assertNull(read("$a core\n$b unknown\n"))
        assertNull(read("$a core\n$b rest"))
    }

    @Test fun foregroundObserverSuppressesDuplicatesAndStaleSnapshots() {
        val observer = PlayQueueWakeGate()
        assertTrue(observer.live("core", Status.DOWNLOADING).isEmpty())
        val old = observer.probe()
        assertEquals(setOf("core"), observer.live("core", Status.COMPLETED))
        assertTrue(observer.live("core", Status.COMPLETED).isEmpty())
        assertEquals(setOf("rest"), observer.snapshot(old, mapOf("core" to Status.FAILED, "rest" to Status.CANCELED)))
        assertTrue(observer.snapshot(observer.probe(), mapOf("rest" to Status.CANCELED)).isEmpty())
        assertTrue(observer.live("core", Status.PENDING).isEmpty())
        assertEquals(setOf("core"), observer.live("core", Status.FAILED))
    }

    @Test fun freshObserverAllowsAFirstCompletedSnapshot() {
        val observer = PlayQueueWakeGate()
        assertEquals(setOf("core"), observer.snapshot(observer.probe(),
            mapOf("core" to Status.COMPLETED, "rest" to Status.WAITING_FOR_WIFI)))
        assertFalse(LibraryPackPolicy.deferSnapshot(LibraryPackMode.AUTOMATIC, listOf(null, Status.PENDING)))
    }
}
