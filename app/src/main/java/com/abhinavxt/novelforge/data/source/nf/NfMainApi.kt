package com.abhinavxt.novelforge.data.source.nf

// QuickNovel compatibility layer (package: data.source.nf) — core API surface.
//
// This file is a port of QuickNovel's MainAPI.kt (GPL-3.0, github.com/LagradOst/QuickNovel)
// with the Android-UI-specific parts removed (UiImage, icons, reviews, EPUB downloads)
// so that QuickNovel provider files can be dropped into NovelForge nearly verbatim.
//
// Providers extend MainAPI and are exposed to the rest of the app through
// NfSourceAdapter, which maps this API onto NovelForge's Source interface.

import com.abhinavxt.novelforge.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import org.jsoup.Jsoup

const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"

abstract class MainAPI {
    open val name = "NONE"
    open val mainUrl = "NONE"

    open val lang = "en" // ISO_639_1

    open val usesCloudFlareKiller = false
    val app get() = if (!usesCloudFlareKiller) NfHttp.app else NfHttp.appWithInterceptor

    fun fixPosterHeaders(headers: Map<String, String>?): Map<String, String>? {
        return if (usesCloudFlareKiller)
            (headers ?: emptyMap()) + DefaultImagesHeaders.useCloudflareKillerHeader
        else headers
    }

    open val rateLimitTime: Long = 0
    val hasRateLimit: Boolean get() = rateLimitTime > 0L
    val rateLimitMutex: Mutex = Mutex()

    // DECLARE HAS ACCESS TO MAIN PAGE INFORMATION
    open val hasMainPage = false

    open val mainCategories: List<Pair<String, String>> = listOf()
    open val orderBys: List<Pair<String, String>> = listOf()
    open val tags: List<Pair<String, String>> = listOf()

    // Kept so provider overrides (if any survive) still compile; unused by NovelForge.
    open val iconId: Int? = null
    open val iconBackgroundId: Int = 0

    open suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        throw NotImplementedError()
    }

    open suspend fun search(query: String): List<SearchResponse>? {
        throw NotImplementedError()
    }

    open suspend fun load(url: String): LoadResponse? {
        throw NotImplementedError()
    }

    open suspend fun loadHtml(url: String): String? {
        throw NotImplementedError()
    }
}

class ErrorLoadingException(message: String? = null) : Exception(message)

fun MainAPI.fixUrlNull(url: String?): String? {
    if (url.isNullOrEmpty()) {
        return null
    }
    return fixUrl(url)
}

fun MainAPI.fixUrl(url: String): String {
    if (url.startsWith("http")) {
        return url
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return mainUrl + url
        }
        return "$mainUrl/$url"
    }
}

//\.([A-z]) instead of \.([^-\s]) to preserve numbers like 17.4
val String?.textClean: String?
    get() = (this
        ?.replace("\\.([A-z]|\\+)".toRegex(), "$1")
        ?.replace("\\+([A-z])".toRegex(), "$1")
            )

fun stripHtml(
    txt: String,
    chapterName: String? = null,
    chapterIndex: Int? = null,
    stripAuthorNotes: Boolean
): String {
    val document = Jsoup.parse(txt)
    try {
        if (stripAuthorNotes) {
            document.select("div.qnauthornotecontainer").remove()
        }
        if (chapterName != null && chapterIndex != null) {
            for (a in document.allElements) {
                if (a != null && a.hasText() &&
                    (a.text() == chapterName || (a.tagName() == "h3" && a.text()
                        .startsWith("Chapter ${chapterIndex + 1}")))
                ) {
                    a.remove() // removes the chapter title heading
                    break
                }
            }
        }
    } catch (e: Exception) {
        logError(e)
    }

    return document.html()
        .replace("<p>.*<strong>Translator:.*?Editor:.*>".toRegex(), "")
        .replace("<.*?Translator:.*?Editor:.*?>".toRegex(), "")
}

data class HomePageList(
    val name: String,
    val list: List<SearchResponse>
)

data class HeadMainPageResponse(
    val url: String,
    val list: List<SearchResponse>,
)

data class SearchResponse(
    val name: String,
    val url: String,
    var posterUrl: String? = null,
    var rating: Int? = null,
    var latestChapter: String? = null,
    val apiName: String,
    var posterHeaders: Map<String, String>? = null
)

