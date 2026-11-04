package com.abhinavxt.novelforge.data.source

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The HTTP client every source shares.
 *
 * Held apart from [SourceManager] because the two answer different
 * questions. SourceManager knows which sources exist, which is the host
 * app's business and stays with it. This is the transport a source needs
 * in order to run, which has to travel with the sources themselves.
 *
 * One client rather than one per source, so connection pooling, the
 * thread pool and the retry policy are shared rather than duplicated
 * thirty times over.
 */
object SourceHttp {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(RetryInterceptor(maxRetries = 3))
        .build()
}

/**
 * Retries failed requests with exponential backoff.
 *
 * Retries network errors and 5xx responses, never 4xx: a 404 or a 403
 * means the server understood and declined, and asking again three more
 * times only makes the source look like an attacker. 3xx responses that
 * OkHttp did not follow (304, cross-protocol redirects) are returned as-is.
 *
 * Only idempotent methods are retried. A POST that timed out may already
 * have reached the server, and replaying it can repeat its side effects.
 *
 * A cancelled call is never retried: OkHttp reports cancellation as an
 * IOException("Canceled"), and sleeping through backoff on it would pin a
 * dispatcher thread for seconds after the caller has gone away.
 */
private class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method !in IDEMPOTENT_METHODS) return chain.proceed(request)

        var lastException: IOException? = null

        for (attempt in 0..maxRetries) {
            if (chain.call().isCanceled()) throw lastException ?: IOException("Canceled")
            try {
                val response = chain.proceed(request)
                if (response.code < 500 || attempt == maxRetries) {
                    return response
                }
                // Server error — close body and retry
                response.close()
            } catch (e: IOException) {
                if (chain.call().isCanceled() || attempt == maxRetries) throw e
                lastException = e
            }
            backoff(chain, attempt)
        }
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }

    /** Sleeps 1s, 2s, 3s, ... but bails out as soon as the call is cancelled. */
    private fun backoff(chain: Interceptor.Chain, attempt: Int) {
        val deadline = System.currentTimeMillis() + 1000L * (attempt + 1)
        while (System.currentTimeMillis() < deadline) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                Thread.sleep(minOf(100L, deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted during retry backoff", e)
            }
        }
    }

    private companion object {
        val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS", "PUT", "DELETE")
    }
}
