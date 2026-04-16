package com.abhinavxt.novelreader.data.source

import com.abhinavxt.novelreader.data.model.Chapter
import com.abhinavxt.novelreader.data.model.Novel
import com.abhinavxt.novelreader.data.model.NovelPreview
import com.abhinavxt.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

open class LibReadSource : Source {

    override val id = "lr"
    override val name = "LibRead"
    override val baseUrl = "https://libread.com"

    // Subclasses can override to strip .html from URLs
    open val removeHtml = false

    private val client = SourceManager.sharedClient

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"

    protected suspend fun fetchDocument(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                Logger.d(name, "Fetching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", baseUrl)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { html ->
                        Jsoup.parse(html, url)
                    }
                } else {
                    Logger.e(name, "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Logger.e(name, "fetchDocument error", e)
                null
            }
        }
    }

    /**
     * Search uses POST with form data, matching QuickNovel's approach.
     */
    override suspend fun search(query: String): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()

        try {
            val document = withContext(Dispatchers.IO) {
                val formBody = FormBody.Builder()
                    .add("searchkey", query)
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/search")
                    .post(formBody)
                    .header("User-Agent", userAgent)
                    .header("Referer", baseUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "*/*")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { Jsoup.parse(it, baseUrl) }
                } else {
                    Logger.e(name, "Search request failed: ${response.code}")
                    null
                }
            } ?: return novels

            // QuickNovel selector: div.li-row > div.li > div.con
            val items = document.select("div.li-row > div.li > div.con")

            for (item in items) {
                try {
                    val h3 = item.selectFirst("div.txt > h3.tit > a") ?: continue
                    val title = h3.attr("title").ifBlank { h3.text() }.trim()
                    if (title.isBlank()) continue

                    val href = h3.attr("href")
                    if (href.isBlank()) continue
                    val novelUrl = fixUrl(href)

                    val slug = extractSlug(novelUrl)
                    if (slug.isBlank()) continue

                    val coverUrl = item.selectFirst("div.pic img")?.attr("src")?.let { fixUrlNull(it) }

                    novels.add(
                        NovelPreview(
                            id = "${id}_$slug",
                            title = title,
                            author = "Unknown",
                            coverUrl = coverUrl,
                            description = "",
                            source = name
                        )
                    )
                } catch (e: Exception) {
                    Logger.e(name, "Error parsing search result", e)
                }
            }
        } catch (e: Exception) {
            Logger.e(name, "Search error", e)
        }

        Logger.d(name, "Search returned ${novels.size} results")
        return novels
    }

    /**
     * Browse popular/latest novels.
     * URL pattern: $mainUrl/sort/latest-release/$page
     */
    override suspend fun getPopular(page: Int): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            for (p in page..(page + 2)) {
                val url = "$baseUrl/sort/latest-release/$p"
                val document = fetchDocument(url) ?: break

                // QuickNovel selector: div.ul-list1.ul-list1-2.ss-custom > div.li-row
                val items = document.select("div.ul-list1.ul-list1-2.ss-custom > div.li-row")
                if (items.isEmpty()) break

                for (item in items) {
                    try {
                        val h3 = item.selectFirst("h3.tit > a") ?: continue
                        val title = h3.attr("title").ifBlank { h3.text() }.trim()
                        if (title.isBlank()) continue

                        val href = h3.attr("href")
                        if (href.isBlank()) continue
                        val novelUrl = fixUrl(href)

                        val slug = extractSlug(novelUrl)
                        if (slug.isBlank() || slug in seenIds) continue
                        seenIds.add(slug)

                        val coverUrl = item.selectFirst("div.pic > a > img")?.attr("src")
                            ?.let { fixUrlNull(it) }

                        novels.add(
                            NovelPreview(
                                id = "${id}_$slug",
                                title = title,
                                author = "Unknown",
                                coverUrl = coverUrl,
                                description = "",
                                source = name
                            )
                        )
                    } catch (e: Exception) {
                        Logger.e(name, "Error parsing novel item", e)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(name, "getPopular error", e)
        }

        Logger.d(name, "getPopular returned ${novels.size} results")
        return novels
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        val document = fetchDocument(novelUrl) ?: return null

        try {
            val slug = extractSlug(novelUrl)

            // Title: h1.tit
            val title = document.selectFirst("h1.tit")?.text()?.trim() ?: return null

            // Author: span.glyphicon-user + next sibling
            val author = document.selectFirst("span.glyphicon.glyphicon-user")
                ?.nextElementSibling()?.text()?.trim() ?: "Unknown"

            // Cover: div.pic > img
            val coverUrl = document.select("div.pic > img").attr("src")
                ?.let { fixUrlNull(it) }

            // Synopsis: div.inner
            val description = document.selectFirst("div.inner")?.text()?.trim() ?: ""

            // Status: span.s1.s3 > a or span.s1.s2 > a
            val statusHeader = document.selectFirst("span.s1.s3")
            val statusHeader0 = document.selectFirst("span.s1.s2")
            val statusText = statusHeader?.selectFirst("a")?.text()
                ?: statusHeader0?.selectFirst("a")?.text() ?: ""
            val status = when (statusText.lowercase().trim()) {
                "ongoing" -> "Ongoing"
                "completed", "complete" -> "Completed"
                else -> "Unknown"
            }

            // Chapters: div.m-newest2 ul.ul-list5 li > a
            val chapterElements = document.select("div.m-newest2 ul.ul-list5 li")
            val chapters = mutableListOf<Chapter>()

            for ((index, li) in chapterElements.withIndex()) {
                try {
                    val a = li.selectFirst("a") ?: continue
                    val chapterTitle = a.text().trim()
                    if (chapterTitle.isBlank()) continue

                    val chapterUrl = fixUrl(a.attr("href"))
                    val chapterId = extractSlug(chapterUrl)
                    val fullId = "${id}_${slug}_$chapterId"

                    chapters.add(
                        Chapter(
                            id = fullId,
                            number = index + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    )
                } catch (e: Exception) {
                    Logger.e(name, "Error parsing chapter", e)
                }
            }

            Logger.d(name, "Loaded novel: $title with ${chapters.size} chapters")

            return Novel(
                id = "${id}_$slug",
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = description,
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e(name, "getNovelDetails error", e)
            return null
        }
    }

    /**
     * Chapter content: div.txt (with watermark removal)
     */
    override suspend fun getChapterContent(chapterUrl: String): String? {
        val document = fetchDocument(chapterUrl) ?: return null

        try {
            val contentElement = document.selectFirst("div.txt") ?: return null

            // Remove notice text
            contentElement.select(".notice-text").remove()

            // Remove ads, scripts
            contentElement.select(
                "script, style, .ads, .ad, .adsbygoogle, ins, iframe, noscript"
            ).remove()

            // Clean watermarks
            var html = contentElement.html()
            html = html.replace("libread.com", "", ignoreCase = true)
            html = html.replace(
                "\uD835\uDCF5\uD835\uDC8A\uD835\uDC83\uD835\uDE67\uD835\uDE5A\uD835\uDC82\uD835\uDCED.\uD835\uDCEC\uD835\uDE64\uD835\uDE62",
                "", ignoreCase = true
            )

            val cleanedElement = Jsoup.parse(html).body()
            val paragraphs = cleanedElement.select("p")

            return if (paragraphs.size > 3) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            } else {
                // Fallback: get all text with br handling
                cleanedElement.select("br").forEach { it.after("\n") }
                cleanedElement.text()
                    .split(Regex("\\n{2,}"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            }
        } catch (e: Exception) {
            Logger.e(name, "getChapterContent error", e)
            return null
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    protected fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
    }

    protected fun fixUrlNull(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url)
    }

    protected fun extractSlug(url: String): String {
        return url.trimEnd('/')
            .substringAfterLast("/")
            .removeSuffix(".html")
    }
}