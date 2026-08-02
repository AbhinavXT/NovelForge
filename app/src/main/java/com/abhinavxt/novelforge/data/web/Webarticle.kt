package com.abhinavxt.novelforge.data.web

/**
 * A readable article extracted from an arbitrary web page.
 *
 * [paragraphs] is already plain text — no HTML survives extraction.
 * That matches how chapter content is stored everywhere else in the
 * app (see ParagraphSplitter: chapters are text split on newlines),
 * so a saved page is indistinguishable from an EPUB-imported chapter
 * to the reader, TTS, FTS and the EPUB exporter.
 */
data class WebArticle(
    /** Cleaned document title (site-name suffix stripped). */
    val title: String,
    /** Author / byline if the page declared one, else blank. */
    val author: String,
    /** og:site_name, or the host as a fallback. Used as the "source" label. */
    val siteName: String,
    /** og:description or the first paragraph — shown in the preview. */
    val excerpt: String,
    /** Absolute URL of the lead image, or null. Downloaded lazily on save. */
    val coverImageUrl: String?,
    /** Body text, one entry per paragraph, already trimmed and non-empty. */
    val paragraphs: List<String>,
    /** Final URL after redirects — what we actually fetched. */
    val url: String
) {
    /** Computed once, not per access — the preview UI reads this on every
     *  recomposition and a long article is thousands of splits. */
    val wordCount: Int by lazy {
        paragraphs.sumOf { p -> p.split(WHITESPACE).count { it.isNotBlank() } }
    }

    /** Body as stored in ChapterEntity.content. */
    fun toChapterContent(): String = paragraphs.joinToString("\n\n")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}