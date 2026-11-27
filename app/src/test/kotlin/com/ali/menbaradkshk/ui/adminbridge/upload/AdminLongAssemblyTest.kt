package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdminLongAssemblyTest {
    @Test fun separateAssemblyRequiresCompletedPartsAndEachExplicitCurrentConfirmation() = runBlocking {
        val base = Files.createTempDirectory("minbar-assembly-").toFile()
        try {
            val account = "a".repeat(64)
            val store = AdminUploadDraftStore(base, account)
            val source = ByteArray(MULTIPART_CHUNK_BYTES) { 3 }
            val draft = store.prepare("fixture.opus", source.size.toLong()) { ByteArrayInputStream(source) }
            val lease = AdminLongUploadLease(account)
            val claim = AdminLongUploadReceipt(draft.id, draft.byteCount, draft.sha256)
            var assemblyCap = true
            var calls = 0
            var invalid = false
            val stage = object : AdminLongUploadWire {
                override suspend fun begin(id: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState {
                    guard()
                    return AdminLongUploadState(1, id, manifest.totalBytes, manifest.wholeSha256, listOf(1), emptyList(), true)
                }
                override suspend fun put(id: String, manifest: AdminMultipartManifest, part: AdminMultipartPart,
                                         bytes: ByteArray, guard: () -> Unit): AdminLongUploadState = error("Unexpected PUT")
                override suspend fun complete(id: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState =
                    error("Unexpected complete")
            }
            val engine = AdminLongUploadOrchestration(store, { lease }, stage,
                assemblyWire = AdminLongAssemblyWire { bound, guard ->
                    guard()
                    calls++
                    AdminLongAssemblyReceipt(bound.draftId, bound.totalBytes + if (invalid) 1 else 0, bound.wholeSha256)
                },
                currentAssemblyLease = { lease.takeIf { assemblyCap } },
            )
            assertTrue(engine.canAssemble)
            assertThrows(IOException::class.java) { engine.confirmAssembly(claim) }
            val confirmed = engine.send(engine.confirmAfterReview(engine.review(draft)))
            assertEquals(claim, confirmed)
            assertEquals(0, calls)
            val revoked = engine.confirmAssembly(confirmed)
            assemblyCap = false
            assertFalse(engine.canAssemble)
            assertTrue(runCatching { engine.assemble(revoked) }.exceptionOrNull() is IOException)
            assertEquals(0, calls)
            assemblyCap = true
            invalid = true
            val failed = engine.confirmAssembly(confirmed)
            assertTrue(runCatching { engine.assemble(failed) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { engine.assemble(failed) }.exceptionOrNull() is IOException)
            assertEquals(1, calls)
            invalid = false
            assertEquals(AdminLongAssemblyReceipt(draft.id, draft.byteCount, draft.sha256),
                engine.assemble(engine.confirmAssembly(confirmed)))
            assertEquals(2, calls)
            assertEquals(listOf(draft), store.list())
        } finally { base.deleteRecursively() }
    }

    @Test fun wirePostsNoBodyAndAcceptsOnlyExactAssembledReceipt() = runBlocking {
        val id = "01d60c03-a988-41a5-956e-8ebd9a0ed0a5"
        val sha = "b".repeat(64)
        val parts = AdminLongUploadReceipt(id, 8388608, sha)
        val receipt = "{\"draftId\":\"$id\",\"totalBytes\":8388608,\"wholeSha256\":\"$sha\",\"assembled\":true}"
        var calls = 0
        val wire = AdminLongUploadHttpWire { request, guard ->
            guard()
            calls++
            assertEquals(LongUploadMethod.POST, request.method)
            assertEquals("/admin/private-long-drafts/$id/assemble", request.path)
            assertNull(request.body)
            assertNull(request.contentType)
            LongUploadResponse(200, "application/json", null, receipt.byteInputStream())
        }
        assertEquals(AdminLongAssemblyReceipt(id, 8388608, sha), wire.assemble(parts) {})
        assertEquals(1, calls)
        val invalid = listOf(receipt.replace("true", "false"), receipt.replace("true", "\"true\""),
            receipt.replace("true", "true,\"assembled\":true"),
            receipt.replace("true", "true,\"prepared\":true"), receipt.replace(sha, "c".repeat(64)),
            receipt.replace("8388608", "8388607"), receipt + "{}", receipt.replace(id, "01d60c03-a988-41a5-956e-8ebd9a0ed0a6"))
        for (bad in invalid) assertThrows(IOException::class.java) { decodeLongAssembly(bad.encodeToByteArray(), parts) }
    }
}
