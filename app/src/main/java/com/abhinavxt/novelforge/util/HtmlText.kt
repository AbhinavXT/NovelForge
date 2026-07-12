package com.abhinavxt.novelforge.util

/**
 * Utility for cleaning HTML-polluted text such as EPUB `<dc:description>`
 * metadata, which publishers frequently fill with raw markup
 * (`<div>`, `<p>`, `<br>`, `<i>`, entity escapes, etc.).
 *
 * Used in two places:
 *  - [com.abhinavxt.novelforge.data.epub.EpubParser] at import time, so new
 *    imports store a clean description in the database.
 *  - The novel detail synopsis composable at display time, so books that
 *    were imported before this fix render correctly without re-importing.
 */
object HtmlText {

    /**
     * Strip all HTML tags and decode common entities, collapsing whitespace
     * into readable prose. Paragraph-level tags become paragraph breaks.
     */
    fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        // Fast path: no markup at all
        if ('<' !in html && '&' !in html) return html.trim()

        return html
            // Remove scripts and styles entirely
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            // Block-level closers and <br> become newlines
            .replace(Regex("</(p|div|br|h[1-6]|li|tr|section)\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            // Remove all remaining tags
            .replace(Regex("<[^>]+>"), "")
            // Decode common entities
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
            }
            // Normalize whitespace: collapse spaces and blank-line runs
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            // Join hard-wrapped source lines: a single \n inside a paragraph
            // becomes a space; double \n (paragraph break) is preserved
            .replace(Regex("(?<!\\n)\\n(?!\\n)"), " ")
            .trim()
    }

    /**
     * Clean and cap a synopsis to [maxChars], cutting at a word boundary
     * and appending an ellipsis when truncated.
     */
    fun cleanSynopsis(raw: String, maxChars: Int = 600): String {
        val clean = stripHtml(raw)
        if (clean.length <= maxChars) return clean

        val cut = clean.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val trimmed = if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut
        return trimmed.trimEnd(' ', ',', ';', ':', '.', '—', '-') + "…"
    }
}