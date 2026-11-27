package com.ali.menbaradkshk.ui.adminbridge.upload

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class OrderedOpusReviewSessionTest {
    private class Fixture(val dir: File, privateScratch: DormantMergeScratchStore? = null) {
        val account = "3".repeat(64)
        val store = AdminUploadDraftStore(dir, account)
        val first = store.prepare("one", 2) { byteArrayOf(2,3).inputStream() }
        val second = store.prepare("two", 2) { byteArrayOf(4,5).inputStream() }
        val order = listOf(second, first)
        val scratch = File(dir, "scratch").apply { check(mkdir()) }
        val timer = Executors.newSingleThreadScheduledExecutor()
        @Volatile var leased = true
        var onLeaseCheck: () -> Unit = {}
        val session = OrderedOpusReviewSession(store, account, scratch, timer, {
            onLeaseCheck()
            if (!leased) throw IOException("lease ended")
        }, privateScratch = privateScratch)
        val payload = checkNotNull(javaClass.getResourceAsStream("/derived-opus-native.ogg")).use { it.readBytes() }
        fun run(port: OrderedOpusReviewSession.Port, budgetMs: Long = 5000,
                verify: (File) -> DerivedOpusLocalDraft.PlaybackEvidence = { proof(it, 9289L) }) =
            session.buildLocalReview(order, 9289, 1, "review", MILLISECONDS.toNanos(budgetMs), 4000, port, verify)
        fun originals() {
            check(store.stageSnapshot(first.id, 20).second.contentEquals(byteArrayOf(2,3)))
            check(store.stageSnapshot(second.id, 20).second.contentEquals(byteArrayOf(4,5)))
            check(scratch.listFiles()!!.isEmpty())
        }
    }
    private fun using(block: (Fixture) -> Unit) {
        val dir = Files.createTempDirectory("opus-review-session").toFile()
        val fixture = Fixture(dir)
        try { block(fixture) } finally { fixture.timer.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun killedBeforeExplicitSaveReclaimsOnlyTheCandidate() = checkKilledProcess(false)
    @Test fun killedAfterExplicitSavePreservesReviewedDraftAndOriginals() = checkKilledProcess(true)

    private fun checkKilledProcess(save: Boolean) {
        val dir = Files.createTempDirectory("opus-review-kill").toFile()
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-XX:-UsePerfData", "-cp", System.getProperty("java.class.path"),
            ReviewChild::class.java.name, dir.path, save.toString())
            .redirectErrorStream(true).redirectOutput(File(dir, "child.log")).start()
        try {
            val ready = File(dir, "ready")
            val until = System.nanoTime() + SECONDS.toNanos(20)
            while (!ready.exists() && process.isAlive && System.nanoTime() < until) Thread.sleep(15)
            check(ready.exists()) { File(dir, "child.log").readText() }
            val (partPath, firstId, secondId, savedId, hash) = ready.readLines()
            val part = File(partPath)
            val scratch = DormantMergeScratchStore(File(dir, "no_backup"),
                DormantMergeScratchStore.Layout.OrderedOpus)
            val attemptDirectory = requireNotNull(part.parentFile)
            val sentinel = File(attemptDirectory.parentFile, "unrelated").apply { writeText("untouched") }
            scratch.recoverAbandoned()
            check(part.exists() == !save)
            process.destroyForcibly()
            check(process.waitFor(10, SECONDS))
            check(part.exists() == !save)
            scratch.recoverAbandoned()
            check(!part.exists() && !attemptDirectory.exists() && sentinel.readText() == "untouched")
            val store = AdminUploadDraftStore(dir, "3".repeat(64))
            check(store.list().size == if (save) 3 else 2)
            check(store.stageSnapshot(firstId, 4000).second.contentEquals(byteArrayOf(2,3)))
            check(store.stageSnapshot(secondId, 4000).second.contentEquals(byteArrayOf(4,5)))
            if (save) {
                val bytes = store.stageSnapshot(savedId, 4000).second
                check(MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) } == hash)
            }
        } finally {
            process.destroyForcibly()
            process.waitFor(10, SECONDS)
            dir.deleteRecursively()
        }
    }

    object ReviewChild {
        @JvmStatic fun main(args: Array<String>) {
            val dir = File(args[0])
            val scratch = DormantMergeScratchStore(File(dir, "no_backup").apply { check(mkdir()) },
                DormantMergeScratchStore.Layout.OrderedOpus)
            val f = Fixture(dir, scratch)
            lateinit var peer: Peer
            val candidate = f.session.prepareLocalReview(f.order, 9289, 1, "review", SECONDS.toNanos(5),
                4000, OrderedOpusReviewSession.Port { job, die ->
                    Peer(frame(job.generation, f.payload).inputStream(), die).also { peer = it }
                }) { proof(it, 9289) }
            peer.died()
            val path = candidate.withCandidate { file, _ -> file.path }
            val saved = if (args[1].toBoolean()) candidate.saveForReview().id else "-"
            val ready = File(dir, "pending").apply {
                writeText(listOf(path, f.first.id, f.second.id, saved, candidate.sha256).joinToString("\n"))
            }
            check(ready.renameTo(File(dir, "ready")))
            Thread.sleep(60_000)
        }
    }

    private class Peer(val contents: InputStream, val died: () -> Unit, val closeOnRetire: Boolean = true) :
        OrderedOpusReviewSession.Endpoint {
        val retired = CountDownLatch(1)
        val retireCount = AtomicInteger()
        override fun open() = contents
        override fun requestRetire() {
            check(retireCount.incrementAndGet() == 1)
            retired.countDown()
            if (closeOnRetire) contents.close()
        }
    }

    @Test fun completeThenWaitForExactEndpointDeathAndVerifyRetry() = using { f ->
        lateinit var firstPeer: Peer
        val saved = f.run(OrderedOpusReviewSession.Port { request, died ->
            check(request.generation == 1L && request.orderedOriginals == f.order)
            Peer(frame(request.generation, f.payload).inputStream(), died).also { firstPeer = it }
        })
        check(f.store.stageSnapshot(saved.id, 4000).second.contentEquals(f.payload))
        check(firstPeer.retired.await(1, SECONDS))
        check(runCatching { f.run(OrderedOpusReviewSession.Port { _, _ -> error("must not open another") }) }
            .exceptionOrNull() is IOException)
        firstPeer.died()
        val second = f.run(OrderedOpusReviewSession.Port { request, died ->
            check(request.generation == 2L)
            firstPeer.died() // Duplicate old death cannot retire or release this endpoint.
            Peer(frame(request.generation, f.payload).inputStream(), died)
        })
        check(second == saved && f.store.list().size == 3)
        f.originals()
    }

    @Test fun wrongStablePlaybackLengthOrRevokedLeaseLeavesOnlyOriginals() = using { f ->
        lateinit var peer: Peer
        for (cancel in listOf(false, true)) {
            val result = runCatching {
                f.run(OrderedOpusReviewSession.Port { req, died ->
                    Peer(frame(req.generation, f.payload).inputStream(), died).also { peer = it }
                }, verify = {
                    if (cancel) f.leased = false
                    proof(it, if (cancel) 9289 else 9600)
                })
            }
            check(result.exceptionOrNull() is IOException)
            check(peer.retired.await(1, SECONDS) && f.store.list().size == 2)
            peer.died()
            f.originals()
        }
    }

    @Test fun exactDeathAfterCompletePipeButBeforeIndependentProofRejects() = using { f ->
        lateinit var peer: Peer
        val result = runCatching {
            f.run(OrderedOpusReviewSession.Port { req, died ->
                Peer(frame(req.generation, f.payload).inputStream(), died).also { peer = it }
            }, verify = { peer.died(); proof(it, 9289) })
        }
        check(result.exceptionOrNull() is IOException && f.store.list().size == 2)
        f.originals()
    }

    @Test fun timerRetiresIndependentlyOfAStalledFrameAndBlocksRetryUntilDeath() = using { f ->
        val readEntered = CountDownLatch(1)
        val unblocked = CountDownLatch(1)
        val blocking = object : InputStream() {
            override fun read(): Int {
                readEntered.countDown()
                check(unblocked.await(3, SECONDS))
                return -1
            }
            override fun close() { unblocked.countDown() }
        }
        lateinit var peer: Peer
        val io = Executors.newSingleThreadExecutor()
        try {
            val outcome = io.submit<Throwable?> {
                runCatching {
                    f.run(OrderedOpusReviewSession.Port { _, died -> Peer(blocking, died).also { peer = it } }, 500)
                }.exceptionOrNull()
            }
            check(readEntered.await(2, SECONDS))
            check(peer.retired.await(2, SECONDS) && outcome.get(2, SECONDS) is IOException)
            check(runCatching { f.run(OrderedOpusReviewSession.Port { _, _ -> error("must not reopen") }) }
                .exceptionOrNull() is IOException)
            peer.died()
            check(f.run(OrderedOpusReviewSession.Port { job, dead ->
                Peer(frame(job.generation, f.payload).inputStream(), dead)
            }).byteCount == f.payload.size.toLong())
            f.originals()
        } finally { io.shutdownNow() }
    }

    @Test fun staleGenerationAndDeclaredOversizeFailBeforeIndependentPlayback() = using { f ->
        lateinit var peer: Peer
        for (oversize in listOf(false, true)) {
            val result = runCatching {
                f.run(OrderedOpusReviewSession.Port { job, died ->
                    val bytes = frame(if (oversize) job.generation else job.generation + 1,
                        f.payload, if (oversize) 4001 else f.payload.size.toLong())
                    Peer(bytes.inputStream(), died).also { peer = it }
                }, verify = { error("must not inspect") })
            }
            check(result.exceptionOrNull() is IOException && f.store.list().size == 2)
            peer.died()
            f.originals()
        }
    }

    @Test fun callerCancelBeforeAdmissionNeverStartsAWorker() = using { f ->
        val port = OrderedOpusReviewSession.Port { _, _ -> error("Must not bind or prepare a retired caller") }
        val failure = runCatching {
            f.session.prepareLocalReview(f.order, 9289, 1, "review", SECONDS.toNanos(5),
                4000, port, checkCaller = { throw IOException("caller stopped") }) {
                error("Must not decode")
            }
        }.exceptionOrNull()
        check(failure is IOException && f.store.list().size == 2)
        f.originals()
    }

    @Test fun callerCancelBetweenWorkerReceiptAndProofPreventsDraft() = using { f ->
        var live = true
        lateinit var peer: Peer
        val failure = runCatching {
            f.session.prepareLocalReview(f.order, 9289, 1, "review", SECONDS.toNanos(5),
                4000, OrderedOpusReviewSession.Port { job, die ->
                    Peer(frame(job.generation, f.payload).inputStream(), die).also { peer = it }
                }, checkCaller = { if (!live) throw IOException("caller stopped") }) { file ->
                    live = false
                    proof(file, 9289)
                }
        }.exceptionOrNull()
        check(failure is IOException && peer.retired.await(1, SECONDS) && f.store.list().size == 2)
        peer.died()
        f.originals()
    }

    @Test fun qualificationAndExplicitAuditionDoNotKeepUntilSave() = using { f ->
        lateinit var peer: Peer
        val pending = f.session.prepareLocalReview(f.order, 9289, 1, "review", SECONDS.toNanos(5),
            4000, OrderedOpusReviewSession.Port { job, die ->
                Peer(frame(job.generation, f.payload).inputStream(), die).also { peer = it }
            }) { proof(it, 9289) }
        check(peer.retired.await(1, SECONDS) && f.scratch.listFiles()!!.size == 1)
        peer.died()
        check(f.store.list().size == 2)
        pending.withCandidate { candidate, lease ->
            lease()
            check(candidate.readBytes().contentEquals(f.payload) && pending.frames48k == 9289L)
        }
        check(f.store.list().size == 2)
        val saved = pending.saveForReview()
        check(f.store.stageSnapshot(saved.id, 4000).second.contentEquals(f.payload))
        check(runCatching { pending.saveForReview() }.exceptionOrNull() is IOException)
        f.originals()
    }

    @Test fun declineOrAccountRevocationNeverKeepAndPreventStalePreview() = using { f ->
        for (revoke in listOf(false, true)) {
            lateinit var peer: Peer
            val pending = f.session.prepareLocalReview(f.order, 9289, 1, "must-not-exist", SECONDS.toNanos(5),
                4000, OrderedOpusReviewSession.Port { job, die ->
                    Peer(frame(job.generation, f.payload).inputStream(), die).also { peer = it }
                }) { proof(it, 9289) }
            peer.died()
            pending.withCandidate { _, check -> check() }
            if (revoke) { f.leased = false; f.session.cancel() } else pending.close()
            check(runCatching { pending.withCandidate { _, _ -> error("stale audition") } }.exceptionOrNull() is IOException)
            check(runCatching { pending.saveForReview() }.exceptionOrNull() is IOException)
            check(f.store.list().size == 2)
            f.originals()
        }
    }

    @Test fun revokeAfterStoreBeginsSaveButBeforeFinalAdmissionLeavesOnlyOriginals() = using { f ->
        lateinit var peer: Peer
        val pending = f.session.prepareLocalReview(f.order, 9289, 1, "cancelled-save", SECONDS.toNanos(5),
            4000, OrderedOpusReviewSession.Port { job, die ->
                Peer(frame(job.generation, f.payload).inputStream(), die).also { peer = it }
            }) { proof(it, 9289) }
        peer.died()
        val accountDirectory = File(File(f.dir, "admin-upload-drafts-v1"), f.account)
        var sawUncommitted = false
        f.onLeaseCheck = {
            if (accountDirectory.list()!!.any { it.endsWith(".part") }) {
                sawUncommitted = true
                f.leased = false
                f.session.cancel()
            }
        }
        check(runCatching { pending.saveForReview() }.exceptionOrNull() is IOException)
        check(sawUncommitted && f.store.list().size == 2 && accountDirectory.list()!!.none { it.endsWith(".part") })
        f.originals()
    }

    @Test fun expiringUnattendedCandidateDeletesScratchWithoutKeeping() = using { f ->
        lateinit var peer: Peer
        val pending = f.session.prepareLocalReview(f.order, 9289, 1, "must-not-exist", SECONDS.toNanos(5),
            4000, OrderedOpusReviewSession.Port { job, die ->
                Peer(frame(job.generation, f.payload).inputStream(), die).also { peer = it }
            }, reviewBudgetNs = MILLISECONDS.toNanos(80)) { proof(it, 9289) }
        peer.died()
        for (i in 0 until 100) {
            if (f.scratch.listFiles()!!.isEmpty()) break
            Thread.sleep(20)
        }
        check(f.store.list().size == 2)
        check(runCatching { pending.saveForReview() }.exceptionOrNull() is IOException)
        f.originals()
    }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun proof(file: File, frames: Long) = DerivedOpusLocalDraft.PlaybackEvidence(
            digest(file.readBytes()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }, frames)
        private fun frame(generation: Long, bytes: ByteArray, size: Long = bytes.size.toLong()): ByteArray =
            ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use {
                    it.writeInt(OpusWorkerResultPipe.MAGIC); it.writeLong(generation); it.writeLong(size)
                    it.write(digest(bytes)); it.write(bytes)
                }
            }.toByteArray()
    }
}
