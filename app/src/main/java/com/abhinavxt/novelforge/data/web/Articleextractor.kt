package com.abhinavxt.novelforge.data.web

import com.abhinavxt.novelforge.util.Logger
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a parsed page into a [WebArticle] — a Readability-style
 * extraction, trimmed to what this app actually needs (plain text, no
 * images, no inline markup).
 *
 * The algorithm, in order:
 *  1. Read metadata FIRST (og:*, twitter:*, <title>) — stripping must
 *     not destroy the tags we're about to read.
 *  2. Strip the obvious non-content: scripts, forms, nav/aside/footer,
 *     and any element whose class/id looks like chrome (sidebar, share,
 *     comments, related…) unless it also looks like content.
 *  3. Score every text block. A block's score flows up to its parent
 *     (full) and grandparent (half) — the winner is the container that
 *     holds the most substantial prose, which is what we want, not the
 *     deepest single paragraph.
 *  4. Discount by link density. A dense block of links is a nav list or
 *     a "related posts" widget, however much text it contains.
 *  5. Pull in the winner's high-scoring siblings — many sites split the
 *     lead paragraph or a pull-quote into a sibling div.
 *  6. Walk the result to plain-text paragraphs.
 *
 * Everything is best-effort. If scoring produces too little text we
 * fall back to <article>, then <main>, then the whole body; if even
 * that fails we return null and the caller shows "couldn't read this
 * page" rather than saving an empty book.
 */
object ArticleExtractor {

    private const val TAG = "ArticleExtractor"

    /** Below this, we assume extraction failed rather than "short article". */
    private const val MIN_ARTICLE_CHARS = 200

    /** A text block shorter than this contributes nothing to scoring. */
    private const val MIN_BLOCK_CHARS = 25

    private val POSITIVE = Regex(
        "article|body|content|entry|hentry|main|page|post|text|blog|story|column|" +
                "chapter|prose|readable|markdown",
        RegexOption.IGNORE_CASE
    )

    private val NEGATIVE = Regex(
        "combx|comment|contact|foot|footer|footnote|masthead|media|meta|outbrain|" +
                "promo|related|scroll|shoutbox|sidebar|sponsor|shopping|tags|tool|widget|" +
                "banner|social|share|subscribe|newsletter|paywall|cookie|consent|" +
                "breadcrumb|pagination|pager|author-box|disqus|popup|modal|drawer|" +
                "skip-link|hidden|nav-|-nav|menu",
        RegexOption.IGNORE_CASE
    )

    /** Boilerplate lines that survive extraction on a lot of sites. */
    private val JUNK_LINE = Regex(
        "^(advertisement|share this|sponsored|related( posts| articles)?|" +
                "sign up|subscribe|follow us|read more|tags?:|comments?)\\s*[:.]?$",
        RegexOption.IGNORE_CASE
    )

