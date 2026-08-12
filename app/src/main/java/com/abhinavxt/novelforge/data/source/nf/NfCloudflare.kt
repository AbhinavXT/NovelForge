package com.abhinavxt.novelforge.data.source.nf

// QuickNovel compatibility layer (package: data.source.nf) — Cloudflare bypass.
//
// Port of QuickNovel's network/CloudflareKiller.kt and network/WebViewResolver.kt.
// When a request comes back looking like a Cloudflare challenge, a hidden WebView
// loads the page, lets the JS challenge run (auto-clicking the turnstile submit
// button when it appears), harvests the cf_clearance cookie from the CookieManager,
// and replays the original request with those cookies.
//
// Requires NfBridge.init(application) to have been called so a Context is available
// for WebView creation. If no context is available the interceptor degrades
// gracefully to a plain proceed().

import android.annotation.SuppressLint
import android.net.http.SslError
import android.util.Log
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.AnyThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val mutex = Mutex()

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    val savedCookies = ConcurrentHashMap<String, Map<String, String>>()

    /** Gets the headers with cookies, webview user agent included. */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, null, savedCookies[URI(url).host] ?: emptyMap())
    }

    private fun clearCookiesForHost(url: HttpUrl) {
        val host = url.host
        savedCookies.remove(host)
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Throwable) {
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val host = request.url.host

        savedCookies[host]?.let { cookies ->
            val response = proceed(request, cookies)
            if (!looksLikeCloudflareChallenge(response)) {
                return@runBlocking response
            }
            response.close()
            clearCookiesForHost(request.url)
        }

        // First try the request normally. Only invoke the WebView bypass when
        // the response actually looks like a Cloudflare challenge.
        val initialResponse = chain.proceed(request)
        if (!looksLikeCloudflareChallenge(initialResponse)) {
            return@runBlocking initialResponse
        }
        initialResponse.close()

        // No context -> cannot spin up a WebView; just retry normally.
        if (NfBridge.appContext == null) {
            Log.w(TAG, "No application context; skipping Cloudflare bypass for $host")
            return@runBlocking chain.proceed(request)
        }

        mutex.withLock {
            if (savedCookies[host] != null || trySolveWithSavedCookies(request)) {
                val cookies = savedCookies[host] ?: emptyMap()
                val response = proceed(request, cookies)
                if (!looksLikeCloudflareChallenge(response)) return@runBlocking response
                response.close()
                clearCookiesForHost(request.url)
            }

            Log.d(TAG, "Resolving Cloudflare for $host...")
            val bypassResponse = bypassCloudflare(request)

            if (bypassResponse != null) {
                Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                return@runBlocking bypassResponse
            }
        }

        Log.w(TAG, "Failed cloudflare at: ${request.url}")
        return@runBlocking chain.proceed(request)
    }

    private fun looksLikeCloudflareChallenge(response: Response): Boolean {
        val code = response.code
        val hasCloudflareHeaders =
            response.header("cf-ray") != null ||
                    response.header("server")?.contains("cloudflare", ignoreCase = true) == true

        if (code == 403 || code == 429 || code == 503) {
            if (hasCloudflareHeaders) return true

            val bodySample = runCatching {
                response.peekBody(1024 * 10).string().lowercase()
            }.getOrDefault("")

            return bodySample.contains("cf-browser-verification") ||
                    bodySample.contains("checking your browser") ||
                    bodySample.contains("just a moment") ||
                    bodySample.contains("/cdn-cgi/")
        }

        val location = response.header("location").orEmpty().lowercase()
        return location.contains("/cdn-cgi/")
    }

    private fun getWebViewCookie(url: String): String? {
        return try {
            CookieManager.getInstance()?.getCookie(url)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns true if the cf cookies were successfully fetched from the CookieManager.
     * Also saves the cookies.
     */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            if (cookie.contains("cf_clearance")) {
                savedCookies[request.url.host] = parseCookieMap(cookie)
                true
            } else false
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val ua = WebViewResolver.webViewUserAgent ?: WebViewResolver.getWebViewUserAgent()
        val userAgentMap = ua?.let { mapOf("user-agent" to it) } ?: emptyMap()

        val headers = getHeaders(
            request.headers.toMap() + userAgentMap,
            null,
            cookies + request.cookies
        )

        return NfHttp.app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
        WebViewResolver(
            // Never exit based on url
            Regex(".^"),
            // Cloudflare needs default user agent
            userAgent = null,
            // Cannot use okhttp (intercepting cookies fails which causes issues)
            useOkhttp = false,
            // Match every url for the requestCallBack
            additionalUrls = listOf(Regex("."))
        ).resolveUsingWebView(url) {
            trySolveWithSavedCookies(request)
        }
        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}

/**
 * When used as Interceptor additionalUrls cannot be returned, use
 * WebViewResolver(...).resolveUsingWebView(...)
 * @param interceptUrl will stop the WebView when reaching this url.
 * @param additionalUrls this will make resolveUsingWebView also return all other
 * requests matching the list of Regex.
 * @param userAgent if null then will use the default user agent
 * @param useOkhttp will try to use the okhttp client as much as possible, but this
 * might cause some requests to fail. Disable for cloudflare.
 */
class WebViewResolver(
    val interceptUrl: Regex,
    val additionalUrls: List<Regex> = emptyList(),
    val userAgent: String? = USER_AGENT,
    val useOkhttp: Boolean = true
) : Interceptor {

    private val blockedTrackerHosts = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "googlesyndication.com",
        "doubleclick.net",
        "adtrafficquality.google",
        "sharethis.com",
        "count-server.sharethis.com",
        "fundingchoicesmessages.google.com"
    )

    private val blacklistedExtensions = setOf(
        "jpg", "png", "webp", "mpg", "mpeg", "jpeg", "webm",
        "mp4", "mp3", "gifv", "flv", "asf", "mov", "mng",
        "mkv", "ogg", "avi", "wav", "woff2", "woff", "ttf",
        "css", "vtt", "srt", "ts", "gif"
    )

    private fun isBlockedTrackerUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return blockedTrackerHosts.any { blocked ->
            host == blocked || host.endsWith(".$blocked")
        }
    }

    companion object {
        var webViewUserAgent: String? = null
        val CONTENT_TYPE_REGEX = Regex("""(.*);(?:.*charset=(.*)(?:|;)|)""")

        @JvmName("getWebViewUserAgent1")
        fun getWebViewUserAgent(): String? {
            return webViewUserAgent ?: NfBridge.appContext?.let { ctx ->
                runBlocking {
                    mainWork {
                        WebView(ctx).settings.userAgentString.also { userAgent ->
                            webViewUserAgent = userAgent
                        }
                    }
                }
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixedRequest = resolveUsingWebView(request).first
            return@runBlocking chain.proceed(fixedRequest ?: request)
        }
    }

    suspend fun resolveUsingWebView(
        url: String,
        referer: String? = null,
        method: String = "GET",
        requestCallBack: (Request) -> Boolean = { false },
    ): Pair<Request?, List<Request>> {
        return resolveUsingWebView(
            requestCreator(method, url, referer = referer), requestCallBack
        )
    }

    /**
     * @param requestCallBack asynchronously return matched requests by either
     * interceptUrl or additionalUrls. If true, destroy WebView.
     * @return the final request (by interceptUrl) and all the collected urls
     * (by additionalUrls).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean = { false }
    ): Pair<Request?, List<Request>> {
        val url = request.url.toString()
        val headers = request.headers
        Log.d(CloudflareKiller.TAG, "Initial web-view request: $url")

        val deferredResponse = CompletableDeferred<Pair<Request?, List<Request>>>()
        var webView: WebView? = null
        val extraRequestList = mutableListOf<Request>()
        var fixedRequest: Request? = null

        fun destroyWebView() {
            main {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        main {
            try {
                webView = WebView(
                    NfBridge.appContext
                        ?: throw RuntimeException("No base context in WebViewResolver")
                ).apply {
                    // Bare minimum to bypass captcha
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webViewUserAgent = settings.userAgentString
                    // Don't set user agent by default; setting it breaks cloudflare.
                    if (userAgent != null) {
                        settings.userAgentString = userAgent
                    }
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? = runBlocking {
                        val webViewUrl = request.url.toString()
                        if (isBlockedTrackerUrl(webViewUrl)) {
                            return@runBlocking WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        if (interceptUrl.containsMatchIn(webViewUrl)) {
                            fixedRequest = request.toRequest().also {
                                requestCallBack(it)
                            }
                            deferredResponse.complete(fixedRequest to extraRequestList)
                            return@runBlocking null
                        }

                        if (additionalUrls.any { it.containsMatchIn(webViewUrl) }) {
                            val req = request.toRequest()
                            extraRequestList.add(req)
                            if (requestCallBack(req)) {
                                deferredResponse.complete(fixedRequest to extraRequestList)
                            }
                        }

                        val path = runCatching { URI(webViewUrl).path }.getOrNull() ?: ""
                        val extension = path.substringAfterLast('.', "").lowercase()

                        return@runBlocking try {
                            when {
                                blacklistedExtensions.contains(extension) ||
                                        webViewUrl.endsWith("/favicon.ico") ||
                                        webViewUrl.startsWith("wss://") -> WebResourceResponse(
                                    "image/png",
                                    null,
                                    null
                                )

                                webViewUrl.contains("recaptcha") || webViewUrl.contains("/cdn-cgi/") ->
                                    super.shouldInterceptRequest(view, request)

                                useOkhttp && request.method == "GET" -> NfHttp.app.get(
                                    webViewUrl,
                                    headers = request.requestHeaders
                                ).okhttpResponse.toWebResourceResponse()

                                useOkhttp && request.method == "POST" -> NfHttp.app.post(
                                    webViewUrl,
                                    headers = request.requestHeaders
                                ).okhttpResponse.toWebResourceResponse()

                                else -> super.shouldInterceptRequest(view, request)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.proceed() // Ignore ssl issues
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url == null || url.contains("cdn-cgi") || url.contains("recaptcha")) return

                        val script = """
                            (function() {
                                if (window.wasClicked) return;

                                function tryClick() {
                                    var isCloudflarePage = document.querySelector('#challenge-form') ||
                                                           document.querySelector('#challenge-running') ||
                                                           document.querySelector('#cf-challenge-running');

                                    if (!isCloudflarePage) {
                                        return;
                                    }

                                    var cfToken = document.querySelector('[name="cf-turnstile-response"]')?.value
                                                  || document.querySelector('#cf-chl-widget-multi-token')?.value;

                                    var submitButton = document.querySelector('#challenge-form button[type="submit"]')
                                                       || document.querySelector('#challenge-form input[type="submit"]');

                                    if (cfToken && submitButton) {
                                        window.wasClicked = true;
                                        submitButton.click();
                                    } else {
                                        if (!window.retryCount) window.retryCount = 0;
                                        if (window.retryCount < 15) {
                                            window.retryCount++;
                                            setTimeout(tryClick, 1000);
                                        }
                                    }
                                }
                                tryClick();
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(script, null)
                    }
                }
                webView?.loadUrl(url, headers.toMap())
            } catch (e: Exception) {
                logError(e)
                deferredResponse.complete(null to emptyList())
            }
        }

        val result = withTimeoutOrNull(60000L) {
            deferredResponse.await()
        }

        if (result == null) {
            Log.d(CloudflareKiller.TAG, "Web-view timeout after 60s")
        }

        destroyWebView()
        return result ?: (fixedRequest to extraRequestList)
    }
}

fun WebResourceRequest.toRequest(): Request {
    return requestCreator(
        this.method,
        this.url.toString(),
        this.requestHeaders,
    )
}

fun Response.toWebResourceResponse(): WebResourceResponse {
    val contentTypeValue = this.header("Content-Type")
    return if (contentTypeValue != null) {
        val found = WebViewResolver.CONTENT_TYPE_REGEX.find(contentTypeValue)
        val contentType = found?.groupValues?.getOrNull(1)?.ifBlank { null } ?: contentTypeValue
        val charset = found?.groupValues?.getOrNull(2)?.ifBlank { null }
        WebResourceResponse(contentType, charset, this.body?.byteStream())
    } else {
        WebResourceResponse("application/octet-stream", null, this.body?.byteStream())
    }
}
