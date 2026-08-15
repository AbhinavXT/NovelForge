package com.abhinavxt.novelforge.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ParagraphSplitter is the highest-leverage class in the app to pin down.
 *
 * Saved reading progress, bookmarks, highlights and FTS jump targets are all
 * stored as integer indexes into the output of [ParagraphSplitter.split].
 * Any change to this function silently invalidates every one of those rows in
 * every user's database -- a bookmark on paragraph 42 quietly starts pointing
 * somewhere else. There is no migration that can fix that after the fact,
 * because the original text may no longer be available.
 *
 * These tests exist to make such a change fail loudly instead.
 *
 * Every assertion below was verified against the real implementation.
 */
class ParagraphSplitterTest {

    // ── split: the index contract ────────────────────────────────────────

    @Test
    fun `splits on double newline`() {
        assertEquals(listOf("one", "two"), ParagraphSplitter.split("one\n\ntwo"))
    }

    @Test
    fun `splits on single newline too`() {
        // Both separators are treated identically. Worth pinning: a future
        // "only split on blank lines" change would renumber every paragraph.
        assertEquals(listOf("one", "two"), ParagraphSplitter.split("one\ntwo"))
    }

    @Test
    fun `trims surrounding whitespace on each paragraph`() {
        assertEquals(listOf("a", "b"), ParagraphSplitter.split("  a  \n\n  b  "))
    }

    @Test
    fun `collapses runs of blank lines rather than emitting empty paragraphs`() {
        // Critical for index stability: a source that double-spaces its
        // paragraphs must not produce different indexes to one that doesn't.
        assertEquals(listOf("a", "b"), ParagraphSplitter.split("a\n\n\n\nb"))
    }

    @Test
    fun `blank line containing only a space is still dropped`() {
        assertEquals(listOf("a", "b"), ParagraphSplitter.split("a\n \n b"))
    }

    @Test
    fun `handles CRLF line endings`() {
        // Some sources and imported EPUBs use Windows line endings. The \r is
        // removed by trim(), so indexes match the Unix case.
        assertEquals(listOf("a", "b"), ParagraphSplitter.split("a\r\nb"))
    }

    @Test
    fun `empty and whitespace-only input yield no paragraphs`() {
        assertEquals(emptyList<String>(), ParagraphSplitter.split(""))
        assertEquals(emptyList<String>(), ParagraphSplitter.split("   "))
        assertEquals(emptyList<String>(), ParagraphSplitter.split("\n\n\n"))
    }

    @Test
    fun `text with no separator is a single paragraph`() {
        assertEquals(listOf("solo"), ParagraphSplitter.split("solo"))
    }

    // ── findFirstMatch: search-to-reader jump targets ────────────────────

    private val paragraphs = listOf(
        "The sword gleamed.",
        "Li Wei drew his blade.",
        "Elder Chen watched."
    )

    @Test
    fun `finds the paragraph containing the query`() {
        assertEquals(1, ParagraphSplitter.findFirstMatch(paragraphs, "blade"))
    }

    @Test
    fun `match is case insensitive`() {
        assertEquals(1, ParagraphSplitter.findFirstMatch(paragraphs, "BLADE"))
    }

    @Test
    fun `matches a multi-word phrase within one paragraph`() {
        assertEquals(2, ParagraphSplitter.findFirstMatch(paragraphs, "Elder Chen"))
    }

    @Test
    fun `falls back to the first token when the full phrase spans paragraphs`() {
        // FTS ANDs terms across the whole chapter, so a hit can be reported
        // for a chapter where no single paragraph holds every word. The
        // fallback lands the user on the first token instead of the top.
        assertEquals(1, ParagraphSplitter.findFirstMatch(paragraphs, "Li nonexistent"))
    }

    @Test
    fun `returns top of chapter when nothing matches`() {
        // NOTE: 0 is overloaded -- it means both "matched in paragraph 0" and
        // "no match, go to the top". Callers cannot distinguish. That is the
        // current contract; if it ever needs to change, change it here first.
        assertEquals(0, ParagraphSplitter.findFirstMatch(paragraphs, "zzz"))
    }

    @Test
    fun `blank query returns top of chapter`() {
        assertEquals(0, ParagraphSplitter.findFirstMatch(paragraphs, ""))
        assertEquals(0, ParagraphSplitter.findFirstMatch(paragraphs, "   "))
    }

    @Test
    fun `empty paragraph list does not crash`() {
        assertEquals(0, ParagraphSplitter.findFirstMatch(emptyList(), "anything"))
    }
}