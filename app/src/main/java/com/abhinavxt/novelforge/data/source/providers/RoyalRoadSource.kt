package com.abhinavxt.novelforge.data.source.providers

import com.abhinavxt.novelforge.data.source.*

import com.abhinavxt.novelforge.data.model.Chapter
import com.abhinavxt.novelforge.data.model.Novel
import com.abhinavxt.novelforge.data.model.NovelPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import com.abhinavxt.novelforge.util.Logger

class RoyalRoadSource : BrowseSource {

    override val id = "royalroad"
    override val name = "Royal Road"
    override val baseUrl = "https://www.royalroad.com"

    private val client = SourceManager.sharedClient

    private suspend fun fetchDocument(url: String): Document? {
        return withContext(Dispatchers.IO) {
            try {
                Logger.d("RoyalRoad", "Fetching: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { html ->
                        Jsoup.parse(html, url)
                    }
                } else {
                    Logger.e("RoyalRoad", "Request failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Logger.e("RoyalRoad", "fetchDocument error", e)
                Logger.e("Error", e)
                null
            }
        }
    }

    override suspend fun search(query: String): List<NovelPreview> {
        val allNovels = mutableListOf<NovelPreview>()
        val seenIds = mutableSetOf<String>()

        try {
            for (page in 1..3) {
                val searchUrl = "$baseUrl/fictions/search?title=${query.replace(" ", "+")}&page=$page"
                val document = fetchDocument(searchUrl) ?: break

                val novels = parseNovelList(document)
                if (novels.isEmpty()) break

                for (novel in novels) {
                    if (novel.id !in seenIds) {
                        seenIds.add(novel.id)
                        allNovels.add(novel)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("RoyalRoad", "Search error", e)
            Logger.e("Error", e)
        }

        Logger.d("RoyalRoad", "Search returned ${allNovels.size} results")
        return allNovels
    }

    override suspend fun getPopular(page: Int): List<NovelPreview> =
        browse(page, category = null, orderBy = null, tag = null)

    override val canBrowse = true

    override val filters = BrowseFilters(
        orderBys = listOf(
            FilterOption("Best Rated", "best-rated"),
            FilterOption("Trending", "trending"),
            FilterOption("Ongoing", "active-popular"),
            FilterOption("Completed", "complete"),
            FilterOption("Popular this week", "weekly-popular"),
            FilterOption("Latest Updates", "latest-updates"),
            FilterOption("New Releases", "new-releases"),
            FilterOption("Rising Stars", "rising-stars"),
        ),
        tags = listOf(
            FilterOption("Action", "action"),
            FilterOption("Adventure", "adventure"),
            FilterOption("Anti-Hero Lead", "anti-hero_lead"),
            FilterOption("Comedy", "comedy"),
            FilterOption("Contemporary", "contemporary"),
            FilterOption("Cyberpunk", "cyberpunk"),
            FilterOption("Drama", "drama"),
            FilterOption("Fantasy", "fantasy"),
            FilterOption("Female Lead", "female_lead"),
            FilterOption("GameLit", "gamelit"),
            FilterOption("Gender Bender", "gender_bender"),
            FilterOption("Grimdark", "grimdark"),
            FilterOption("Harem", "harem"),
            FilterOption("High Fantasy", "high_fantasy"),
            FilterOption("Historical", "historical"),
            FilterOption("Horror", "horror"),
            FilterOption("LitRPG", "litrpg"),
            FilterOption("Low Fantasy", "low_fantasy"),
            FilterOption("Magic", "magic"),
            FilterOption("Male Lead", "male_lead"),
            FilterOption("Martial Arts", "martial_arts"),
            FilterOption("Mystery", "mystery"),
            FilterOption("Mythos", "mythos"),
            FilterOption("Portal Fantasy / Isekai", "summoned_hero"),
            FilterOption("Progression", "progression"),
            FilterOption("Psychological", "psychological"),
            FilterOption("Reincarnation", "reincarnation"),
            FilterOption("Romance", "romance"),
            FilterOption("Satire", "satire"),
            FilterOption("Sci-fi", "sci_fi"),
            FilterOption("Secret Identity", "secret_identity"),
            FilterOption("Short Story", "one_shot"),
            FilterOption("Slice of Life", "slice_of_life"),
            FilterOption("Space Opera", "space_opera"),
            FilterOption("Strategy", "strategy"),
            FilterOption("Strong Lead", "strong_lead"),
            FilterOption("Time Loop", "loop"),
            FilterOption("Time Travel", "time_travel"),
            FilterOption("Tragedy", "tragedy"),
            FilterOption("Virtual Reality", "virtual_reality"),
            FilterOption("War and Military", "war_and_military"),
            FilterOption("Wuxia", "wuxia"),
        ),
    )

    override suspend fun browse(
        page: Int,
        category: String?,
        orderBy: String?,
        tag: String?,
    ): List<NovelPreview> {
        val list = orderBy ?: "best-rated"
        // RR's trending and rising-stars lists are single-page.
        if (page > 1 && list in setOf("trending", "rising-stars")) return emptyList()

        return try {
            val genreParam = if (tag.isNullOrBlank()) "" else "&genre=$tag"
            val url = "$baseUrl/fictions/$list?page=$page$genreParam"
            val document = fetchDocument(url) ?: return emptyList()
            parseNovelList(document).distinctBy { it.id }
        } catch (e: Exception) {
            Logger.e("RoyalRoad", "browse error (p$page, $list)", e)
            emptyList()
        }
    }

    private fun parseNovelList(document: Document): List<NovelPreview> {
        val novels = mutableListOf<NovelPreview>()

        val items = document.select(".fiction-list-item")

        for (item in items) {
            try {
                val titleElement = item.selectFirst(".fiction-title a") ?: continue
                val title = titleElement.text().trim()
                if (title.isBlank()) continue

                val novelUrl = titleElement.attr("abs:href")

                // Extract ID from URL: /fiction/12345/novel-name -> 12345
                val id = novelUrl
                    .substringAfter("/fiction/")
                    .substringBefore("/")

                if (id.isBlank()) continue

                val author = item.selectFirst(".author")?.text()
                    ?.removePrefix("by ")
                    ?.trim()
                    ?: "Unknown"

                val coverUrl = item.selectFirst("img")?.attr("abs:src")

                val description = item.selectFirst(".description")?.text()?.trim() ?: ""

                novels.add(
                    NovelPreview(
                        id = "rr_$id",
                        title = title,
                        author = author,
                        coverUrl = coverUrl,
                        description = description,
                        source = name
                    )
                )
            } catch (e: Exception) {
                Logger.e("RoyalRoad", "Error parsing novel item", e)
                Logger.e("Error", e)
            }
        }

        return novels
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel? {
        val document = fetchDocument(novelUrl) ?: return null

        try {
            val id = novelUrl
                .substringAfter("/fiction/")
                .substringBefore("/")

            val title = document.selectFirst("h1.font-white")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: return null

            val author = document.selectFirst("h4.font-white a")?.text()?.trim()
                ?: document.selectFirst("a[href*=/profile/]")?.text()?.trim()
                ?: "Unknown"

            val coverUrl = document.selectFirst(".cover-art-container img")?.attr("abs:src")
                ?: document.selectFirst(".fic-header img")?.attr("abs:src")

            val description = document.selectFirst(".description .hidden-content")?.text()?.trim()
                ?: document.selectFirst(".description")?.text()?.trim()
                ?: ""

            val statusElement = document.select(".fiction-info .label").find {
                it.text().contains("ONGOING", ignoreCase = true) ||
                        it.text().contains("COMPLETED", ignoreCase = true) ||
                        it.text().contains("HIATUS", ignoreCase = true)
            }
            val status = statusElement?.text()?.trim() ?: "Unknown"

            // Get ALL chapters
            val chapters = fetchAllChapters(document, id)

            Logger.d("RoyalRoad", "Loaded novel: $title with ${chapters.size} chapters")

            return Novel(
                id = "rr_$id",
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = description,
                source = name,
                status = status,
                chapters = chapters
            )
        } catch (e: Exception) {
            Logger.e("RoyalRoad", "getNovelDetails error", e)
            Logger.e("Error", e)
            return null
        }
    }

    private fun fetchAllChapters(document: Document, novelId: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        val seenUrls = mutableSetOf<String>()

        try {
            // Royal Road shows all chapters in a table on the fiction page
            val chapterRows = document.select("#chapters tbody tr, table.chapter-list tr, .chapter-row")

            for ((index, row) in chapterRows.withIndex()) {
                try {
                    val linkElement = row.selectFirst("a[href*=/chapter/]") ?: continue
                    val chapterTitle = linkElement.text().trim()
                    if (chapterTitle.isBlank()) continue

                    val chapterUrl = linkElement.attr("abs:href")
                    if (chapterUrl.isBlank() || chapterUrl in seenUrls) continue
                    seenUrls.add(chapterUrl)

                    // Extract chapter ID from URL
                    val chapterId = chapterUrl
                        .substringAfter("/chapter/")
                        .substringBefore("/")
                        .ifBlank { index.toString() }

                    chapters.add(
                        Chapter(
                            id = "rr_${novelId}_$chapterId",
                            number = index + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("RoyalRoad", "Error parsing chapter row", e)
                }
            }

            // If no chapters found with the table, try alternative selectors
            if (chapters.isEmpty()) {
                val chapterLinks = document.select("a[href*=/chapter/]")

                for ((index, link) in chapterLinks.withIndex()) {
                    val chapterUrl = link.attr("abs:href")
                    if (chapterUrl in seenUrls) continue
                    seenUrls.add(chapterUrl)

                    val chapterTitle = link.text().trim()
                    if (chapterTitle.isBlank() || chapterTitle.length < 3) continue

                    val chapterId = chapterUrl
                        .substringAfter("/chapter/")
                        .substringBefore("/")
                        .ifBlank { index.toString() }

                    chapters.add(
                        Chapter(
                            id = "rr_${novelId}_$chapterId",
                            number = chapters.size + 1,
                            title = chapterTitle,
                            url = chapterUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("RoyalRoad", "fetchAllChapters error", e)
            Logger.e("Error", e)
        }

        return chapters
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val document = fetchDocument(chapterUrl) ?: return null

        try {
            val contentElement = document.selectFirst(".chapter-content")
                ?: document.selectFirst(".chapter-inner")
                ?: document.selectFirst("[class*=chapter]")
                ?: return null

            // Remove author notes and ads
            contentElement.select(
                ".author-note, .author-note-portlet, script, .ad-wrapper, .ads, " +
                        "style, .advertisement, .hidden, noscript"
            ).remove()

            val paragraphs = contentElement.select("p")

            return if (paragraphs.isNotEmpty()) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            } else {
                contentElement.text()
            }
        } catch (e: Exception) {
            Logger.e("RoyalRoad", "getChapterContent error", e)
            Logger.e("Error", e)
            return null
        }
    }
}