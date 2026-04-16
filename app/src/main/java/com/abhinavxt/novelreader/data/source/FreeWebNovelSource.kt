package com.abhinavxt.novelreader.data.source

import com.abhinavxt.novelreader.util.Logger
import org.jsoup.Jsoup

/**
 * FreeWebNovel is a sister site of LibRead with identical HTML structure.
 * Extends LibReadSource and overrides only the domain and watermark cleaning.
 */
class FreeWebNovelSource : LibReadSource() {

    override val id = "fwn"
    override val name = "FreeWebNovel"
    override val baseUrl = "https://freewebnovel.com"
    override val removeHtml = true

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

            // Clean FreeWebNovel-specific watermarks
            var html = contentElement.html()
            html = html.replace("New novel chapters are published on Freewebnovel.com.", "")
            html = html.replace("The source of this content is Freewebnᴏvel.com.", "")
            html = html.replace(
                "☞ We are moving Freewebnovel.com to Libread.com, Please visit libread.com for more chapters! ☜",
                ""
            )
            html = html.replace("freewebnovel.com", "", ignoreCase = true)

            val cleanedElement = Jsoup.parse(html).body()
            val paragraphs = cleanedElement.select("p")

            return if (paragraphs.size > 3) {
                paragraphs
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && it.length > 3 }
                    .joinToString("\n\n")
            } else {
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
}