    /** Tags that force a paragraph break when walking for text. */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "blockquote", "pre", "td", "tr", "dd", "dt", "figcaption",
        "header", "footer", "aside", "main", "ul", "ol", "dl", "table", "hr"
    )

    fun extract(doc: Document): WebArticle? {
        // ── 1. Metadata, before anything is removed ──────────────
        val siteName = metaContent(doc, "og:site_name")
            ?.takeIf { it.isNotBlank() }
            ?: hostOf(doc.baseUri())
        val rawTitle = metaContent(doc, "og:title")
            ?: metaContent(doc, "twitter:title")
            ?: doc.title().takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()
            ?: "Untitled page"
        val title = cleanTitle(rawTitle, siteName)
        val author = extractAuthor(doc)
        val metaExcerpt = (
                metaContent(doc, "og:description")
                    ?: metaContent(doc, "twitter:description")
                    ?: metaContent(doc, "description")
                )?.trim().orEmpty()
        val coverImageUrl = extractCoverUrl(doc)

        // ── 2. Strip chrome ──────────────────────────────────────
        stripNonContent(doc)

        // ── 3-5. Find the article container ──────────────────────
        val container = findContainer(doc)
        if (container == null) {
            Logger.w(TAG, "No article container found for ${doc.baseUri()}")
            return null
        }

        // ── 6. Flatten to paragraphs ─────────────────────────────
        val paragraphs = toParagraphs(container)
        val charCount = paragraphs.sumOf { it.length }
        if (paragraphs.isEmpty() || charCount < MIN_ARTICLE_CHARS) {
            Logger.w(TAG, "Extraction too thin (${charCount} chars) for ${doc.baseUri()}")
            return null
        }

        return WebArticle(
            title = title,
            author = author,
            siteName = siteName,
            excerpt = metaExcerpt.ifBlank { paragraphs.first().take(300) },
            coverImageUrl = coverImageUrl,
            paragraphs = paragraphs,
            url = doc.baseUri()
        )
    }

    // ─── Metadata ────────────────────────────────────────────────

    private fun metaContent(doc: Document, key: String): String? =
        doc.selectFirst("meta[property=$key]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=$key]")?.attr("content")?.takeIf { it.isNotBlank() }

    private fun extractAuthor(doc: Document): String {
        val fromMeta = metaContent(doc, "author")
            ?: metaContent(doc, "article:author")
            ?: metaContent(doc, "twitter:creator")
        if (!fromMeta.isNullOrBlank() && !fromMeta.startsWith("http")) {
            return fromMeta.trim()
        }
        // Common byline markup, in decreasing order of reliability.
        val selectors = listOf(
            "[itemprop=author] [itemprop=name]",
            "[itemprop=author]",
            "[rel=author]",
            ".author-name", ".byline__name", ".byline", ".author", ".post-author"
        )
        for (sel in selectors) {
            val text = doc.selectFirst(sel)?.text()?.trim().orEmpty()
            if (text.isNotEmpty() && text.length <= 80) {
                // Strip a leading "By " that most bylines carry.
                return text.removePrefix("By ").removePrefix("by ").trim()
            }
        }
        return ""
    }

    private fun extractCoverUrl(doc: Document): String? {
        val candidates = listOf(
            doc.selectFirst("meta[property=og:image]")?.absUrl("content"),
            doc.selectFirst("meta[name=twitter:image]")?.absUrl("content"),
            doc.selectFirst("link[rel=image_src]")?.absUrl("href")
        )
        return candidates.firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }
    }

    private fun cleanTitle(raw: String, siteName: String): String {
        var t = raw.trim()
        // "Article title | Site Name" / "Article title - Site Name"
        if (siteName.isNotBlank()) {
            for (sep in listOf(" | ", " - ", " — ", " – ", " :: ", " » ")) {
                val idx = t.lastIndexOf(sep)
                if (idx > 0 && t.substring(idx + sep.length).trim().equals(siteName.trim(), true)) {
                    t = t.substring(0, idx).trim()
                    break
                }
            }
        }
        return t.ifBlank { "Untitled page" }.take(300)
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.").orEmpty()
    } catch (e: Exception) {
        ""
    }

    // ─── Stripping ───────────────────────────────────────────────

    private fun stripNonContent(doc: Document) {
        doc.select(
            "script, style, noscript, iframe, svg, canvas, form, button, input, " +
                    "select, textarea, template, object, embed, video, audio, " +
                    "nav, aside, [role=navigation], [role=banner], [role=complementary], " +
                    "[aria-hidden=true]"
        ).remove()

        // <footer> only at page level — some sites use <footer> inside an
        // <article> for the byline, which we don't want to nuke wholesale.
        doc.select("body > footer, body > * > footer").remove()

        // Class/id heuristics. An element that looks like chrome AND doesn't
        // look like content gets dropped, unless it still holds a lot of
        // prose (some CMSes emit class="post-content share-enabled").
        doc.select("div, section, ul, ol, span, header, footer, form, table").forEach { el ->
            if (!el.hasParent()) return@forEach
            val signature = "${el.className()} ${el.id()}"
            if (signature.isBlank()) return@forEach
            if (NEGATIVE.containsMatchIn(signature) && !POSITIVE.containsMatchIn(signature)) {
                if (el.text().length < 400) el.remove()
            }
        }
    }

    // ─── Scoring ─────────────────────────────────────────────────

    private fun findContainer(doc: Document): Element? {
        val scores = HashMap<Element, Double>()

        for (block in doc.select("p, pre, blockquote, td")) {
            val text = block.text()
            if (text.length < MIN_BLOCK_CHARS) continue

            // Base: one point for existing, one per comma (a proxy for real
            // sentences), plus length in 100-char units capped at 3 so one
            // giant block can't outweigh a genuinely long article.
            val blockScore = 1.0 +
                    text.count { it == ',' } +
                    min(text.length / 100.0, 3.0)

            block.parent()?.let { parent ->
                scores[parent] = (scores[parent] ?: baseScore(parent)) + blockScore
                parent.parent()?.let { grand ->
                    scores[grand] = (scores[grand] ?: baseScore(grand)) + blockScore / 2.0
                }
            }
        }

        // No <p>/<pre>/<blockquote>/<td> anywhere means the page is built
        // out of <br>-separated text — extremely common on chapter pages
        // and older blogs. Those score nothing above, so give them their
        // own pass before falling back to the whole body.
        if (scores.isEmpty()) return brStyleContainer(doc) ?: fallbackContainer(doc)

        // Link density discount, applied at selection time so it can't
        // distort the upward score propagation above.
        val best = scores.maxByOrNull { (el, score) -> score * (1.0 - linkDensity(el)) }?.key
            ?: return fallbackContainer(doc)

        val bestScore = scores[best] ?: 0.0
        if (bestScore <= 0.0) return fallbackContainer(doc)

        return withSiblings(best, scores, bestScore)
    }

    /** Class/id and tag-name priors, before any text is counted. */
    private fun baseScore(el: Element): Double {
        var score = when (el.tagName()) {
            "article", "main" -> 8.0
            "section", "div" -> 3.0
            "blockquote", "pre", "td" -> 3.0
            "address", "ol", "ul", "dl", "dd", "dt", "li", "form" -> -3.0
            "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5.0
            else -> 0.0
        }
        val signature = "${el.className()} ${el.id()}"
        if (signature.isNotBlank()) {
            if (POSITIVE.containsMatchIn(signature)) score += 25.0
            if (NEGATIVE.containsMatchIn(signature)) score -= 25.0
        }
        return score
    }

    /** Fraction of an element's text that sits inside anchors. */
    private fun linkDensity(el: Element): Double {
        val total = el.text().length
        if (total == 0) return 0.0
        val linkChars = el.select("a").sumOf { it.text().length }
        return min(linkChars.toDouble() / total, 1.0)
    }

    /**
     * Readability's sibling pass: a sibling of the winner is part of the
     * article if it scored well itself, or if it's a substantial, mostly
     * link-free paragraph. Returns a detached container holding the
     * winner plus any accepted siblings.
     */
    private fun withSiblings(
        best: Element,
        scores: Map<Element, Double>,
        bestScore: Double
    ): Element {
        val parent = best.parent() ?: return best
        val threshold = max(10.0, bestScore * 0.2)

        val accepted = parent.children().filter { sibling ->
            when {
                sibling === best -> true
                (scores[sibling] ?: 0.0) >= threshold -> true
                sibling.tagName() == "p" &&
                        sibling.text().length > 80 &&
                        linkDensity(sibling) < 0.25 -> true
                else -> false
            }
        }

        if (accepted.size <= 1) return best

        // Clone into a detached div so we never mutate the live document —
        // the caller may still want to re-run extraction with a fallback.
        val container = Element("div")
        accepted.forEach { container.appendChild(it.clone()) }
        return container
    }

    /**
     * Second chance for pages with no paragraph tags at all: a <div> full
     * of text broken up by <br>. ownText() is the signal — it counts only
     * the text an element holds *directly*, so an outer wrapper scores 0
     * and the element actually holding the prose wins. Without this, such
     * pages fall through to <body> and drag the whole page chrome in.
     */
    private fun brStyleContainer(doc: Document): Element? {
        var best: Element? = null
        var bestScore = 0.0
        for (el in doc.select("div, section, td, article")) {
            val ownLength = el.ownText().length
            if (ownLength < MIN_ARTICLE_CHARS) continue
            val score = ownLength * (1.0 - linkDensity(el)) + baseScore(el)
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best
    }

    private fun fallbackContainer(doc: Document): Element? =
        doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.selectFirst("[role=main]")
            ?: doc.body()

    // ─── Flattening to text ──────────────────────────────────────

    private fun toParagraphs(root: Element): List<String> {
        val out = mutableListOf<String>()
        collect(root, out)

        val cleaned = mutableListOf<String>()
        for (raw in out) {
            val line = raw.trim()
            // A one- or two-character line is leftover punctuation from a
            // stripped element, never a real paragraph.
            if (line.length <= 2) continue
            if (JUNK_LINE.matches(line)) continue
            // Collapse the consecutive duplicates some sites emit for a11y
            // (visually-hidden copies of a heading, for instance).
            if (cleaned.lastOrNull() == line) continue
            cleaned += line
        }
        return cleaned
    }

    /**
     * Depth-first walk that buffers inline content and flushes a
     * paragraph at every block boundary and <br>.
     *
     * Doing this by hand rather than via Element.text() preserves
     * paragraph structure — text() would collapse the whole article
     * into a single wall, which would break TTS chunking, the reader's
     * saved paragraph indexes, bookmarks and highlights.
     */
    private fun collect(el: Element, out: MutableList<String>) {
        val buffer = StringBuilder()

        fun flush() {
            val text = normalizeWhitespace(buffer.toString())
            if (text.isNotEmpty()) out += text
            buffer.setLength(0)
        }

        for (node: Node in el.childNodes()) {
            when (node) {
                is TextNode -> buffer.append(node.text())
                is Element -> when {
                    node.tagName() == "br" -> flush()
                    node.tagName() in BLOCK_TAGS -> {
                        flush()
                        collect(node, out)
                    }
                    // Inline (a, em, strong, span, code…): keep it in the
                    // current paragraph. Pad so adjacent inlines don't fuse
                    // words together; normalizeWhitespace tidies it up.
                    else -> buffer.append(' ').append(node.text()).append(' ')
                }
                else -> Unit // comments, doctype, etc.
            }
        }
        flush()
    }

    private fun normalizeWhitespace(s: String): String =
        s.replace('\u00A0', ' ')       // nbsp — invisible but breaks trim()
            .replace(Regex("\\s+"), " ")
            .trim()
}