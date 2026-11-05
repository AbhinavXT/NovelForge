package com.abhinavxt.novelforge.data.annas

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener

/**
 * Gets a page past DDoS-Guard in a WebView.
 *
 * DDoS-Guard first tries a silent JavaScript check, which a hidden WebView
 * passes like any browser. When that isn't enough it serves a manual check
 * (a checkbox, then an image CAPTCHA) that only the person holding the
 * phone can answer. [render] gives up on that page at once and reports
 * [Result.NeedsPerson]; the screen then shows the same page in a visible
 * WebView via [watch], and the person completes the check.
 *
 * Either way the cookies the check hands out stay in [CookieManager], so
 * the next requests from this device skip it until they expire.
 */
internal object ChallengeWebView {

    private const val TAG = "ChallengeWebView"
    private const val POLL_MS = 1_000L
    private const val SNAPSHOT_JS = "document.documentElement.outerHTML"

    sealed interface Result {
        data class Passed(val html: String) : Result
        data object NeedsPerson : Result
        data object TimedOut : Result
    }

    /** True for DDoS-Guard's manual check, which no script can pass. */
    fun isManualCheck(html: String): Boolean =
        "ddg-captcha" in html || "could not verify your browser automatically" in html

    /** Loads [url] in a hidden WebView and waits for it to clear the check. */
    suspend fun render(context: Context, url: String, timeoutMs: Long = 25_000L): Result {
        val done = CompletableDeferred<Result>()
        var webView: WebView? = null
        var stop: (() -> Unit)? = null

        withContext(Dispatchers.Main) {
            val view = WebView(context.applicationContext)
            webView = view
            // Covers aren't needed to read the result list.
            view.settings.blockNetworkImage = true
            stop = watch(view) { html ->
                done.complete(
                    if (html == null) Result.NeedsPerson else Result.Passed(html)
                )
            }
            view.loadUrl(url)
        }

        val result = withTimeoutOrNull(timeoutMs) { done.await() } ?: Result.TimedOut
        withContext(Dispatchers.Main) {
            stop?.invoke()
            webView?.stopLoading()
            webView?.destroy()
        }
        if (result !is Result.Passed) Logger.w(TAG, "Check not passed ($result): $url")
        return result
    }

    /**
     * Configures [view] and polls it until the page is past the check.
     *
     * [onResult] is called once, on the main thread: with the page's HTML
     * when it passes, or with null the first time the manual check shows
     * (only if [reportManualCheck]; a visible WebView wants to keep going
     * so the person can answer it).
     *
     * @return a function that stops polling. Call it before destroying [view].
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun watch(
        view: WebView,
        reportManualCheck: Boolean = true,
        onResult: (String?) -> Unit
    ): () -> Unit {
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        fun finish(html: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            CookieManager.getInstance().flush()
            onResult(html)
        }

        fun snapshot() {
            if (finished) return
            view.evaluateJavascript(SNAPSHOT_JS) { raw ->
                val html = decode(raw) ?: return@evaluateJavascript
                when {
                    html.length < 512 -> Unit // still loading
                    !AnnasArchiveParser.isChallenge(html) -> finish(html)
                    reportManualCheck && isManualCheck(html) -> finish(null)
                }
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = snapshot()
        }
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (finished) return
                snapshot()
                handler.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)

        return {
            finished = true
            handler.removeCallbacksAndMessages(null)
        }
    }

    /** evaluateJavascript hands back a JSON-encoded string literal. */
    private fun decode(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }
}
