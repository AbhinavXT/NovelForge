package com.abhinavxt.novelforge.data.source

import com.abhinavxt.novelforge.data.source.providers.*
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object SourceManager {

    /**
     * #10: Shared OkHttpClient for all sources.
     * #11: Includes retry interceptor with exponential backoff.
     */
    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor(RetryInterceptor(maxRetries = 3))
        .build()

    private val sources = mutableMapOf<String, Source>()

    init {
        registerSource(RoyalRoadSource())
        registerSource(ReadNovelFullSource())
        registerSource(FreeWebNovelSource())
        registerSource(LibReadSource())
        registerSource(NovelFullNetSource())
        registerSource(PawReadSource())
        registerSource(PrimordialTranslationSource())
        registerSource(WtrLabSource())

        // Sources ported from QuickNovel via the nf compatibility layer.
        // Their Source.id doubles as the novel-id prefix, so the generic
        // fallbacks in getSourceFromNovelId()/constructNovelUrl() resolve them
        // without any per-source hardcoding here.
        com.abhinavxt.novelforge.data.source.nf.NfSources.all().forEach { registerSource(it) }
    }

    private fun registerSource(source: Source) {
        sources[source.id] = source
    }

    fun getSource(id: String): Source? = sources[id]

    fun getAllSources(): List<Source> = sources.values.toList()

    fun getDefaultSource(): Source =
        sources["royalroad"] ?: error("RoyalRoad source not registered")

    fun getSourceFromNovelId(novelId: String): Source? {
        val sourceId = novelId.substringBefore("_")
        return when (sourceId) {
            "rr" -> sources["royalroad"]
            "rnf" -> sources["rnf"]
            "fwn" -> sources["fwn"]
            "lr" -> sources["lr"]
            "nfn" -> sources["nfn"]
            "pr" -> sources["pr"]
            "pt" -> sources["pt"]
            "wtr" -> sources["wtr"]
            // Adapter-backed sources use their Source.id as the prefix directly
            // (e.g. "qanv_..." -> sources["qanv"]), so no mapping table is needed.
            else -> sources[sourceId]
        }
    }

    /**
     * Build the full URL for a novel from its ID.
     * Single source of truth — used by Navigation, HomeViewModel, UpdateCheckerWorker.
     */
    fun constructNovelUrl(novelId: String): String {
        return when {
            novelId.startsWith("rr_") ->
                "https://www.royalroad.com/fiction/${novelId.removePrefix("rr_")}"
            novelId.startsWith("rnf_") ->
                "https://readnovelfull.com/${novelId.removePrefix("rnf_")}.html"
            novelId.startsWith("fwn_") ->
                "https://freewebnovel.com/novel/${novelId.removePrefix("fwn_")}"
            novelId.startsWith("lr_") ->
                "https://libread.com/libread/${novelId.removePrefix("lr_")}"
            novelId.startsWith("nfn_") ->
                "https://novelfull.net/${novelId.removePrefix("nfn_")}.html"
            novelId.startsWith("pr_") -> {
                val slug = novelId.removePrefix("pr_").replace("~", "/")
                "https://pawread.com/$slug"
            }
            novelId.startsWith("pt_") ->
                "https://primodialtranslation.com/series/${novelId.removePrefix("pt_")}/"
            novelId.startsWith("wtr_") -> {
                // "wtr_{rawId}~{slug}" -> /en/novel/{rawId}/{slug}
                val body = novelId.removePrefix("wtr_")
                val rawId = body.substringBefore("~")
                val slug = body.substringAfter("~", "")
                "https://wtr-lab.com/en/novel/$rawId/$slug"
            }
            novelId.startsWith("local_") ->
                "local://$novelId"
            else -> {
                // Adapter-backed sources encode the full novel URL into the id
                // (base64url), so the adapter itself can reverse it. This keeps
                // constructNovelUrl() free of per-source cases for all ~40
                // ported sources.
                (getSourceFromNovelId(novelId) as? com.abhinavxt.novelforge.data.source.nf.NfSourceAdapter)
                    ?.novelUrlFromId(novelId)
                    ?: ""
            }
        }
    }
}

/**
 * OkHttp interceptor that retries failed requests with exponential backoff.
 * Retries on IOException (network errors) and 5xx server errors.
 */
private class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null

        for (attempt in 0..maxRetries) {
            try {
                val response = chain.proceed(request)
                // Don't retry on client errors (4xx), only server errors (5xx)
                if (response.isSuccessful || response.code in 400..499) {
                    return response
                }
                // Server error — close body and retry
                if (attempt < maxRetries) {
                    response.close()
                    Thread.sleep(1000L * (attempt + 1)) // 1s, 2s, 3s
                } else {
                    return response
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            }
        }
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }
}