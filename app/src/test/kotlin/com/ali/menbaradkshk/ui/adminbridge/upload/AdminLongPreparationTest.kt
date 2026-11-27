package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdminLongPreparationTest {
    @Test fun privatePreparationNeedsActualAssemblyCurrentLeaseAndExactFrozenIntent() = runBlocking {
        val directory = Files.createTempDirectory("minbar-prepare-test").toFile()
        try {
            val account = "a".repeat(64)
            val store = AdminUploadDraftStore(directory, account)
            val source = ByteArray(MULTIPART_CHUNK_BYTES) { 5 }
            val draft = store.prepare("fixture.opus", source.size.toLong()) { source.inputStream() }
            val lease = AdminLongUploadLease(account)
            val expected = AdminLongAssemblyReceipt(draft.id, draft.byteCount, draft.sha256)
            val stage = object : AdminLongUploadWire {
                override suspend fun begin(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState {
                    guard()
                    return AdminLongUploadState(1,draftId,manifest.totalBytes,manifest.wholeSha256,listOf(1),emptyList(),true)
                }
                override suspend fun put(draftId: String, manifest: AdminMultipartManifest, part: AdminMultipartPart,
                    bytes: ByteArray, guard: () -> Unit): AdminLongUploadState = error("unexpected PUT")
                override suspend fun complete(draftId: String, manifest: AdminMultipartManifest, guard: () -> Unit): AdminLongUploadState =
                    error("unexpected completion")
            }
            var permission = true
            var calls = 0
            var unknown = true
            val flow = AdminLongUploadOrchestration(store, { lease }, stage,
                assemblyWire = AdminLongAssemblyWire { _, guard -> guard(); expected },
                currentAssemblyLease = { lease },
                prepareWire = AdminLongPrepareWire { assembly, intent, guard ->
                    guard()
                    calls++
                    assertEquals(expected, assembly)
                    assertEquals(INTENT, intent)
                    if (unknown) throw IOException("synthetic lost acknowledgement")
                    AdminLongPreparationReceipt(assembly.draftId, assembly.totalBytes, assembly.wholeSha256)
                },
                currentPrepareLease = { lease.takeIf { permission } },
            )
            assertThrows(IOException::class.java) { flow.confirmPreparation(expected, INTENT) }
            val parts = flow.send(flow.confirmAfterReview(flow.review(draft)))
            assertThrows(IOException::class.java) { flow.confirmPreparation(expected, INTENT) }
            assertEquals(expected, flow.assemble(flow.confirmAssembly(parts)))
            val approved = flow.confirmPreparation(expected, INTENT)
            permission = false
            assertTrue(runCatching { flow.prepare(approved) }.exceptionOrNull() is IOException)
            assertEquals(0, calls)
            permission = true
            assertNull(flow.savedPreparation(expected))
            val firstAttempt = flow.confirmPreparation(expected, INTENT)
            assertTrue(runCatching { flow.prepare(firstAttempt) }.exceptionOrNull() is IOException)
            assertEquals(1, calls)
            assertEquals(INTENT, AdminUploadDraftStore(directory, account).confirmedLongPreparation(draft))
            assertTrue(runCatching { flow.prepare(firstAttempt) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { flow.prepare(flow.confirmPreparation(expected, INTENT.copy(title="Replacement"))) }.exceptionOrNull() is IOException)
            assertEquals(1, calls)
            unknown = false
            assertEquals(AdminLongPreparationReceipt(expected.draftId,expected.totalBytes,expected.wholeSha256),
                flow.prepare(flow.confirmPreparation(expected,INTENT)))
            assertEquals(2, calls)
            assertEquals(listOf(draft),store.list())
        } finally { directory.deleteRecursively() }
    }

    @Test fun privatePrepareWireMatchesExactClosedContract() = runBlocking {
        val id = "01d60c03-a988-41a5-956e-8ebd9a0ed0a5"
        val sha = "b".repeat(64)
        val assembly = AdminLongAssemblyReceipt(id,8388608,sha)
        val expected = AdminLongUploadReceipt(id,8388608,sha)
        val reply = "{\"draftId\":\"$id\",\"totalBytes\":8388608,\"wholeSha256\":\"$sha\",\"prepared\":true}"
        val wire = AdminLongUploadHttpWire { request, guard ->
            guard()
            assertEquals(LongUploadMethod.POST,request.method)
            assertEquals("/admin/private-long-drafts/$id/prepare",request.path)
            assertEquals("application/json",request.contentType)
            assertEquals("{\"title\":\"درس \\\"جديد\\\"\",\"speaker\":\"\",\"description\":\"سطر\\nثان\",\"categoryId\":\"c\",\"subcategoryId\":\"s\",\"expectedCategoryName\":\"القسم\",\"expectedSubcategoryName\":\"الفرع\",\"totalBytes\":8388608,\"wholeSha256\":\"$sha\"}",
                request.body!!.decodeToString())
            LongUploadResponse(200,"application/json",reply.encodeToByteArray().size.toLong(),reply.byteInputStream())
        }
        assertEquals(AdminLongPreparationReceipt(id,8388608,sha),wire.prepare(assembly,INTENT){})
        for (other in listOf(reply.replace("prepared","assembled"), reply.replace("true","false"),
            reply.replace("true","true,\"publicUrl\":\"bad\""), reply.replace("true","true,\"prepared\":true"),
            reply.replace(sha,"c".repeat(64)), reply.replace("8388608","8388609")))
            assertThrows(IOException::class.java) { decodeLongProof(other.encodeToByteArray(),expected,"prepared") }
    }

    private companion object {
        val INTENT = AdminLongPrepareIntent("درس \"جديد\"", "", "سطر\nثان", "c", "s", "القسم", "الفرع")
    }
}
