package com.ali.menbaradkshk.ui.adminbridge.upload

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.os.Looper
import com.ali.menbaradkshk.media.OrdinaryAudioPause
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
class OrderedOpusMergeWorkerTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val d = "d".repeat(64)
    private val e = "e".repeat(64)

    @Test fun startAcceptsTwoToFourDistinctBoundedInputs() {
        assertTrue(OrderedOpusMergeWire.validStart(2, longArrayOf(10, 20), listOf(a, b)))
        assertTrue(OrderedOpusMergeWire.validStart(4, longArrayOf(1, 2, 3, 4), listOf(a, b, c, d)))
        assertFalse(OrderedOpusMergeWire.validStart(1, longArrayOf(10), listOf(a)))
        assertFalse(OrderedOpusMergeWire.validStart(5, longArrayOf(1, 1, 1, 1, 1), listOf(a, b, c, d, e)))
        assertFalse(OrderedOpusMergeWire.validStart(2, longArrayOf(10), listOf(a, b)))
        assertFalse(OrderedOpusMergeWire.validStart(2, longArrayOf(10, 0), listOf(a, b)))
        assertFalse(OrderedOpusMergeWire.validStart(2, longArrayOf(10, MAX_ORIGINAL_BYTES + 1), listOf(a, b)))
        assertFalse(OrderedOpusMergeWire.validStart(2, longArrayOf(10, 20), listOf(a, a)))
        assertFalse(OrderedOpusMergeWire.validStart(2, longArrayOf(10, 20), listOf(a, "XYZ")))
        assertFalse(OrderedOpusMergeWire.validStart(2, null, listOf(a, b)))
    }

    @Test fun workerOnlyRunsInItsOwnNamedProcess() {
        assertTrue(OrderedOpusMergeWire.permittedProcess("com.ali.menbaradkshk", "com.ali.menbaradkshk:opusmerge"))
        assertTrue(OrderedOpusMergeWire.permittedProcess("com.ali.menbaradkshk.dev", "com.ali.menbaradkshk.dev:opusmerge"))
        assertFalse(OrderedOpusMergeWire.permittedProcess("com.ali.menbaradkshk", "com.ali.menbaradkshk"))
        assertFalse(OrderedOpusMergeWire.permittedProcess("com.ali.menbaradkshk", "com.ali.menbaradkshk:other"))
        assertFalse(OrderedOpusMergeWire.permittedProcess("com.evil.app", "com.evil.app:opusmerge"))
        assertFalse(OrderedOpusMergeWire.permittedProcess("com.ali.menbaradkshk", null))
        assertTrue(OrderedOpusMergeWire.IDLE_BIND_MS < OrderedOpusMergeWire.CODEC_BUDGET_MS)
        assertTrue(OrderedOpusMergeWire.CODEC_BUDGET_MS < OrderedOpusMergeWire.PROCESS_LIFETIME_MS)
    }

    @Test fun copyAcceptsOnlyExactLengthAndDigest() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        OrderedOpusMergeJoin.copyStream(ByteArrayInputStream(data), data.size.toLong(), sha(data), {}) { buf, n -> out.write(buf, 0, n) }
        assertTrue(out.toByteArray().contentEquals(data))
        assertThrows(IOException::class.java) {
            OrderedOpusMergeJoin.copyStream(ByteArrayInputStream(data), data.size - 1L, sha(data), {}) { _, _ -> }
        }
        assertThrows(IOException::class.java) {
            OrderedOpusMergeJoin.copyStream(ByteArrayInputStream(data.copyOf(data.size - 1)), data.size.toLong(), sha(data), {}) { _, _ -> }
        }
        val flipped = data.clone().also { it[7] = (it[7] + 1).toByte() }
        assertThrows(IOException::class.java) {
            OrderedOpusMergeJoin.copyStream(ByteArrayInputStream(flipped), data.size.toLong(), sha(data), {}) { _, _ -> }
        }
    }

    @Test fun copyStopsWhenLeaseCheckFails() {
        val data = ByteArray(300_000)
        var calls = 0
        assertThrows(IOException::class.java) {
            OrderedOpusMergeJoin.copyStream(ByteArrayInputStream(data), data.size.toLong(), sha(data),
                { if (++calls > 2) throw IOException("lease ended") }) { _, _ -> }
        }
    }

    @Test fun manifestDeclaresPrivateNonExportedIsolatedWorker() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val services = doc.getElementsByTagName("service")
        val worker = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name").endsWith("OrderedOpusMergeService") }
        assertEquals("false", worker.getAttributeNS(ns, "exported"))
        assertEquals(":opusmerge", worker.getAttributeNS(ns, "process"))
        assertEquals(0, worker.getElementsByTagName("intent-filter").length)
    }

    @Test fun hostLeaseEndsOnCloseOrWhenSessionNoLongerAllowed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var allowed = true
        val host = LocalOrderedOpusReviewHost(context, "f".repeat(64), { allowed })
        assertSame(host.lease, host.current())
        host.checkLease()
        allowed = false
        assertNull(host.current())
        assertThrows(IOException::class.java) { host.checkLease() }
        allowed = true
        assertSame(host.lease, host.current())
        host.close()
        assertNull(host.current())
        assertThrows(IOException::class.java) { host.checkLease() }
    }

    @Test fun ordinaryAudioPauseCallsRegisteredPlayerOnly() {
        shadowOf(Looper.getMainLooper()).idle()
        var paused = 0
        val hook: () -> Unit = { paused++ }
        assertTrue(OrdinaryAudioPause.pauseNow()) // nothing playing
        OrdinaryAudioPause.register(hook)
        assertTrue(OrdinaryAudioPause.pauseNow())
        assertEquals(1, paused)
        OrdinaryAudioPause.unregister({ })
        assertTrue(OrdinaryAudioPause.pauseNow())
        assertEquals(2, paused)
        OrdinaryAudioPause.unregister(hook)
        assertTrue(OrdinaryAudioPause.pauseNow())
        assertEquals(2, paused)
    }
}
