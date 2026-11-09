package com.abhinavxt.novelforge.data.source.plugin

import org.jsoup.Jsoup

/**
 * Removes what isn't the story from a chapter's HTML: author-note boxes
 * (`div.qnauthornotecontainer`, the class several novel sites use) and short
 * "Translator: … Editor: …" credit lines. Plugins opt out with `stripCredits: false`.
 */
internal object CreditStripper {

    private val CREDIT = Regex(
        """^(translator|translated by|editor|edited by|proofreader|proofread by|tl|ed|pr|tlc|qc)s?\s*[:：]""",
        RegexOption.IGNORE_CASE
    )
    private const val MAX_CREDIT_CHARS = 200

    fun strip(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        doc.select("div.qnauthornotecontainer").remove()
        doc.body().select("p, div, span, strong, b, em, i, h1, h2, h3, h4, h5, h6")
            .filter { el -> el.children().none { it.isBlock } }
            .filter { el -> el.text().trim().let { it.length <= MAX_CREDIT_CHARS && CREDIT.containsMatchIn(it) } }
            .forEach { it.remove() }
        return doc.body().html()
    }
}
