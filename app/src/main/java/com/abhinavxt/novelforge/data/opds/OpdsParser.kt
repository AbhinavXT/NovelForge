package com.abhinavxt.novelforge.data.opds

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** A downloadable file of a book. */
data class OpdsAcquisition(
    val url: String,
    val type: String,
    /** The catalog's own label, e.g. "EPUB3 (E-readers incl. Send-to-Kindle)". */
    val title: String = "",
    val length: Long? = null
)

/**
 * One entry of a feed: a book (it has [acquisitions]) or a folder to open
 * ([navigationUrl]). Some catalogs list books as folders that lead to the
 * book's own feed; opening one shows the book there.
 */
data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val summary: String = "",
    val coverUrl: String = "",
    val navigationUrl: String? = null,
    /** EPUB files only, best first: what the importer can read. */
    val acquisitions: List<OpdsAcquisition> = emptyList(),
    /** Formats offered that NovelForge can't import ("PDF", "Kindle"…), for telling the user why. */
    val otherFormats: List<String> = emptyList()
) {
    val isBook: Boolean get() = acquisitions.isNotEmpty() || otherFormats.isNotEmpty()
}

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String? = null,
    /** A ready search URL with `{searchTerms}`, when the feed gives one directly. */
    val searchTemplate: String? = null,
    /** Otherwise, an OpenSearch description to read the template from. */
    val openSearchUrl: String? = null
)

/**
 * Reads OPDS 1.x catalog feeds (Atom XML), as served by Project Gutenberg,
 * Standard Ebooks, Calibre-Web, Kavita, Komga and others. Pure jsoup, so it
 * runs on the JVM in tests.
 */
object OpdsParser {

    const val EPUB_TYPE = "application/epub+zip"
    private const val ACQUISITION = "http://opds-spec.org/acquisition"

    /** Acquisition kinds that hand over the file. Buy, borrow and subscribe don't. */
    private val FREE_ACQUISITIONS = setOf(ACQUISITION, "$ACQUISITION/open-access")

    private val COVER_RELS = listOf(
        "http://opds-spec.org/image",
        "http://opds-spec.org/cover",
        "x-stanza-cover-image",
        "http://opds-spec.org/image/thumbnail",
        "http://opds-spec.org/thumbnail",
        "x-stanza-cover-image-thumbnail",
    )

    /** Links that are about the feed itself, not somewhere to go. */
    private val NON_NAVIGATION_RELS = setOf(
        "self", "start", "up", "search", "alternate", "related", "first", "last", "previous", "prev", "next",
    )

    /** True when [text] looks like an Atom feed rather than an HTML page. */
    fun looksLikeFeed(text: String): Boolean {
        val head = text.take(2048).lowercase()
        return "<feed" in head && "<html" !in head.substringBefore("<feed")
    }

    fun parseFeed(xml: String, baseUrl: String): OpdsFeed {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        val feed = doc.selectFirst("feed") ?: doc
        val feedLinks = feed.children().filter { it.normalName() == "link" }

        val search = feedLinks.filter { it.attr("rel") == "search" }
        val template = search.firstOrNull {
            "atom+xml" in it.attr("type") && "{searchTerms}" in it.attr("href")
        }?.let { absolute(it, "href") }
        val openSearch = search.firstOrNull { "opensearchdescription" in it.attr("type") }
            ?.let { absolute(it, "href") }

        return OpdsFeed(
            title = feed.children().firstOrNull { it.normalName() == "title" }?.text().orEmpty(),
            entries = feed.children().filter { it.normalName() == "entry" }.map(::parseEntry),
            nextUrl = feedLinks.firstOrNull { it.attr("rel") == "next" }?.let { absolute(it, "href") },
            searchTemplate = template,
            openSearchUrl = openSearch
        )
    }

    /** The Atom search URL template from an OpenSearch description, if it has one. */
    fun parseOpenSearch(xml: String, baseUrl: String): String? {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        val urls = doc.select("Url, url")
        val url = urls.firstOrNull { "atom+xml" in it.attr("type") && "opds" in it.attr("type") }
            ?: urls.firstOrNull { "atom+xml" in it.attr("type") }
        return url?.let { absolute(it, "template") }
    }

