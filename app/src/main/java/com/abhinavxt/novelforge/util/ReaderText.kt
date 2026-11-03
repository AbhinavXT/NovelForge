package com.abhinavxt.novelforge.util

import org.jsoup.Jsoup

/**
 * NovelForge's reader consumes plain text with paragraphs separated by blank
 * lines (see the native sources), while QuickNovel providers and JS plugins
 * return chapter HTML. This converts one to the other.
 */
object ReaderText {

    fun fromHtml(html: String): String {
        val document = Jsoup.parse(html)
        document.select("script, style, noscript, iframe, ins, .ads, .advertisement").remove()

        val paragraphs = document.select("p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n\n")
        }

        // Fallback for content that is bare text/<br> separated.
        document.select("br").append("\\n")
        val text = document.body()?.wholeText() ?: document.wholeText()
        return text
            .replace("\\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
}
