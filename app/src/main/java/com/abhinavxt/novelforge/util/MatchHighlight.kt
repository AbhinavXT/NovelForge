package com.abhinavxt.novelforge.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * Pure text helpers for the reader's in-book find feature. Lives in
 * util (not ui.screens) because both ReaderContent (ui.screens) and
 * PagedReaderContent (ui.components) need them, and components
 * shouldn't depend on screens.
 */
object MatchHighlight {

    /** Queries shorter than this match half the chapter — not useful. */
    const val MIN_QUERY_LENGTH = 2

    /**
     * All case-insensitive occurrences of [query] in [text] as
     * character ranges (inclusive first, inclusive last — IntRange
     * semantics; convert with `last + 1` for span ends).
     */
    fun findOccurrences(text: String, query: String): List<IntRange> {
        if (query.length < MIN_QUERY_LENGTH) return emptyList()
        val result = mutableListOf<IntRange>()
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            result += i until (i + query.length)
            // Non-overlapping scan — "aaa"/"aa" yields one match, which
            // is what every find-in-page implementation does.
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return result
    }

    /**
     * Adds search-match background spans for [ranges] on top of an
     * already-appended AnnotatedString (call inside
     * buildAnnotatedString AFTER append + any highlight spans, so
     * search styling wins in overlaps).
     *
     * The active match is rendered inverted (theme text color as
     * background, theme background as text) — high contrast on every
     * reader theme including AMOLED and custom colors, without
     * hard-coding an accent that could vanish against a user-chosen
     * background.
     */
    fun AnnotatedString.Builder.addSearchSpans(
        ranges: List<IntRange>,
        activeRange: IntRange?,
        textColor: Color,
        backgroundColor: Color
    ) {
        ranges.forEach { r ->
            if (r == activeRange) {
                addStyle(
                    SpanStyle(background = textColor, color = backgroundColor),
                    r.first, r.last + 1
                )
            } else {
                addStyle(
                    SpanStyle(background = textColor.copy(alpha = 0.18f)),
                    r.first, r.last + 1
                )
            }
        }
    }

    /**
     * Parses an FTS4 snippet whose matches are wrapped in
     * \u0001…\u0002 markers (the convention used by all our snippet()
     * queries) into an AnnotatedString with bold match spans.
     */
    fun parseFtsSnippet(snippet: String): AnnotatedString = buildAnnotatedString {
        var bold = false
        val plain = StringBuilder()

        fun flush() {
            if (plain.isEmpty()) return
            if (bold) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(plain.toString())
                pop()
            } else {
                append(plain.toString())
            }
            plain.clear()
        }

        for (ch in snippet) {
            when (ch) {
                '\u0001' -> { flush(); bold = true }
                '\u0002' -> { flush(); bold = false }
                else -> plain.append(ch)
            }
        }
        flush()
    }

    /**
     * Convenience for render paths that have no other spans (paged
     * mode): plain text in, annotated with match spans out. Returns
     * null when there's nothing to mark so callers can keep the
     * cheaper plain-Text path.
     */
    fun annotateMatches(
        text: String,
        query: String,
        activeRange: IntRange?,
        textColor: Color,
        backgroundColor: Color
    ): AnnotatedString? {
        val ranges = findOccurrences(text, query)
        if (ranges.isEmpty()) return null
        return buildAnnotatedString {
            append(text)
            addSearchSpans(ranges, activeRange, textColor, backgroundColor)
        }
    }
}
