package com.ali.menbaradkshk.ui.adminbridge.share

import com.ali.menbaradkshk.ui.adminbridge.upload.AdminUploadDraftStore
import com.ali.menbaradkshk.ui.adminbridge.upload.DraftSizeException
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModeratorSharePolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun audioMimeAndExternalAuthoritiesOnly() {
        for (type in listOf("audio/*", "audio/mpeg", "audio/x-m4a", "audio/ogg")) {
            assertTrue(supportsModeratorAudioShareType(type))
        }
        for (type in listOf(null, "*/*", "image/jpeg", "video/mp4", "text/plain", "audio/mp4; charset=utf8")) {
            assertFalse(supportsModeratorAudioShareType(type))
        }
        val own = "com.ali.menbaradkshk"
        assertTrue(validModeratorShareAuthority("external.recorder", own))
        for (bad in listOf(null, own, "$own.fileprovider", "user@external.recorder", "file:%2F", Spaced)) {
            assertFalse(validModeratorShareAuthority(bad, own))
        }
    }

    @Test fun noSourceOpenWithoutConsentActiveAccountAndTemporaryGrant() {
        val store = AdminUploadDraftStore(folder.root, "a".repeat(64))
        val disallowed = listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))
        for ((consent, active, grant) in disallowed) {
            assertThrows(IllegalStateException::class.java) {
                prepareModeratorSharedAudio(store, consent, { active }, { grant }, {}, {}) { error("opened source before authorization") }
            }
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun exactRecordingRemainsInVerifiedAccountOnlyAndRepeatedBytesDoNotDuplicate() {
        val account = AdminUploadDraftStore(folder.root, "a".repeat(64))
        val other = AdminUploadDraftStore(folder.root, "b".repeat(64))
        val bytes = byteArrayOf(0x41, 0x42, 0x43)
        var validation = 0
        fun prepare() = prepareModeratorSharedAudio(account, true, { true }, { true }, {}, { file ->
            assertTrue(bytes.contentEquals(file.readBytes()))
            ++validation
        }) { ByteArrayInputStream(bytes) }
        val first = prepare()
        assertEquals(first, prepare())
        assertEquals(bytes.size.toLong(), first.byteCount)
        assertEquals(2, validation)
        assertEquals(listOf(first), account.list())
        assertTrue(other.list().isEmpty())
    }

    @Test fun capAndRevokedGrantLeaveNoDraft() {
        val limited = AdminUploadDraftStore(folder.root, "a".repeat(64), maxBytes = 4)
        assertThrows(DraftSizeException::class.java) {
            prepareModeratorSharedAudio(limited, true, { true }, { true }, {}, {
                error("over-cap data reached format check")
            }) { ByteArrayInputStream(ByteArray(5)) }
        }
        var active = true
        assertThrows(IllegalStateException::class.java) {
            prepareModeratorSharedAudio(limited, true, { active }, { true }, {}, {
                error("closed lease reached format check")
            }) {
                object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        active = false
                        return super.read(buffer, offset, length)
                    }
                }
            }
        }
        assertTrue(limited.list().isEmpty())
    }

    @Test fun duplicateReselectionAfterReverificationCannotCompleteOnRevokedAccountOrGrant() {
        val oldStore = AdminUploadDraftStore(folder.root, "a".repeat(64))
        val otherAccount = AdminUploadDraftStore(folder.root, "b".repeat(64))
        val bytes = byteArrayOf(7, 8, 9)
        val existing = prepareModeratorSharedAudio(oldStore, true, { true }, { true }, {}, {}) {
            ByteArrayInputStream(bytes)
        }
        val restoredStore = AdminUploadDraftStore(folder.root, "a".repeat(64))
        for (invalidateAccount in listOf(true, false)) {
            var lease = true
            var grant = true
            assertThrows(IllegalStateException::class.java) {
                prepareModeratorSharedAudio(restoredStore, true, { lease }, { grant }, {}, {
                    if (invalidateAccount) lease = false else grant = false
                }) { ByteArrayInputStream(bytes) }
            }
        }
        // Preserve the already completed draft, never duplicate it or move it to another account.
        assertEquals(listOf(existing), restoredStore.list())
        assertTrue(otherAccount.list().isEmpty())
        assertEquals(existing, prepareModeratorSharedAudio(restoredStore, true, { true }, { true }, {}, {}) {
            ByteArrayInputStream(bytes)
        })
    }

    companion object { private const val Spaced = " external.recorder" }
}
