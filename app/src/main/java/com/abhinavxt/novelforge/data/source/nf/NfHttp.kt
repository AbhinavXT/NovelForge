package com.abhinavxt.novelforge.data.source.nf

// QuickNovel compatibility layer (package: data.source.nf) — HTTP shim.
//
// QuickNovel uses the NiceHttp library (com.lagradost.nicehttp.Requests). Rather than
// pulling in that dependency, this file provides the small slice of its API surface
// that the ported providers actually use:
//
//   app.get(url, headers =, referer =, params =, cookies =, timeout =) -> NfResponse
//   app.post(url, ..., data = mapOf(...))                              -> NfResponse
//   response.text / .document / .code / .url / .headers / .okhttpResponse
//   response.parsed<T>() / response.parsedSafe<T>()
//
// Everything runs on the shared OkHttpClient from SourceManager so retry behaviour
// and timeouts stay consistent with the native NovelForge sources.

import android.content.Context
import com.abhinavxt.novelforge.data.source.SourceManager
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Holds the application context needed by the Cloudflare WebView resolver.
 * Initialised once from NovelReaderApplication.onCreate() via NfBridge.init(this).
 */
object NfBridge {
    @Volatile
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

// Main-thread coroutine helpers (port of QuickNovel's util/Coroutines.kt subset).
private val nfMainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

fun main(work: suspend () -> Unit) {
    nfMainScope.launch { work() }
}

suspend fun <T> mainWork(work: () -> T): T = withContext(Dispatchers.Main) { work() }

/** Suspending execution of an OkHttp call (port of nicehttp's Requests.Companion.await). */
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }
}

/** Cookies attached to an outgoing request, parsed from its Cookie header. */
val Request.cookies: Map<String, String>
    get() = this.header("Cookie")?.let { cookieHeader ->
        cookieHeader.split(";").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else pair.substring(0, idx).trim() to pair.substring(idx + 1).trim()
        }.toMap()
    } ?: emptyMap()

/**
 * Build okhttp Headers from a header map + optional referer + cookie map
 * (port of nicehttp's getHeaders).
 */
fun getHeaders(
    headers: Map<String, String>,
    referer: String?,
    cookie: Map<String, String>
): Headers {
    val refererMap = referer?.let { mapOf("referer" to it) } ?: emptyMap()
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") { "${it.key}=${it.value};" }
        ) else emptyMap()
    val tempHeaders = (headers + cookieMap + refererMap)
    val builder = Headers.Builder()
    for ((key, value) in tempHeaders) {
        try {
            builder.add(key, value)
        } catch (_: IllegalArgumentException) {
            // skip malformed header values instead of crashing the whole request
        }
    }
    return builder.build()
}

/** Minimal port of nicehttp's requestCreator, used by the WebView resolver. */
fun requestCreator(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
): Request {
    return Request.Builder()
        .url(url)
        .headers(getHeaders(headers, referer, emptyMap()))
        .method(method, if (method.equals("GET", true) || method.equals("HEAD", true)) null else ByteArray(0).toRequestBody())
        .build()
}

/** Response wrapper mirroring nicehttp's NiceResponse surface used by the providers. */
class NfResponse(val okhttpResponse: Response) {
    val text: String by lazy { okhttpResponse.body?.string() ?: "" }
    val url: String by lazy { okhttpResponse.request.url.toString() }
    val code: Int get() = okhttpResponse.code
    val headers: Headers get() = okhttpResponse.headers
    val cookies: Map<String, String> by lazy {
        okhttpResponse.headers.values("set-cookie")
            .flatMap { it.split(";") }
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null
                else pair.substring(0, idx).trim() to pair.substring(idx + 1).trim()
            }.toMap()
    }

    /** Lazy parsed Jsoup document, base URI set so abs:href works. */
    val document: Document by lazy { Jsoup.parse(text, url) }

    /** Parse the body as JSON into T. Throws on malformed input. */
    inline fun <reified T : Any> parsed(): T {
        return nfGson.fromJson(text, object : TypeToken<T>() {}.type)
    }

    /** Parse the body as JSON into T, returning null on any failure. */
    inline fun <reified T : Any> parsedSafe(): T? {
        return try {
            nfGson.fromJson<T>(text, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
}

/**
 * Requests-style client. One instance wraps the plain shared client, a second wraps
 * a client with the CloudflareKiller interceptor attached (see NfHttp below).
 */
class NfRequests(
    val baseClient: OkHttpClient,
    var defaultHeaders: Map<String, String> = emptyMap()
) {

    private fun clientFor(timeout: Long): OkHttpClient {
        if (timeout <= 0) return baseClient
        return baseClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun appendParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        val builder = httpUrl.newBuilder()
        for ((k, v) in params) builder.addQueryParameter(k, v)
        return builder.build().toString()
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        timeout: Long = 0L,
    ): NfResponse {
        val client =
            if (allowRedirects) clientFor(timeout)
            else clientFor(timeout).newBuilder().followRedirects(false).followSslRedirects(false).build()
        val request = Request.Builder()
            .url(appendParams(url, params))
            .headers(getHeaders(defaultHeaders + headers, referer, cookies))
            .get()
            .build()
        return NfResponse(client.newCall(request).await())
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        requestBody: RequestBody? = null,
        timeout: Long = 0L,
    ): NfResponse {
        val body: RequestBody = requestBody
            ?: data?.let { map ->
                FormBody.Builder().apply {
                    for ((k, v) in map) add(k, v)
                }.build()
            }
            ?: ByteArray(0).toRequestBody()
        val request = Request.Builder()
            .url(appendParams(url, params))
            .headers(getHeaders(defaultHeaders + headers, referer, cookies))
            .post(body)
            .build()
        return NfResponse(clientFor(timeout).newCall(request).await())
    }
}

/** Singletons mirroring QuickNovel's MainActivity.Companion.app / appWithInterceptor. */
object NfHttp {
    val app: NfRequests by lazy {
        NfRequests(
            baseClient = SourceManager.sharedClient,
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        )
    }

    val appWithInterceptor: NfRequests by lazy {
        NfRequests(
            baseClient = SourceManager.sharedClient.newBuilder()
                .addInterceptor(CloudflareKiller())
                .build(),
            defaultHeaders = mapOf("user-agent" to USER_AGENT)
        )
    }
}
