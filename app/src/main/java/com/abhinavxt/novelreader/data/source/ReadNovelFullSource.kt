package com.abhinavxt.novelreader.data.source

import android.util.Log
import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.data.model.NovelPreview
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class ReadNovelFullSource : Source {

    override val id = "rnf"
    override val name = "ReadNovelFull"
    override val baseUrl = "https://readnovelfull.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private suspend fun fetchDocument(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                Logger.d("ReadNovelFull", "Fetching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", baseUrl)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { html ->
                        Jsoup.parse(html, url)
                    }
                } else {
                    Logger.e("ReadNovelFull", "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Logger.e("ReadNovelFull", "fetchDocument error", e)
                Logger.e("Error", e)
                null
            }
        }
    }

    override suspend fun search(query: String): List<NovelPreview> {
        val allNovels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            // Use correct search URL from QuickNovel
            for (page in 1..3) {
                val searchUrl = "$baseUrl/novel-list/search?keyword=${query.replace(" ", "+")}&page=$page"
                val document = fetchDocument(searchUrl) ?: break

                val novels = parseSearchResults(document)
                if (novels.isEmpty()) break

                for (novel in novels) {
                    if (novel.id !in seenIds) {
                        seenIds.add(novel.id)
                        allNovels.add(novel)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("ReadNovelFull", "Search error", e)
            Logger.e("Error", e)
        }

        Logger.d("ReadNovelFull", "Search returned ${allNovels.size} results")
        return allNovels
    }

    private fun parseSearchResults(document: Document): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()

        // Selector from QuickNovel: div.col-novel-main > div.list-novel > div.row
        val rows = document.select("div.col-novel-main > div.list-novel > div.row")

        for (row in rows) {
            try {
                val divs = row.select("> div > div")
                if (divs.size < 2) continue

                // Poster from first div
                val poster = divs.getOrNull(0)
                    ?.selectFirst("img")
                    ?.attr("src")
                    ?.replace("t-200x89", "t-300x439")  // Get larger image

                // Title and URL from second div
                val titleHeader = divs.getOrNull(1)?.selectFirst("h3.novel-title > a")
                val href = titleHeader?.attr("abs:href") ?: continue
                val title = titleHeader.text().trim()
                if (title.isBlank()) continue

                val novelSlug = extractNovelSlug(href)
                if (novelSlug.isBlank()) continue

                novels.add(
                    NovelPreview(
                        id = "rnf_$novelSlug",
                        title = title,
                        author = "Unknown",
                        coverUrl = poster,
                        description = "",
                        source = name
                    )
                )
            } catch (e: Exception) {
                Logger.e("ReadNovelFull", "Error parsing search result", e)
                Logger.e("Error", e)
            }
        }

        return novels
    }

    override suspend fun getPopular(page: Int): List<NovelPreview> {
        val allNovels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            for (p in page..(page + 2)) {
                val url = "$baseUrl/novel-list/most-popular-novel?page=$p"
                val document = fetchDocument(url) ?: break

                val novels = parseSearchResults(document)
                if (novels.isEmpty()) break

                for (novel in novels) {
                    if (novel.id !in seenIds) {
                        seenIds.add(novel.id)
                        allNovels.add(novel)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("ReadNovelFull", "getPopular error", e)
            Logger.e("Error", e)
        }

        return allNovels
    }

    private fun extractNovelSlug(url: String): String {
        return url
            .substringAfterLast("/")
            .removeSuffix(".html")
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        val document = fetchDocument(novelUrl) ?: return null

        try {
            val slug = extractNovelSlug(novelUrl)

            // Get novelId from data-novel-id attribute (key insight from QuickNovel!)
            val novelId = document.selectFirst("div#rating")?.attr("data-novel-id")
                ?: document.selectFirst("[data-novel-id]")?.attr("data-novel-id")

            Logger.d("ReadNovelFull", "Novel slug: $slug, novelId: $novelId")

            // Title: div.books > div.desc > h3.title
            val bookInfo = document.selectFirst("div.col-info-desc > div.info-holder > div.books")
            val title = bookInfo?.selectFirst("div.desc > h3.title")?.text()?.trim()
                ?: document.selectFirst("h3.title")?.text()?.trim()
                ?: return null

            // Author from info-meta
            val author = getInfoMeta(document, "Author:")
                ?.selectFirst("a")?.text()?.trim()
                ?: "Unknown"

            // Cover: div.book > img
            val coverUrl = bookInfo?.selectFirst("div.book > img")?.attr("abs:src")
                ?: document.selectFirst(".book img")?.attr("abs:src")

            // Synopsis: div.desc-text
            val description = document.selectFirst("div.desc-text")?.text()?.trim() ?: ""

            // Status from info-meta
            val statusText = getInfoMeta(document, "Status:")
                ?.selectFirst("a")?.text()?.trim() ?: ""

            val status = when {
                statusText.contains("Completed", ignoreCase = true) -> "Completed"
                statusText.contains("Ongoing", ignoreCase = true) -> "Ongoing"
                else -> "Unknown"
            }

            // Get ALL chapters using AJAX endpoint with novelId
            val chapters = if (novelId != null && novelId.isNotBlank()) {
                fetchChaptersFromAjax(novelId, slug)
            } else {
                fetchChaptersFromPage(document, slug)
            }

            Logger.d("ReadNovelFull", "Loaded novel: $title with ${chapters.size} chapters")

            return Novel(
                id = "rnf_$slug",
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = description,
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e("ReadNovelFull", "getNovelDetails error", e)
            Logger.e("Error", e)
            return null
        }
    }

    // Helper function to get info from info-meta list (like QuickNovel's getData)
    private fun getInfoMeta(document: Document, label: String): Element? {
        val infoMetas = document.select("ul.info-meta > li")
        for (item in infoMetas) {
            if (item.selectFirst("h3")?.text()?.contains(label, ignoreCase = true) == true) {
                return item
            }
        }
        return null
    }

    // Fetch chapters using AJAX endpoint with numeric novelId
    private suspend fun fetchChaptersFromAjax(novelId: String, novelSlug: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val seenIds = mutableSetOf<String>()

        try {
            val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
            val document = fetchDocument(ajaxUrl) ?: return chapters

            // Selector from QuickNovel: div.panel-body > div.row > div > ul.list-chapter > li > a
            val chapterLinks = document.select("div.panel-body > div.row > div > ul.list-chapter > li > a")

            Logger.d("ReadNovelFull", "Found ${chapterLinks.size} chapters from AJAX")

            for ((index, link) in chapterLinks.withIndex()) {
                try {
                    val chapterTitle = link.selectFirst("span")?.text()?.trim()
                        ?: link.text().trim()
                    if (chapterTitle.isBlank()) continue

                    val chapterUrl = link.attr("abs:href").ifBlank {
                        baseUrl + link.attr("href")
                    }
                    if (chapterUrl.isBlank()) continue

                    val chapterId = extractChapterId(chapterUrl)
                    val fullId = "rnf_${novelSlug}_$chapterId"

                    if (fullId in seenIds) continue
                    seenIds.add(fullId)

                    chapters.add(
                        Chapter(
                            id = fullId,
                            number = index + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("ReadNovelFull", "Error parsing chapter", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("ReadNovelFull", "fetchChaptersFromAjax error", e)
            Logger.e("Error", e)
        }

        return chapters
    }

    // Fallback: fetch chapters from the main page
    private fun fetchChaptersFromPage(document: Document, novelSlug: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val seenIds = mutableSetOf<String>()

        try {
            val chapterLinks = document.select("#list-chapter a, .list-chapter a, ul.list-chapter li a")

            Logger.d("ReadNovelFull", "Found ${chapterLinks.size} chapters from page")

            for ((index, link) in chapterLinks.withIndex()) {
                try {
                    val chapterTitle = link.selectFirst("span")?.text()?.trim()
                        ?: link.text().trim()
                    if (chapterTitle.isBlank()) continue

                    val chapterUrl = link.attr("abs:href").ifBlank {
                        baseUrl + link.attr("href")
                    }
                    if (chapterUrl.isBlank() || !chapterUrl.contains("chapter")) continue

                    val chapterId = extractChapterId(chapterUrl)
                    val fullId = "rnf_${novelSlug}_$chapterId"

                    if (fullId in seenIds) continue
                    seenIds.add(fullId)

                    chapters.add(
                        Chapter(
                            id = fullId,
                            number = index + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("ReadNovelFull", "Error parsing chapter from page", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("ReadNovelFull", "fetchChaptersFromPage error", e)
            Logger.e("Error", e)
        }

        return chapters
    }

    private fun extractChapterId(url: String): String {
        return url
            .substringAfterLast("/")
            .removeSuffix(".html")
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val document = fetchDocument(chapterUrl) ?: return null

        try {
            // Main selector from QuickNovel: div#chr-content
            val contentElement = document.selectFirst("div#chr-content")
                ?: document.selectFirst("#chr-content")
                ?: document.selectFirst("#chapter-content")
                ?: document.selectFirst(".chr-c")
                ?: findLargestTextContainer(document)
                ?: return null

            // Remove ads, scripts, etc.
            contentElement.select(
                "script, style, .ads, .ad, .advertisement, " +
                        ".google-auto-placed, .adsbygoogle, ins, iframe, " +
                        "noscript, .hidden, [style*=display:none]"
            ).remove()

            // Get HTML and clean it
            var html = contentElement.html()

            // Remove common watermarks (from QuickNovel)
            html = html.replace("[Updated from F r e e w e b n o v e l. c o m]", "")
            html = html.replace("Read latest chapters at f(r)eewebnovel Only", "")
            html = html.replace("freewebnovel.com", "")
            html = html.replace("readnovelfull.com", "")
            html = html.replace("ƒreewebnovel.com", "")
            html = html.replace("Find authorized novels in Webnovel，faster updates, better experience，Please click www.webnovel.com for visiting.", "")

            // Parse cleaned HTML
            val cleanedElement = Jsoup.parse(html).body()

            val paragraphs = cleanedElement.select("p")

            return if (paragraphs.size > 3) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && it.length > 5 }
                    .joinToString("\n\n")
            } else {
                extractTextFromHtml(cleanedElement)
            }
        } catch (e: Exception) {
            Logger.e("ReadNovelFull", "getChapterContent error", e)
            Logger.e("Error", e)
            return null
        }
    }

    private fun findLargestTextContainer(document: Document): Element? {
        var bestElement: Element? = null
        var maxLength = 0

        val candidates = document.select("div, article, section")

        for (element in candidates) {
            val text = element.ownText() + element.select("p").text()
            val textLength = text.length

            val className = element.className().lowercase()
            val elementId = element.id().lowercase()

            if (className.contains("nav") ||
                className.contains("header") ||
                className.contains("footer") ||
                className.contains("menu") ||
                className.contains("sidebar") ||
                elementId.contains("nav") ||
                elementId.contains("header") ||
                elementId.contains("footer")) {
                continue
            }

            if (textLength > maxLength && textLength > 500) {
                maxLength = textLength
                bestElement = element
            }
        }

        return bestElement
    }

    private fun extractTextFromHtml(element: Element): String {
        element.select("br").forEach { it.after("\n") }
        element.select("p").forEach { it.after("\n\n") }

        val text = element.text()

        return text
            .split(Regex("\\s{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 5 }
            .joinToString("\n\n")
    }
}