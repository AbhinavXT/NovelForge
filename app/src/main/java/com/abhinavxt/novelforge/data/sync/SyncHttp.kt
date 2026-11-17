package com.abhinavxt.novelforge.data.sync

import com.abhinavxt.novelforge.BuildConfig
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Dedicated OkHttp client for WebDAV sync; never shared with the source client. */
object SyncHttp {

    fun build(settings: SyncSettings): OkHttpClient {
        val base = settings.baseUrl()
        val user = settings.username
        val pass = settings.password.orEmpty()
        val pin = settings.pinnedCertSha256?.let(::normalizeFingerprint)

        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            // Auth is decided per request URL, so a redirect to another host never carries it.
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val auth = authHeaderFor(req.url, base, user, pass)
                val b = req.newBuilder().header("User-Agent", "NovelForge/${BuildConfig.VERSION_NAME}")
                if (auth != null) b.header("Authorization", auth)
                chain.proceed(b.build())
            })

        if (pin != null && base != null) {
            val tm = PinnedTrustManager(pin)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, tm)
            // The pin identifies the certificate; the name on a self-signed one is irrelevant.
            builder.hostnameVerifier(HostnameVerifier { host, _ -> host == base.host })
        }
        return builder.build()
    }

    /** Pure: the Authorization header for [url], or null when it is not the configured server. */
    fun authHeaderFor(url: HttpUrl, base: HttpUrl?, user: String, password: String): String? {
        if (base == null || user.isEmpty()) return null
        if (url.host != base.host || url.port != base.port || url.scheme != base.scheme) return null
        return Credentials.basic(user, password, Charsets.UTF_8)
    }

    /** Uppercase hex without separators, or null when not a 64-digit hex string. */
    fun normalizeFingerprint(s: String): String? {
        val hex = s.filter { it != ':' && !it.isWhitespace() }.uppercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
    }

    fun formatFingerprint(sha256: ByteArray): String =
        sha256.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun fingerprintOf(cert: X509Certificate): String =
        formatFingerprint(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

    /** Pure: does [actual] equal [pin], ignoring case and colons? A malformed pin never matches. */
    fun pinMatches(pin: String?, actual: String): Boolean {
        val p = pin?.let(::normalizeFingerprint) ?: return false
        return p == normalizeFingerprint(actual)
    }

    /**
     * Connects once, trusting nothing it reads, and returns the leaf certificate's
     * SHA-256 as colon-separated hex. Blocking: call off the main thread.
     */
    fun fetchCertificateSha256(url: HttpUrl): String? = try {
        var leaf: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                leaf = chain.firstOrNull()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(capture), SecureRandom())
        val socket = ctx.socketFactory.createSocket(url.host, url.port) as SSLSocket
        socket.use { s ->
            s.soTimeout = 10_000
            s.startHandshake()
        }
        leaf?.let(::fingerprintOf)
    } catch (e: Exception) {
        null
    }

    private class PinnedTrustManager(private val pin: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates not supported")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty chain")
            if (!pinMatches(pin, fingerprintOf(leaf))) {
                throw CertificateException("certificate does not match the pinned fingerprint")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
