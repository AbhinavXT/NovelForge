package com.abhinavxt.novelreader.data.source

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Source for primodialtranslation.com
 * WordPress site using a Flavor-type novel reader theme.
 * URL patterns:
 *   Series listing : /series/
 *   Novel detail   : /series/{slug}/
 *   Chapter        : /{novel-slug}-chapter-{n}/ or linked from novel detail
 *   Search         : /?s={query}
 */
class PrimordialTranslationSource : Source {

    override val id = "pt"
    override val name = "Primordial Translation"
    override val baseUrl = "https://primodialtranslation.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private suspend fun fetchDocument(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                Logger.d("PrimordialTL", "Fetching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
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
                    Logger.e("PrimordialTL", "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Logger.e("PrimordialTL", "fetchDocument error", e)
                null
            }
        }
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val document = fetchDocument("$baseUrl/?s=$encoded") ?: return novels

            // Flavor theme search results: .bsx articles or .listupd .bs
            val items = document.select(".bsx, .bs, article.bs, .listupd .bs")
            if (items.isEmpty()) {
                // Fallback: standard WP search results
                val wpItems = document.select("article, .post, .search-result")
                for (item in wpItems) {
                    val preview = parseNovelCard(item, seenIds) ?: continue
                    novels.add(preview)
                }
            } else {
                for (item in items) {
                    val preview = parseNovelCard(item, seenIds) ?: continue
                    novels.add(preview)
                }
            }
        } catch (e: Exception) {
            Logger.e("PrimordialTL", "Search error", e)
        }

        Logger.d("PrimordialTL", "Search returned ${novels.size} results")
        return novels
    }

    // ── Popular / Browse ────────────────────────────────────────────────

    override suspend fun getPopular(page: Int): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            // Try series listing page with pagination
            val url = if (page == 1) "$baseUrl/series/" else "$baseUrl/series/?page=$page"
            val document = fetchDocument(url) ?: return novels

            val items = document.select(".bsx, .bs, article.bs, .listupd .bs, .soralist .bsx")
            if (items.isEmpty()) {
                // Fallback: try all links inside the series listing
                val seriesLinks = document.select("a[href*=/series/]")
                val seen = mutableSetOf<String>()
                for (link in seriesLinks) {
                    val href = link.attr("abs:href")
                    if (href == "$baseUrl/series/" || href in seen) continue
                    if (!href.startsWith("$baseUrl/series/")) continue
                    seen.add(href)

                    val imgAlt = link.selectFirst("img")?.attr("alt")?.trim()
                    val title = link.text().trim().ifBlank { imgAlt ?: "" }
                    if (title.isBlank()) continue
                    val slug = extractSlug(href)
                    if (slug.isBlank() || slug in seenIds) continue
                    seenIds.add(slug)

                    val coverUrl = link.selectFirst("img")?.let { img ->
                        img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
                    }?.ifBlank { null }

                    novels.add(
                        NovelPreview(
                            id = "pt_$slug",
                            title = title,
                            author = "Unknown",
                            coverUrl = coverUrl,
                            description = "",
                            source = name
                        )
                    )
                }
            } else {
                for (item in items) {
                    val preview = parseNovelCard(item, seenIds) ?: continue
                    novels.add(preview)
                }
            }
        } catch (e: Exception) {
            Logger.e("PrimordialTL", "getPopular error", e)
        }

        Logger.d("PrimordialTL", "getPopular returned ${novels.size} results")
        return novels
    }

    // ── Novel Details ───────────────────────────────────────────────────

    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        val document = fetchDocument(novelUrl) ?: return null

        try {
            val slug = extractSlug(novelUrl)

            // Title — try multiple selectors common in Flavor themes
            val title = document.selectFirst(".entry-title")?.text()?.trim()
                ?: document.selectFirst("h1.entry-title")?.text()?.trim()
                ?: document.selectFirst(".infox h1")?.text()?.trim()
                ?: document.selectFirst(".seriestuheader h1")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: return null

            // Author
            val author = extractMetaValue(document, "Author", "Status")
                ?: extractMetaValue(document, "Writer", "Status")
                ?: document.selectFirst(".infox span:contains(Author) + span")?.text()?.trim()
                ?: "Unknown"

            // Cover image
            val coverUrl = document.selectFirst(".thumb img, .seriesthumbnail img, .seriestucontl img, .infoanime img")
                ?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
                }?.ifBlank { null }

            // Description
            val description = document.selectFirst(".entry-content[itemprop=description], .synp .entry-content, .seriestucontent .entry-content, .summary .entry-content")
                ?.text()?.trim()
                ?: document.selectFirst(".entry-content p")?.text()?.trim()
                ?: ""

            // Status
            val statusText = extractMetaValue(document, "Status", "Type")
                ?: document.selectFirst(".infox span:contains(Status) + span")?.text()?.trim()
                ?: ""
            val status = when {
                statusText.contains("Completed", ignoreCase = true) -> "Completed"
                statusText.contains("Ongoing", ignoreCase = true) -> "Ongoing"
                else -> "Unknown"
            }

            // Chapters — Flavor theme uses .eplister ul li a
            val chapters = mutableListOf<Chapter>()
            val seenChapterIds = mutableSetOf<String>()

            val chapterLinks = document.select(".eplister ul li a, .chapterlist li a, #chapterlist ul li a, .bxcl ul li a")

            if (chapterLinks.isNotEmpty()) {
                for ((index, link) in chapterLinks.withIndex()) {
                    try {
                        val chapterTitle = link.selectFirst(".chapternum, .epl-num, span")?.text()?.trim()
                            ?: link.text().trim()
                        if (chapterTitle.isBlank()) continue

                        val chapterUrl = link.attr("abs:href")
                        if (chapterUrl.isBlank()) continue
                        val chapterId = extractSlug(chapterUrl)
                        val fullId = "pt_${slug}_$chapterId"

                        if (fullId in seenChapterIds) continue
                        seenChapterIds.add(fullId)

                        chapters.add(
                            Chapter(
                                id = fullId,
                                number = index + 1,
                                title = chapterTitle,
                                url = chapterUrl
                            )
                        )
                    } catch (e: Exception) {
                        Logger.e("PrimordialTL", "Error parsing chapter", e)
                    }
                }
            }

            // Flavor themes list chapters newest-first; reverse so ch 1 is first
            if (chapters.size > 1) {
                val firstTitle = chapters.first().title.lowercase()
                val lastTitle = chapters.last().title.lowercase()
                val firstNum = Regex("\\d+").find(firstTitle)?.value?.toIntOrNull() ?: 0
                val lastNum = Regex("\\d+").find(lastTitle)?.value?.toIntOrNull() ?: 0
                if (firstNum > lastNum) {
                    chapters.reverse()
                    chapters.forEachIndexed { index, ch ->
                        chapters[index] = ch.copy(number = index + 1)
                    }
                }
            }

            Logger.d("PrimordialTL", "Loaded novel: $title with ${chapters.size} chapters")

            return Novel(
                id = "pt_$slug",
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = description,
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e("PrimordialTL", "getNovelDetails error", e)
            return null
        }
    }

    // ── Chapter Content ─────────────────────────────────────────────────

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val document = fetchDocument(chapterUrl) ?: return null

        try {
            // Flavor theme chapter content selectors
            val contentElement = document.selectFirst(".epcontent, .entry-content, .rdminimal, #readerarea, .text-left, .chapter-content")
                ?: findLargestTextContainer(document)
                ?: return null

            // Remove noise
            contentElement.select(
                "script, style, .ads, .ad, .advertisement, " +
                        ".google-auto-placed, .adsbygoogle, ins, iframe, " +
                        "noscript, .hidden, [style*=display:none], " +
                        ".code-block, .adblock, .sharedaddy, .wp-block-buttons"
            ).remove()

            var html = contentElement.html()

            // Clean common watermarks
            html = html.replace("primodialtranslation.com", "")
            html = html.replace("primordial translation", "", ignoreCase = true)

            val cleanedElement = Jsoup.parse(html).body()
            val paragraphs = cleanedElement.select("p")

            return if (paragraphs.size > 3) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            } else {
                extractTextFromHtml(cleanedElement)
            }
        } catch (e: Exception) {
            Logger.e("PrimordialTL", "getChapterContent error", e)
            return null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Parse a novel card element (used in search results and browse pages).
     * Covers common Flavor theme card structures.
     */
    private fun parseNovelCard(element: Element, seenIds: MutableSet<String>): NovelPreview? {
        try {
            // Title link — try multiple selectors
            val titleLink = element.selectFirst("a[href*=/series/]")
                ?: element.selectFirst(".tt a, .bigor a, h2 a, h3 a, .ntitle a")
                ?: element.selectFirst("a[title]")
                ?: return null

            val href = titleLink.attr("abs:href")
            if (href.isBlank() || !href.contains("/series/")) return null

            val title = titleLink.attr("title").ifBlank { titleLink.text() }.trim()
            if (title.isBlank()) return null

            val slug = extractSlug(href)
            if (slug.isBlank() || slug in seenIds) return null
            seenIds.add(slug)

            val coverUrl = element.selectFirst("img")?.let { img ->
                img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
            }?.ifBlank { null }

            return NovelPreview(
                id = "pt_$slug",
                title = title,
                author = "Unknown",
                coverUrl = coverUrl,
                description = "",
                source = name
            )
        } catch (e: Exception) {
            Logger.e("PrimordialTL", "Error parsing novel card", e)
            return null
        }
    }

    /**
     * Extract a value from the info/meta table on novel detail pages.
     * Looks for a row containing [label] and returns the adjacent value,
     * stopping before [nextLabel] if provided.
     */
    private fun extractMetaValue(document: Document, label: String, nextLabel: String?): String? {
        // Flavor theme: .infox .spe span or .tsinfo .imptdt
        val spans = document.select(".infox .spe span, .tsinfo .imptdt, .infotable td, .info-desc .spe span")
        for (span in spans) {
            val text = span.text().trim()
            if (text.contains(label, ignoreCase = true)) {
                // Value might be after a colon or in a child <a>/<span>
                val a = span.selectFirst("a")
                if (a != null) return a.text().trim()
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) return parts[1].trim()
            }
        }

        // Fallback: look for <b> or <strong> containing label
        val bolds = document.select("b, strong")
        for (bold in bolds) {
            if (bold.text().contains(label, ignoreCase = true)) {
                val parent = bold.parent() ?: continue
                val value = parent.text().replace(bold.text(), "").trim().trimStart(':').trim()
                if (value.isNotBlank()) return value
            }
        }

        return null
    }

    private fun findLargestTextContainer(document: Document): Element? {
        var bestElement: Element? = null
        var maxLength = 0

        for (element in document.select("div, article, section")) {
            val text = element.ownText() + element.select("p").text()
            val textLength = text.length

            val className = element.className().lowercase()
            val elementId = element.id().lowercase()
            if (className.contains("nav") || className.contains("header") ||
                className.contains("footer") || className.contains("sidebar") ||
                elementId.contains("nav") || elementId.contains("header") ||
                elementId.contains("footer")
            ) continue

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

        return element.text()
            .split(Regex("\\s{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 3 }
            .joinToString("\n\n")
    }

    private fun extractSlug(url: String): String {
        return url.trimEnd('/')
            .substringAfterLast("/")
            .removeSuffix(".html")
            .replace("/", "")
    }
}