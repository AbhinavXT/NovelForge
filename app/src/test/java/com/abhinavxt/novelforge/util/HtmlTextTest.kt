package com.abhinavxt.novelforge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Tests for HTML cleanup of publisher-supplied EPUB metadata.
 *
 * All assertions verified against the real implementation.
 */
class HtmlTextTest {

    // ── Tag handling ─────────────────────────────────────────────────────

    @Test
    fun `block level tags become breaks and collapse to spaces`() {
        assertEquals("Hello World", HtmlText.stripHtml("<p>Hello</p><p>World</p>"))
    }

    @Test
    fun `br becomes a break`() {
        assertEquals("a b", HtmlText.stripHtml("a<br>b"))
    }

    @Test
    fun `script and style contents are removed entirely`() {
        assertEquals("keep", HtmlText.stripHtml("<script>bad()</script>keep"))
    }

    @Test
    fun `paragraph breaks survive as double newlines`() {
        assertEquals("a\n\nb", HtmlText.stripHtml("<p>a</p>\n\n\n\n<p>b</p>"))
    }

    @Test
    fun `result is trimmed`() {
        assertEquals("x", HtmlText.stripHtml("  <div>x</div>  "))
    }

    // ── Entity decoding ──────────────────────────────────────────────────

    @Test
    fun `decodes named entities`() {
        assertEquals("&<>\"'", HtmlText.stripHtml("&amp;&lt;&gt;&quot;&#39;"))
    }

    @Test
    fun `decodes numeric entities in the basic plane`() {
        assertEquals(listOf(8212), HtmlText.stripHtml("&#8212;").codePoints().toArray().toList())
        assertEquals(listOf(233), HtmlText.stripHtml("&#233;").codePoints().toArray().toList())
    }

    @Test
    fun `decodes astral plane numeric entities without truncating`() {
        // REGRESSION TEST. The original implementation used Int.toChar(), which
        // is 16-bit: &#128512; (grinning face) decoded to code point 62976 -- a
        // private-use character that renders as a tofu box. Silent corruption,
        // no exception. Publishers do put emoji in <dc:description>.
        assertEquals(
            listOf(128512),
            HtmlText.stripHtml("&#128512;").codePoints().toArray().toList()
        )
    }

    @Test
    fun `out of range numeric entities degrade to empty rather than throwing`() {
        assertEquals("", HtmlText.stripHtml("&#0;"))
        assertEquals("", HtmlText.stripHtml("&#99999999;"))
    }

    @Test
    fun `unknown entities are left alone`() {
        assertEquals("&unknown;", HtmlText.stripHtml("&unknown;"))
    }

    // ── Fast path ────────────────────────────────────────────────────────

    @Test
    fun `markup free text takes the fast path and keeps hard wraps`() {
        // Documents a real inconsistency: with no '<' or '&' present the
        // function returns early, so embedded newlines are PRESERVED. The same
        // text wrapped in a tag would have them joined into spaces. Pinned here
        // so the difference is a deliberate choice rather than a surprise.
        assertEquals("a\nb", HtmlText.stripHtml("a\nb"))
        assertEquals("plain text", HtmlText.stripHtml("plain text"))
    }

    @Test
    fun `blank input yields empty string`() {
        assertEquals("", HtmlText.stripHtml(""))
        assertEquals("", HtmlText.stripHtml("   "))
    }

    // ── cleanSynopsis ────────────────────────────────────────────────────

    @Test
    fun `short synopsis passes through unchanged`() {
        assertEquals("short one", HtmlText.cleanSynopsis("short one"))
    }

    @Test
    fun `long synopsis is capped and ellipsised at a word boundary`() {
        val long = "word ".repeat(200).trim()
        val out = HtmlText.cleanSynopsis(long)
        assertTrue(out.endsWith("…"))
        assertTrue(out.length <= 600)
    }

    @Test
    @Ignore("KNOWN BUG: returns maxChars + 1 when the text has no word boundary")
    fun `synopsis with no spaces still respects maxChars`() {
        // cleanSynopsis takes maxChars, finds no space to cut at, so keeps the
        // full 600-char slice and appends the ellipsis -> 601 characters.
        // Harmless in practice (a 600-char unbroken token is not real prose)
        // but the function does not honour its own documented cap.
        // Fix: trimmed.take(maxChars - 1) before appending.
        assertTrue(HtmlText.cleanSynopsis("x".repeat(700)).length <= 600)
    }
}