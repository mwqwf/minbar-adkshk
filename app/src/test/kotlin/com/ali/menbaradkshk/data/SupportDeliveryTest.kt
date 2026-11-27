package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SupportDeliveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val device = "synthetic-device"
    private val filename = "12345678-a.m4a"
    private val thread = "synthetic-thread"
    private val key = "user/$device/support/1795440000_$filename"
    private val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val receipt = SupportUploadReceipt(true, key, digest, 3)
    private fun recording() = folder.newFile().apply { writeText("abc") }

    @Test fun verifiedDeliveryLeavesCleanupToOutboxAfterReceipt() {
        val file = recording()
        verifySupportMessageDelivery(file, device, filename, thread, { sent, name ->
            assertEquals(file, sent)
            assertEquals(filename, name)
            receipt
        }, {
            assertEquals(key, it)
            assertTrue(file.exists())
            SupportPostReceipt(true, thread)
        })
        assertTrue(file.exists())
    }

    @Test fun disjointWrongOrMalformedReceiptNeverPostsOrRemovesFile() {
        for (bad in listOf(
            receipt.copy(key = null), receipt.copy(key = ""), receipt.copy(key = true),
            receipt.copy(key = "https://example.invalid/$key"), receipt.copy(key = "$key%2f"),
            receipt.copy(key = key.replace(device, "unrelated-device")),
            receipt.copy(key = key.replace("support/", "transcripts/")),
            receipt.copy(key = key.replace(filename, "second.m4a")),
            receipt.copy(sha256 = "0".repeat(64)),
            receipt.copy(accepted = "true"), receipt.copy(sizeBytes = 3.0),
            receipt.copy(sizeBytes = 4), receipt.copy(sizeBytes = null),
        )) {
            val file = recording()
            assertThrows(IllegalArgumentException::class.java) {
                verifySupportMessageDelivery(file, device, filename, thread, { _, _ -> bad },
                    { error("Must not send a text-only partial message.") })
            }
            assertEquals("abc", file.readText())
        }
    }

    @Test fun failedPostOrMismatchedThreadLeavesDraft() {
        for (bad in listOf(SupportPostReceipt(null), SupportPostReceipt("true", thread),
            SupportPostReceipt(true), SupportPostReceipt(true, "different-thread"))) {
            val file = recording()
            assertThrows(IllegalArgumentException::class.java) {
                verifySupportMessageDelivery(file, device, filename, thread, { _, _ -> receipt }, { bad })
            }
            assertEquals("abc", file.readText())
        }
    }

    @Test fun refusingUploadOrPostLeavesAudioUnchanged() {
        val file = recording()
        val refused = IOException("synthetic refusal")
        assertEquals(refused, assertThrows(IOException::class.java) {
            verifySupportMessageDelivery(file, device, filename, null, { _, _ -> throw refused },
                { error("No post after failing to upload") })
        })
        assertEquals(refused, assertThrows(IOException::class.java) {
            verifySupportMessageDelivery(file, device, filename, null, { _, _ -> receipt }, { throw refused })
        })
        assertEquals("abc", file.readText())
    }

    @Test fun validAppendAllowsLongByteCountAndNoThreadEcho() {
        val file = recording()
        verifySupportMessageDelivery(file, device, filename, null, { _, _ -> receipt.copy(sizeBytes = 3L) }, {
            assertEquals(key, it)
            SupportPostReceipt(true)
        })
        assertTrue(file.exists())
    }

    @Test fun textOnlyNeverCallsUpload() {
        verifySupportMessageDelivery(null, device, filename, thread, { _: File, _: String -> error("No audio") }, {
            assertEquals(null, it)
            SupportPostReceipt(true, thread)
        })
    }

    @Test fun missingOrEmptyAudioNeverCallsNetwork() {
        for (file in listOf(File(folder.root, "missing"), folder.newFile())) {
            assertThrows(IllegalArgumentException::class.java) {
                verifySupportMessageDelivery(file, device, filename, null, { _, _ -> error("No upload") },
                    { error("No post") })
            }
        }
    }
}
