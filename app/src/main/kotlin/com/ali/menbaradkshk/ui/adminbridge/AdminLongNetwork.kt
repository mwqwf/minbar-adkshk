package com.ali.menbaradkshk.ui.adminbridge

import com.ali.menbaradkshk.ui.adminbridge.feature.GUARDED_LESSON_FEATURE_ANDROID_ON
import com.ali.menbaradkshk.ui.adminbridge.feature.checkGuardedLessonFeatureRequest
import com.ali.menbaradkshk.ui.adminbridge.order.GUARDED_LESSON_ORDER_ANDROID_ON
import com.ali.menbaradkshk.ui.adminbridge.order.checkGuardedLessonOrderRequest
import com.ali.menbaradkshk.adminsurface.support.checkGuardedSupportReplyRequest
import com.ali.menbaradkshk.ui.adminbridge.invites.checkGuardedInviteRequest
import com.ali.menbaradkshk.ui.adminbridge.publication.checkGuardedDiscoveryRequest
import com.ali.menbaradkshk.ui.adminbridge.publication.checkGuardedNewLessonRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadMethod
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadRequest
import com.ali.menbaradkshk.ui.adminbridge.upload.LongUploadResponse
import com.ali.menbaradkshk.ui.adminbridge.upload.MAX_LONG_STAGE_RESPONSE
import com.ali.menbaradkshk.ui.adminbridge.upload.MULTIPART_CHUNK_BYTES
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

// Enabling requires a deliberately reviewed new build AND literal capability on a verified session.
internal const val PRIVATE_LONG_ANDROID_ON = false
internal const val PRIVATE_LONG_ASSEMBLY_ANDROID_ON = false
internal const val PRIVATE_LONG_PREPARE_ANDROID_ON = false
internal const val GUARDED_NEW_LESSON_ANDROID_ON = false
internal const val GUARDED_LESSON_DISCOVERY_ANDROID_ON = false
internal const val GUARDED_INVITES_ANDROID_ON = false
internal const val GUARDED_SUPPORT_REPLY_ANDROID_ON = false
private val SESSION_TOKEN = Regex("ms_[0-9a-f]{64}")
private val ACCOUNT_KEY = Regex("[0-9a-f]{64}")
private val LONG_ROUTE = Regex(
    "/admin/private-long-drafts/[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}(?:/(?:parts/(?:[1-9]|1[0-2])|complete|assemble|prepare))?",
)

internal fun privateLongUploadAllowed(buildEnabled: Boolean, serverGrant: Boolean, token: String, accountKey: String) =
    buildEnabled && serverGrant && SESSION_TOKEN.matches(token) && ACCOUNT_KEY.matches(accountKey)

internal fun checkLongNetworkRequest(request: LongUploadRequest) {
    if (!LONG_ROUTE.matches(request.path) || request.followRedirects ||
        request.maxResponseBytes != MAX_LONG_STAGE_RESPONSE) throw IOException("Invalid private request")
    val valid = when {
        "/parts/" in request.path -> request.method == LongUploadMethod.PUT &&
            request.contentType == "application/octet-stream" &&
            request.contentLength in 1..MULTIPART_CHUNK_BYTES
        (request.path.endsWith("/complete") || request.path.endsWith("/assemble")) -> request.method == LongUploadMethod.POST &&
            request.contentType == null && request.body == null
        request.path.endsWith("/prepare") -> request.method == LongUploadMethod.POST &&
            request.contentType == "application/json" && request.contentLength in 1..MAX_LONG_STAGE_RESPONSE
        request.method == LongUploadMethod.GET -> request.contentType == null && request.body == null
        else -> request.method == LongUploadMethod.POST && request.contentType == "application/json" &&
            request.contentLength in 1..MAX_LONG_STAGE_RESPONSE
    }
    if (!valid) throw IOException("Invalid private request")
}

/** Session close atomically prevents registration and aborts already registered requests. */
internal class AdminLongNetworkCalls {
    private var closed = false
    private val running = mutableSetOf<Call>()
    @Synchronized fun add(call: Call): Boolean = !closed && running.add(call)
    @Synchronized fun remove(call: Call) { running.remove(call) }
    fun close() {
        val active = synchronized(this) {
            closed = true
            running.toList().also { running.clear() }
        }
        active.forEach { it.cancel() }
    }
}

internal object AdminLongNetwork {
    private class WireGuard(val guard: () -> Unit) {
        val allowFirst = AtomicBoolean(true)
        fun active() {
            try {
                guard()
            } catch (e: Exception) {
                throw IOException("Private request stopped", e)
            }
        }
    }

