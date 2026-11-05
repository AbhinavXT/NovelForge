package com.abhinavxt.novelforge.data.annas

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** One search result. [md5] is the file's hash, which LibGen shares. */
data class AnnasBook(
    val md5: String,
    val title: String,
    val author: String = "",
    val publisher: String = "",
    val coverUrl: String = "",
    /** The raw "English [en] · EPUB · 0.6MB · 2005 · …" summary line. */
    val metadata: String = "",
    val language: String = "",
    val format: String = "",
    val size: String = ""
)

/**
 * Turns Anna's Archive search pages into [AnnasBook]s. Pure jsoup, no
 * Android, so it runs on the JVM in tests.
 *
 * Two card layouts are recognised. The current one has separate cover,
 * title and author links inside a details block; the older one wraps the
 * whole card in a single anchor with an `h3` title. Mirrors don't all
 * upgrade at once, so both stay.
 */
object AnnasArchiveParser {

    sealed interface Page {
        data class Results(val books: List<AnnasBook>) : Page

        /** A DDoS-Guard (or Cloudflare) check instead of the site. */
        data object Challenge : Page

        /** The domain lapsed and is now a parking page. */
        data object Parked : Page

        /** Not a challenge, but no result cards and no "no results" text. */
        data object Unrecognized : Page
    }

    private val MD5_PATH = Regex("^/md5/([0-9a-fA-F]{32})/?$")
    private val LANGUAGE = Regex("""\[([a-z]{2,3}(?:[-_]\w+)?)]""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""\b\d+(?:[.,]\d+)?\s*[KMGT]?i?B\b""", RegexOption.IGNORE_CASE)
    private val FORMAT = Regex(
        "pdf|epub|mobi|azw3?|djvu?|txt|rtf|docx?|cb[rz]|fb2|zip|html",
        RegexOption.IGNORE_CASE
    )
    private val NO_RESULTS = listOf("no files found", "no results")

    fun parseSearch(html: String, baseUrl: String): Page {
        if (isParked(html)) return Page.Parked
        val doc = Jsoup.parse(html, baseUrl)
        if (isChallenge(doc)) return Page.Challenge
        revealLazyCards(doc)

        val books = LinkedHashMap<String, AnnasBook>()
        for (anchor in doc.select("a[href*=/md5/]")) {
            val md5 = md5Of(anchor.attr("href")) ?: continue
            if (md5 in books) continue
            (currentCard(anchor, md5) ?: legacyCard(anchor, md5))?.let { books[md5] = it }
        }

        if (books.isEmpty()) {
            val visible = doc.text().lowercase()
            if (NO_RESULTS.none { it in visible }) return Page.Unrecognized
        }
        return Page.Results(books.values.toList())
    }

    fun isParked(html: String): Boolean {
        val lower = html.lowercase()
        return "forsale.min.js" in lower || "this domain may be for sale" in lower
    }

    fun isChallenge(doc: Document): Boolean {
        val title = doc.title().lowercase()
        return "ddos-guard" in title ||
                "just a moment" in title ||
                "attention required" in title ||
                doc.selectFirst("script[src*=ddos-guard], script[src*=challenge-platform]") != null ||
                doc.selectFirst("#challenge-form, #cf-challenge-running") != null
    }

    fun isChallenge(html: String): Boolean = isChallenge(Jsoup.parse(html))

    fun md5Of(href: String): String? {
        val path = runCatching { URI(href).path }.getOrNull() ?: return null
        return MD5_PATH.find(path)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Only the first few cards are live HTML. The rest ship inside comments
     * in `.js-scroll-hidden` blocks and are revealed by script on scroll.
     */
    private fun revealLazyCards(doc: Document) {
        for (container in doc.select(".js-scroll-hidden")) {
            val comments = container.childNodes().filterIsInstance<Comment>()
            for (comment in comments) {
                val html = comment.data
                comment.remove()
                container.append(html)
            }
        }
    }

    private fun currentCard(anchor: Element, md5: String): AnnasBook? {
        if (!anchor.hasClass("js-vim-focus") || anchor.selectFirst("h3") != null) return null
        val details = anchor.parent()?.parent() ?: return null
        val card = details.parent() ?: return null
        val title = cleanText(anchor)
        if (title.isBlank()) return null
        return AnnasBook(
            md5 = md5,
            title = title,
            author = iconLink(details, "mdi--user-edit"),
            publisher = iconLink(details, "mdi--company"),
            coverUrl = coverOf(card),
        ).withMetadata(summaryText(details.selectFirst(".text-gray-800")))
    }

    private fun legacyCard(anchor: Element, md5: String): AnnasBook? {
        val title = anchor.selectFirst("h3") ?: anchor.selectFirst(".font-bold") ?: return null
        if (cleanText(title).isBlank()) return null
        // A cover-only anchor sits next to the real card; skip it.
        if (anchor.selectFirst(".js-aarecord-list-fallback-cover") != null) return null
        val author = anchor.selectFirst(".italic")
        val publisher = title.nextElementSibling()?.takeIf { it != author }
        return AnnasBook(
            md5 = md5,
            title = cleanText(title),
            author = cleanText(author),
            publisher = cleanText(publisher),
            coverUrl = coverOf(anchor),
        ).withMetadata(cleanText(anchor.selectFirst(".text-gray-500")))
    }

    private fun AnnasBook.withMetadata(summary: String): AnnasBook {
        val parts = summary.split(',', '·').map { it.trim() }
        return copy(
            metadata = summary,
            language = LANGUAGE.find(summary)?.groupValues?.get(1).orEmpty(),
            format = parts.firstOrNull { FORMAT.matches(it) }?.lowercase().orEmpty(),
            size = SIZE.find(summary)?.value.orEmpty()
        )
    }

    /** A card with no cover still has an `<img src="">`, which resolves to the page itself. */
    private fun coverOf(container: Element): String =
        container.select("img[src]")
            .firstOrNull { it.attr("src").isNotBlank() }
            ?.absUrl("src")
            .orEmpty()

    private fun iconLink(container: Element, icon: String): String {
        val node = container.selectFirst("[class*=$icon]") ?: return ""
        return cleanText(node.closest("a"))
    }

    /** The summary line, minus the inline links (save, report) it carries. */
    private fun summaryText(node: Element?): String {
        if (node == null) return ""
        val clone = node.clone()
        clone.select("a, script, style").remove()
        return cleanText(clone).trimEnd(' ', '·')
    }

    /** Text without icons, ranking metadata and other hidden spans. */
    private fun cleanText(node: Element?): String {
        if (node == null) return ""
        val clone = node.clone()
        clone.select(".select-none, script, style, .hidden").remove()
        return clone.text().trim()
    }
}
