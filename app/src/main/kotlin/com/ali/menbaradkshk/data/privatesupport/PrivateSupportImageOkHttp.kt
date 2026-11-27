package com.ali.menbaradkshk.data.privatesupport

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Dormant, listener-only. Do not turn on until the UI + identity lease and signed release are qualified.
private const val PRIVATE_SUPPORT_IMAGE_ENGINE_ENABLED = false
private const val WORKER_HOST = "minbar-api.mushafak.workers.dev"
private val privateImagePath = Regex("/v2/private-support/threads/spt_[a-f0-9]{32}/image")
private val privateImageBearer = Regex("Bearer sil_[a-f0-9]{64}")

/** Never use a shared configured app client, interceptor, cache, proxy, or test trust manager here. */
private val privateSupportImageClient by lazy { privateSupportImageClientBuilder().build() }

internal fun fetchPrivateSupportImageWithDedicatedEngine(
    capabilityEnabled: Boolean,
    explicitView: Boolean,
    threadId: String,
    installationCredential: String,
    stillCurrent: () -> Boolean,
): ByteArray? {
    if (!PRIVATE_SUPPORT_IMAGE_ENGINE_ENABLED) return null
    return fetchPrivateSupportImageBytes(
        capabilityEnabled, explicitView, threadId, installationCredential, stillCurrent,
    ) { request ->
        if (!stillCurrent()) throw IOException("Private image selection changed")
        openPrivateSupportImageExchange(request) { call ->
            openPrivateSupportImageWithOkHttp(call, privateSupportImageClient, stillCurrent)
        }
    }
}

// Internal construction seam only for loopback TLS tests. Production always uses the sealed
// private client above, with the platform trust manager and default hostname verifier.
internal fun privateSupportImageClientBuilder(): OkHttpClient.Builder =
    OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(PrivateSupportImageTlsProfile.connectionTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(PrivateSupportImageTlsProfile.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(PrivateSupportImageTlsProfile.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(PrivateSupportImageTlsProfile.entireCallTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .addNetworkInterceptor { chain ->
            val permit = chain.request().tag(PrivateImageSendPermit::class.java)
            // OkHttp can transparently reissue a 503 even when retryOnConnectionFailure is false.
            // The permit survives follow-up request copies, and a second attempt is never written.
            if (permit == null || !permit.unused.compareAndSet(true, false) || !permit.stillCurrent()) {
                throw IOException("Private image send unavailable")
            }
            val answer = chain.proceed(chain.request())
            if (answer.code != 200) {
                // Stop in the network interceptor, before OkHttp interprets follow-up status
                // codes such as 503/Retry-After: 0 as another application request.
                answer.close()
                throw IOException("Private image response rejected")
            }
            answer
        }

private class PrivateImageSendPermit(val stillCurrent: () -> Boolean) {
    val unused = AtomicBoolean(true)
}

internal fun openPrivateSupportImageWithOkHttp(
    call: PrivateSupportImageCall,
    client: OkHttpClient,
    stillCurrent: () -> Boolean,
): PrivateSupportImageRawExchange {
    val url = call.url.toHttpUrl()
    val bearer = call.requestHeader("Authorization")
    if (call.method != "GET" || url.scheme != "https" || url.host != WORKER_HOST || url.port != 443 ||
        !privateImagePath.matches(url.encodedPath) || url.encodedQuery != null || url.encodedFragment != null ||
        url.username.isNotEmpty() || url.password.isNotEmpty() ||
        bearer == null || !privateImageBearer.matches(bearer)
    ) throw IOException("Private image target rejected")
    val request = Request.Builder().url(url).get()
        .header("Authorization", bearer)
        .header("Accept", "image/jpeg")
        .header("Accept-Encoding", "identity")
        .header("Cache-Control", "no-store")
        .header("Connection", "close")
        .tag(PrivateImageSendPermit::class.java, PrivateImageSendPermit(stillCurrent))
        .build()
    val response = client.newCall(request).execute()
    return PrivateOkHttpExchange(response)
}

private class PrivateOkHttpExchange(private val response: Response) : PrivateSupportImageRawExchange {
    override val status: Int get() = response.code
    override val headers: List<Pair<String, String>>
        get() = response.headers.run { List(size) { i -> name(i) to value(i) } }

    override fun openBody(): InputStream =
        response.body?.byteStream() ?: throw IOException("Private image body missing")

    override fun close() = response.close()

    override fun toString() = "PrivateOkHttpExchange(redacted)"
}
