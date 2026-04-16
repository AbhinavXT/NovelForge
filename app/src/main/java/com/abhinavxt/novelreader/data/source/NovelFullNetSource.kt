package com.abhinavxt.novelreader.data.source

import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.data.model.NovelPreview
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * NovelFull.net — uses the "AllNovel" HTML structure from QuickNovel.
 * Shares selectors with allnovel.org, novelfull.com, etc.
 * Confirmed accessible without Cloudflare.
 */
class NovelFullNetSource : Source {

    override val id = "nfn"
    override val name = "NovelFull"
    override val baseUrl = "https://novelfull.net"

    // Mobile user-agent required per QuickNovel
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 13; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36"

    private val client = SourceManager.sharedClient

    private suspend fun fetchDocument(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                Logger.d("NovelFullNet", "Fetching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", mobileUserAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { Jsoup.parse(it, url) }
                } else {
                    Logger.e("NovelFullNet", "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Logger.e("NovelFullNet", "fetchDocument error", e)
                null
            }
        }
    }

    /**
     * Search: GET $mainUrl/search?keyword=query
     * Selector: #list-page > .archive > .list > .row
     */
    override suspend fun search(query: String): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()

        try {
            val document = fetchDocument("$baseUrl/search?keyword=$query") ?: return novels

            val items = document.select("#list-page > .archive > .list > .row")

            for (item in items) {
                try {
                    val titleEl = item.selectFirst(">div>div>.truyen-title>a")
                        ?: item.selectFirst(">div>div>.novel-title>a") ?: continue
                    val title = titleEl.text().trim()
                    if (title.isBlank()) continue

                    val href = titleEl.attr("href")
                    if (href.isBlank()) continue

                    val slug = extractSlug(fixUrl(href))
                    if (slug.isBlank()) continue

                    val coverUrl = item.selectFirst(">div>div>img")?.attr("src")?.let { fixUrlNull(it) }

                    novels.add(
                        NovelPreview(
                            id = "nfn_$slug",
                            title = title,
                            author = "Unknown",
                            coverUrl = coverUrl,
                            description = "",
                            source = name
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("NovelFullNet", "Error parsing search result", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("NovelFullNet", "Search error", e)
        }

        Logger.d("NovelFullNet", "Search returned ${novels.size} results")
        return novels
    }

    /**
     * Browse: GET $mainUrl/hot-novel?page=N
     * Selector: div.list > div.row
     */
    override suspend fun getPopular(page: Int): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            for (p in page..(page + 2)) {
                val url = "$baseUrl/hot-novel?page=$p"
                val document = fetchDocument(url) ?: break

                val items = document.select("div.list>div.row")
                if (items.isEmpty()) break

                for (item in items) {
                    try {
                        val a = item.selectFirst("div > div > h3.truyen-title > a") ?: continue
                        val title = a.text().trim()
                        if (title.isBlank()) continue

                        val href = a.attr("href")
                        if (href.isBlank()) continue

                        val slug = extractSlug(fixUrl(href))
                        if (slug.isBlank() || slug in seenIds) continue
                        seenIds.add(slug)

                        val coverUrl = item.selectFirst("div > div > img")?.attr("src")
                            ?.let { fixUrlNull(it) }

                        novels.add(
                            NovelPreview(
                                id = "nfn_$slug",
                                title = title,
                                author = "Unknown",
                                coverUrl = coverUrl,
                                description = "",
                                source = name
                            )
                        )
                    } catch (e: Exception) {
                        Logger.e("NovelFullNet", "Error parsing novel item", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("NovelFullNet", "getPopular error", e)
        }

        Logger.d("NovelFullNet", "getPopular returned ${novels.size} results")
        return novels
    }

    /**
     * Novel details use AJAX chapter endpoint: $mainUrl/ajax-chapter-option?novelId=N
     */
    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        val document = fetchDocument(novelUrl) ?: return null

        try {
            val slug = extractSlug(novelUrl)

            val title = document.selectFirst("h3.title")?.text()?.trim() ?: return null

            // Extract novelId for AJAX chapter fetch
            val dataNovelId = document.select("#rating").attr("data-novel-id")

            // Info divs: div.info > div or ul.info > li
            val infoDivs = document.select("div.info > div").takeIf { it.isNotEmpty() }
                ?: document.select("ul.info > li")

            val author = infoDivs.find { it.text().contains("Author:") }
                ?.selectFirst("a")?.text()?.trim() ?: "Unknown"

            val coverUrl = document.selectFirst("div.book img")?.let { img ->
                val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
                fixUrlNull(src)
            }

            val description = document.selectFirst("div.desc-text")?.text()?.trim() ?: ""

            val statusText = infoDivs.find { it.text().contains("Status:") }
                ?.selectFirst("a")?.text()?.trim() ?: ""
            val status = when (statusText.lowercase()) {
                "ongoing", "on-going" -> "Ongoing"
                "completed", "complete" -> "Completed"
                else -> "Unknown"
            }

            // Fetch chapters via AJAX endpoint
            val chapters = if (dataNovelId.isNotBlank()) {
                fetchChaptersFromAjax(dataNovelId, slug)
            } else {
                emptyList()
            }

            Logger.d("NovelFullNet", "Loaded novel: $title with ${chapters.size} chapters")

            return Novel(
                id = "nfn_$slug",
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = description,
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e("NovelFullNet", "getNovelDetails error", e)
            return null
        }
    }

    /**
     * QuickNovel pattern: GET $mainUrl/ajax-chapter-option?novelId=N
     * Response contains <select> with <option> tags, or .list-chapter > li > a
     */
    private suspend fun fetchChaptersFromAjax(novelId: String, novelSlug: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()

        try {
            val ajaxUrl = "$baseUrl/ajax-chapter-option?novelId=$novelId"
            val document = fetchDocument(ajaxUrl) ?: return chapters

            // Try option tags first (older format)
            var parsed = document.select("select > option")
            if (parsed.isEmpty()) {
                // Fallback: list-chapter links
                parsed = document.select(".list-chapter>li>a")
            }

            for ((index, c) in parsed.withIndex()) {
                try {
                    var chapterUrl = c.attr("value")
                    if (chapterUrl.isNullOrBlank()) chapterUrl = c.attr("href")
                    if (chapterUrl.isNullOrBlank()) continue

                    chapterUrl = fixUrl(chapterUrl)

                    val chapterTitle = c.text().trim().ifBlank { "Chapter ${index + 1}" }
                    val chapterId = extractSlug(chapterUrl)
                    val fullId = "nfn_${novelSlug}_$chapterId"

                    chapters.add(
                        Chapter(
                            id = fullId,
                            number = index + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("NovelFullNet", "Error parsing chapter", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("NovelFullNet", "fetchChaptersFromAjax error", e)
        }

        return chapters
    }

    /**
     * Chapter content: #chapter-content or #chr-content
     */
    override suspend fun getChapterContent(chapterUrl: String): String? {
        val document = fetchDocument(chapterUrl) ?: return null

        try {
            val contentElement = document.selectFirst("#chapter-content")
                ?: document.selectFirst("#chr-content")
                ?: return null

            contentElement.select(
                "script, style, iframe, ins, noscript, .ads, .ad, .adsbygoogle"
            ).remove()

            var html = contentElement.html()
            html = html.replace("[Updated from F r e e w e b n o v e l. c o m]", "")
            html = html.replace(
                Regex("<iframe .* src=\"//ad.{0,2}-ads.com/.*\" style=\".*\"></iframe>"),
                " "
            )

            val cleanedElement = Jsoup.parse(html).body()
            val paragraphs = cleanedElement.select("p")

            return if (paragraphs.size > 3) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            } else {
                cleanedElement.text()
            }
        } catch (e: Exception) {
            Logger.e("NovelFullNet", "getChapterContent error", e)
            return null
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
    }

    private fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    private fun extractSlug(url: String): String {
        return url.trimEnd('/')
            .substringAfterLast("/")
            .removeSuffix(".html")
    }
}