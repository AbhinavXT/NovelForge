package com.abhinavxt.novelforge.data.source.net

// Browser identity and Cloudflare handling for source requests.
//
// Cloudflare answers some requests with a JavaScript challenge page instead
// of the content. A real browser runs the script and is handed a
// cf_clearance cookie, which Cloudflare accepts only together with the
// User-Agent of the browser that earned it. So the interceptor loads the
// page in a hidden WebView, waits for that cookie to appear in the shared
// CookieManager, and replays the request with the WebView's cookies and
// User-Agent. Later requests to the same site send them straight away.

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.abhinavxt.novelforge.util.Logger
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The User-Agent sent when a source doesn't set its own. */
const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/** The application context and the system WebView's User-Agent, for code outside the UI. */
object WebViewSupport {

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedUserAgent: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    internal val context: Context? get() = appContext

    /** The WebView's User-Agent; null before [init] or on a device without a working WebView. */
    val userAgent: String?
        get() = cachedUserAgent ?: appContext
            ?.let { runCatching { WebSettings.getDefaultUserAgent(it) }.getOrNull() }
            ?.also { cachedUserAgent = it }
}

/**
 * Gets requests past Cloudflare's JavaScript challenge (see the top of this file).
 * Challenges that need a person (a CAPTCHA) time out after [solveTimeoutMs] and
 * the challenge response is returned as is.
 */
class CloudflareInterceptor(private val solveTimeoutMs: Long = 30_000L) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val sentClearance = clearanceFor(original.url)
        val response = chain.proceed(withWebViewIdentity(original))
        if (!isChallenge(response)) return response

        val context = WebViewSupport.context ?: return response
        // The solver needs the main thread; waiting on it from there would deadlock.
        if (Looper.myLooper() == Looper.getMainLooper()) return response
        response.close()

        synchronized(locks.getOrPut(original.url.host) { Any() }) {
            // Another request may have solved it while this one waited.
            if (clearanceFor(original.url) == sentClearance) solve(context, original)
        }
        return chain.proceed(withWebViewIdentity(original))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solve(context: Context, request: Request) {
        val url = request.url.toString()
        val before = clearanceFor(request.url)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        var view: WebView? = null

        val poll = object : Runnable {
            override fun run() {
                val now = clearanceFor(request.url)
                if (now != null && now != before) done.countDown() else main.postDelayed(this, POLL_MS)
            }
        }
        main.post {
            runCatching {
                CookieManager.getInstance().setAcceptCookie(true)
                view = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    loadUrl(url, mapOf("Referer" to url))
                }
                main.postDelayed(poll, POLL_MS)
            }.onFailure {
                Logger.e(TAG, "no WebView to solve the challenge: ${it.message}")
                done.countDown()
            }
        }

        val solved = done.await(solveTimeoutMs, TimeUnit.MILLISECONDS)
        main.post {
            main.removeCallbacks(poll)
            CookieManager.getInstance().flush()
            view?.stopLoading()
            view?.destroy()
        }
        if (!solved) Logger.w(TAG, "challenge not solved within ${solveTimeoutMs / 1000}s: ${request.url.host}")
    }

    internal companion object {
        private const val TAG = "Cloudflare"
        private const val POLL_MS = 500L
        private const val CLEARANCE = "cf_clearance"
        private val locks = ConcurrentHashMap<String, Any>()

        /** A Cloudflare challenge page rather than the site's own answer (an error page or real content). */
        fun isChallenge(response: Response): Boolean {
            if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
            if (response.code != 403 && response.code != 503) return false
            if (response.header("Server")?.startsWith("cloudflare", ignoreCase = true) != true) return false
            val body = runCatching { response.peekBody(64 * 1024).string() }.getOrDefault("")
            return CHALLENGE_MARKERS.any { it in body }
        }

        private val CHALLENGE_MARKERS = listOf("challenge-platform", "cf_chl_", "cf-browser-verification", "Just a moment...")

        /**
         * [request] with the WebView's cookies for its URL and, when one of them is
         * a clearance cookie, the WebView's User-Agent. Cookies the request sets itself win.
         */
        fun withWebViewIdentity(request: Request): Request {
            val webCookies = webViewCookies(request.url) ?: return request
            val merged = mergeCookies(request.header("Cookie"), webCookies)
            val builder = request.newBuilder().header("Cookie", merged)
            if ("$CLEARANCE=" in webCookies) WebViewSupport.userAgent?.let { builder.header("User-Agent", it) }
            return builder.build()
        }

        fun mergeCookies(own: String?, extra: String): String {
            fun parse(header: String?) = header.orEmpty().split(';').map { it.trim() }.filter { '=' in it }
            val ownCookies = parse(own)
            val ownNames = ownCookies.map { it.substringBefore('=') }.toSet()
            return (ownCookies + parse(extra).filter { it.substringBefore('=') !in ownNames }).joinToString("; ")
        }

        private fun webViewCookies(url: HttpUrl): String? =
            if (WebViewSupport.context == null) null
            else runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()?.ifBlank { null }

        private fun clearanceFor(url: HttpUrl): String? =
            webViewCookies(url)?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$CLEARANCE=") }
    }
}
