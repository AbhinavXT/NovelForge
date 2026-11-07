package com.abhinavxt.novelforge.data.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * One line of a book's own table of contents.
 *
 * @param chapter the chapter's number (1-based, as stored)
 * @param paragraph the paragraph the entry points at within that chapter
 * @param depth 0 for top-level entries, 1 for their children, and so on
 */
data class TocEntry(val title: String, val depth: Int, val chapter: Int, val paragraph: Int)

/**
 * Reads the table of contents an EPUB declares: the EPUB 3 navigation
 * document, or the EPUB 2 NCX when there is none.
 *
 * Entries come back pointing at files, not chapters. [EpubParser] maps them,
 * since only it knows which files became which chapters.
 */
internal object EpubToc {

    data class RawEntry(val title: String, val depth: Int, val path: String, val fragment: String?)

    /**
     * @param resolve an href relative to the TOC file -> the file's path in
     *   the EPUB, or null when it isn't there
     */
    fun parseNav(xhtml: String, resolve: (String) -> String?): List<RawEntry> {
        val doc = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val navs = doc.getElementsByTag("nav").ifEmpty { doc.getElementsByTag("html:nav") }
        val toc = navs.firstOrNull { nav ->
            nav.attr("epub:type").split(' ').contains("toc") || nav.attr("role") == "doc-toc"
        } ?: navs.firstOrNull() ?: return emptyList()
        val list = toc.children().firstOrNull { it.local() == "ol" || it.local() == "ul" }
            ?: return emptyList()
        val out = mutableListOf<RawEntry>()
        walkNavList(list, 0, resolve, out)
        return out
    }

    private fun walkNavList(list: Element, depth: Int, resolve: (String) -> String?, out: MutableList<RawEntry>) {
        for (li in list.children()) {
            if (li.local() != "li") continue
            val link = li.children().firstOrNull { it.local() == "a" || it.local() == "span" }
            val title = link?.text()?.let(::cleanTitle).orEmpty()
            val href = link?.attr("href").orEmpty()
            val path = href.takeIf { it.isNotEmpty() }?.let(resolve)
            if (title.isNotEmpty() && path != null) {
                out += RawEntry(title, depth, path, fragmentOf(href))
            }
            // An entry without a target (a part heading) still has children.
            val nested = li.children().firstOrNull { it.local() == "ol" || it.local() == "ul" }
            if (nested != null) {
                val childDepth = if (title.isNotEmpty() && path != null) depth + 1 else depth
                walkNavList(nested, childDepth, resolve, out)
            }
        }
    }

    fun parseNcx(xml: String, resolve: (String) -> String?): List<RawEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val navMap = doc.getElementsByTag("navMap").firstOrNull()
            ?: doc.getElementsByTag("ncx:navMap").firstOrNull()
            ?: return emptyList()
        val out = mutableListOf<RawEntry>()
        walkNavPoints(navMap, 0, resolve, out)
        return out
    }

    private fun walkNavPoints(parent: Element, depth: Int, resolve: (String) -> String?, out: MutableList<RawEntry>) {
        for (point in parent.children()) {
            if (point.local() != "navPoint") continue
            val title = point.children().firstOrNull { it.local() == "navLabel" }
                ?.text()?.let(::cleanTitle).orEmpty()
            val src = point.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
            val path = src.takeIf { it.isNotEmpty() }?.let(resolve)
            if (title.isNotEmpty() && path != null) {
                out += RawEntry(title, depth, path, fragmentOf(src))
            }
            walkNavPoints(point, if (title.isNotEmpty() && path != null) depth + 1 else depth, resolve, out)
        }
    }

    private fun fragmentOf(href: String): String? =
        href.substringAfter('#', "").takeIf { it.isNotEmpty() }

    private fun cleanTitle(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}

/** Tag name without a namespace prefix ("html:nav" -> "nav"). */
private fun Element.local(): String = tagName().substringAfterLast(':')