    /**
     * Fills an OpenSearch template: the query for `{searchTerms}`, and nothing
     * for the optional parameters (`{startPage?}`) a catalog doesn't need.
     */
    fun fillTemplate(template: String, query: String): String {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return template
            .replace("{searchTerms}", encoded)
            // Every brace escaped: Android's ICU regex rejects a bare "}" that the JVM accepts.
            .replace(Regex("\\{[^\\}]*\\?\\}"), "")
    }

    private fun parseEntry(entry: Element): OpdsEntry {
        val links = entry.children().filter { it.normalName() == "link" }

        val epubs = mutableListOf<OpdsAcquisition>()
        val others = mutableListOf<String>()
        for (link in links) {
            val rel = link.attr("rel")
            if (!rel.startsWith(ACQUISITION)) continue
            val type = link.attr("type").substringBefore(';').trim().lowercase()
            if (rel !in FREE_ACQUISITIONS) continue
            if (type == EPUB_TYPE) {
                epubs += OpdsAcquisition(
                    url = absolute(link, "href") ?: continue,
                    type = type,
                    title = link.attr("title"),
                    length = link.attr("length").toLongOrNull()
                )
            } else {
                formatName(type)?.let { if (it !in others) others += it }
            }
        }

        val navigation = links.firstOrNull { link ->
            val rel = link.attr("rel")
            "atom+xml" in link.attr("type") && rel !in NON_NAVIGATION_RELS && !rel.startsWith(ACQUISITION)
        }?.let { absolute(it, "href") }

        val cover = COVER_RELS.firstNotNullOfOrNull { rel ->
            links.firstOrNull { it.attr("rel") == rel }?.let { absolute(it, "href") }
        }?.takeUnless { it.startsWith("data:") }.orEmpty() // inline icons, not covers

        return OpdsEntry(
            id = childText(entry, "id"),
            title = childText(entry, "title").ifBlank { "Untitled" },
            authors = entry.children().filter { it.normalName() == "author" }
                .mapNotNull { a -> a.children().firstOrNull { it.normalName() == "name" }?.text()?.trim() }
                .filter { it.isNotEmpty() },
            summary = summaryOf(entry),
            coverUrl = cover,
            navigationUrl = navigation,
            acquisitions = rankEpubs(epubs),
            otherFormats = others
        )
    }

    /**
     * Best EPUB first: with images over without (Gutenberg labels them
     * "no images"), then EPUB3 over older EPUB.
     */
    fun rankEpubs(epubs: List<OpdsAcquisition>): List<OpdsAcquisition> =
        epubs.sortedWith(
            compareBy<OpdsAcquisition>(
                { "noimages" in it.url.lowercase() || "no images" in it.title.lowercase() },
                { !(it.url.lowercase().contains("epub3") || it.title.lowercase().contains("epub3")) }
            )
        )

    private fun summaryOf(entry: Element): String {
        val node = entry.children().firstOrNull { it.normalName() == "summary" }
            ?: entry.children().firstOrNull { it.normalName() == "content" }
            ?: return ""
        // Content is often escaped HTML (type="html") or inline XHTML; either way, keep the text.
        val raw = if (node.attr("type") == "xhtml") node.html() else node.wholeText()
        return Jsoup.parse(raw).text().trim()
    }

    private fun formatName(type: String): String? = when {
        type == "application/pdf" -> "PDF"
        "mobipocket" in type || "kindle" in type || "x-mobi" in type -> "Kindle"
        "fb2" in type -> "FB2"
        "comicbook" in type || "cbz" in type || "cbr" in type -> "Comic"
        type == "text/plain" -> "Plain text"
        type.startsWith("audio/") -> "Audio"
        else -> null
    }

    private fun childText(entry: Element, name: String): String =
        entry.children().firstOrNull { it.normalName() == name }?.text()?.trim().orEmpty()

    private fun absolute(el: Element, attr: String): String? {
        val raw = el.attr(attr).trim()
        if (raw.isEmpty()) return null
        el.absUrl(attr).takeIf { it.isNotBlank() }?.let { return it }
        // absUrl gives up on templates: braces aren't legal in a URL. Resolve
        // with them swapped out, then put them back.
        return runCatching {
            val safe = raw.replace("{", "%7B").replace("}", "%7D")
            java.net.URL(java.net.URL(el.baseUri()), safe).toString()
                .replace("%7B", "{").replace("%7D", "}")
        }.getOrDefault(raw)
    }
}