fun MainAPI.newSearchResponse(
    name: String,
    url: String,
    fix: Boolean = true,
    initializer: SearchResponse.() -> Unit = { },
): SearchResponse {
    val builder = SearchResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name
    )
    builder.initializer()
    builder.posterHeaders = fixPosterHeaders(builder.posterHeaders)
    return builder
}

enum class ReleaseStatus(val displayName: String) {
    Ongoing("Ongoing"),
    Completed("Completed"),
    Paused("Hiatus"),
    Dropped("Dropped"),
    Stubbed("Stubbed"),
}

fun LoadResponse.setStatus(status: String?): Boolean {
    if (status == null) {
        return false
    }
    this.status = when (status.lowercase().trim()) {
        "ongoing", "on-going", "on_going", "releasing" -> ReleaseStatus.Ongoing
        "completed", "complete", "done" -> ReleaseStatus.Completed
        "hiatus", "paused", "pause" -> ReleaseStatus.Paused
        "dropped", "drop" -> ReleaseStatus.Dropped
        "stub", "stubbed" -> ReleaseStatus.Stubbed
        else -> return false
    }
    return true
}

interface LoadResponse {
    val url: String
    val name: String
    var author: String?
    var posterUrl: String?

    // RATING IS FROM 0-1000
    var rating: Int?
    var peopleVoted: Int?
    var views: Int?
    var synopsis: String?
    var tags: List<String>?
    var status: ReleaseStatus?
    var posterHeaders: Map<String, String>?

    val apiName: String
    var related: List<SearchResponse>?
}

data class StreamResponse(
    override val url: String,
    override val name: String,
    val data: List<ChapterData>,
    override val apiName: String,
    override var author: String? = null,
    override var posterUrl: String? = null,
    override var rating: Int? = null,
    override var peopleVoted: Int? = null,
    override var views: Int? = null,
    override var synopsis: String? = null,
    override var tags: List<String>? = null,
    override var status: ReleaseStatus? = null,
    override var posterHeaders: Map<String, String>? = null,
    var nextChapter: ChapterData? = null,
    override var related: List<SearchResponse>? = null
) : LoadResponse

suspend fun MainAPI.newStreamResponse(
    name: String,
    url: String,
    data: List<ChapterData>,
    fix: Boolean = true,
    initializer: suspend StreamResponse.() -> Unit = { },
): StreamResponse {
    val builder = StreamResponse(
        name = name,
        url = if (fix) fixUrl(url) else url,
        apiName = this.name,
        data = data
    )
    builder.initializer()
    builder.posterHeaders = fixPosterHeaders(builder.posterHeaders)

    return builder
}

data class ChapterData(
    val name: String,
    val url: String,
    var dateOfRelease: String? = null,
    val views: Int? = null,
)

fun MainAPI.newChapterData(
    name: String,
    url: String,
    fix: Boolean = true,
    initializer: ChapterData.() -> Unit = { },
): ChapterData {
    val builder = ChapterData(name = name, url = if (fix) fixUrl(url) else url)
    builder.initializer()

    return builder
}

// ---------------------------------------------------------------------------
// Helpers ported from QuickNovel's mvvm/ArchComponentExt.kt and util/AppUtils.kt
// ---------------------------------------------------------------------------

fun logError(throwable: Throwable) {
    Logger.e("NfSource", "Error: ${throwable.message}")
    throwable.printStackTrace()
}

fun <T> safe(apiCall: () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }
}

suspend fun <T> safeAsync(apiCall: suspend () -> T): T? {
    return try {
        apiCall.invoke()
    } catch (throwable: Throwable) {
        logError(throwable)
        null
    }
}

object DefaultImagesHeaders {
    val useCloudflareKillerHeader = "useCloudflareKiller" to "true"
    val useIgnore500Header = "useIgnore500" to "true"
}

// JSON helpers backed by Gson (QuickNovel uses Jackson; provider annotations were
// rewritten from @JsonProperty to @SerializedName during the port).
val nfGson: Gson = Gson()

inline fun <reified T> parseJson(value: String): T {
    return nfGson.fromJson(value, object : TypeToken<T>() {}.type)
}

inline fun <reified T> tryParseJson(value: String?): T? {
    return try {
        parseJson(value ?: return null)
    } catch (e: Exception) {
        null
    }
}

fun Any.toJson(): String {
    if (this is String) return this
    return nfGson.toJson(this)
}
