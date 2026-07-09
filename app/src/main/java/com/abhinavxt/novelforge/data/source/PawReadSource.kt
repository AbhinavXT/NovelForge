package com.abhinavxt.novelforge.data.source

import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import com.abhinavxt.novelforge.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * PawRead — unique site structure with onclick chapter navigation.
 * Confirmed accessible without Cloudflare.
 * Selectors matched exactly from QuickNovel's PawReadProver.
 */
class PawReadSource : Source {

    override val id = "pr"
    override val name = "PawRead"
    override val baseUrl = "https://pawread.com"

    private val client = SourceManager.sharedClient

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private suspend fun fetchDocument(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                Logger.d("PawRead", "Fetching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { Jsoup.parse(it, url) }
                } else {
                    Logger.e("PawRead", "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Logger.e("PawRead", "fetchDocument error", e)
                null
            }
        }
    }

    /**
     * Search: GET $mainUrl/search/?keywords=query
     * Selector: .list-comic-thumbnail > .caption > h3 > a
     */
    override suspend fun search(query: String): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()

        try {
            val document = fetchDocument("$baseUrl/search/?keywords=$query") ?: return novels

            val items = document.select(".list-comic-thumbnail")

            for (item in items) {
                try {
                    val node = item.selectFirst(".caption>h3>a") ?: continue
                    val title = node.text().trim()
                    if (title.isBlank()) continue

                    val href = node.attr("href")
                    if (href.isBlank()) continue

                    val fullUrl = fixUrl(href)
                    Logger.d("PawRead", "Search result href='$href' fullUrl='$fullUrl'")

                    // Keep the path after domain for correct URL reconstruction
                    val relativePath = fullUrl.removePrefix(baseUrl).trim('/')
                    val slug = relativePath.replace("/", "~")
                    if (slug.isBlank()) continue

                    val coverUrl = item.selectFirst(".image-link>img")?.attr("src")
                        ?.let { fixUrlNull(it) }

                    novels.add(
                        NovelPreview(
                            id = "pr_$slug",
                            title = title,
                            author = "Unknown",
                            coverUrl = coverUrl,
                            description = "",
                            source = name
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("PawRead", "Error parsing search result", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("PawRead", "Search error", e)
        }

        Logger.d("PawRead", "Search returned ${novels.size} results")
        return novels
    }

    /**
     * Browse: GET $mainUrl/list/all-All/update/?page=N
     * Same selector as search
     */
    override suspend fun getPopular(page: Int): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            for (p in page..(page + 2)) {
                val url = "$baseUrl/list/all-All/click/?page=$p"
                val document = fetchDocument(url) ?: break

                val items = document.select(".list-comic-thumbnail")
                if (items.isEmpty()) break

                for (item in items) {
                    try {
                        val node = item.selectFirst(".caption>h3>a") ?: continue
                        val title = node.text().trim()
                        if (title.isBlank()) continue

                        val href = node.attr("href")
                        if (href.isBlank()) continue

                        val fullUrl = fixUrl(href)
                        val relativePath = fullUrl.removePrefix(baseUrl).trim('/')
                        val slug = relativePath.replace("/", "~")
                        if (slug.isBlank() || slug in seenIds) continue
                        seenIds.add(slug)

                        val coverUrl = item.selectFirst(".image-link>img")?.attr("src")
                            ?.let { fixUrlNull(it) }

                        novels.add(
                            NovelPreview(
                                id = "pr_$slug",
                                title = title,
                                author = "Unknown",
                                coverUrl = coverUrl,
                                description = "",
                                source = name
                            )
                        )
                    } catch (e: Exception) {
                        Logger.e("PawRead", "Error parsing novel item", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("PawRead", "getPopular error", e)
        }

        Logger.d("PawRead", "getPopular returned ${novels.size} results")
        return novels
    }

    /**
     * Novel detail: chapters use onclick='go_to(N)' pattern.
     * QuickNovel extracts chapter IDs via regex on onclick attribute.
     */
    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        val document = fetchDocument(novelUrl) ?: return null

        try {
            // Derive slug consistently with search (path with ~ encoding)
            val relativePath = novelUrl.removePrefix(baseUrl).trim('/')
            val slug = relativePath.replace("/", "~")
            val board = document.selectFirst("#tab1_board") ?: return null

            val title = board.selectFirst("div>h1")?.text()?.trim() ?: return null

            // Author and other info from #views_info > div
            val info = document.select("#views_info>div")
            val author = info.getOrNull(3)?.text()?.trim() ?: "Unknown"

            // Cover: background-image in style attribute
            val coverStyle = board.selectFirst(">.col-md-3>div")?.attr("style") ?: ""
            val coverUrl = Regex("image:url\\((.*)\\)").find(coverStyle)?.groupValues?.get(1)
                ?.let { fixUrlNull(it) }

            val description = document.selectFirst("#simple-des")?.text()?.trim() ?: ""

            val tags = document.select(".tags").map { it.text().trim().removePrefix("#").trim() }
            val status = when {
                tags.any { it.contains("Completed", true) } -> "Completed"
                tags.any { it.contains("Ongoing", true) } -> "Ongoing"
                else -> "Unknown"
            }

            // Chapters: .item-box elements with onclick containing chapter ID
            val chapterRegex = Regex("'(\\d+)'")
            val prefix = "${novelUrl.trimEnd('/')}/"

            val chapters = document.select(".item-box")
                .filter { it.selectFirst("div>svg") == null } // Skip locked chapters
                .mapIndexedNotNull { index, select ->
                    try {
                        val chapterTitle = select.selectFirst("div>span.c_title")?.text()?.trim()
                            ?: return@mapIndexedNotNull null
                        val onclick = select.attr("onclick")
                        val chapterId = chapterRegex.find(onclick)?.groupValues?.get(1)
                            ?: return@mapIndexedNotNull null
                        val chapterUrl = "$prefix$chapterId.html"

                        Chapter(
                            id = "pr_${slug}_$chapterId",
                            number = index + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    } catch (e: Exception) {
                        Logger.e("PawRead", "Error parsing chapter", e)
                        null
                    }
                }

            Logger.d("PawRead", "Loaded novel: $title with ${chapters.size} chapters")

            return Novel(
                id = "pr_$slug",
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = description,
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e("PawRead", "getNovelDetails error", e)
            return null
        }
    }

    /**
     * Chapter content: #chapter_item
     */
    override suspend fun getChapterContent(chapterUrl: String): String? {
        val document = fetchDocument(chapterUrl) ?: return null

        try {
            // Check for countdown (unreleased chapter)
            val countdown = document.selectFirst("#countdown")
            if (countdown != null) {
                return "Chapter not yet released. Time remaining: ${countdown.text()}"
            }

            val contentElement = document.selectFirst("#chapter_item") ?: return null

            contentElement.select(
                "script, style, iframe, ins, noscript, .ads, .ad, .adsbygoogle"
            ).remove()

            val paragraphs = contentElement.select("p")

            return if (paragraphs.size > 3) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            } else {
                contentElement.html()
                    .replace(Regex("<br\\s*/?>"), "\n")
                    .let { Jsoup.parse(it).text() }
                    .split(Regex("\\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            }
        } catch (e: Exception) {
            Logger.e("PawRead", "getChapterContent error", e)
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