    // A network interceptor checks again immediately before actual transmission, and refuses
    // any implicit follow-up (including Retry-After responses OkHttp may otherwise follow).
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val tag = chain.request().tag(WireGuard::class.java) ?: throw IOException("Private request unbound")
                if (!tag.allowFirst.compareAndSet(true, false)) throw IOException("Private request repeated")
                tag.active()
                chain.proceed(chain.request())
            }.build()
    }

    suspend fun exchange(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkLongNetworkRequest(request)
        if (!PRIVATE_LONG_ANDROID_ON || !SESSION_TOKEN.matches(token) ||
            (request.path.endsWith("/assemble") && !PRIVATE_LONG_ASSEMBLY_ANDROID_ON) ||
            (request.path.endsWith("/prepare") && (!PRIVATE_LONG_ASSEMBLY_ANDROID_ON || !PRIVATE_LONG_PREPARE_ANDROID_ON)))
            throw IOException("Private session unavailable")
        return exchangeValidated(request, token, calls, false, beforeOpen)
    }

    suspend fun exchangeGuardedNewLesson(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkGuardedNewLessonRequest(request)
        if (!GUARDED_NEW_LESSON_ANDROID_ON || !SESSION_TOKEN.matches(token))
            throw IOException("Lesson publication session unavailable")
        return exchangeValidated(request, token, calls, true, beforeOpen)
    }

    suspend fun exchangeGuardedNewLessonDiscovery(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkGuardedDiscoveryRequest(request)
        if (!GUARDED_NEW_LESSON_ANDROID_ON || !GUARDED_LESSON_DISCOVERY_ANDROID_ON || !SESSION_TOKEN.matches(token))
            throw IOException("Lesson discovery session unavailable")
        return exchangeValidated(request, token, calls, true, beforeOpen)
    }

    suspend fun exchangeGuardedInvites(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkGuardedInviteRequest(request)
        if (!GUARDED_INVITES_ANDROID_ON || !SESSION_TOKEN.matches(token)) throw IOException("Invite session unavailable")
        return exchangeValidated(request, token, calls, true, beforeOpen)
    }

    suspend fun exchangeGuardedSupportReply(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkGuardedSupportReplyRequest(request)
        if (!GUARDED_SUPPORT_REPLY_ANDROID_ON || !SESSION_TOKEN.matches(token))
            throw IOException("Support reply session unavailable")
        return exchangeValidated(request, token, calls, true, beforeOpen)
    }

    suspend fun exchangeGuardedLessonFeature(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkGuardedLessonFeatureRequest(request)
        if (!GUARDED_LESSON_FEATURE_ANDROID_ON || !SESSION_TOKEN.matches(token))
            throw IOException("Lesson feature session unavailable")
        return exchangeValidated(request, token, calls, true, beforeOpen)
    }

    suspend fun exchangeGuardedLessonOrder(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        checkGuardedLessonOrderRequest(request)
        if (!GUARDED_LESSON_ORDER_ANDROID_ON || !SESSION_TOKEN.matches(token))
            throw IOException("Lesson order session unavailable")
        return exchangeValidated(request, token, calls, true, beforeOpen)
    }

    private suspend fun exchangeValidated(
        request: LongUploadRequest, token: String, calls: AdminLongNetworkCalls,
        requirePrivateJson: Boolean, beforeOpen: () -> Unit,
    ): LongUploadResponse {
        beforeOpen()
        val guard = WireGuard(beforeOpen)
        val limit = request.maxResponseBytes
        val bytes = request.body?.copyOf()
        val body = if (bytes != null) object : RequestBody() {
            override fun contentType(): MediaType = checkNotNull(request.contentType).toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                var position = 0
                while (position < bytes.size) {
                    guard.active()
                    val n = minOf(64 * 1024, bytes.size - position)
                    sink.write(bytes, position, n)
                    position += n
                }
            }
        } else if (request.method == LongUploadMethod.POST) object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = 0L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { guard.active() }
        } else null
        val outgoing = Request.Builder().url(AdminBridgeApi.ORIGIN + request.path)
            .method(request.method.name, body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Accept-Encoding", "identity")
            .tag(WireGuard::class.java, guard)
            .build()
        return suspendCancellableCoroutine { continuation ->
            try {
                beforeOpen()
            } catch (e: Exception) {
                bytes?.fill(0)
                continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            val call = client.newCall(outgoing)
            if (!calls.add(call)) {
                call.cancel()
                bytes?.fill(0)
                continuation.resumeWithException(IOException("Private session closed"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            // On cancellation the network may still be exiting writeTo briefly. Do not let
            // the caller zero its part array before OkHttp exits: the call owns a separate copy.
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        bytes?.fill(0)
                        calls.remove(call)
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                guard.active()
                                if (it.code != 200) throw AdminBridgeFailure(it.code)
                                if (requirePrivateJson) {
                                    val cache = it.headers.values("Cache-Control").flatMap { v -> v.split(',') }
                                        .map { v -> v.trim().lowercase() }.toSet()
                                    if (!cache.containsAll(setOf("no-store", "private")))
                                        throw IOException("Lesson publication response must be private")
                                }
                                if (it.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false)
                                    throw IOException("Private response encoding invalid")
                                val responseBody = it.body ?: throw IOException("Private response absent")
                                val advertised = responseBody.contentLength().takeUnless { n -> n == -1L }
                                if (advertised?.let { n -> n !in 1L..limit.toLong() } == true)
                                    throw IOException("Private response size invalid")
                                val responseBytes = responseBody.byteStream().use { input ->
                                    val output = ByteArrayOutputStream()
                                    val scratch = ByteArray(1024)
                                    while (output.size() < limit) {
                                        guard.active()
                                        val n = input.read(scratch, 0, minOf(scratch.size, limit - output.size()))
                                        if (n < 0) break
                                        if (n == 0) throw IOException("Private response unreadable")
                                        output.write(scratch, 0, n)
                                    }
                                    guard.active()
                                    if (output.size() == limit && input.read() != -1)
                                        throw IOException("Private response too large")
                                    output.toByteArray()
                                }
                                LongUploadResponse(it.code, it.header("Content-Type"), advertised, responseBytes.inputStream())
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        } finally {
                            bytes?.fill(0)
                            calls.remove(call)
                        }
                    }
                })
            } catch (e: Exception) {
                bytes?.fill(0)
                calls.remove(call)
                call.cancel()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }
}
