package com.abhinavxt.novelforge.data.source.providers

import com.abhinavxt.novelforge.data.source.nf.ChapterData
import com.abhinavxt.novelforge.data.source.nf.ErrorLoadingException
import com.abhinavxt.novelforge.data.source.nf.HeadMainPageResponse
import com.abhinavxt.novelforge.data.source.nf.LoadResponse
import com.abhinavxt.novelforge.data.source.nf.MainAPI
import com.abhinavxt.novelforge.data.source.nf.SearchResponse
import com.abhinavxt.novelforge.data.source.nf.fixUrl
import com.abhinavxt.novelforge.data.source.nf.fixUrlNull
import com.abhinavxt.novelforge.data.source.nf.logError
import com.abhinavxt.novelforge.data.source.nf.newChapterData
import com.abhinavxt.novelforge.data.source.nf.newSearchResponse
import com.abhinavxt.novelforge.data.source.nf.newStreamResponse
import com.abhinavxt.novelforge.data.source.nf.setStatus

class LightNovelTranslationsProvider: MainAPI() {
    override val name = "Light Novel Translations"
    override val mainUrl = "https://lightnovelstranslations.com"

    override val hasMainPage = true

    override val mainCategories = listOf(
        "Most Liked" to "most-liked",
        "Most Recent" to "most-recent"
    )

    override val tags = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )

    override val orderBys = emptyList<Pair<String, String>>()

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse
    {
        val range = if (page == 1) {
            1..3
        } else {
            val actualPage = page + 2
            actualPage..actualPage
        }
        val novels = mutableListOf<SearchResponse>()
        var url = ""
        for(i in range){
            url = "$mainUrl/read/page/$i?sortby=$mainCategory&status=$tag"
            val document = app.get(url).document
             novels.addAll( document.select("div.read_list-story-item").mapNotNull { el ->
                val link = el.selectFirst(".item_thumb a") ?: return@mapNotNull null
                val img = el.selectFirst(".item_thumb img")?.attr("src")
                val title = link.attr("title")
                val href = link.attr("href")

                newSearchResponse(name = title, url = href) {
                    posterUrl = fixUrlNull(img)
                }
            })
        }

        return HeadMainPageResponse(url, novels)
    }

    override suspend fun load(url: String): LoadResponse
    {
        val document = app.get(url).document

        val title = document.selectFirst("div.novel_title h3")?.text()?.trim() ?: throw Exception("Title not found")

        val chapters = mutableListOf<ChapterData>()
        document.select("li.chapter-item.unlock").forEach { li ->
            val link = li.selectFirst("a") ?: return@forEach
            val chapterTitle = link.text().trim()
            val href = link.attr("href")

            chapters.add(
                newChapterData(
                    name = chapterTitle,
                    url = href
                )
            )
        }

        return newStreamResponse(title, fixUrl(url), chapters) {
            this.author = document.selectFirst("div.novel_detail_info li:contains(Author)") ?.text()?.trim().orEmpty()
            this.posterUrl = fixUrlNull(document.selectFirst("div.novel-image img")?.attr("src"))
            this.synopsis = synopsis
            setStatus(document.selectFirst("div.novel_status")?.text()?.trim())
        }
    }

    override suspend fun loadHtml(url: String): String? {
        return try {
            val document = app.get(url).document
            val content = document.selectFirst("div.text_story")
            content?.select("div.ads_content")?.remove()
            content?.html()
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val formData = mapOf("field-search" to query)
        val response = app.post("$mainUrl/read", data = formData)
        val document = response.document

        return document.select("div.read_list-story-item").mapNotNull { el ->
            val link = el.selectFirst(".item_thumb a") ?: return@mapNotNull null
            val img = el.selectFirst(".item_thumb img")?.attr("src")
            val title = link.attr("title").orEmpty()
            val href = link.attr("href").orEmpty()

            newSearchResponse(title, href) {
                posterUrl = fixUrlNull(img)
            }
        }
    }
}